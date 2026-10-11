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
import org.mockito.Mockito;

import java.rmi.registry.Registry;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client process waits in {@code waitUntilDone()} until the search task reaches the DONE
 * transition. A search that dies of heap exhaustion can also die in the code that reports the
 * failure, and the executor swallows what the task throws, so the client used to hang until the
 * master gave up on it.
 */
public class ClientNodeSearchTaskAbortTest {

    private boolean oldClientOnThread;
    private boolean oldSandbox;

    @BeforeEach
    public void saveProperties() {
        oldClientOnThread = Properties.CLIENT_ON_THREAD;
        oldSandbox = Properties.SANDBOX;
        Properties.CLIENT_ON_THREAD = false;
        Properties.SANDBOX = false;
        TimeController.resetSingleton();
    }

    @AfterEach
    public void restoreProperties() {
        ClientNodeImpl.SEARCH_FOR_TESTING = null;
        Properties.CLIENT_ON_THREAD = oldClientOnThread;
        Properties.SANDBOX = oldSandbox;
        TimeController.resetSingleton();
    }

    /** An error that cannot even be turned into a message, as when there is no memory left. */
    private static class UnprintableError extends Error {
        @Override
        public String toString() {
            throw new IllegalStateException("no memory to print this");
        }
    }

    /** A client whose DONE transition fails with an {@link UnprintableError}. */
    private static class FailingDoneClient extends ClientNodeImpl<TestChromosome> {
        FailingDoneClient(Registry registry) {
            super(registry, "Client-0");
        }

        @Override
        public void changeState(ClientState state, ClientStateInformation information) {
            if (state == ClientState.DONE) {
                throw new UnprintableError();
            }
            super.changeState(state, information);
        }
    }

    @Test
    public void clientIsReleasedWhenTheSearchAndItsFailureHandlingBothDie() throws Exception {
        ClientNodeImpl.SEARCH_FOR_TESTING = () -> {
            throw new UnprintableError();
        };
        FailingDoneClient client = new FailingDoneClient(Mockito.mock(Registry.class));

        client.startNewSearch();

        assertTrue(client.doneLatch.await(30, TimeUnit.SECONDS),
                "the client must be released, not left waiting for the master's timeout");
    }

    @Test
    public void doneTransitionReleasesTheClientEvenIfBookkeepingFails() throws Exception {
        ClientNodeImpl<TestChromosome> client =
                new ClientNodeImpl<>(Mockito.mock(Registry.class), "Client-0");
        // A phase that precedes the one the time controller is in makes updateState() throw.
        TimeController.getInstance().updateState(ClientState.FINISHED);
        try {
            client.changeState(ClientState.DONE);
        } catch (RuntimeException expected) {
            // the bookkeeping failure is still reported
        }

        assertTrue(client.doneLatch.await(5, TimeUnit.SECONDS));
    }
}
