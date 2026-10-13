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
package org.evosuite.testcase.execution;

import org.evosuite.llm.response.TestRepairLoop;
import org.evosuite.runtime.sandbox.Sandbox;

import java.util.Set;

/**
 * Activates the sandbox for SUT execution in the client and re-registers EvoSuite's own worker
 * threads as privileged. Those workers (snippet compilation, parse-phase compilation) are created
 * before the security manager exists, so they must be added after every activation; a new worker
 * pool only has to be added here.
 */
public final class ClientSandbox {

    private ClientSandbox() {
    }

    /**
     * Initializes the sandbox with no additional privileged threads, then registers the client's
     * worker threads as privileged.
     */
    public static void activate() {
        Sandbox.initializeSecurityManagerForSUT();
        registerWorkerThreadsAsPrivileged();
    }

    /**
     * Initializes the sandbox with the given privileged threads, then registers the client's
     * worker threads as privileged.
     *
     * @param privileged threads that keep full permissions under the sandbox
     */
    public static void activate(Set<Thread> privileged) {
        Sandbox.initializeSecurityManagerForSUT(privileged);
        registerWorkerThreadsAsPrivileged();
    }

    private static void registerWorkerThreadsAsPrivileged() {
        ExecutableSnippetEngine.INSTANCE.registerCompilationThreadAsPrivileged();
        TestRepairLoop.registerParseCompileThreadAsPrivileged();
    }
}
