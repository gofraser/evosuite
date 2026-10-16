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
package org.evosuite.eclipse.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.ResolverConfiguration;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.ProjectSetup;
import org.junit.jupiter.api.Test;

/**
 * The EvoSuite Maven plug-in's m2e lifecycle mapping: m2e runs its setup goal when it configures
 * a project, which makes the generated-tests folder a test source folder, and leaves the other
 * goals alone. Needs the Maven plug-in in a local repository that m2e can use offline, named by
 * a settings file: {@code -Devosuite.it.mavenSettings=/path/to/settings.xml}. Skipped otherwise.
 */
public class MavenPluginLifecycleMappingTest {

    private static final String POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
            + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
            + "    xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>t</groupId><artifactId>wired</artifactId><version>1</version>\n"
            + "  <properties><maven.compiler.release>17</maven.compiler.release>"
            + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>\n"
            + "  <build><plugins><plugin>\n"
            + "    <groupId>org.evosuite.plugins</groupId><artifactId>evosuite-maven-plugin</artifactId>\n"
            + "    <version>1.2.1-SNAPSHOT</version>\n"
            + "    <executions>\n"
            + "      <execution><id>wire</id><goals><goal>setup</goal></goals></execution>\n"
            + "      <execution><id>gen</id><phase>process-test-classes</phase>"
            + "<goals><goal>generate</goal></goals></execution>\n"
            + "    </executions>\n"
            + "  </plugin></plugins></build>\n"
            + "</project>\n";

    @Test
    public void m2eRunsSetupAndKeepsTheTestFolder() throws Exception {
        String settings = System.getProperty("evosuite.it.mavenSettings", "");
        assumeTrue(!settings.isBlank() && !"null".equals(settings), "no Maven settings for the m2e test");
        MavenPlugin.getMavenConfiguration().setUserSettingsFile(settings);

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("wired");
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        TestProjects.folder(project, "src/main/java");
        TestProjects.folder(project, "src/test/evosuite");
        project.getFile("pom.xml").create(new ByteArrayInputStream(POM.getBytes(StandardCharsets.UTF_8)), true, null);

        MavenPlugin.getProjectConfigurationManager().enableMavenNature(project, new ResolverConfiguration(),
                new NullProgressMonitor());
        assertWired(project);

        // Maven > Update Project, which is where hand-made build path entries get lost.
        MavenPlugin.getProjectConfigurationManager().updateProjectConfiguration(project, new NullProgressMonitor());
        assertWired(project);

        // With the folder in place, the Eclipse plug-in has nothing to add.
        IJavaProject javaProject = JavaCore.create(project);
        long stamp = project.getFile(".classpath").getModificationStamp();
        GenerationOptions options = new GenerationOptions();
        ProjectSetup.prepareTestFolder(javaProject, options, null);
        assertEquals(stamp, project.getFile(".classpath").getModificationStamp());
    }

    private static void assertWired(IProject project) throws Exception {
        List<String> problems = new ArrayList<>();
        for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
            if (marker.getAttribute(IMarker.SEVERITY, -1) >= IMarker.SEVERITY_WARNING
                    && marker.getType().startsWith("org.eclipse.m2e")) {
                problems.add(marker.getType() + ": " + marker.getAttribute(IMarker.MESSAGE, ""));
            }
        }
        assertEquals(List.of(), problems, "m2e reports problems with the pom");

        IJavaProject javaProject = JavaCore.create(project);
        assertTrue(javaProject.exists(), "m2e did not make a Java project");
        IClasspathEntry folder = null;
        for (IClasspathEntry entry : javaProject.getRawClasspath()) {
            if (entry.getPath().equals(IPath.fromOSString("/wired/src/test/evosuite"))) {
                folder = entry;
            }
        }
        assertNotNull(folder, "src/test/evosuite is not on the build path: " + List.of(javaProject.getRawClasspath()));
        assertEquals(IClasspathEntry.CPE_SOURCE, folder.getEntryKind());
        assertTrue(folder.isTest(), "src/test/evosuite is not a test source folder");
    }
}
