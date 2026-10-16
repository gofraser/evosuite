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
package org.evosuite.executionmode;

import com.examples.with.different.packagename.StaticPrinting;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers the case where the master spawns a real client process (not "on thread") but the
 * client never manages to register back via RMI within the configured timeout. In production
 * this used to be logged as an error but the master process would still exit 0, so a driving
 * wrapper script (e.g. on a cluster) would never retry the run. See
 * {@link TestGeneration#generateTests}.
 */
public class ClientConnectionTimeoutSystemTest extends SystemTestBase {

    @Test
    public void timedOutClientConnectionAbortsWithException() {
        Properties.CLIENT_ON_THREAD = false;
        // A 1ms budget is not enough to spawn a JVM and have it register back via RMI,
        // so this deterministically drives the "timed out waiting for client(s)" path
        // regardless of machine speed.
        Properties.CLIENT_CONNECTION_TIMEOUT_MS = 1;

        String targetClass = StaticPrinting.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;

        EvoSuite evosuite = new EvoSuite();
        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> evosuite.parseCommandLine(command),
                "Expected the master to abort (non-zero exit path) when no client connects in time");

        Assertions.assertTrue(ex.getMessage().contains("Timed out"),
                "Unexpected exception message: " + ex.getMessage());
    }
}
