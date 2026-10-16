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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.evosuite.maven.util.EvoSuiteRunner;

import java.util.List;
import java.util.Properties;

/**
 * What the goals that run EvoSuite in a separate process have in common.
 */
public abstract class AbstractEvoSuiteMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${plugin.artifacts}", required = true, readonly = true)
    protected List<Artifact> artifacts;

    @Component
    protected ToolchainManager toolchainManager;

    /**
     * Create a runner that starts EvoSuite with the JDK of the build's toolchain, if one
     * is selected (eg with maven-toolchains-plugin), and otherwise with the one Maven uses.
     *
     * @return the runner
     */
    protected EvoSuiteRunner createRunner() {
        String java = null;
        Toolchain toolchain = toolchainManager == null ? null
                : toolchainManager.getToolchainFromBuildContext("jdk", session);
        if (toolchain != null) {
            java = toolchain.findTool("java");
            getLog().info("Using toolchain " + toolchain + ": " + java);
        }
        return new EvoSuiteRunner(getLog(), artifacts, java);
    }

    /**
     * Value of a property under its old, unprefixed name, for callers (eg the IntelliJ plugin) that still
     * pass {@code -DoldName=...} and poms that still set {@code <oldName>} as a property. Maven binds a
     * parameter to one property only, so the old name has to be looked up by hand. As in Maven, the command
     * line takes precedence over the pom, and the new name over the old one given the same way.
     *
     * @param newKey the current property name
     * @param oldKey the deprecated property name
     * @return the value given under the old name, or {@code null} if the new name decides
     */
    protected String legacyProperty(String newKey, String oldKey) {
        Properties user = session == null ? null : session.getUserProperties();
        Properties pom = project == null ? null : project.getProperties();
        for (Properties properties : new Properties[]{user, pom}) {
            if (properties == null) {
                continue;
            }
            if (properties.containsKey(newKey)) {
                return null;
            }
            if (properties.containsKey(oldKey)) {
                getLog().warn("Property '" + oldKey + "' is deprecated, use '" + newKey + "' instead");
                return properties.getProperty(oldKey);
            }
        }
        return null;
    }
}
