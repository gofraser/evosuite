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
package org.evosuite.runtime.sandbox;

import org.evosuite.testcase.execution.ExecutableSnippetEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketPermission;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SUT code that LLM-generated snippets call must get the same permissions as SUT code in a
 * regular test, so having the snippet engine on the stack must not open the network.
 */
public class MSecurityManagerSnippetEngineTest {

    private MSecurityManager securityManager;
    private ExecutorService executor;

    @BeforeEach
    public void init() {
        executor = Executors.newSingleThreadExecutor();
        securityManager = new MSecurityManager();
        securityManager.apply();
        securityManager.goingToExecuteTestCase();
    }

    @AfterEach
    public void done() {
        securityManager.goingToEndTestCase();
        securityManager.restoreDefaultManager();
        executor.shutdownNow();
    }

    private void checkConnect(boolean viaSnippetEngine) throws Exception {
        Runnable sut = () -> System.getSecurityManager()
                .checkPermission(new SocketPermission("example.org:80", "connect,resolve"));
        executor.submit(() -> {
            if (viaSnippetEngine) {
                ExecutableSnippetEngine.invoke(sut);
            } else {
                sut.run();
            }
        }).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void networkIsDeniedToSutCodeInRegularTest() {
        ExecutionException e = assertThrows(ExecutionException.class, () -> checkConnect(false));
        assertInstanceOf(SecurityException.class, e.getCause());
    }

    @Test
    public void networkIsDeniedToSutCodeCalledFromSnippetEngine() {
        ExecutionException e = assertThrows(ExecutionException.class, () -> checkConnect(true));
        assertInstanceOf(SecurityException.class, e.getCause());
    }
}
