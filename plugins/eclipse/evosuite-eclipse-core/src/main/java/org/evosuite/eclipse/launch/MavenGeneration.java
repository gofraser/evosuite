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

import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;

import java.util.*;

/**
 * Optional whole-class generation via Eclipse's Maven runner; protected/partial requests stay in
 * the IDE workflow.
 */
public final class MavenGeneration {
    private MavenGeneration() {}

    public static boolean applies(
            IJavaProject project, List<GenerationTarget> targets, GenerationOptions options)
            throws CoreException {
        if (!project.getProject().hasNature("org.eclipse.m2e.core.maven2Nature")
                || !new ProjectScope(project.getProject())
                        .getNode(EvoSuitePreferences.QUALIFIER)
                        .getBoolean("delegateToMaven", false)) return false;
        if (targets.stream().anyMatch(t -> !t.methods().isEmpty() || t.extendsTests())
                || options.llmSwitchedOff()
                || options.effectiveNoRuntimeDependency()
                || !options.effectiveRandomSeed().isBlank()
                || !options.extraArguments.isBlank()
                || !options.pomExtraArguments.isBlank()
                || options.pomProperties.keySet().stream()
                        .anyMatch(
                                k ->
                                        k.equals("random_seed")
                                                || k.startsWith("llm_sut_context")
                                                || k.equals("llm_max_tokens")
                                                || k.equals("llm_timeout_seconds")
                                                || k.equals("llm_retry_max_attempts")
                                                || k.equals("llm_temperature"))
                || !options.llmContextMode.equals("SIGNATURE_ONLY")
                || options.llmMaxTokens != 32768
                || options.llmTimeoutSeconds != 60
                || options.llmRetryMaxAttempts != 2
                || !options.llmTemperature.equals("0.7")) return false;
        var folder = ProjectSetup.testFolder(project.getProject(), options);
        for (var target : targets)
            if (EvoSuiteCommand.suiteFile(
                            folder.getLocation().toFile(), target.className(), options.testSuffix)
                    .isFile()) return false;
        return true;
    }

    public static void launch(
            IJavaProject project, List<GenerationTarget> targets, GenerationOptions options)
            throws CoreException {
        var manager = DebugPlugin.getDefault().getLaunchManager();
        var type =
                manager.getLaunchConfigurationType("org.eclipse.m2e.Maven2LaunchConfigurationType");
        if (type == null)
            throw new CoreException(
                    Activator.error("Install Eclipse Maven Integration to delegate generation."));
        var config =
                type.newInstance(
                        null,
                        manager.generateLaunchConfigurationName(
                                "EvoSuite " + project.getElementName()));
        config.setAttribute(
                IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
                project.getProject().getLocation().toOSString());
        config.setAttribute(
                "M2_GOALS", "compile org.evosuite.plugins:evosuite-maven-plugin:generate");
        config.setAttribute(
                "M2_PROPERTIES",
                List.of(
                        "evosuite.cuts="
                                + String.join(
                                        ",",
                                        targets.stream()
                                                .map(GenerationTarget::className)
                                                .toList())));
        var job =
                new org.eclipse.core.runtime.jobs.Job(
                        "EvoSuite Maven generation: " + project.getElementName()) {
                    private final List<RunHistory.Entry> records = new ArrayList<>();

                    {
                        for (var target : targets) {
                            var record = RunHistory.create(project, target, options, this, false);
                            RunHistory.update(record, "delegated", true);
                            records.add(record);
                        }
                    }

                    @Override
                    protected IStatus run(IProgressMonitor monitor) {
                        ILaunch launch = null;
                        IStatus status = Status.OK_STATUS;
                        try {
                            records.forEach(
                                    e -> {
                                        RunHistory.update(e, "started", System.currentTimeMillis());
                                        RunHistory.update(e, "status", "Delegated to Maven");
                                    });
                            launch = config.launch(ILaunchManager.RUN_MODE, monitor);
                            while (!launch.isTerminated() && launch.getProcesses().length > 0) {
                                if (monitor.isCanceled()) {
                                    launch.terminate();
                                    status = Status.CANCEL_STATUS;
                                    break;
                                }
                                Thread.sleep(200);
                            }
                            if (!monitor.isCanceled())
                                for (var process : launch.getProcesses())
                                    if (process.getExitValue() != 0)
                                        status =
                                                Activator.error(
                                                        "Maven generation failed; see the Maven"
                                                            + " console.");
                            project.getProject()
                                    .refreshLocal(
                                            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                                            null);
                        } catch (CoreException e) {
                            status = e.getStatus();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            status = Status.CANCEL_STATUS;
                        } finally {
                            if (status.getSeverity() == IStatus.CANCEL
                                    && launch != null
                                    && launch.canTerminate())
                                try {
                                    launch.terminate();
                                } catch (CoreException e) {
                                    Activator.log(e.getStatus());
                                }
                            for (var record : records)
                                RunHistory.finish(
                                        record,
                                        status.isOK()
                                                ? "Maven completed"
                                                : "Maven stopped / failed",
                                        "Maven controls settings, seeds and export; repeat this"
                                            + " request through the Maven runner. "
                                                + status.getMessage());
                        }
                        return status;
                    }
                };
        job.setUser(true);
        job.schedule();
    }
}
