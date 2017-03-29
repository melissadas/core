package org.hobbit.core.components;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.hobbit.core.Commands;
import org.hobbit.core.Constants;
import org.hobbit.core.components.dummy.DummyComponentExecutor;
import org.hobbit.core.components.test.InMemoryEvaluationStore;
import org.hobbit.core.components.test.InMemoryEvaluationStore.ResultPairImpl;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

/**
 * Tests the workflow of the {@link AbstractTaskGenerator} class and the
 * communication between the {@link AbstractDataGenerator},
 * {@link AbstractSystemAdapter}, {@link AbstractEvaluationStorage} and
 * {@link AbstractTaskGenerator} classes. Note that this test needs a running
 * RabbitMQ instance. Its host name can be set using the
 * {@link #RABBIT_HOST_NAME} parameter.
 * 
 * @author Michael R&ouml;der (roeder@informatik.uni-leipzig.de)
 *
 */
public class EvaluationModuleTest extends AbstractEvaluationModule {

    private static final String RABBIT_HOST_NAME = "192.168.99.100";

    @Rule
    public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

    private Map<String, ResultPairImpl> expectedResults = new HashMap<>();
    // private int numberOfMessages = 10000;
    private int numberOfMessages = 10;
    private Set<String> receivedResults = new HashSet<>();

    @Test
    public void test() throws Exception {
        environmentVariables.set(Constants.RABBIT_MQ_HOST_NAME_KEY, RABBIT_HOST_NAME);
        environmentVariables.set(Constants.HOBBIT_SESSION_ID_KEY, "0");
        environmentVariables.set(Constants.HOBBIT_EXPERIMENT_URI_KEY, Constants.EXPERIMENT_URI_NS + "123");

        // Create the eval store and add some data
        InMemoryEvaluationStore evalStore = new InMemoryEvaluationStore();
        Random rand = new Random();

        String taskId;
        ResultPairImpl pair;
        long timestamp;
        byte resultData[];
        boolean expResultMissing, sysResultMissing;
        for (int i = 0; i < numberOfMessages; ++i) {
            taskId = Integer.toString(i);
            expResultMissing = rand.nextDouble() > 0.9;
            sysResultMissing = !expResultMissing && (rand.nextDouble() > 0.9);
            pair = new ResultPairImpl();

            if (!expResultMissing) {
                timestamp = rand.nextLong();
                resultData = RabbitMQUtils.writeString(taskId);
                evalStore.putResult(true, taskId, timestamp, resultData);
                pair.setExpected(InMemoryEvaluationStore.putTimestampInFront(timestamp, resultData));
            }

            if (!sysResultMissing) {
                timestamp = rand.nextLong();
                resultData = expResultMissing ? RabbitMQUtils.writeString(taskId)
                        : RabbitMQUtils.writeString(Integer.toString(rand.nextInt()));
                evalStore.putResult(false, taskId, timestamp, resultData);
                pair.setActual(InMemoryEvaluationStore.putTimestampInFront(timestamp, resultData));
            }

            expectedResults.put(taskId, pair);
        }
        DummyComponentExecutor evalStoreExecutor = new DummyComponentExecutor(evalStore);
        Thread evalStoreThread = new Thread(evalStoreExecutor);
        evalStoreThread.start();

        try {
            init();

            run();
            sendToCmdQueue(Commands.EVAL_STORAGE_TERMINATE);

            evalStoreThread.join();

            Assert.assertTrue(evalStoreExecutor.isSuccess());

            String expectedTaskIds[] = expectedResults.keySet().toArray(new String[expectedResults.size()]);
            Arrays.sort(expectedTaskIds);
            String receivedTaskIds[] = receivedResults.toArray(new String[receivedResults.size()]);
            Arrays.sort(receivedTaskIds);
            Assert.assertArrayEquals(expectedTaskIds, receivedTaskIds);
        } finally {
            close();
        }
    }

    @Override
    protected void evaluateResponse(byte[] expectedData, byte[] receivedData, long taskSentTimestamp,
            long responseReceivedTimestamp) throws Exception {
        try {
            Assert.assertTrue((expectedData.length + receivedData.length) > 0);
            String taskId = expectedData.length > 0 ? RabbitMQUtils.readString(expectedData)
                    : RabbitMQUtils.readString(receivedData);
            Assert.assertTrue(taskId + " is not known.", expectedResults.containsKey(taskId));
            ResultPairImpl pair = expectedResults.get(taskId);

            if (expectedData.length == 0) {
                Assert.assertNull(pair.getExpected());
                Assert.assertEquals(0, taskSentTimestamp);
            } else {
                Assert.assertNotNull(pair.getExpected());
                Assert.assertArrayEquals(IOUtils.toByteArray(pair.getExpected()), expectedData);
            }

            if (receivedData.length == 0) {
                Assert.assertNull(pair.getActual());
                Assert.assertEquals(0, responseReceivedTimestamp);
            } else {
                Assert.assertNotNull(pair.getActual());
                Assert.assertArrayEquals(IOUtils.toByteArray(pair.getActual()), receivedData);
            }

            receivedResults.add(taskId);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    protected Model summarizeEvaluation() throws Exception {
        return ModelFactory.createDefaultModel();
    }

}
