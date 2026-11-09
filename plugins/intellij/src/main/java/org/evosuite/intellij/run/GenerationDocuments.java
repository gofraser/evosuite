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
package org.evosuite.intellij.run;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Generation needs saved project sources, but must not save other projects' edits. */
public final class GenerationDocuments {
    private GenerationDocuments() {}

    public static List<Document> modified(Project project) {
        var files = FileDocumentManager.getInstance();
        var index = ProjectFileIndex.getInstance(project);
        String base = project.getBasePath();
        return Arrays.stream(files.getUnsavedDocuments()).filter(document -> {
            VirtualFile file = files.getFile(document);
            return file != null && (index.isInContent(file)
                    || base != null && file.isInLocalFileSystem()
                            && file.toNioPath().startsWith(Path.of(base)));
        }).toList();
    }

    public static boolean saveBeforeGeneration(Project project) {
        var documents = modified(project);
        if (documents.isEmpty()) return true;
        int answer = Messages.showDialog(project,
                GenerationDialogs.message("Save " + documents.size() + " modified file(s) in this project before generating tests? "
                        + "EvoSuite reads saved sources and compiled classes."),
                "Save Project Files for EvoSuite", new String[] {"Save and Generate", "Cancel"},
                0, Messages.getQuestionIcon());
        if (answer != 0) return false;
        var files = FileDocumentManager.getInstance();
        documents.forEach(files::saveDocument);
        if (documents.stream().anyMatch(files::isDocumentUnsaved)) {
            Messages.showErrorDialog(project,
                    "Some project files could not be saved. Save them before generating tests.", "EvoSuite");
            return false;
        }
        return true;
    }

    static void deleteFile(VirtualFile file) {
        try {
            file.delete(GenerationDocuments.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot remove " + file.getPath(), ex);
        }
    }

    public static VirtualFile createFile(Path path, String source) {
        VirtualFile file = null;
        try {
            VirtualFile directory = VfsUtil.createDirectories(path.getParent().toString());
            file = directory.createChildData(GenerationDocuments.class, path.getFileName().toString());
            var documents = FileDocumentManager.getInstance();
            Document document = documents.getDocument(file);
            if (document == null) throw new IOException("No document for " + path);
            document.setText(source);
            documents.saveDocument(document);
            return file;
        } catch (IOException | RuntimeException ex) {
            if (file != null) {
                try {
                    file.delete(GenerationDocuments.class);
                } catch (IOException cleanup) {
                    ex.addSuppressed(cleanup);
                }
            }
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cannot create " + path, ex);
        }
    }
}
