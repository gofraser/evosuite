/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.rmi;

import org.evosuite.rmi.service.MasterNodeImpl;
import org.evosuite.rmi.service.MasterNodeLocal;
import org.evosuite.rmi.service.MasterNodeRemote;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

/**
 * This class should be used only in the Master process, not the clients.
 * Used to initialize and store all the RMI services in the master.
 * It is also used to start the RMI registry.
 *
 * @author arcuri
 */
public class MasterServices {

    private static final Logger logger = LoggerFactory.getLogger(MasterServices.class);

    private static final MasterServices instance = new MasterServices();

    private int registryPort = -1;

    /**
     * We store it to avoid issues with GC.
     */
    private Registry registry;

    private MasterNodeImpl masterNode;


    protected MasterServices() {
    }


    /**
     * Returns the singleton instance of {@link MasterServices}.
     *
     * @return the singleton instance
     */
    public static MasterServices getInstance() {
        return instance;
    }


    /**
     * Starts the RMI registry.
     *
     * @return true if started
     * @throws IllegalStateException if already running
     */
    public boolean startRegistry() throws IllegalStateException {

        if (registry != null) {
            logger.warn("RMI registry already running on port {}", registryPort);
            return true;
        }

        /*
         * Unfortunately, it does not seem possible to start a RMI registry on an
         * ephemeral port. So, we start with a port, and see if free. If not, try the
         * next one, etc. Note, it is important to start from a random port to avoid issues
         * with several masters running on same node, eg when experiments on cluster.
         */

        int port = 2000;
        port += Randomness.nextInt(20000);

        final int TRIES = 100;
        List<Integer> candidates = new ArrayList<>(TRIES + 2);
        for (int i = 0; i < TRIES; i++) {
            candidates.add(port + i);
        }
        Integer ephemeralPort = findEphemeralPort();
        if (ephemeralPort != null) {
            candidates.add(ephemeralPort);
        }
        // Default RMI registry port as a last resort
        candidates.add(1099);

        RemoteException lastException = null;
        for (Integer candidatePort : candidates) {
            try {
                UtilsRMI.ensureRegistryOnLoopbackAddress();

                registry = LocateRegistry.createRegistry(candidatePort);
                registryPort = candidatePort;
                return true;
            } catch (RemoteException e) {
                lastException = e;
                // Try to reuse an existing registry on this port
                if (attachToExistingRegistry(candidatePort)) {
                    return true;
                }
            }
        }

        if (lastException != null) {
            logger.error("Failed to start RMI registry", lastException);
        }
        return false;
    }

    private static Integer findEphemeralPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            return null;
        }
    }

    private boolean attachToExistingRegistry(int port) {
        try {
            Registry existing = LocateRegistry.getRegistry("127.0.0.1", port);
            existing.list(); // force connection attempt
            registry = existing;
            registryPort = port;
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Return the port on which the registry is running.
     *
     * @return a negative value if no registry is running
     */
    public int getRegistryPort() {
        return registryPort;
    }

    /**
     * Registers the RMI services.
     *
     * @throws RemoteException if registration fails
     */
    public void registerServices() throws RemoteException {
        masterNode = new MasterNodeImpl(registry);
        MasterNodeRemote stub = (MasterNodeRemote) UtilsRMI.exportObject(masterNode);
        registry.rebind(MasterNodeRemote.RMI_SERVICE_NAME, stub);
    }


    /**
     * Returns the local master node.
     *
     * @return the master node
     */
    public MasterNodeLocal getMasterNode() {
        return masterNode;
    }

    /**
     * Stops the RMI services and the registry.
     */
    public void stopServices() {
        if (masterNode != null) {
            try {
                UnicastRemoteObject.unexportObject(masterNode, true);
            } catch (NoSuchObjectException e) {
                logger.warn("Failed to delete MasterNode RMI instance", e);
            }
            masterNode = null;
        }

        if (registry != null) {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (NoSuchObjectException e) {
                logger.warn("Failed to stop RMI registry", e);
            }
            registry = null;
        }
    }
}
