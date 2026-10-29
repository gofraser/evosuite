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

import org.eclipse.core.runtime.*;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.*;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;

/**
 * Read-only description of resolved settings and project prerequisites; never reads credentials.
 */
public final class ConfigurationCheck {
    private ConfigurationCheck() {}

    public static String inspect(IJavaProject project, IProgressMonitor monitor) {
        GenerationOptions options = EvoSuitePreferences.resolve(project.getProject(), monitor);
        StringBuilder result =
                new StringBuilder(
                        "Project: "
                                + project.getElementName()
                                + "\nProfile: "
                                + (EvoSuitePreferences.usesProjectSettings(project.getProject())
                                        ? "project"
                                        : "workspace")
                                + "\n");
        for (String key : SettingKeys.all()) {
            String value = SettingKeys.value(options, key);
            if (key.equals("extraArguments"))
                value = EvoSuiteCommand.forDisplay(EvoSuiteCommand.splitArguments(value));
            result.append(key).append(" = ").append(value).append('\n');
        }
        if (!options.fromPom.isEmpty())
            result.append("\nMaven overrides: ").append(String.join("; ", options.fromPom));
        result.append(
                "\n"
                    + "Additional arguments override profile values; Maven properties/extraArgs"
                    + " take precedence. Output locations are pinned; Search only always disables"
                    + " the LLM.\n");
        try {
            var java = ProjectSetup.javaInstallation(project, options);
            result.append("\nJDK: ")
                    .append(java.executable)
                    .append(" (Java ")
                    .append(java.version)
                    .append(java.hasCompiler ? ", javac available)" : ", MISSING javac)");
            result.append("\nOutput: ")
                    .append(ProjectSetup.testFolder(project.getProject(), options).getLocation());
            String format = options.effectiveTestFormat();
            if (format.isBlank()) format = ProjectSetup.detectedTestFormat(project);
            result.append("\nJUnit: ")
                    .append(format)
                    .append(
                            (format.equals(GenerationOptions.JUNIT4)
                                            ? project.findType("org.junit.Test") != null
                                            : project.findType("org.junit.jupiter.api.Test") != null)
                                    ? " available"
                                    : " MISSING");
            result.append("\nEvoSuite runtime: ")
                    .append(
                            options.effectiveNoRuntimeDependency()
                                    ? "not required"
                                    : project.findType("org.evosuite.runtime.EvoRunnerParameters")
                                                    == null
                                            ? "MISSING"
                                            : "available");
        } catch (CoreException e) {
            result.append("\nSetup problem: ").append(e.getMessage());
        }
        return result.toString();
    }

    public static void show(IJavaProject project, Shell shell) {
        Job.create("Checking EvoSuite setup", monitor -> {
            String message = inspect(project, monitor);
            shell.getDisplay().asyncExec(() -> open(shell, message));
        }).schedule();
    }

    private static void open(Shell shell, String message) {
        if (shell.isDisposed()) return;
        new Dialog(shell) {
            @Override
            protected Control createDialogArea(Composite parent) {
                Composite area = (Composite) super.createDialogArea(parent);
                Text text = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
                text.setText(message);
                GridData layout = new GridData(SWT.FILL, SWT.FILL, true, true);
                layout.widthHint = 700;
                layout.heightHint = 450;
                text.setLayoutData(layout);
                return area;
            }

            @Override
            protected void configureShell(Shell shell) {
                super.configureShell(shell);
                shell.setText("Effective EvoSuite configuration / project setup");
            }

            @Override
            protected boolean isResizable() { return true; }
        }.open();
    }
}
