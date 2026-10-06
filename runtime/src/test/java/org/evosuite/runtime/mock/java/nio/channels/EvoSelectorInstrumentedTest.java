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
package org.evosuite.runtime.mock.java.nio.channels;

import org.evosuite.runtime.EvoRunner;
import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.vnet.VirtualNetwork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationTargetException;

/**
 * Loads generated-test-shaped selector code after the agent is active. The
 * probe is loaded by name only, so its ordinary JDK factory calls are
 * transformed and this test covers MockList registration.
 */
@RunWith(EvoRunner.class)
@EvoRunnerParameters(useVNET = true)
public class EvoSelectorInstrumentedTest {

    @Before
    public void resetNetwork() {
        VirtualNetwork.getInstance().reset();
    }

    @After
    public void deactivateAgent() {
        InstrumentingAgent.deactivate();
    }

    @Test
    public void ordinaryFactoriesShareVirtualProviderAndExchangeData() throws Exception {
        invokeProbe("ordinaryFactoriesShareVirtualProviderAndExchangeData");
    }

    @Test
    public void selectedKeysRetainReadinessUntilRemovedAndCancelledKeysDeregister() throws Exception {
        invokeProbe("selectedKeysRetainReadinessUntilRemovedAndCancelledKeysDeregister");
    }

    @Test
    public void blockedSelectWakesForIncomingConnectionAndClientData() throws Exception {
        invokeProbe("blockedSelectWakesForIncomingConnectionAndClientData");
    }

    @Test
    public void blockedReadWakesForSocketInputShutdown() throws Exception {
        invokeProbe("blockedReadWakesForSocketInputShutdown");
    }

    @Test
    public void longTimeoutWakeupInterruptAndCloseReleaseBlockedSelection() throws Exception {
        invokeProbe("longTimeoutWakeupInterruptAndCloseReleaseBlockedSelection");
    }

    @Test
    public void disabledFactoriesAndForeignProvidersRemainNative() throws Exception {
        invokeProbe("disabledFactoriesAndForeignProvidersRemainNative");
    }

    /** Runs outside instrumentation, observing a real selector monitor wait. */
    public static void awaitSelectorWait(Thread thread) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            if (!thread.isAlive()) {
                throw new AssertionError("selection finished before it reached the network wait");
            }
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals("org.evosuite.runtime.vnet.VirtualNetwork")
                            && frame.getMethodName().equals("awaitStateChange")) {
                        return;
                    }
                }
            }
            Thread.yield();
        }
        throw new AssertionError("selection did not reach the network wait");
    }

    private static void invokeProbe(String method) throws Exception {
        InstrumentingAgent.activate();
        try {
            Class<?> probe = Class.forName(
                    "com.examples.selector.EvoSelectorInstrumentedProbe");
            try {
                probe.getMethod(method).invoke(null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw e;
            }
        } finally {
            InstrumentingAgent.deactivate();
        }
    }
}
