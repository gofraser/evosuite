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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SpawnProcessManagerLeaseTest {

    @Test
    public void concurrentExecutionsShareOneServer() {
        SpawnProcessManagerLease first = SpawnProcessManagerLease.acquire(null);
        int port = first.getPort();
        try {
            // a second module of a parallel build must neither fail nor get a different server
            SpawnProcessManagerLease second = SpawnProcessManagerLease.acquire(null);
            assertEquals(port, second.getPort());

            first.close();
            assertTrue(isListening(port), "server stopped while another execution still held a lease");

            second.close();
            assertFalse(isListening(port), "server kept running after the last lease was released");
        } finally {
            first.close();
        }
    }

    @Test
    public void closingTwiceReleasesOnlyOnce() {
        SpawnProcessManagerLease first = SpawnProcessManagerLease.acquire(null);
        SpawnProcessManagerLease second = SpawnProcessManagerLease.acquire(null);
        int port = first.getPort();

        first.close();
        first.close();
        assertTrue(isListening(port), "a repeated close released another execution's lease");

        second.close();
        assertFalse(isListening(port));
    }

    private static boolean isListening(int port) {
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
