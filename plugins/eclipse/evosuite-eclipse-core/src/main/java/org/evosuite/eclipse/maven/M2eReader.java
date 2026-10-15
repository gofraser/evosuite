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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.swt.widgets.Display;

/**
 * Reads the EvoSuite Maven plug-in's configuration through m2e, which provides the effective
 * model: parent poms, plugin management and properties are already resolved. Only loaded when
 * m2e is installed (see {@link MavenSupport}).
 */
final class M2eReader {

    /** The id of the execution that runs a goal invoked from the command line. */
    static final String DEFAULT_CLI = "default-cli";

    private M2eReader() {
    }

    /**
     * The plug-in's configuration, {@code null} if the project does not use the plug-in, or
     * {@link PomSettings#NOT_LOADED} on the UI thread if m2e has not loaded the pom yet.
     */
    static PomSettings read(IProject project, IProgressMonitor monitor) throws CoreException {
        IMavenProjectFacade facade = MavenPlugin.getMavenProjectRegistry().getProject(project);
        return facade == null ? null : read(facade, monitor);
    }

    static PomSettings read(IMavenProjectFacade facade, IProgressMonitor monitor) throws CoreException {
        MavenProject model;
        if (Display.getCurrent() != null) {
            // Loading the model can read poms and resolve dependencies over the network, which
            // must not block the UI, so the UI thread only takes what m2e has loaded already.
            model = facade.getMavenProject();
            if (model == null) {
                return PomSettings.NOT_LOADED;
            }
        } else {
            // m2e, unlike most of Eclipse, does not accept a null monitor.
            model = facade.getMavenProject(monitor == null ? new NullProgressMonitor() : monitor);
        }
        if (model == null) {
            return null;
        }
        return read(model);
    }

    private static PomSettings read(MavenProject model) {
        Plugin plugin = plugin(model);
        if (plugin == null) {
            return null;
        }
        Map<String, String> pluginConfiguration = new LinkedHashMap<>();
        Map<String, String> generate = new LinkedHashMap<>();
        Map<String, String> setup = new LinkedHashMap<>();
        collect(plugin.getConfiguration(), pluginConfiguration);
        for (PluginExecution execution : plugin.getExecutions()) {
            // mvn evosuite:generate runs as the execution default-cli, which gets the plug-in's
            // configuration and that of an execution with this id, but not that of other
            // executions of the goal: those only run if the pom binds them to a phase.
            if (DEFAULT_CLI.equals(execution.getId())) {
                collect(execution.getConfiguration(), generate);
            }
            if (execution.getGoals().contains("setup")) {
                collect(execution.getConfiguration(), setup);
            }
        }
        Map<String, String> properties = new LinkedHashMap<>();
        model.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        // As the Maven plug-in decides the test format: JUnit 4 if the project has junit:junit
        // among its dependencies but not junit-jupiter-api.
        boolean junit4 = false;
        boolean jupiter = false;
        for (Artifact artifact : model.getArtifacts()) {
            junit4 |= "junit".equals(artifact.getGroupId()) && "junit".equals(artifact.getArtifactId());
            jupiter |= "org.junit.jupiter".equals(artifact.getGroupId())
                    && "junit-jupiter-api".equals(artifact.getArtifactId());
        }
        return new PomSettings(pluginConfiguration, generate, setup, properties, junit4 && !jupiter);
    }

    /**
     * The plug-in as {@code mvn evosuite:generate} finds it: among the build plug-ins, else in
     * the plug-in management, whose configuration Maven also applies to a goal invoked by name.
     * A pom that only sets {@code evosuite.*} properties does not count: such properties, like
     * {@code evosuite.version}, are as likely to serve other purposes.
     */
    private static Plugin plugin(MavenProject model) {
        String key = PomSettings.GROUP_ID + ":" + PomSettings.ARTIFACT_ID;
        Plugin plugin = model.getPlugin(key);
        if (plugin == null && model.getPluginManagement() != null) {
            plugin = model.getPluginManagement().getPluginsAsMap().get(key);
        }
        return plugin;
    }

    static String serverPassword(String serverId) throws CoreException {
        Server server = MavenPlugin.getMaven().getSettings().getServer(serverId);
        if (server == null) {
            return null;
        }
        String password = MavenPlugin.getMaven().decryptPassword(server).getPassword();
        return password == null || password.isBlank() ? null : password;
    }

    private static void collect(Object configuration, Map<String, String> values) {
        if (!(configuration instanceof Xpp3Dom)) {
            return;
        }
        for (Xpp3Dom child : ((Xpp3Dom) configuration).getChildren()) {
            if (child.getChildCount() == 0 && child.getValue() != null) {
                values.put(child.getName(), child.getValue());
            } else if ("evosuiteProperties".equals(child.getName())) {
                for (Xpp3Dom entry : child.getChildren()) {
                    values.put(PomSettings.PROPERTY_PREFIX + entry.getName(),
                            entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }
    }
}
