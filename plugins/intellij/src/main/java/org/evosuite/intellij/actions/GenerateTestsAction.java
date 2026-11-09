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
package org.evosuite.intellij.actions;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiPackage;

import org.evosuite.intellij.run.GenerationService;
import org.jetbrains.annotations.NotNull;

public class GenerateTestsAction extends DumbAwareAction {
    protected TargetCollector.Mode mode() {
        return TargetCollector.Mode.CLASSES;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var presentation = event.getPresentation();
        if (project == null) {
            presentation.setEnabledAndVisible(false);
            return;
        }

        var mode = mode();
        var selection = TargetCollector.snapshot(event, mode);
        boolean relevant = !selection.modules().isEmpty()
                || selection.elements().stream().anyMatch(element ->
                        element instanceof PsiPackage || element instanceof PsiDirectory
                                || element instanceof PsiJavaFile || element.getContainingFile() instanceof PsiJavaFile)
                || selection.files().stream().anyMatch(file -> file.isDirectory() || "java".equals(file.getExtension()));
        boolean indexing = DumbService.isDumb(project);
        boolean enabled = relevant && !indexing && TargetCollector.hasEligibleTarget(project, selection, mode);
        presentation.setVisible(relevant);
        presentation.setEnabled(enabled);
        if (enabled) presentation.setDescription(getTemplatePresentation().getDescription());
        else if (indexing) presentation.setDescription("Wait for project indexing to finish.");
        else presentation.setDescription(switch (mode) {
            case CLASSES -> "Choose a production Java class, package or module.";
            case METHODS -> "Choose a callable method of a top-level production class.";
            case EXTEND, COVERAGE -> "Choose existing test classes or a test source folder.";
        });
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null)
            GenerationService.getInstance()
                    .submit(event.getProject(), TargetCollector.snapshot(event, mode()), mode());
    }
}
