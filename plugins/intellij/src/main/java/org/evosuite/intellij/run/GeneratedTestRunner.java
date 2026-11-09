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
package org.evosuite.intellij.run;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;

import java.nio.file.Path;

public final class GeneratedTestRunner {
    private GeneratedTestRunner() {}

    public static void run(Project project, Path output) {
        new Task.Backgroundable(project, "Preparing EvoSuite Tests", true) {
            private RunnerAndConfigurationSettings settings;
            private String error;

            @Override
            public void run(ProgressIndicator indicator) {
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(output);
                try {
                    settings = IdeThread.smartRead(project, () -> configurationForFile(project, file));
                } catch (IllegalArgumentException ex) {
                    error = ex.getMessage();
                }
            }

            @Override
            public void onSuccess() {
                if (project.isDisposed()) return;
                if (error != null) {
                    Messages.showErrorDialog(project, error, "EvoSuite: Run Tests");
                    return;
                }
                var executor = DefaultRunExecutor.getRunExecutorInstance();
                var builder = ExecutionEnvironmentBuilder.createOrNull(executor, settings);
                if (builder == null) {
                    Messages.showErrorDialog(project, "IntelliJ’s JUnit runner is unavailable.", "EvoSuite: Run Tests");
                    return;
                }
                RunManager.getInstance(project).setTemporaryConfiguration(settings);
                ProgramRunnerUtil.executeConfiguration(builder.activeTarget().build(), true, true);
            }
        }.queue();
    }

    private static RunnerAndConfigurationSettings configurationForFile(Project project, VirtualFile file) {
        if (file == null || !(PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile java))
            throw new IllegalArgumentException("The generated Java test file is missing or cannot be read.");
        var suite = suiteClass(java);
        if (suite == null)
            throw new IllegalArgumentException("No test class matches the generated file’s name.");
        if (!ProjectFileIndex.getInstance(project).isInTestSourceContent(file))
            throw new IllegalArgumentException(
                    "IntelliJ does not recognize this folder as test sources. Check Settings / readiness "
                            + "or reimport your Maven or Gradle project.");
        return configuration(project, suite);
    }

    public static PsiClass suiteClass(PsiJavaFile file) {
        String name = file.getName().replaceFirst("\\.java$", "");
        return java.util.Arrays.stream(file.getClasses())
                .filter(type -> name.equals(type.getName()))
                .findFirst()
                .orElse(null);
    }

    public static RunnerAndConfigurationSettings configuration(Project project, PsiClass type) {
        var settings =
                RunManager.getInstance(project)
                        .createConfiguration(
                                "EvoSuite: " + type.getName(),
                                JUnitConfigurationType.getInstance()
                                        .getConfigurationFactories()[0]);
        ((JUnitConfiguration) settings.getConfiguration()).beClassConfiguration(type);
        settings.setActivateToolWindowBeforeRun(true);
        settings.setFocusToolWindowBeforeRun(true);
        return settings;
    }
}
