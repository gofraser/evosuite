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
package org.evosuite.ga.stoppingconditions;

import org.evosuite.Properties;
import org.evosuite.ga.Chromosome;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Ends the search when something connects to {@code Properties.STOPPING_PORT}.
 *
 * <p>The condition owns its server socket and a daemon listener thread. {@link #accept()} opens
 * them and is idempotent; {@link #searchFinished} closes the socket, which unblocks the thread
 * and frees the port. The condition can be started again afterwards.</p>
 *
 * @author Gordon Fraser
 */
public class SocketStoppingCondition<T extends Chromosome<T>> implements StoppingCondition<T> {

    private static final long serialVersionUID = -8260473153410290373L;

    // There should only be one instance that opens the socket -> singleton design pattern
    private static SocketStoppingCondition<?> instance = null;

    private volatile boolean interrupted = false;

    private ServerSocket serverSocket;

    private Thread listener;

    private static final Logger logger = LoggerFactory.getLogger(SocketStoppingCondition.class);

    private static final long LISTENER_EXIT_WAIT_MS = 5_000L;

    private SocketStoppingCondition() {
        // singleton pattern
    }

    /**
     * Returns the singleton instance of SocketStoppingCondition.
     *
     * @param <T> the type of chromosome
     * @return the singleton instance
     */
    @SuppressWarnings("unchecked")
    public static <T extends Chromosome<T>> SocketStoppingCondition<T> getInstance() {
        if (instance == null) {
            instance = new SocketStoppingCondition<>();
        }

        // Unchecked cast always succeeds as long as we're not actually doing anything with a `T`
        // instance.
        return (SocketStoppingCondition<T>) instance;
    }

    /**
     * Starts listening on {@code Properties.STOPPING_PORT} for a stop request: a connection to
     * the port ends the search. The port is bound before this method returns. Calling it while a
     * listener is already running does nothing.
     */
    public synchronized void accept() {
        if (listener != null && listener.isAlive()) {
            return;
        }
        final ServerSocket socket;
        try {
            socket = new ServerSocket(Properties.STOPPING_PORT);
        } catch (IOException e) {
            LoggingUtils.getEvoLogger().warn("Failed to create socket on port "
                    + Properties.STOPPING_PORT);
            return;
        }
        serverSocket = socket;
        listener = new Thread(() -> listen(socket), "EvoSuite-stopping-socket");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen(ServerSocket socket) {
        try {
            socket.accept().close();
            LoggingUtils.getEvoLogger().info("* Stopping request received");
            interrupted = true;
        } catch (IOException e) {
            if (!socket.isClosed()) {
                logger.info("Error while waiting for a stopping request: " + e);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                logger.info("Error while closing socket: " + e);
            }
        }
    }

    /**
     * Closes the listening socket, which ends the listener thread, and frees the port.
     */
    private synchronized void close() {
        Thread oldListener = listener;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.info("Error while closing socket: " + e);
            }
            serverSocket = null;
        }
        listener = null;
        // On Linux the port stays bound until the listener has returned from accept(), so a
        // new listener started right away would fail to bind. Wait for the old one to end.
        if (oldListener != null && oldListener != Thread.currentThread()) {
            try {
                oldListener.join(LISTENER_EXIT_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void searchStarted(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    @Override
    public void iteration(GeneticAlgorithm<T> algorithm) {
        // TODO Auto-generated method stub

    }

    @Override
    public void searchFinished(GeneticAlgorithm<T> algorithm) {
        close();
    }

    @Override
    public void fitnessEvaluation(T individual) {
        // TODO Auto-generated method stub

    }

    @Override
    public void modification(T individual) {
        // TODO Auto-generated method stub

    }

    @Override
    public void forceCurrentValue(long value) {
        // TODO Auto-generated method stub

    }

    @Override
    public long getCurrentValue() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getLimit() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public boolean isFinished() {
        return interrupted;
    }

    @Override
    public void reset() {
        interrupted = false;
    }

    @Override
    public void setLimit(long limit) {
        // TODO Auto-generated method stub

    }

}
