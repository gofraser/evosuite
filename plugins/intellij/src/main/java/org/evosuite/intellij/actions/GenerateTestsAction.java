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
        boolean enabled =
                event.getProject() != null
                        && !com.intellij.openapi.project.DumbService.isDumb(event.getProject());
        TargetCollector.Selection selection = TargetCollector.snapshot(event);
        enabled &=
                !selection.modules().isEmpty()
                        || selection.files().stream()
                                .anyMatch(f -> f.isDirectory() || "java".equals(f.getExtension()))
                        || selection.elements().stream()
                                .anyMatch(
                                        e ->
                                                e instanceof com.intellij.psi.PsiPackage
                                                        || e
                                                                instanceof
                                                                com.intellij.psi.PsiDirectory
                                                        || e instanceof com.intellij.psi.PsiJavaFile
                                                        || e instanceof com.intellij.psi.PsiClass
                                                        || e instanceof com.intellij.psi.PsiMethod);
        if (enabled && mode() == TargetCollector.Mode.CLASSES && selection.modules().isEmpty()) {
            enabled =
                    selection.files().stream()
                                    .anyMatch(com.intellij.openapi.vfs.VirtualFile::isDirectory)
                            || selection.elements().stream()
                                    .anyMatch(
                                            e ->
                                                    e instanceof com.intellij.psi.PsiPackage
                                                            || e
                                                                    instanceof
                                                                    com.intellij.psi.PsiDirectory
                                                            || e.getContainingFile() != null
                                                                    && e.getContainingFile()
                                                                                    .getVirtualFile()
                                                                            != null
                                                                    && !com.intellij.openapi.roots
                                                                            .ProjectFileIndex
                                                                            .getInstance(
                                                                                    event
                                                                                            .getProject())
                                                                            .isInTestSourceContent(
                                                                                    e.getContainingFile()
                                                                                            .getVirtualFile()));
        }
        if (enabled && mode() == TargetCollector.Mode.METHODS)
            enabled =
                    selection.elements().stream()
                            .anyMatch(
                                    e ->
                                            e instanceof com.intellij.psi.PsiMethod m
                                                    && MethodRules.whyNotTestable(m) == null);
        if (enabled && mode() == TargetCollector.Mode.EXTEND)
            enabled =
                    selection.elements().stream()
                            .anyMatch(
                                    e -> {
                                        var file = e.getContainingFile();
                                        return file != null
                                                && file.getVirtualFile() != null
                                                && com.intellij.openapi.roots.ProjectFileIndex
                                                        .getInstance(event.getProject())
                                                        .isInTestSourceContent(
                                                                file.getVirtualFile());
                                    });
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null)
            GenerationService.getInstance()
                    .submit(event.getProject(), TargetCollector.snapshot(event), mode());
    }
}
