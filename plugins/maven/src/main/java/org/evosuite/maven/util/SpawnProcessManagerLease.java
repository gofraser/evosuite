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
package org.evosuite.maven.util;

import org.evosuite.utils.SpawnProcessKeepAliveChecker;

import java.util.Objects;

/**
 * Shares the keep-alive server of {@link SpawnProcessKeepAliveChecker} between concurrent mojo executions.
 *
 * <p>The checker is a singleton, and Maven loads the plugin once for the whole reactor. In a parallel
 * build ({@code mvn -T}) several modules run {@code generate} at the same time: without sharing, the
 * second execution fails because the server is already running, and the first one to finish stops the
 * server the others still depend on. Each execution therefore takes a lease, and the server (or the
 * registration with a remote manager, eg the IntelliJ plugin) lives while at least one lease is held.</p>
 */
public final class SpawnProcessManagerLease implements AutoCloseable {

    private static final Object LOCK = new Object();

    private static int holders = 0;

    private static int activePort = -1;

    private static Integer activeRemotePort = null;

    private final Integer remotePort;

    private final int port;

    private boolean closed = false;

    private SpawnProcessManagerLease(Integer remotePort, int port) {
        this.remotePort = remotePort;
        this.port = port;
    }

    /**
     * Take a lease on the spawn process manager.
     *
     * @param remotePort port of a manager run by another process, or {@code null} to run one in this JVM
     * @return the lease, which gives the port spawned processes have to report to
     * @throws IllegalStateException if the manager could not be started, or if a concurrent execution
     *                               uses a different remote port
     */
    public static SpawnProcessManagerLease acquire(Integer remotePort) throws IllegalStateException {
        synchronized (LOCK) {
            if (holders == 0) {
                if (remotePort != null) {
                    SpawnProcessKeepAliveChecker.getInstance().registerToRemoteServerAndDieIfFails(remotePort);
                    activePort = remotePort;
                } else {
                    int port = SpawnProcessKeepAliveChecker.getInstance().startServer();
                    if (port < 0) {
                        throw new IllegalStateException("Failed to start the spawn process manager");
                    }
                    activePort = port;
                }
                activeRemotePort = remotePort;
            } else if (!Objects.equals(activeRemotePort, remotePort)) {
                throw new IllegalStateException("Concurrent EvoSuite executions use different spawn manager ports: "
                        + activeRemotePort + " and " + remotePort);
            }
            holders++;
            return new SpawnProcessManagerLease(remotePort, activePort);
        }
    }

    /**
     * The port spawned processes have to report to.
     *
     * @return the port
     */
    public int getPort() {
        return port;
    }

    @Override
    public void close() {
        synchronized (LOCK) {
            if (closed) {
                return;
            }
            closed = true;
            holders--;
            if (holders > 0) {
                return;
            }
            if (remotePort != null) {
                SpawnProcessKeepAliveChecker.getInstance().unRegister();
            } else {
                SpawnProcessKeepAliveChecker.getInstance().stopServer();
            }
            activePort = -1;
            activeRemotePort = null;
        }
    }
}
