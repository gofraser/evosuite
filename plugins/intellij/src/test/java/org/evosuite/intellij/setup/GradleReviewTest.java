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
package org.evosuite.intellij.setup;

import com.intellij.buildsystem.model.unified.UnifiedDependency;

import junit.framework.TestCase;

import java.nio.file.*;
import java.util.*;

public class GradleReviewTest extends TestCase {
    public void testMissingSubprojectScriptUsesSurroundingKotlinDsl() throws Exception {
        Path root = Files.createTempDirectory("evosuite-kotlin-dsl-");
        try {
            Files.writeString(root.resolve("settings.gradle.kts"), "include(\"app\")");
            Path app = Files.createDirectories(root.resolve("app"));
            assertEquals(app.resolve("build.gradle.kts"), GradleDependencySetup.buildFile(app));
            Files.writeString(app.resolve("build.gradle"), "");
            assertEquals(app.resolve("build.gradle"), GradleDependencySetup.buildFile(app));
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    public void testJUnit4ExplicitlyOverridesAnExistingJUnitPlatformTask() {
        for (boolean kotlin : List.of(false, true)) {
            String original = "tasks.test { useJUnitPlatform() }\n";
            String configured =
                    GradleDependencySetup.update(
                            original,
                            GradleDependencySetup.configuration(
                                    "custom/tests", "test", kotlin, false));
            assertTrue(
                    configured.lastIndexOf("useJUnit()")
                            > configured.indexOf("useJUnitPlatform()"));
            var dependency =
                    new UnifiedDependency("junit", "junit", "4.13.2", "testImplementation");
            configured = GradleDependencySetup.updateDependencies(configured, List.of(dependency));
            assertEquals(
                    configured,
                    GradleDependencySetup.updateDependencies(configured, List.of(dependency)));
            String changedRoot =
                    GradleDependencySetup.update(
                            configured,
                            GradleDependencySetup.configuration(
                                    "new/tests", "test", kotlin, false));
            assertTrue(changedRoot.contains("testImplementation(\"junit:junit:4.13.2\")"));
            assertFalse(changedRoot.contains("custom/tests"));
        }
    }

    public void testUnrelatedDeclarationsDoNotSuppressNeededTestDependency() {
        String script = "// junit:junit:4.13.2 is available\n"
                + "dependencies { compileOnly(\"junit:junit:4.13.2\") }\n";
        var dependency = new UnifiedDependency("junit", "junit", "4.13.2", "testImplementation");
        String updated = GradleDependencySetup.updateDependencies(script, List.of(dependency));
        assertTrue(updated.startsWith(script));
        assertTrue(updated.contains("testImplementation(\"junit:junit:4.13.2\")"));
        assertEquals(updated, GradleDependencySetup.updateDependencies(updated, List.of(dependency)));
    }
}
