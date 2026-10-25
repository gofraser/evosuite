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
import org.evosuite.ga.Chromosome;
import org.evosuite.rmi.service.ClientNodeImpl;
import org.evosuite.rmi.service.ClientNodeLocal;
import org.evosuite.rmi.service.DummyClientNodeImpl;
import org.evosuite.statistics.RuntimeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * This class should be used only in the Client processes, not the master.
 * Used to initialize and store all the RMI services in the clients
 *
 * @author arcuri
 */
public class ClientServices<T extends Chromosome<T>> {

    private static final Logger logger = LoggerFactory.getLogger(ClientServices.class);

    private static final ClientServices<?> instance = new ClientServices<>();

    private volatile ClientNodeImpl<T> clientNode = new DummyClientNodeImpl<>();

    protected ClientServices() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends Chromosome<T>> ClientServices<T> getInstance() {
        return (ClientServices<T>) instance;
    }

    /**
     * Registers the client services with the RMI registry using the provided identifier.
     *
     * @param identifier the unique identifier for this client
     * @return true if registration was successful, false otherwise
     */
    public boolean registerServices(String identifier) {

        UtilsRMI.ensureRegistryOnLoopbackAddress();

        try {
            int port = Properties.PROCESS_COMMUNICATION_PORT;
            Registry registry = LocateRegistry.getRegistry("127.0.0.1", port);
            ClientNodeImpl<T> node = new ClientNodeImpl<>(registry, identifier);
            synchronized (this) {
                clientNode = node;
            }
            Remote stub = UtilsRMI.exportObject(node);
            registry.rebind(node.getClientRmiIdentifier(), stub);
            return node.init();
        } catch (Exception e) {
            logger.error("Failed to register client services", e);
            return false;
        }
    }

    public ClientNodeLocal<T> getClientNode() {
        return clientNode;
    }

    /**
     * Stops the client services and unexports the RMI object.
     */
    public void stopServices() {
        // Stop the node that is registered now: another client may register while this one is
        // winding down, and it must keep its node.
        ClientNodeImpl<T> node = clientNode;
        if (node != null) {
            node.stop();
            int i = 0;
            final int tries = 10;
            boolean done = false;
            try {
                while (!done) {
                    /*
                     * A call from Master could still be active on this node. so we cannot
                     * forcely stop the client, we need to wait
                     */
                    done = UnicastRemoteObject.unexportObject(node, false);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    i++;
                    if (i >= tries) {
                        logger.error("Tried " + tries + " times to stop RMI ClientNode, giving up");
                        break;
                    }
                }
            } catch (NoSuchObjectException e) {
                //this could happen if Master has removed the registry
                logger.debug("Failed to delete ClientNode RMI instance", e);
            }
            synchronized (this) {
                if (clientNode == node) {
                    clientNode = new DummyClientNodeImpl<>();
                }
            }
        }
    }

    /**
     * Shorthand for the commonly used trackOutputVariable method.
     *
     * @param outputVariable The runtime variable to track
     * @param value          The value of the runtime variable
     */
    public static void track(RuntimeVariable outputVariable, Object value) {
        ClientServices.getInstance().getClientNode().trackOutputVariable(outputVariable, value);
    }

    /**
     * Like {@link #track}, for statistics that must never disturb the search: a failure to
     * publish is logged at debug level and otherwise ignored.
     */
    public static void trackQuietly(RuntimeVariable outputVariable, Object value) {
        try {
            track(outputVariable, value);
        } catch (Throwable t) {
            logger.debug("Could not publish {} stat", outputVariable, t);
        }
    }
}
