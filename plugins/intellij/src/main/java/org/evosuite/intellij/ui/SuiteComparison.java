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
    public record Pair(String name, Document existing, String generated) {}

    private SuiteComparison() {}

    public static SimpleDiffRequest request(Project project, Pair pair) {
        DiffContentFactory factory = DiffContentFactory.getInstance();
        var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getFile(pair.existing());
        boolean readOnly = file == null || !pair.existing().isWritable() || !file.isWritable();
        String location = file == null ? pair.name() : file.getPresentableUrl();
        SimpleDiffRequest request =
                new SimpleDiffRequest(
                        "EvoSuite: " + location,
                        factory.create(project, pair.existing()),
                        factory.create(
                                pair.generated(),
                                com.intellij.ide.highlighter.JavaFileType.INSTANCE),
                        file == null ? "No destination file — create it to keep generated changes"
                                : readOnly ? "Existing file (read-only)"
                                : "Existing file — edits use IntelliJ autosave",
                        "Generated tests — copy wanted changes left (read-only)");
        request.putUserData(
                com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS,
                new boolean[] {readOnly, true});
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
