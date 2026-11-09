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
package org.evosuite.eclipse.handlers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.evosuite.eclipse.EvoSuiteJars;
import org.evosuite.eclipse.launch.ProjectSetup;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.evosuite.ide.GenerationOptions;

public final class ProjectReadinessHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) {
        var shell = HandlerUtil.getActiveShell(event);
        if (shell == null) return null;
        Set<IProject> projects = new LinkedHashSet<>();
        for (Object selected : GenerateTestsHandler.selectedElements(event)) {
            IJavaElement java = Adapters.adapt(selected, IJavaElement.class);
            IResource resource = java == null ? Adapters.adapt(selected, IResource.class) : java.getResource();
            if (resource != null) projects.add(resource.getProject());
        }
        if (projects.isEmpty()) {
            MessageDialog.openInformation(shell, "EvoSuite Project Setup", "Select a Java project or open a Java editor.");
            return null;
        }
        var display = shell.getDisplay();
        Job.create("Checking EvoSuite project setup", monitor -> {
            List<String> sections = new ArrayList<>();
            for (IProject project : projects) {
                if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                List<String> lines = new ArrayList<>();
                lines.add(project.getName());
                try {
                    var java = JavaCore.create(project);
                    if (!java.exists()) throw new IllegalArgumentException("This is not an open Java project.");
                    var options = EvoSuitePreferences.resolve(project, monitor);
                    var output = ProjectSetup.testFolder(project, options);
                    var jdk = ProjectSetup.javaInstallation(java, options);
                    EvoSuiteJars.evosuite();
                    lines.add("Java " + jdk.version + ": " + jdk.executable);
                    if (!jdk.hasCompiler) lines.add("Configure a JDK with javac to validate generated tests.");
                    lines.add("Output: " + output.getFullPath() + " / *" + options.testSuffix + ".java");
                    boolean jupiter = GenerationOptions.JUNIT5.equals(options.effectiveTestFormat());
                    lines.add("Test format: " + options.effectiveTestFormat());
                    if (java.findType(jupiter ? "org.junit.jupiter.api.Test" : "org.junit.Test") == null) {
                        lines.add("JUnit is missing from the build path.");
                    }
                    if (!options.effectiveNoRuntimeDependency()
                            && java.findType("org.evosuite.runtime.EvoRunnerParameters") == null) {
                        lines.add("EvoSuite Runtime is missing from the build path.");
                    }
                    lines.add(options.effectiveNoRuntimeDependency() ? "Runtime-free output" : "Runtime-dependent output");
                    lines.add("Build path setup: " + (options.configureBuildPath ? "enabled" : "disabled"));
                    lines.add("Search budget: " + options.effectiveSearchBudget() + " seconds per class");
                    lines.add("Random seed: " + (options.effectiveRandomSeed().isBlank()
                            ? "new seed for each run" : options.effectiveRandomSeed()));
                    lines.add("LLM provider: " + options.effectiveLlmProvider());
                    if (!options.fromPom.isEmpty()) lines.add("POM overrides: " + String.join(", ", options.fromPom));
                } catch (CoreException | IllegalArgumentException e) {
                    lines.add(e.getMessage());
                }
                sections.add(String.join("\n", lines));
            }
            display.asyncExec(() -> {
                if (!shell.isDisposed()) MessageDialog.openInformation(shell, "EvoSuite Project Setup",
                        String.join("\n\n", sections));
            });
            return Status.OK_STATUS;
        }).schedule();
        return null;
    }
}
