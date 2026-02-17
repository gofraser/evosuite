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
package org.evosuite.runtime.mock.java.net;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.List;

public class EvoURLStreamHandler extends MockURLStreamHandler {

    private final String protocol;

    /**
     * Creates a new stream handler for the specified protocol.
     *
     * @param protocol the protocol
     * @throws IllegalArgumentException if protocol is null or empty
     */
    public EvoURLStreamHandler(String protocol) throws IllegalArgumentException {
        super();

        if (protocol == null || protocol.trim().isEmpty()) {
            throw new IllegalArgumentException("Null protocol");
        }

        this.protocol = protocol.trim().toLowerCase();
    }

    /**
     * Checks if the given protocol is supported by this handler.
     *
     * @param protocol the protocol to check
     * @return true if the protocol is supported
     */
    public static boolean isValidProtocol(String protocol) {
        if (protocol == null) {
            return false;
        }

        protocol = protocol.trim().toLowerCase();

        // these depend on what in the "sun.net.www.protocol" package
        List<String> list = Arrays.asList("file", "ftp", "gopher", "http", "https", "jar", "mailto", "netdoc");

        return list.contains(protocol);
    }

    @Override
    protected URLConnection openConnection(URL u) throws IOException {

        if (!u.getProtocol().trim().equalsIgnoreCase(this.protocol)) {
            // should never happen
            throw new IOException("Error, protocol mismatch: " + u.getProtocol() + " != " + this.protocol);
        }

        if ("http".equals(protocol) || "https".equals(protocol)) {
            return new EvoHttpURLConnection(u);
        }

        if ("file".equals(protocol) || "jar".equals(protocol)) {
            // Delegate local/resource protocols to the JDK implementation.
            // Returning null here causes URL#openStream() to throw NPE.
            return new URL(u.toExternalForm()).openConnection();
        }

        throw new IOException("Unsupported protocol in EvoURLStreamHandler: " + protocol);
    }
}
