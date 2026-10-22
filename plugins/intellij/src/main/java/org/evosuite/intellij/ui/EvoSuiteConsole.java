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
package org.evosuite.intellij.ui;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;

@Service(Service.Level.PROJECT)
public final class EvoSuiteConsole implements Disposable {
    private final Project project;
    private final ConsoleView view;
    private volatile boolean hasOutput;
    private volatile Runnable outputListener;

    public EvoSuiteConsole(Project project) {
        this.project = project;
        view = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
    }

    public static EvoSuiteConsole getInstance(Project project) {
        return project.getService(EvoSuiteConsole.class);
    }

    public ConsoleView view() {
        return view;
    }

    public boolean hasOutput() {
        return hasOutput;
    }

    public void onOutput(Runnable listener) {
        outputListener = listener;
        if (hasOutput) notifyOutput();
    }

    private void notifyOutput() {
        ApplicationManager.getApplication().invokeLater(() -> {
            var listener = outputListener;
            if (!project.isDisposed() && listener != null) listener.run();
        });
    }

    public void println(String line) {
        if (!project.isDisposed()) {
            view.print(line + "\n", ConsoleViewContentType.NORMAL_OUTPUT);
            if (!hasOutput) {
                hasOutput = true;
                notifyOutput();
            }
        }
    }

    public void show() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!project.isDisposed()) {
                                var window =
                                        ToolWindowManager.getInstance(project)
                                                .getToolWindow("EvoSuite");
                                if (window != null) window.show();
                            }
                        });
    }

    @Override
    public void dispose() {
        outputListener = null;
        view.dispose();
    }
}
