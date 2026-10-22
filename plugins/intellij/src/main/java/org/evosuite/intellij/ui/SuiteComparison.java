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
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;

import java.util.*;

public final class SuiteComparison {
    public record Pair(String name, Document existing, String generated, java.nio.file.Path destination) {
        public Pair(String name, Document existing, String generated) {
            this(name, existing, generated, null);
        }
    }

    public static Pair pending(java.nio.file.Path path, String source) {
        return new Pair(
                path.toString(),
                com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(""),
                source,
                path);
    }

    /** An explicit acceptance never overwrites a file which appeared meanwhile. */
    public static void acceptNew(Project project, Pair pair) {
        if (pair.destination() == null) return;
        var path = pair.destination();
        var fs = com.intellij.openapi.vfs.LocalFileSystem.getInstance();
        var existing = fs.refreshAndFindFileByNioFile(path);
        if (existing != null) {
            var doc =
                    com.intellij
                            .openapi
                            .fileEditor
                            .FileDocumentManager
                            .getInstance()
                            .getDocument(existing);
            if (doc != null) show(project, List.of(new Pair(pair.name(), doc, pair.generated())));
            return;
        }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                project,
                "EvoSuite: accept generated file",
                null,
                () -> {
                    try {
                        var directory =
                                com.intellij.openapi.vfs.VfsUtil.createDirectories(
                                        path.getParent().toString());
                        if (directory.findChild(path.getFileName().toString()) != null)
                            throw new IllegalStateException(
                                    "File appeared; reopen its comparison before applying"
                                            + " changes.");
                        var file =
                                directory.createChildData(
                                        SuiteComparison.class, path.getFileName().toString());
                        var manager =
                                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
                        var doc = manager.getDocument(file);
                        if (doc == null) throw new IllegalStateException("Cannot edit " + path);
                        doc.setText(pair.generated());
                        manager.saveDocument(doc);
                        String name = file.getNameWithoutExtension();
                        if (!name.endsWith("_scaffolding"))
                            org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(
                                            project)
                                    .record(
                                            path,
                                            !org.evosuite.ide.SuiteMerger.isGeneratedSuite(
                                                    pair.generated(), name));
                        com.intellij
                                .openapi
                                .fileEditor
                                .FileEditorManager
                                .getInstance(project)
                                .openFile(file, true);
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException("Cannot create " + path, ex);
                    }
                });
    }

    private SuiteComparison() {}

    public static SimpleDiffRequest request(Project project, Pair pair) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        var pendingFile =
                pair.destination() == null
                        ? null
                        : com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                                .refreshAndFindFileByNioFile(pair.destination());
        Document document =
                pendingFile == null ? pair.existing() : manager.getDocument(pendingFile);
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
                                com.intellij.ide.highlighter.JavaFileType.INSTANCE),
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
                com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                new boolean[] {readOnly, true});
        if (pair.destination() != null && file == null)
            request.putUserData(
                    com.intellij.diff.util.DiffUserDataKeys.CONTEXT_ACTIONS,
                    List.of(
                            new com.intellij.openapi.project.DumbAwareAction(
                                    "Accept generated file",
                                    "Create this pending file without replacing existing files",
                                    null) {
                                @Override
                                public void actionPerformed(
                                        com.intellij.openapi.actionSystem.AnActionEvent event) {
                                    acceptNew(project, pair);
                                }
                            }));
        return request;
    }

    public static void show(Project project, List<Pair> pairs) {
        if (project.isDisposed() || pairs.isEmpty()) return;
        List<DiffRequest> requests =
                pairs.stream().map(p -> (DiffRequest) request(project, p)).toList();
        DiffManager.getInstance()
                .showDiff(project, new SimpleDiffRequestChain(requests), DiffDialogHints.DEFAULT);
    }
}
