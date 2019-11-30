/**
 * This file is part of core.
 *
 * core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with core.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.hobbit.core.components.dummy;

import org.apache.commons.configuration2.Configuration;
import org.hobbit.core.components.AbstractDataGenerator;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.junit.Ignore;

@Ignore
public class DummyDataCreator extends AbstractDataGenerator {

    private int dataSize;

    public DummyDataCreator(int dataSize) {
        this.dataSize = dataSize;
    }
    public DummyDataCreator(int dataSize, Configuration c) {
        this(dataSize);
        this.configVar = c;
    }

    @Override
    protected void generateData() throws Exception {
        byte data[];
        for (int i = 0; i < dataSize; ++i) {
            data = RabbitMQUtils.writeString(Integer.toString(i));
            sendDataToSystemAdapter(data);
            sendDataToTaskGenerator(data);
        }
    }

}
