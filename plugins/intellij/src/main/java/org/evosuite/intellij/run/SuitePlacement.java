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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;

import org.evosuite.ide.*;
import org.evosuite.intellij.ui.SuiteComparison;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Every decision and document edit is made on EDT, against current editor contents. */
public final class SuitePlacement {
    public record Snapshot(String source, long stamp, Snapshot scaffolding) {}

    public record Placed(
            VirtualFile suite, List<SuiteComparison.Pair> comparisons, boolean written, boolean skipped) {}

    private SuitePlacement() {}

    public static Snapshot snapshot(Path path) {
        Snapshot suite = snapshotFile(path);
        if (suite == null) return null;
        String name = path.getFileName().toString().replaceFirst("\\.java$", "");
        return new Snapshot(suite.source(), suite.stamp(),
                snapshotFile(path.resolveSibling(name + "_scaffolding.java")));
    }

    private static Snapshot snapshotFile(Path path) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        Document doc = file == null ? null : FileDocumentManager.getInstance().getDocument(file);
        return doc == null ? null : new Snapshot(doc.getText(), file.getModificationStamp(), null);
    }

    private static boolean changed(Snapshot before, VirtualFile file, Document document) {
        if (before == null) return document != null;
        return document == null || !before.source().equals(document.getText())
                || before.stamp() != file.getModificationStamp();
    }

    public static boolean generated(String source, String name) {
        return SuiteMerger.isGeneratedSuite(source, name);
    }

    public static boolean generated(Project project, Path path, String source, String name) {
        return generated(source, name)
                || org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(project)
                                .owns(path, source)
                        && SuiteMerger.isValidSuite(source, name);
    }

    private static void record(Project project, Path path, String fresh, String name) {
        org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(project)
                .record(path, !generated(fresh, name));
    }

    public static Placed place(
            Project project,
            GenerationTarget target,
            GenerationOptions options,
            Path destination,
            GenerationRun.Result generated,
            SuiteAction requested,
            Snapshot consent,
            Consumer<String> console)
            throws IOException {
        String produced = Files.readString(generated.suite());
        String suiteName=destination.getFileName().toString().replaceFirst("\\.java$", "");
        String fresh = SuiteMerger.isGeneratedSuite(produced,suiteName) ? produced
                : org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(project).decorate(destination,produced);
        String scaffold =
                Files.isRegularFile(generated.scaffolding())
                        ? Files.readString(generated.scaffolding())
                        : null;
        return IdeThread.edt(
                () -> {
                    String name = destination.getFileName().toString().replaceFirst("\\.java$", "");
                    if (target.extendsTests() && SuiteMerger.testCount(fresh, name) == 0) {
                        console.accept(
                                "No new tests for "
                                        + target.label()
                                        + ". Existing tests are unchanged; see the search and"
                                        + " coverage output above.");
                        return new Placed(null, List.of(), false, false);
                    }
                    VirtualFile existing =
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(destination);
                    Document document =
                            existing == null
                                    ? null
                                    : FileDocumentManager.getInstance().getDocument(existing);
                    Path scaffoldPath = destination.resolveSibling(name + "_scaffolding.java");
                    VirtualFile oldScaffold =
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(scaffoldPath);
                    Document scaffoldDocument =
                            oldScaffold == null
                                    ? null
                                    : FileDocumentManager.getInstance().getDocument(oldScaffold);
                    boolean differentScaffold =
                            scaffold != null
                                    && scaffoldDocument != null
                                    && !scaffold.equals(scaffoldDocument.getText());
                    if (document != null && !generated(project, destination, document.getText(), name)) {
                        console.accept(
                                EvoSuiteCommand.notAnEvoSuiteSuite(
                                        target.className(), destination.toFile()));
                        return new Placed(null, List.of(), false, true);
                    }
                    List<SuiteComparison.Pair> diffs = new ArrayList<>();
                    if (document == null) {
                        if (differentScaffold) {
                            console.accept(
                                    name
                                            + ": resolve the scaffolding and accept the pending"
                                            + " suite before running tests.");
                            diffs.add(
                                    new SuiteComparison.Pair(
                                            scaffoldPath.toString(),
                                            scaffoldDocument,
                                            scaffold));
                            diffs.add(SuiteComparison.pending(destination, fresh));
                            return new Placed(null, List.copyOf(diffs), false, false);
                        }
                        VirtualFile[] created = {null};
                        WriteCommandAction.runWriteCommandAction(
                                project,
                                "EvoSuite: create " + name,
                                null,
                                () -> {
                                    created[0] = write(project, destination, fresh);
                                    if (scaffold != null && oldScaffold == null)
                                        write(project, scaffoldPath, scaffold);
                                });
                        console.accept(
                                "Wrote "
                                        + name
                                        + " ("
                                        + SuiteMerger.testCount(fresh, name)
                                        + " tests).");
                        record(project, destination, fresh, name);
                        return new Placed(created[0], List.copyOf(diffs), true, false);
                    }
                    SuiteAction chosen = requested;
                    boolean partial = !target.methods().isEmpty() || target.extendsTests();
                    if (consent == null) {
                        chosen = partial ? SuiteAction.ADD : SuiteAction.COMPARE;
                        console.accept(
                                name
                                        + " appeared while this run waited or ran: "
                                        + (partial
                                                ? "adding new tests."
                                                : "opening a comparison."));
                    } else if (chosen == SuiteAction.REPLACE
                            && (changed(consent, existing, document)
                                    || changed(consent.scaffolding(), oldScaffold, scaffoldDocument))) {
                        chosen = SuiteAction.COMPARE;
                        console.accept(
                                name
                                        + " or its scaffolding changed since the replacement"
                                        + " question; opening a comparison.");
                    }
                    if (!existing.isWritable()
                            || chosen == SuiteAction.REPLACE
                                    && oldScaffold != null
                                    && !oldScaffold.isWritable()) {
                        console.accept(name + " or its scaffolding is read-only; opening a comparison.");
                        chosen = SuiteAction.COMPARE;
                    }
                    if (chosen == SuiteAction.REPLACE
                            && (FileDocumentManager.getInstance().isDocumentUnsaved(document)
                                    || scaffoldDocument != null
                                            && FileDocumentManager.getInstance()
                                                    .isDocumentUnsaved(scaffoldDocument))) {
                        console.accept(name + " has unsaved edits; opening a comparison.");
                        chosen = SuiteAction.COMPARE;
                    }
                    if (chosen == SuiteAction.ADD && differentScaffold) {
                        console.accept(
                                "Cannot add tests to "
                                        + name
                                        + ": scaffolding differs; opening a comparison.");
                        chosen = SuiteAction.COMPARE;
                    }
                    try {
                        if (chosen == SuiteAction.ADD) {
                            SuiteMerger.Result merge =
                                    SuiteMerger.merge(document.getText(), fresh, name,
                                            org.evosuite.intellij.settings.GeneratedSuiteRegistry
                                                    .getInstance(project)
                                                    .owns(destination));
                            if (merge.isMerged()) {
                                boolean dirty =
                                        FileDocumentManager.getInstance()
                                                .isDocumentUnsaved(document);
                                WriteCommandAction.runWriteCommandAction(
                                        project,
                                        "EvoSuite: add tests to " + name,
                                        null,
                                        () -> {
                                            if (scaffold != null && oldScaffold == null)
                                                write(project, scaffoldPath, scaffold);
                                            if (!merge.added.isEmpty())
                                                document.setText(merge.source);
                                        });
                                if (!dirty)
                                    FileDocumentManager.getInstance().saveDocument(document);
                                console.accept(
                                        name
                                                + ": added "
                                                + merge.added.size()
                                                + " tests, skipped "
                                                + merge.duplicates
                                                + " duplicates."
                                                + (dirty && !merge.added.isEmpty()
                                                        ? " The editor still has unsaved changes." : ""));
                                record(project, destination, fresh, name);
                                return new Placed(existing, List.of(), !merge.added.isEmpty(), false);
                            }
                            console.accept(
                                    "Cannot add tests to "
                                            + name
                                            + ": "
                                            + merge.incompatibility
                                            + "; opening a comparison.");
                        } else if (chosen == SuiteAction.REPLACE) {
                            WriteCommandAction.runWriteCommandAction(
                                    project,
                                    "EvoSuite: replace " + name,
                                    null,
                                    () -> {
                                        document.setText(fresh);
                                        if (scaffold != null)
                                            write(project, scaffoldPath, scaffold);
                                    });
                            FileDocumentManager.getInstance().saveDocument(document);
                            console.accept(
                                    "Replaced "
                                            + name
                                            + ". The previous suite is in local history.");
                            record(project, destination, fresh, name);
                            return new Placed(existing, List.of(), true, false);
                        }
                    } catch (com.intellij.openapi.progress.ProcessCanceledException ex) {
                        throw ex;
                    } catch (RuntimeException ex) {
                        console.accept(
                                "Cannot update "
                                        + name
                                        + ": "
                                        + ex.getMessage()
                                        + "; opening a comparison.");
                    }
                    diffs.add(
                            new SuiteComparison.Pair(
                                    destination.getFileName().toString(), document, fresh));
                    if (differentScaffold)
                        diffs.add(
                                new SuiteComparison.Pair(
                                        scaffoldPath.getFileName().toString(),
                                        scaffoldDocument,
                                        scaffold));
                    else if (scaffold != null && oldScaffold == null) {
                        diffs.add(SuiteComparison.pending(scaffoldPath, scaffold));
                            console.accept("Scaffolding is pending acceptance: " + scaffoldPath);
                    }
                    return new Placed(null, List.copyOf(diffs), false, false);
                });
    }

    private static VirtualFile write(Project project, Path path, String source) {
        try {
            VirtualFile directory = VfsUtil.createDirectories(path.getParent().toString());
            VirtualFile file = directory.findChild(path.getFileName().toString());
            if (file == null)
                file =
                        directory.createChildData(
                                SuitePlacement.class, path.getFileName().toString());
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null) throw new IOException("No document for " + path);
            document.setText(source);
            FileDocumentManager.getInstance().saveDocument(document);
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
