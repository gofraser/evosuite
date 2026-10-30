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
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SocketStoppingConditionTest {

    private int oldPort;
    private SocketStoppingCondition<TestSuiteChromosome> condition;

    @Before
    public void setUp() throws IOException {
        oldPort = Properties.STOPPING_PORT;
        try (ServerSocket probe = new ServerSocket(0)) {
            Properties.STOPPING_PORT = probe.getLocalPort();
        }
        condition = SocketStoppingCondition.getInstance();
        condition.reset();
    }

    @After
    public void tearDown() {
        condition.searchFinished(null);
        condition.reset();
        Properties.STOPPING_PORT = oldPort;
    }

    private static long listenerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("EvoSuite-stopping-socket"))
                .count();
    }

    private static void waitUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    public void startingTwiceKeepsOneListener() {
        condition.accept();
        condition.accept();
        assertEquals(1, listenerThreads());
    }

    @Test
    public void searchFinishedFreesPortAndEndsThread() throws Exception {
        condition.accept();
        assertEquals(1, listenerThreads());
        condition.searchFinished(null);

        waitUntil(() -> listenerThreads() == 0);
        assertEquals(0, listenerThreads());
        try (ServerSocket again = new ServerSocket(Properties.STOPPING_PORT)) {
            assertTrue(again.isBound());
        }
        assertFalse(condition.isFinished());
    }

    @Test
    public void canBeStartedAgainAfterSearchFinished() {
        condition.accept();
        condition.searchFinished(null);
        condition.accept();
        assertEquals(1, listenerThreads());
        try (ServerSocket taken = new ServerSocket(Properties.STOPPING_PORT)) {
            fail("port should be held by the listener: " + taken);
        } catch (IOException expected) {
            // port is in use
        }
    }

    @Test
    public void connectionStopsTheSearch() throws Exception {
        condition.accept();
        assertFalse(condition.isFinished());
        try (Socket s = new Socket("localhost", Properties.STOPPING_PORT)) {
            assertTrue(s.isConnected());
        }
        waitUntil(condition::isFinished);
        assertTrue(condition.isFinished());
        waitUntil(() -> listenerThreads() == 0);
        assertEquals(0, listenerThreads());
    }
}
