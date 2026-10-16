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
package org.evosuite.rmi.service;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.Registry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * State changes are reported to the master for every goal during minimization. They used to
 * start a new thread each, which the sandbox then kept in its privileged set for good.
 */
public class ClientNodeRemoteActionThreadTest {

    private boolean oldClientOnThread;

    @BeforeEach
    public void saveProperties() {
        oldClientOnThread = Properties.CLIENT_ON_THREAD;
        Properties.CLIENT_ON_THREAD = false;
        TimeController.resetSingleton();
    }

    @AfterEach
    public void restoreProperties() {
        Properties.CLIENT_ON_THREAD = oldClientOnThread;
        TimeController.resetSingleton();
    }

    @Test
    public void stateNotificationsReuseOneThread() throws Exception {
        Registry registry = mock(Registry.class);
        MasterNodeRemote master = mock(MasterNodeRemote.class);
        when(registry.lookup(MasterNodeRemote.RMI_SERVICE_NAME)).thenReturn(master);
        List<Thread> senders = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            senders.add(Thread.currentThread());
            return null;
        }).when(master).evosuite_informChangeOfStateInClient(eq("Client-0"),
                eq(ClientState.MINIMIZATION), any(ClientStateInformation.class));

        ClientNodeImpl<TestChromosome> client = new ClientNodeImpl<>(registry, "Client-0");
        for (int i = 0; i < 20; i++) {
            client.changeState(ClientState.MINIMIZATION);
        }

        assertEquals(20, senders.size(), "every notification must reach the master");
        Set<Thread> distinct = ConcurrentHashMap.newKeySet();
        distinct.addAll(senders);
        assertEquals(1, distinct.size(), "notifications must not start a thread each");
        assertTrue(senders.get(0).isDaemon());
    }

    @Test
    public void notificationAfterAStuckOneStillReachesTheMaster() throws Exception {
        Registry registry = mock(Registry.class);
        MasterNodeRemote master = mock(MasterNodeRemote.class);
        when(registry.lookup(MasterNodeRemote.RMI_SERVICE_NAME)).thenReturn(master);
        CountDownLatch release = new CountDownLatch(1);
        List<ClientState> delivered = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ClientState state = invocation.getArgument(1);
            if (state == ClientState.SEARCH) {
                // A master that does not answer: stuck until the test ends.
                release.await(30, TimeUnit.SECONDS);
            }
            delivered.add(state);
            return null;
        }).when(master).evosuite_informChangeOfStateInClient(eq("Client-0"),
                any(ClientState.class), any(ClientStateInformation.class));

        ClientNodeImpl<TestChromosome> client = new ClientNodeImpl<>(registry, "Client-0");
        try {
            client.changeState(ClientState.SEARCH);
            client.changeState(ClientState.MINIMIZATION);

            assertTrue(delivered.contains(ClientState.MINIMIZATION),
                    "a stuck notification must not block the ones after it");
        } finally {
            release.countDown();
        }
    }
}
