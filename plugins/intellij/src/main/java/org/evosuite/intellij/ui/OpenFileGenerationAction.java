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
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
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
        return delegate.getActionUpdateThread();
    }

    private AnActionEvent editorEvent(AnActionEvent event, Project project, Editor editor) {
        if (editor == null || editor.isDisposed()) return null;
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
        Project project = event.getProject();
        boolean enabled = false;
        if (project != null && !project.isDisposed()) {
            Editor editor = event.getUpdateSession().compute(this, "selectedEditor", ActionUpdateThread.EDT,
                    () -> FileEditorManager.getInstance(project).getSelectedTextEditor());
            enabled = updateTarget(editorEvent(event, project, editor));
        }
        event.getPresentation().setEnabled(enabled);
        event.getPresentation().setVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.isDisposed()) return;
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null && getActionUpdateThread() == ActionUpdateThread.BGT) {
            ReadAction.nonBlocking(() -> {
                var target = editorEvent(event, project, editor);
                return updateTarget(target) ? target : null;
            }).expireWith(project)
                    .expireWhen(editor::isDisposed)
                    .finishOnUiThread(ModalityState.current(), target -> perform(project, target))
                    .submit(AppExecutorUtil.getAppExecutorService());
            return;
        }
        var target = editorEvent(event, project, editor);
        perform(project, updateTarget(target) ? target : null);
    }

    private boolean updateTarget(AnActionEvent target) {
        return target != null && ActionUtil.updateAction(delegate, target).isPerformed()
                && target.getPresentation().isEnabledAndVisible();
    }

    private void perform(Project project, AnActionEvent target) {
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project, "Wait for project indexing to finish before generating tests.", "EvoSuite");
        } else if (target != null) {
            ActionUtil.performAction(delegate, target);
        } else {
            Messages.showInfoMessage(project,
                    "Open a Java production file to generate tests, place the caret in a method for method tests,"
                            + " or open a test file to extend tests. You can also right-click classes,"
                            + " packages or modules in the Project view.", "EvoSuite: Select a Java Target");
        }
    }
}
