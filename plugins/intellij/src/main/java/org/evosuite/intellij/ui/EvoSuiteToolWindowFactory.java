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

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.ContentFactory;

import org.evosuite.intellij.run.GenerationService;
import org.jetbrains.annotations.NotNull;

import java.awt.BorderLayout;

import javax.swing.JPanel;

public final class EvoSuiteToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow window) {
        var console = EvoSuiteConsole.getInstance(project).view();
        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(
                new AnAction(
                        "Stop",
                        "Cancel this project's EvoSuite requests",
                        AllIcons.Actions.Suspend) {
                    @Override
                    public @NotNull ActionUpdateThread getActionUpdateThread() {
                        return ActionUpdateThread.BGT;
                    }

                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e) {
                        GenerationService.getInstance().cancel(project);
                    }
                });
        actions.addAll(console.createConsoleActions());
        JPanel panel = new JPanel(new BorderLayout());
        var toolbar =
                ActionManager.getInstance().createActionToolbar("EvoSuiteConsole", actions, false);
        toolbar.setTargetComponent(console.getComponent());
        panel.add(toolbar.getComponent(), BorderLayout.WEST);
        panel.add(console.getComponent(), BorderLayout.CENTER);
        window.getContentManager()
                .addContent(ContentFactory.getInstance().createContent(panel, "", false));
    }
}
