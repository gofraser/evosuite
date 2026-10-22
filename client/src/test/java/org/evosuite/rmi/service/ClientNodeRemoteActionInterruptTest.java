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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search thread is interrupted when the client stops, which can happen while it still waits
 * for the master to acknowledge the DONE notification. That interrupt only ends the wait: the
 * notification already in flight must not be interrupted in the middle of its RMI call, and the
 * notification that follows (FINISHED) must still reach the master.
 */
public class ClientNodeRemoteActionInterruptTest {

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
    public void interruptedCallerLeavesTheNotificationInFlightAlone() throws Exception {
        Registry registry = mock(Registry.class);
        MasterNodeRemote master = mock(MasterNodeRemote.class);
        when(registry.lookup(MasterNodeRemote.RMI_SERVICE_NAME)).thenReturn(master);
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptedMidCall = new AtomicBoolean();
        List<ClientState> delivered = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ClientState state = invocation.getArgument(1);
            if (state == ClientState.DONE) {
                inFlight.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interruptedMidCall.set(true);
                    throw e;
                }
            }
            delivered.add(state);
            return null;
        }).when(master).evosuite_informChangeOfStateInClient(any(String.class),
                any(ClientState.class), any(ClientStateInformation.class));

        ClientNodeImpl<TestChromosome> client = new ClientNodeImpl<>(registry, "Client-0");
        Thread search = new Thread(() -> client.changeState(ClientState.DONE));
        search.start();
        try {
            assertTrue(inFlight.await(10, TimeUnit.SECONDS), "precondition: DONE is in flight");
            search.interrupt();
            search.join(10_000);
            assertFalse(search.isAlive(), "the interrupted caller must stop waiting");

            release.countDown();
            client.changeState(ClientState.FINISHED);

            assertFalse(interruptedMidCall.get(), "the in-flight notification was interrupted");
            assertTrue(delivered.contains(ClientState.DONE), "DONE must reach the master");
            assertTrue(delivered.contains(ClientState.FINISHED), "FINISHED must reach the master");
        } finally {
            release.countDown();
        }
    }
}
