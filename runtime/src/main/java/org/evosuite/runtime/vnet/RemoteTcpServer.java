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
package org.evosuite.runtime.vnet;

import org.evosuite.runtime.mock.java.net.SocketIn;
import org.evosuite.runtime.mock.java.net.SocketOut;
import org.evosuite.runtime.vnet.VirtualNetwork.ConnectionType;

import java.io.IOException;

/**
 * Class used to represent a remote TCP server, listening to a specific port.
 * The simulated server will handle only one incoming connections.
 * To simulate the accepting of several connection on same port, different
 * instances of this class will be needed.
 * It is possible to have more than one "server" on same port.
 *
 * @author arcuri
 */
public class RemoteTcpServer {

    /**
     * Connection (if any) toward the SUT.
     */
    private final NativeTcp connection;

    private final SocketOut out;

    private final SocketIn in;

    /**
     * Constructs a remote TCP server with the given address.
     *
     * @param address the address of the remote server
     * @throws IllegalArgumentException if the address is not a TCP connection type
     */
    public RemoteTcpServer(EndPointInfo address) throws IllegalArgumentException {
        if (!address.getType().equals(ConnectionType.TCP)) {
            throw new IllegalArgumentException("Invalid type: " + address.getType());
        }
        connection = new NativeTcp(null, address);
        in = new SocketIn(connection, false);
        out = new SocketOut(connection, false);
    }

    public EndPointInfo getAddress() {
        return connection.getRemoteEndPoint();
    }

    /**
     * Called by the virtual network when the system under test tries to connect to this server.
     *
     * @param sutAddress the address of the SUT
     * @return the native TCP connection
     * @throws IOException if the server is already connected or the SUT address is null
     */
    public synchronized NativeTcp connect(EndPointInfo sutAddress) throws IOException {
        if (this.connection.isBound()) {
            throw new IOException("Server is already connected");
        }

        if (sutAddress == null) {
            throw new IOException("Null local SUT address");
        }

        connection.bind(sutAddress);
        return connection;
    }

    public boolean sendMessage(String msg) {
        //note: we can send even if connection is not bound yet (msg will be on a buffer)

        try {
            out.write(msg.getBytes());
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    public String getAllReceivedDataAsString() {
        if (!connection.isBound()) {
            return null;
        }

        try {
            byte[] buffer = new byte[in.available()];
            in.read(buffer);
            return new String(buffer);
        } catch (IOException e) {
            return null;
        }
    }
}
