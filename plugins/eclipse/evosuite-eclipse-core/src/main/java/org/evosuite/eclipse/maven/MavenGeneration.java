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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.m2e.core.MavenPlugin;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.EvoSuiteJars;
import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.GenerationOptions;

public final class MavenGeneration {
    private static final String TYPE = "org.eclipse.m2e.Maven2LaunchConfigurationType";

    private MavenGeneration() {}

    public static boolean canRun(IJavaProject project, GenerationOptions options) throws CoreException {
        if (!options.usePomSettings || !"_ESTest".equals(options.testSuffix)
                || !project.getProject().hasNature(MavenSupport.MAVEN_NATURE)
                || manager().getLaunchConfigurationType(TYPE) == null
                || !MavenSupport.m2eInstalled.getAsBoolean()
                || EvoSuiteCommand.additionalArguments(options).stream().anyMatch(arg -> arg.startsWith("-Djunit_suffix="))) {
            return false;
        }
        try {
            for (String variable : List.of("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                    "_JAVA_OPTIONS")) {
                if (overridesOutput(System.getenv().getOrDefault(variable, ""))) return false;
            }
            for (Path dir = project.getProject().getLocation().toFile().toPath(); dir != null; dir = dir.getParent()) {
                for (String config : List.of("maven.config", "jvm.config")) {
                    Path file = dir.resolve(".mvn").resolve(config);
                    if (Files.isRegularFile(file) && overridesOutput(Files.readString(file))) return false;
                }
            }
            return true;
        } catch (IOException e) {
            throw new CoreException(Activator.error("Cannot read the Maven invocation configuration.", e));
        }
    }

    private static boolean overridesOutput(String text) {
        return text.contains("evosuite.testFolder") || text.contains("evosuite.cuts")
                || text.contains("-DtargetFolder") || text.contains("-Dcuts");
    }

    public static int run(IJavaProject project, String target, Path staging, Map<String, String> environment,
                          IProgressMonitor monitor) throws CoreException, InterruptedException {
        String path = staging.toAbsolutePath().toString();
        if (path.contains("\"") || path.contains("\n") || path.contains("\r")) {
            throw new CoreException(Activator.error("The staging path cannot be passed to the Maven launcher."));
        }
        var manager = manager();
        var type = manager.getLaunchConfigurationType(TYPE);
        if (type == null) throw new CoreException(Activator.error("The m2e Maven launcher is not available."));
        var configuration = type.newInstance(null, manager.generateLaunchConfigurationName("EvoSuite Maven — " + target));
        var maven = MavenPlugin.getMavenConfiguration();
        var facade = MavenPlugin.getMavenProjectRegistry().getProject(project.getProject());
        configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
                project.getProject().getLocation().toOSString());
        configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, project.getElementName());
        configuration.setAttribute(IJavaLaunchConfigurationConstants.ATTR_JRE_CONTAINER_PATH,
                JavaRuntime.computeJREEntry(project).getPath().toPortableString());
        configuration.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, environment);
        configuration.setAttribute("M2_GOALS", "compile org.evosuite.plugins:evosuite-maven-plugin:"
                + EvoSuiteJars.version() + ":generate");
        configuration.setAttribute("M2_PROPERTIES", List.of("cuts=" + target, "targetFolder=" + path));
        configuration.setAttribute("M2_NON_RECURSIVE", true);
        configuration.setAttribute("M2_SAVE_BEFORE_LAUNCH", false);
        configuration.setAttribute("M2_OFFLINE", maven.isOffline());
        configuration.setAttribute("M2_USER_SETTINGS", maven.getUserSettingsFile());
        if (facade != null) {
            var resolver = facade.getResolverConfiguration();
            configuration.setAttribute("M2_PROFILES", resolver.getSelectedProfiles());
            configuration.setAttribute("M2_WORKSPACE_RESOLUTION", resolver.isResolveWorkspaceProjects());
        }
        var launch = configuration.launch(ILaunchManager.RUN_MODE, monitor, false);
        try {
            if (launch.getProcesses().length == 0) {
                throw new CoreException(Activator.error("Eclipse's Maven launcher did not start a process."));
            }
            while (!launch.isTerminated()) {
                if (monitor.isCanceled()) throw new OperationCanceledException();
                Thread.sleep(150);
            }
            int exit = 0;
            for (var process : launch.getProcesses()) {
                if (process.getExitValue() != 0) exit = process.getExitValue();
            }
            return exit;
        } finally {
            if (launch.canTerminate()) launch.terminate();
        }
    }

    private static ILaunchManager manager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }
}
