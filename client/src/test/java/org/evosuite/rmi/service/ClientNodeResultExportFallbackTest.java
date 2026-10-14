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

import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the full and the lightweight result exports both fail, the client falls back to sending
 * a coverage summary. That last step must go through the same bounded remote-action path as the
 * other two: a call made directly on the calling (search) thread has no timeout, so a master
 * that stopped answering hangs the client.
 *
 * <p>The test does not wait for a timeout. It checks on which thread the summary reaches the
 * master: the bounded path runs remote calls on the client's remote-action thread.
 */
public class ClientNodeResultExportFallbackTest {

    @Test
    public void coverageSummaryFallbackIsNotSentFromTheCallingThread() throws Exception {
        Registry registry = mock(Registry.class);
        MasterNodeRemote master = mock(MasterNodeRemote.class);
        when(registry.lookup(MasterNodeRemote.RMI_SERVICE_NAME)).thenReturn(master);
        doThrow(new RemoteException("result export refused"))
                .when(master).evosuite_collectTestGenerationResult(anyString(), anyList());
        List<Thread> summaryThreads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            summaryThreads.add(Thread.currentThread());
            return null;
        }).when(master).evosuite_collectStatistics(anyString(), any(RuntimeVariable.class), any());

        ClientNodeImpl<TestChromosome> client = new ClientNodeImpl<>(registry, "Client-0");
        try {
            List<TestGenerationResult> results =
                    Collections.singletonList(TestGenerationResultBuilder.buildSuccessResult());
            Method send = ClientNodeImpl.class.getDeclaredMethod(
                    "sendTestGenerationResultBestEffort", List.class);
            send.setAccessible(true);
            send.invoke(client, results);
        } finally {
            client.stop();
        }

        assertFalse(summaryThreads.isEmpty(), "precondition: the coverage summary was sent");
        for (Thread thread : summaryThreads) {
            assertTrue(thread != Thread.currentThread(),
                    "the coverage summary was sent directly on the calling thread, without a timeout");
        }
    }
}
