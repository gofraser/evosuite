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
package org.evosuite.maven.util;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GenerationSettingsTest {

    @Test
    public void memoryFollowsTheCoresUnlessConfigured() {
        // two cores used to fail with the fixed default of 800 MB, as CTG needs 500 per core
        assertEquals(2000, GenerationSettings.memoryInMB(null, 2));
        assertEquals(1000, GenerationSettings.memoryInMB(null, 1));
        assertEquals(1500, GenerationSettings.memoryInMB(1500, 2));
    }

    @Test
    public void durationsNeedUnits() {
        assertEquals(30, GenerationSettings.parseSeconds("30s"));
        assertEquals(120, GenerationSettings.parseSeconds("2m"));
        assertEquals(90, GenerationSettings.parseSeconds("1m30s"));
        assertEquals(3600, GenerationSettings.parseSeconds(" 1H "));
        for (String invalid : Arrays.asList("90", "", "1.5m", "m", "30 s")) {
            assertThrows(IllegalArgumentException.class, () -> GenerationSettings.parseSeconds(invalid), invalid);
        }
    }

    @Test
    public void projectsWithOnlyJUnit4GetJUnit4Tests() {
        Artifact junit4 = artifact("junit", "junit");
        Artifact jupiter = artifact("org.junit.jupiter", "junit-jupiter-api");

        assertEquals("JUNIT4", GenerationSettings.testFormatFor(Collections.singletonList(junit4)));
        assertNull(GenerationSettings.testFormatFor(Arrays.asList(junit4, jupiter)), "JUnit 5 wins when both");
        assertNull(GenerationSettings.testFormatFor(Collections.singletonList(jupiter)));
        assertNull(GenerationSettings.testFormatFor(Collections.emptyList()));
    }

    private static Artifact artifact(String groupId, String artifactId) {
        return new DefaultArtifact(groupId, artifactId, "1.0", "test", "jar", null, new DefaultArtifactHandler("jar"));
    }
}
