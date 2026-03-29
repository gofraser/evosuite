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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class OpenRegistryTest {

    @Test
    @Timeout(20)
    public void openTest() throws RemoteException, NotBoundException {
        Assumptions.assumeTrue(canBindServerSocket(), "Socket binding is not permitted in this environment");

        final String previousHostname = System.getProperty("java.rmi.server.hostname");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");

        int port = 2000;
        Registry registry = null;
        Registry createdRegistry = null;
        FooImpl foo = null;

        try {
            for (int i = 0; i < 1000; i++) {
                try {
                    createdRegistry = LocateRegistry.createRegistry(port);
                    break;
                } catch (java.rmi.server.ExportException e) {
                    //it could happen that the port is already in use
                    port++;
                }
            }

            registry = LocateRegistry.getRegistry(port);
            Assertions.assertNotNull(registry);

            try {
                LocateRegistry.createRegistry(port);
                Assertions.fail();
            } catch (Exception e) {
            }

            try {
                ServerSocket socket = new ServerSocket(port);
                Assertions.fail();
            } catch (Exception e) {
            }

            foo = new FooImpl();
            Ifoo stub = (Ifoo) UnicastRemoteObject.exportObject(foo, 0);
            String service = "Foo";
            createdRegistry.rebind(service, stub);

            Ifoo lookedup = (Ifoo) createdRegistry.lookup(service);
            Assertions.assertEquals("Hello World", lookedup.getString());
        } finally {
            if (foo != null) {
                try {
                    UnicastRemoteObject.unexportObject(foo, true);
                } catch (Exception ignored) {
                }
            }
            if (createdRegistry != null) {
                try {
                    UnicastRemoteObject.unexportObject(createdRegistry, true);
                } catch (Exception ignored) {
                }
            }
            if (previousHostname != null) {
                System.setProperty("java.rmi.server.hostname", previousHostname);
            } else {
                System.clearProperty("java.rmi.server.hostname");
            }
        }
    }

    private static boolean canBindServerSocket() {
        try (ServerSocket ignored = new ServerSocket(0)) {
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    interface Ifoo extends Remote {
        String getString() throws RemoteException;
    }

    class FooImpl implements Ifoo {

        @Override
        public String getString() throws RemoteException {
            return "Hello World";
        }

    }
}
