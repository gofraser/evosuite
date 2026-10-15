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

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;

/**
 * Reads the EvoSuite Maven plug-in's configuration through m2e, which provides the effective
 * model: parent poms, plugin management and properties are already resolved. Only loaded when
 * m2e is installed (see {@link MavenSupport}).
 */
final class M2eReader {

    private M2eReader() {
    }

    /** The plug-in's configuration, or {@code null} if the project does not use the plug-in. */
    static PomSettings read(IProject project, IProgressMonitor monitor) throws CoreException {
        IMavenProjectFacade facade = MavenPlugin.getMavenProjectRegistry().getProject(project);
        if (facade == null) {
            return null;
        }
        // m2e, unlike most of Eclipse, does not accept a null monitor.
        MavenProject model = facade.getMavenProject(monitor == null ? new NullProgressMonitor() : monitor);
        if (model == null) {
            return null;
        }
        Plugin plugin = model.getPlugin(PomSettings.GROUP_ID + ":" + PomSettings.ARTIFACT_ID);
        if (plugin == null) {
            return null;
        }
        Map<String, String> pluginConfiguration = new LinkedHashMap<>();
        Map<String, String> generate = new LinkedHashMap<>();
        Map<String, String> setup = new LinkedHashMap<>();
        collect(plugin.getConfiguration(), pluginConfiguration);
        for (PluginExecution execution : plugin.getExecutions()) {
            if (execution.getGoals().contains("generate")) {
                collect(execution.getConfiguration(), generate);
            }
            if (execution.getGoals().contains("setup")) {
                collect(execution.getConfiguration(), setup);
            }
        }
        Map<String, String> properties = new LinkedHashMap<>();
        model.getProperties().forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        return new PomSettings(pluginConfiguration, generate, setup, properties);
    }

    private static void collect(Object configuration, Map<String, String> values) {
        if (!(configuration instanceof Xpp3Dom)) {
            return;
        }
        for (Xpp3Dom child : ((Xpp3Dom) configuration).getChildren()) {
            if (child.getChildCount() == 0 && child.getValue() != null) {
                values.put(child.getName(), child.getValue());
            }
        }
    }
}
