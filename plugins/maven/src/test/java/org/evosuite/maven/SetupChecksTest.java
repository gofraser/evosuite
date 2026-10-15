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
package org.evosuite.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SetupChecksTest {

    @Test
    public void runtimeHasToMatchThePluginVersion() {
        Artifact runtime = new DefaultArtifact("org.evosuite", "evosuite-standalone-runtime", "1.2.0", "test", "jar",
                null, new DefaultArtifactHandler("jar"));

        String mismatch = SetupMojo.versionMismatch("1.2.1", runtime);
        assertTrue(mismatch.contains("1.2.0") && mismatch.contains("1.2.1"), mismatch);
        assertNull(SetupMojo.versionMismatch("1.2.0", runtime));
    }
}
