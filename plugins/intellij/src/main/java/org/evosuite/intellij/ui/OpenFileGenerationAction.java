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

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

/** Tool-window focus must not replace the Java editor's generation target. */
public final class OpenFileGenerationAction extends DumbAwareAction {
    private final AnAction delegate;

    public OpenFileGenerationAction(String actionId, String text) {
        this(ActionManager.getInstance().getAction(actionId), text);
    }

    OpenFileGenerationAction(AnAction action, String text) {
        delegate = action;
        getTemplatePresentation().setText(text);
        getTemplatePresentation().setDescription(text);
        getTemplatePresentation().setIcon(delegate.getTemplatePresentation().getIcon());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    private AnActionEvent editorEvent(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return null;
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return null;
        var file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (file == null) return null;
        var context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, file)
                .build();
        return AnActionEvent.createEvent(context, delegate.getTemplatePresentation().clone(),
                "EvoSuiteToolWindow", ActionUiKind.TOOLBAR, event.getInputEvent());
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var target = editorEvent(event);
        if (target != null) ActionUtil.updateAction(delegate, target);
        event.getPresentation().setEnabled(target != null && target.getPresentation().isEnabled());
        event.getPresentation().setVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var target = editorEvent(event);
        if (target != null) {
            if (ActionUtil.updateAction(delegate, target).isPerformed()
                    && target.getPresentation().isEnabledAndVisible()) {
                ActionUtil.performAction(delegate, target);
                return;
            }
        }
        if (event.getProject() != null && com.intellij.openapi.project.DumbService.isDumb(event.getProject())) {
            Messages.showInfoMessage(event.getProject(), "Wait for project indexing to finish before generating tests.", "EvoSuite");
            return;
        }
        Messages.showInfoMessage(event.getProject(),
                "Open a Java production file to generate tests, place the caret in a method for method tests,"
                        + " or open a test file to extend tests. You can also right-click classes,"
                        + " packages or modules in the Project view.", "EvoSuite: Select a Java Target");
    }
}
