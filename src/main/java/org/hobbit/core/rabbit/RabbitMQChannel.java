package org.hobbit.core.rabbit;

import java.nio.ByteBuffer;

import org.hobbit.core.components.AbstractCommandReceivingComponent;
import org.hobbit.core.components.commonchannel.CommonChannel;

public class RabbitMQChannel implements CommonChannel {

    @Override
    public byte[] readBytes(Object callback, Object classs, String queue) {
        return new byte[0];
    }

    @Override
    public void writeBytes(byte[] data) {

    }

    @Override
    public void writeBytes(ByteBuffer buffer, String queue) {

    }

	@Override
	public void close() {
		// TODO Auto-generated method stub
		
	}
}