/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.mock.java.io.MockIOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class MockChannelTest {

    private boolean oldUseVnet;
    private boolean oldFramework;

    @Before
    public void setUp() {
        oldUseVnet = RuntimeSettings.useVNET;
        oldFramework = MockFramework.isEnabled();
    }

    @After
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
        Assert.assertTrue(MockList.shouldBeMocked(SocketChannel.class.getName()));
        Assert.assertTrue(MockList.shouldBeMocked(ServerSocketChannel.class.getName()));
        Assert.assertTrue(MockList.shouldBeMocked(DatagramChannel.class.getName()));
        Assert.assertTrue(MockList.shouldBeMocked(AsynchronousSocketChannel.class.getName()));
        Assert.assertTrue(MockList.shouldBeMocked(AsynchronousServerSocketChannel.class.getName()));
    }

    @Test
    public void testOpenBlockedWhenMockingEnabled() throws Exception {
        MockFramework.enable();

        try {
            MockSocketChannel.open();
            Assert.fail("Expected mocked SocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockServerSocketChannel.open();
            Assert.fail("Expected mocked ServerSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockDatagramChannel.open();
            Assert.fail("Expected mocked DatagramChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockAsynchronousSocketChannel.open();
            Assert.fail("Expected mocked AsynchronousSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }

        try {
            MockAsynchronousServerSocketChannel.open();
            Assert.fail("Expected mocked AsynchronousServerSocketChannel.open() to be blocked");
        } catch (MockIOException expected) {
            // expected
        }
    }

    @Test
    public void testOpenAllowedWhenMockingDisabled() throws Exception {
        MockFramework.disable();

        SocketChannel sc = MockSocketChannel.open();
        Assert.assertNotNull(sc);
        sc.close();

        ServerSocketChannel ssc = MockServerSocketChannel.open();
        Assert.assertNotNull(ssc);
        ssc.close();

        DatagramChannel dc = MockDatagramChannel.open();
        Assert.assertNotNull(dc);
        dc.close();

        AsynchronousSocketChannel asc = MockAsynchronousSocketChannel.open();
        Assert.assertNotNull(asc);
        asc.close();

        AsynchronousServerSocketChannel assc = MockAsynchronousServerSocketChannel.open();
        Assert.assertNotNull(assc);
        assc.close();
    }
}
