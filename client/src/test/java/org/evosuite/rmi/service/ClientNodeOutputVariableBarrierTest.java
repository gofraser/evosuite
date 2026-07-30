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
 */
package org.evosuite.rmi.service;

import org.evosuite.Properties;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestChromosome;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.rmi.registry.Registry;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ClientNodeOutputVariableBarrierTest {

    private boolean oldClientOnThread;

    @Before
    public void saveProperties() {
        oldClientOnThread = Properties.CLIENT_ON_THREAD;
        Properties.CLIENT_ON_THREAD = false;
    }

    @After
    public void restoreProperties() {
        Properties.CLIENT_ON_THREAD = oldClientOnThread;
    }

    @Test
    public void barrierWaitsUntilEarlierVariablesReachMaster() throws Exception {
        Registry registry = mock(Registry.class);
        MasterNodeRemote master = mock(MasterNodeRemote.class);
        when(registry.lookup(MasterNodeRemote.RMI_SERVICE_NAME)).thenReturn(master);

        ClientNodeImpl<TestChromosome> client = new ClientNodeImpl<>(registry, "Client-0");
        assertTrue(client.init());
        try {
            client.trackOutputVariable(RuntimeVariable.ObjectRuleFirstShotSuccesses, 7);
            client.trackOutputVariable(RuntimeVariable.ObjectRuleReachedNotCovered, 7);

            assertTrue(client.awaitPendingOutputVariables(5_000L));

            InOrder order = inOrder(master);
            order.verify(master).evosuite_collectStatistics(
                    "Client-0", RuntimeVariable.ObjectRuleFirstShotSuccesses, 7);
            order.verify(master).evosuite_collectStatistics(
                    "Client-0", RuntimeVariable.ObjectRuleReachedNotCovered, 7);
        } finally {
            client.stop();
        }
    }
}
