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

import com.intellij.diff.*;
import com.intellij.diff.chains.SimpleDiffRequestChain;
import com.intellij.diff.requests.*;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;

import org.evosuite.intellij.run.GenerationDocuments;
import org.evosuite.intellij.run.IdeThread;
import org.evosuite.intellij.settings.GeneratedSuiteRegistry;

import java.nio.file.Path;
import java.util.List;

public final class SuiteComparison {
    private SuiteComparison() {}

    public record Pair(String name, Document existing, String generated, Path destination) {
        public Pair(String name, Document existing, String generated) {
            this(name, existing, generated, null);
        }
    }

    public static Pair pending(Path path, String source) {
        return new Pair(path.toString(), EditorFactory.getInstance().createDocument(""), source, path);
    }

    public static void acceptNew(Project project, Pair pair) {
        Path path = pair.destination();
        if (path == null) return;
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file != null) {
            Document existing = FileDocumentManager.getInstance().getDocument(file);
            if (existing != null) show(project, List.of(new Pair(pair.name(), existing, pair.generated())));
            return;
        }
        IdeThread.writeCommand(project, "EvoSuite: accept generated file", () -> {
            var created = GenerationDocuments.createFile(path, pair.generated());
            GeneratedSuiteRegistry.getInstance(project).recordAccepted(path, pair.generated());
            FileEditorManager.getInstance(project).openFile(created, true);
        });
    }

    public static SimpleDiffRequest request(Project project, Pair pair) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var manager = FileDocumentManager.getInstance();
        var pendingFile = pair.destination() == null ? null
                : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pair.destination());
        Document document = pendingFile == null ? null : manager.getDocument(pendingFile);
        if (document == null) document = pair.existing();
        var file = manager.getFile(document);
        boolean readOnly = file == null || !document.isWritable() || !file.isWritable();
        String location = file == null ? pair.name() : file.getPresentableUrl();
        SimpleDiffRequest request =
                new SimpleDiffRequest(
                        "EvoSuite: " + location,
                        factory.create(project, document),
                        factory.create(
                                pair.generated(),
                                JavaFileType.INSTANCE),
                        file == null
                                ? "No destination file — create it to keep generated changes"
                                : readOnly
                                        ? "Existing file (read-only)"
                                        : "Existing file — edits use IntelliJ autosave",
                        pair.generated().isEmpty() && pair.name().endsWith("_scaffolding.java")
                                ? "Obsolete scaffolding — replace the suite, then copy empty"
                                      + " content left (read-only)"
                                : "Generated tests — copy wanted changes left (read-only)");
        request.putUserData(
                DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                new boolean[] {readOnly, true});
        if (pair.destination() != null && file == null)
            request.putUserData(
                    DiffUserDataKeys.CONTEXT_ACTIONS,
                    List.of(
                            new DumbAwareAction(
                                    "Accept generated file",
                                    "Create this pending file without replacing existing files",
                                    null) {
                                @Override
                                public void actionPerformed(
                                        AnActionEvent event) {
                                    acceptNew(project, pair);
                                }
                            }));
        return request;
    }

    public static void show(Project project, List<Pair> pairs) {
        if (project.isDisposed() || pairs.isEmpty()) return;
        var requests = pairs.stream().<DiffRequest>map(pair -> request(project, pair)).toList();
        DiffManager.getInstance()
                .showDiff(project, new SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT);
    }
}
