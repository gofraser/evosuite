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
        var project =
                event.getProject();
        boolean indexing =
                project != null
                        && com.intellij.openapi.project.DumbService.isDumb(project);
        var selection = TargetCollector.snapshot(event, mode());
        boolean enabled =
                project != null
                        && !indexing
                        && TargetCollector.hasEligibleTarget(project, selection, mode());
        event.getPresentation().setEnabled(enabled);
        boolean relevant =
                !selection.modules().isEmpty()
                        || selection.elements().stream()
                                .anyMatch(
                                        element ->
                                                element instanceof com.intellij.psi.PsiPackage
                                                        || element
                                                                instanceof
                                                                com.intellij.psi.PsiDirectory
                                                        || element
                                                                instanceof com.intellij.psi.PsiJavaFile
                                                        || element.getContainingFile()
                                                                instanceof com.intellij.psi.PsiJavaFile)
                        || selection.files().stream()
                                    .anyMatch(
                                        file ->
                                                file.isDirectory()
                            || "java".equals(file.getExtension()));
        event.getPresentation().setVisible(project != null
                                                                    && relevant);
        event.getPresentation()
                                                                                    .setDescription(
                        enabled
                                ? event
                                                                                            .getPresentation()
                                                                                            .getText()
                                : indexing
                                        ? "Wait for project indexing to finish."
                                        : mode() == TargetCollector.Mode.METHODS
                                                ? "Choose a callable method of a top-level"
                                                        + " production class; use Extend Tests for"
                                                        + " test files."
                                                : (mode() == TargetCollector.Mode.EXTEND
                                                                || mode()
                                                                        == TargetCollector.Mode
                                                                                .COVERAGE)
                                                        ? "Choose existing test classes or a test"
                                                                + " source folder."
                                                        : "Choose a production class, package or"
                                                              + " module; interfaces and generated"
                                                              + " tests are excluded.");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null)
            GenerationService.getInstance()
                    .submit(event.getProject(), TargetCollector.snapshot(event, mode()), mode());
    }
}
