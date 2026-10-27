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

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The classes EvoSuite works on and the classpath it runs them on, as the goals of the plugin pass
 * them to EvoSuite.
 */
public final class ProjectClasspath {

    private final String target;

    private final String classpath;

    private ProjectClasspath(String target, String classpath) {
        this.target = target;
        this.classpath = classpath;
    }

    /**
     * Collects the target and the classpath of a project.
     *
     * <p>The target is what the project compiled to a folder. In a multi-module build, the folders of
     * the modules the project depends on are on its compile classpath too, but only its own are
     * targeted. The classpath is the test classpath without the libraries that bring their own
     * instrumentation and clash with EvoSuite's.</p>
     *
     * @param project the project the goal runs on
     * @param log     where skipped libraries are reported
     * @return the target and the classpath, each {@code null} if empty
     * @throws MojoExecutionException if the dependencies of the project are not resolved
     */
    public static ProjectClasspath of(MavenProject project, Log log) throws MojoExecutionException {
        try {
            Set<String> targets = new LinkedHashSet<>();
            for (String element : project.getCompileClasspathElements()) {
                File file = new File(element);
                if (!element.endsWith(".jar") && file.exists()
                        && file.getAbsolutePath().startsWith(project.getBuild().getOutputDirectory())) {
                    targets.add(element);
                }
            }

            Set<String> elements = new LinkedHashSet<>();
            for (String element : project.getTestClasspathElements()) {
                String lower = element.toLowerCase();
                if (lower.contains("powermock")) {
                    // PowerMock just leads to a lot of troubles, as it includes tools.jar code
                    log.warn("Skipping PowerMock dependency at: " + element);
                } else if (lower.contains("jmockit")) {
                    // JMockit has same issue
                    log.warn("Skipping JMockit dependency at: " + element);
                } else if (new File(element).exists()) {
                    log.debug("TEST ELEMENT: " + element);
                    elements.add(element);
                }
            }
            return new ProjectClasspath(join(targets), join(elements));
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Failed to resolve the project classpath: " + e.getMessage(), e);
        }
    }

    private static String join(Set<String> elements) {
        return elements.isEmpty() ? null : String.join(File.pathSeparator, elements);
    }

    /**
     * @return the classes to work on, separated by the path separator, or {@code null} if none
     */
    public String getTarget() {
        return target;
    }

    /**
     * @return the classpath of the tests, separated by the path separator, or {@code null} if empty
     */
    public String getClasspath() {
        return classpath;
    }

    /**
     * @return whether there is nothing to run EvoSuite on
     */
    public boolean isEmpty() {
        return target == null || classpath == null;
    }

    /**
     * Writes the classpath to a temporary file that EvoSuite reads, as long classpaths do not fit on
     * the command line of every operating system.
     *
     * @return the file, which is deleted when Maven exits
     * @throws IOException if the file cannot be written
     */
    public File writeClasspathToFile() throws IOException {
        File file = File.createTempFile("EvoSuite_classpathFile", ".txt");
        file.deleteOnExit();
        Files.write(file.toPath(), Collections.singletonList(classpath), StandardCharsets.UTF_8);
        return file;
    }
}
