/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.rmi;

import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertSame;

public class ClientServicesTest {

    /** Lets another client register while this one winds down. */
    private static final class ReplacedWhileStoppingNode extends ClientNodeImpl {
        private final Field field;
        private final Object replacement;

        ReplacedWhileStoppingNode(Field field, Object replacement) {
            super(null, "stopping-client");
            this.field = field;
            this.replacement = replacement;
        }

        @Override
        public void stop() {
            try {
                field.set(ClientServices.getInstance(), replacement);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * Test processes run client after client in one JVM. The client that stops late must not
     * take the node of the client that registered in the meantime away.
     */
    @Test
    public void stopServicesKeepsTheNodeOfAClientRegisteredInTheMeantime() throws Exception {
        Field clientNodeField = ClientServices.class.getDeclaredField("clientNode");
        clientNodeField.setAccessible(true);
        Object previous = clientNodeField.get(ClientServices.getInstance());
        try {
            DummyClientNodeImpl<?> newer = new DummyClientNodeImpl<>();
            clientNodeField.set(ClientServices.getInstance(),
                    new ReplacedWhileStoppingNode(clientNodeField, newer));

            ClientServices.getInstance().stopServices();

            assertSame(newer, clientNodeField.get(ClientServices.getInstance()));
        } finally {
            clientNodeField.set(ClientServices.getInstance(), previous);
        }
    }
}
