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


import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.rmi.service.ClientState;
import org.evosuite.rmi.service.MasterNodeLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ServicesTest {

    private int currentPort;
    private boolean currentClientOnThread;
    private String currentRmiSpi;

    @BeforeEach
    public void init() {
        currentPort = Properties.PROCESS_COMMUNICATION_PORT;
        currentClientOnThread = Properties.CLIENT_ON_THREAD;
        currentRmiSpi = System.getProperty("java.rmi.server.RMIClassLoaderSpi");
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{currentTestClasspathEntry()});
        resetMasterClassLoaderState();
    }

    @AfterEach
    public void tearDown() {
        Properties.PROCESS_COMMUNICATION_PORT = currentPort;
        Properties.CLIENT_ON_THREAD = currentClientOnThread;
        if (currentRmiSpi == null) {
            System.clearProperty("java.rmi.server.RMIClassLoaderSpi");
        } else {
            System.setProperty("java.rmi.server.RMIClassLoaderSpi", currentRmiSpi);
        }
        resetMasterClassLoaderState();
    }

    @Test
    public void testMasterClientCommunication() throws Exception {
        MasterServices master = new MasterServices();
        master.startRegistry();
        master.registerServices();

        Properties.PROCESS_COMMUNICATION_PORT = master.getRegistryPort();

        ClientServices<?> clients = new ClientServices<>();
        clients.registerServices("ClientNode");

        ClientNodeLocal<?> clientNode = clients.getClientNode();
        clientNode.changeState(ClientState.STARTED);

        MasterNodeLocal masterNode = master.getMasterNode();
        String summary = masterNode.getSummaryOfClientStatuses();

        Assertions.assertNotNull(summary);
        Assertions.assertTrue(summary.contains(ClientState.STARTED.toString()), "summary=" + summary);
    }

    @Test
    public void installClassLoadingContextDoesNotMarkMasterInClientOnThreadMode() throws Exception {
        Properties.CLIENT_ON_THREAD = true;
        invokeInstallMasterClassLoadingContext();

        Assertions.assertFalse(MasterClassLoader.isMasterProcess(),
                "CLIENT_ON_THREAD mode must not enable master-process classloading short-circuit");
        Assertions.assertNull(MasterClassLoader.getIfInitialized(),
                "CLIENT_ON_THREAD mode should not initialize master classloader");
        Assertions.assertEquals(EvoSuiteRMIClassLoaderSpi.class.getName(),
                System.getProperty("java.rmi.server.RMIClassLoaderSpi"));
    }

    @Test
    public void installClassLoadingContextMarksMasterAndInitializesLoaderWhenOffThread() throws Exception {
        Properties.CLIENT_ON_THREAD = false;
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{currentTestClasspathEntry()});

        invokeInstallMasterClassLoadingContext();

        Assertions.assertTrue(MasterClassLoader.isMasterProcess(),
                "Master process flag should be enabled in normal mode");
        Assertions.assertNotNull(MasterClassLoader.getIfInitialized(),
                "Master classloader should be initialized in normal mode");
    }

    private static void invokeInstallMasterClassLoadingContext() throws Exception {
        Method m = MasterServices.class.getDeclaredMethod("installMasterClassLoadingContext");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void resetMasterClassLoaderState() {
        try {
            Field instance = MasterClassLoader.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
            Field masterProcess = MasterClassLoader.class.getDeclaredField("masterProcess");
            masterProcess.setAccessible(true);
            masterProcess.setBoolean(null, false);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static String currentTestClasspathEntry() {
        try {
            return new File(ServicesTest.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
