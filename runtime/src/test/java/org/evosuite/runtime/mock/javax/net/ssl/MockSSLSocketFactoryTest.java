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
package org.evosuite.runtime.mock.javax.net.ssl;

import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.runtime.vnet.VirtualNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.net.Socket;

public class MockSSLSocketFactoryTest {

    @BeforeEach
    public void init() {
        VirtualNetwork.getInstance().reset();
        VirtualNetwork.getInstance().init();
    }

    @AfterEach
    public void tearDown() {
        MockFramework.disable();
    }

    @Test
    public void getDefaultReturnsMockFactoryWhenEnabled() {
        MockFramework.enable();
        SocketFactory factory = MockSSLSocketFactory.getDefault();
        Assertions.assertTrue(factory instanceof SSLSocketFactory);
        Assertions.assertTrue(factory.getClass().getName().contains("MockSSLSocketFactory"));
    }

    @Test
    public void createSocketFromStreamReturnsMockSslSocketWhenEnabled() throws Exception {
        MockFramework.enable();
        SSLSocketFactory factory = (SSLSocketFactory) MockSSLSocketFactory.getDefault();
        Socket socket = factory.createSocket(new Socket(), new java.io.ByteArrayInputStream(new byte[0]), false);
        Assertions.assertTrue(socket instanceof MockSSLSocket);
    }

    @Test
    public void createSocketNoArgReturnsMockSslSocketWhenEnabled() throws Exception {
        MockFramework.enable();
        SSLSocketFactory factory = (SSLSocketFactory) MockSSLSocketFactory.getDefault();
        Socket socket = factory.createSocket();
        Assertions.assertTrue(socket instanceof MockSSLSocket);
    }
}
