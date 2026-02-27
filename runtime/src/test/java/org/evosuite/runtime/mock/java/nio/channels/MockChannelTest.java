/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.java.io.MockIOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class MockChannelTest {

    private boolean oldUseVnet;
    private boolean oldFramework;

    @BeforeEach
    public void setUp() {
        oldUseVnet = RuntimeSettings.useVNET;
        oldFramework = MockFramework.isEnabled();
    }

    @AfterEach
    public void tearDown() {
        RuntimeSettings.useVNET = oldUseVnet;
        if (oldFramework) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    public void testChannelClassesRegisteredInVnetMocks() {
        RuntimeSettings.useVNET = true;
        Assertions.assertTrue(MockList.shouldBeMocked(SocketChannel.class.getName()));
        Assertions.assertTrue(MockList.shouldBeMocked(ServerSocketChannel.class.getName()));
        Assertions.assertTrue(MockList.shouldBeMocked(DatagramChannel.class.getName()));
        Assertions.assertTrue(MockList.shouldBeMocked(AsynchronousSocketChannel.class.getName()));
        Assertions.assertTrue(MockList.shouldBeMocked(AsynchronousServerSocketChannel.class.getName()));
    }

    @Test
    public void testOpenBlockedWhenMockingEnabled() throws Exception {
        MockFramework.enable();

        try {
            MockSocketChannel.open();
            Assertions.fail("Expected mocked SocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockServerSocketChannel.open();
            Assertions.fail("Expected mocked ServerSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockDatagramChannel.open();
            Assertions.fail("Expected mocked DatagramChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockAsynchronousSocketChannel.open();
            Assertions.fail("Expected mocked AsynchronousSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockAsynchronousServerSocketChannel.open();
            Assertions.fail("Expected mocked AsynchronousServerSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }
    }

    @Test
    public void testOpenAllowedWhenMockingDisabled() throws Exception {
        MockFramework.disable();

        SocketChannel sc = MockSocketChannel.open();
        Assertions.assertNotNull(sc);
        sc.close();

        ServerSocketChannel ssc = MockServerSocketChannel.open();
        Assertions.assertNotNull(ssc);
        ssc.close();

        DatagramChannel dc = MockDatagramChannel.open();
        Assertions.assertNotNull(dc);
        dc.close();

        AsynchronousSocketChannel asc = MockAsynchronousSocketChannel.open();
        Assertions.assertNotNull(asc);
        asc.close();

        AsynchronousServerSocketChannel assc = MockAsynchronousServerSocketChannel.open();
        Assertions.assertNotNull(assc);
        assc.close();
    }
}
