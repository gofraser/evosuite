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
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    public void explicitArgLinesWithoutTheAgentAreFound() {
        Plugin surefire = testPlugin("maven-surefire-plugin", "-Xmx2g");
        Plugin failsafe = testPlugin("maven-failsafe-plugin", "@{argLine} -Xmx2g");
        Plugin compiler = testPlugin("maven-compiler-plugin", "-Xmx2g"); // not a test plugin

        assertEquals(Collections.singletonList("maven-surefire-plugin"),
                SetupMojo.pluginsIgnoringArgLine(Arrays.asList(surefire, failsafe, compiler), "argLine"));
        assertTrue(SetupMojo.pluginsIgnoringArgLine(
                Collections.singletonList(testPlugin("maven-surefire-plugin", "${argLine} -ea")), "argLine").isEmpty());
        assertTrue(SetupMojo.pluginsIgnoringArgLine(
                Collections.singletonList(testPlugin("maven-surefire-plugin", null)), "argLine").isEmpty());
    }

    @Test
    public void argLinesOfExecutionsCountToo() {
        Plugin surefire = testPlugin("maven-surefire-plugin", null);
        PluginExecution execution = new PluginExecution();
        execution.setConfiguration(configuration("-Xmx2g"));
        surefire.addExecution(execution);

        assertEquals(Collections.singletonList("maven-surefire-plugin"),
                SetupMojo.pluginsIgnoringArgLine(Collections.singletonList(surefire), "argLine"));
    }

    private static Plugin testPlugin(String artifactId, String argLine) {
        Plugin plugin = new Plugin();
        plugin.setArtifactId(artifactId);
        if (argLine != null) {
            plugin.setConfiguration(configuration(argLine));
        }
        return plugin;
    }

    private static Xpp3Dom configuration(String argLine) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom child = new Xpp3Dom("argLine");
        child.setValue(argLine);
        configuration.addChild(child);
        return configuration;
    }
}
