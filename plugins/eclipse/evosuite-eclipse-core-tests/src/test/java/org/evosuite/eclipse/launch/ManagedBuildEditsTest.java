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
package org.evosuite.eclipse.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ManagedBuildEditsTest {
    @Test
    void mavenKeepsExistingExecutionIdsAndScopes() throws Exception {
        String pom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <!-- project comment -->
                  <dependencies>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId>
                      <version>4.12</version><scope>provided</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin>
                    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>
                    <executions>
                      <execution><id>my-setup</id><goals><goal>setup</goal></goals>
                        <configuration><agent>false</agent></configuration></execution>
                      <execution><id>generate</id><goals><goal>generate</goal></goals></execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """;
        var junit = List.of(new ManagedBuildSetup.Dependency("junit", "junit", "4.13.2"));
        String updated = MavenBuildEdits.configure(pom, junit, "src/test/generated", "1.2.1-SNAPSHOT");
        assertTrue(updated.contains("<!-- project comment -->"));
        assertTrue(updated.contains("<id>my-setup</id>"));
        assertTrue(updated.contains("<id>generate</id>"));
        assertTrue(updated.contains("<agent>false</agent>"));
        assertTrue(updated.contains("<scope>provided</scope>"));
        assertTrue(updated.contains("<version>4.13.2</version>"));
        assertTrue(updated.contains("<testFolder>src/test/generated</testFolder>"));
        assertEquals(1, updated.lines().filter(line -> line.contains("<artifactId>junit</artifactId>")).count());
        assertEquals(updated, MavenBuildEdits.configure(updated, junit, "src/test/generated", "1.2.1-SNAPSHOT"));
    }

    @Test
    void gradleKeepsOneManagedBlockAndUpdatesTheFolder() throws Exception {
        for (boolean kotlin : List.of(false, true)) {
            String source = "plugins { id(\"java\") }\n";
            var dependencies = List.of(new ManagedBuildSetup.Dependency("org.junit.jupiter", "junit-jupiter", "5.14.3"),
                    new ManagedBuildSetup.Dependency("org.junit.platform", "junit-platform-launcher", "1.14.3"));
            String added = GradleBuildEdits.configure(source, dependencies, "src/test/evosuite", "test", kotlin, true);
            assertTrue(added.startsWith(source));
            assertTrue(added.contains("add(\"testRuntimeOnly\", \"org.junit.platform:junit-platform-launcher:1.14.3\")"));
            assertTrue(added.contains("useJUnitPlatform()"));
            assertEquals(added, GradleBuildEdits.configure(added, dependencies, "src/test/evosuite", "test", kotlin, true));
            String moved = GradleBuildEdits.configure(added, dependencies, "src/generated/test", "integrationTest", kotlin, true);
            assertFalse(moved.contains("src/test/evosuite"));
            assertTrue(moved.contains("java.srcDir(\"src/generated/test\")"));
            assertTrue(moved.contains("sourceSets.named(\"integrationTest\")"));
        }
    }
}
