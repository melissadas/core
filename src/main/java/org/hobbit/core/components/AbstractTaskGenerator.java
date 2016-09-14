package org.hobbit.core.components;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.apache.commons.io.Charsets;
import org.hobbit.core.Commands;
import org.hobbit.core.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.MessageProperties;

/**
 * This abstract class implements basic functions that can be used to implement
 * a task generator.
 * 
 * <p>
 * The following environment variables are expected:
 * <ul>
 * <li>{@link Constants#GENERATOR_ID_KEY}</li>
 * <li>{@link Constants#GENERATOR_COUNT_KEY}</li>
 * </ul>
 * </p>
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public abstract class AbstractTaskGenerator extends AbstractCommandReceivingComponent
        implements GeneratedDataReceivingComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTaskGenerator.class);

    /**
     * Default value of the {@link #maxParallelProcessedMsgs} attribute.
     */
    private static final int DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES = 100;

    /**
     * Mutex used to wait for the start signal after the component has been
     * started and initialized.
     */
    private Semaphore startTaskGenMutex = new Semaphore(0);
    /**
     * Semaphore used to control the number of messages that can be processed in
     * parallel.
     */
    private Semaphore currentlyProcessedMessages;
    /**
     * The id of this generator.
     */
    private int generatorId;
    /**
     * The number of task generators created by the benchmark controller.
     */
    private int numberOfGenerators;
    /**
     * The task id that will be assigned to the next task generated by this
     * generator.
     */
    private long nextTaskId;
    /**
     * The maximum number of incoming messages that are processed in parallel.
     * Additional messages have to wait.
     */
    private int maxParallelProcessedMsgs = DEFAULT_MAX_PARALLEL_PROCESSED_MESSAGES;
    /**
     * Name of the incoming queue with which the task generator can receive data
     * from the data generators.
     */
    protected String dataGen2TaskGenQueueName;
    /**
     * The Channel of the incoming queue with which the task generator can
     * receive data from the data generators.
     */
    protected Channel dataGen2TaskGen;
    /**
     * Name of the queue to the system.
     */
    protected String taskGen2SystemQueueName;
    /**
     * Channel of the queue to the system.
     */
    protected Channel taskGen2System;
    /**
     * Name of the queue to the evaluation storage.
     */
    protected String taskGen2EvalStoreQueueName;
    /**
     * Channel of the queue to the evaluation storage.
     */
    protected Channel taskGen2EvalStore;

    @Override
    public void init() throws Exception {
        super.init();
        Map<String, String> env = System.getenv();

        if (!env.containsKey(Constants.GENERATOR_ID_KEY)) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_ID_KEY + "\" from the environment. Aborting.");
        }
        try {
            generatorId = Integer.parseInt(env.get(Constants.GENERATOR_ID_KEY));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_ID_KEY + "\" from the environment. Aborting.", e);
        }
        nextTaskId = generatorId;

        if (!env.containsKey(Constants.GENERATOR_COUNT_KEY)) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_COUNT_KEY + "\" from the environment. Aborting.");
        }
        try {
            numberOfGenerators = Integer.parseInt(env.get(Constants.GENERATOR_COUNT_KEY));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Couldn't get \"" + Constants.GENERATOR_COUNT_KEY + "\" from the environment. Aborting.", e);
        }

        taskGen2SystemQueueName = generateSessionQueueName(Constants.TASK_GEN_2_SYSTEM_QUEUE_NAME);
        taskGen2System = connection.createChannel();
        taskGen2System.queueDeclare(taskGen2SystemQueueName, false, false, true, null);

        taskGen2EvalStoreQueueName = generateSessionQueueName(Constants.TASK_GEN_2_EVAL_STORAGE_QUEUE_NAME);
        taskGen2EvalStore = connection.createChannel();
        taskGen2EvalStore.queueDeclare(taskGen2EvalStoreQueueName, false, false, true, null);

        @SuppressWarnings("resource")
        GeneratedDataReceivingComponent receiver = this;
        dataGen2TaskGenQueueName = generateSessionQueueName(Constants.DATA_GEN_2_TASK_GEN_QUEUE_NAME);
        dataGen2TaskGen = connection.createChannel();
        dataGen2TaskGen.queueDeclare(dataGen2TaskGenQueueName, false, false, true, null);
        dataGen2TaskGen.basicConsume(dataGen2TaskGenQueueName, true, new DefaultConsumer(dataGen2TaskGen) {
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, BasicProperties properties, byte[] body)
                    throws IOException {
                try {
                    currentlyProcessedMessages.acquire();
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted while waiting for mutex.", e);
                }
                receiver.receiveGeneratedData(body);
                currentlyProcessedMessages.release();
            }
        });

        currentlyProcessedMessages = new Semaphore(maxParallelProcessedMsgs);
    }

    @Override
    public void run() throws Exception {
        boolean terminate = false;
        sendToCmdQueue(Commands.TASK_GENERATOR_READY_SIGNAL);
        // Wait for the start message
        startTaskGenMutex.acquire();

        while ((!terminate) || (dataGen2TaskGen.messageCount(dataGen2TaskGenQueueName) > 0)) {
            Thread.sleep(1000);
        }
        // Collect all open mutex counts to make sure that there is no message
        // that is still processed
        Thread.sleep(1000);
        currentlyProcessedMessages.acquire(maxParallelProcessedMsgs);
    }

    @Override
    public void receiveGeneratedData(byte[] data) {
        try {
            generateTask(data);
        } catch (Exception e) {
            LOGGER.error("Exception while generating task");
        }
    }

    /**
     * Generates a task from the given data, sends it to the system, takes the
     * timestamp of the moment at which the message has been sent to the system
     * and sends it together with the expected response to the evaluation
     * storage.
     * 
     * @param data
     *            incoming data generated by a data generator
     * @throws Exception
     *             if a sever error occurred
     */
    protected abstract void generateTask(byte[] data) throws Exception;

    /**
     * Generates the next unique ID for a task.
     * 
     * @return the next unique task ID
     */
    protected synchronized String getNextTaskId() {
        String taskIdString = Long.toString(nextTaskId);
        nextTaskId += numberOfGenerators;
        return taskIdString;
    }

    @Override
    public void receiveCommand(byte command, byte[] data) {
        // If this is the signal to start the data generation
        if (command == Commands.TASK_GENERATOR_START_SIGNAL) {
            // release the mutex
            startTaskGenMutex.release();
        }
    }

    /**
     * This method sends the given data and the given timestamp of the task with
     * the given task id to the evaluation storage.
     * 
     * @param taskIdString
     *            the id of the task
     * @param timestamp
     *            the timestamp of the moment in which the task has been sent to
     *            the system
     * @param data
     *            the expected response for the task with the given id
     * @throws IOException
     *             if there is an error during the sending
     */
    protected void sendTaskToEvalStorage(String taskIdString, long timestamp, byte[] data) throws IOException {
        byte[] taskIdBytes = taskIdString.getBytes(Charsets.UTF_8);
        // + 4 for taskIdBytes.length
        // + 4 for data length (time stamp + data)
        // + 8 for time stamp
        // + 4 for data.length
        int dataLength = 8 + 4 + data.length;
        int capacity = 4 + taskIdString.length() + 4 + dataLength;
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.putInt(taskIdBytes.length);
        buffer.put(taskIdBytes);
        buffer.putInt(dataLength);
        buffer.putLong(timestamp);
        buffer.putInt(data.length);
        buffer.put(data);
        taskGen2EvalStore.basicPublish("", taskGen2SystemQueueName, MessageProperties.PERSISTENT_BASIC, buffer.array());
    }

    /**
     * Sends the given task with the given task id and data to the system.
     * 
     * @param taskIdString
     *            the id of the task
     * @param data
     *            the data of the task
     * @throws IOException
     *             if there is an error during the sending
     */
    protected void sendTaskToSystemAdapter(String taskIdString, byte[] data) throws IOException {
        byte[] taskIdBytes = taskIdString.getBytes(Charsets.UTF_8);
        // + 4 for taskIdBytes.length
        // + 4 for data.length
        int capacity = 4 + 4 + taskIdBytes.length + data.length;
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        buffer.putInt(taskIdBytes.length);
        buffer.put(taskIdBytes);
        buffer.putInt(data.length);
        buffer.put(data);
        taskGen2System.basicPublish("", taskGen2EvalStoreQueueName, MessageProperties.PERSISTENT_BASIC, buffer.array());
    }

    public int getGeneratorId() {
        return generatorId;
    }

    public int getNumberOfGenerators() {
        return numberOfGenerators;
    }

    public void setMaxParallelProcessedMsgs(int maxParallelProcessedMsgs) {
        this.maxParallelProcessedMsgs = maxParallelProcessedMsgs;
    }

    @Override
    public void close() throws IOException {
        if (dataGen2TaskGen != null) {
            try {
                dataGen2TaskGen.close();
            } catch (Exception e) {
            }
        }
        if (taskGen2System != null) {
            try {
                taskGen2System.close();
            } catch (Exception e) {
            }
        }
        if (taskGen2EvalStore != null) {
            try {
                taskGen2EvalStore.close();
            } catch (Exception e) {
            }
        }
        super.close();
    }
}
