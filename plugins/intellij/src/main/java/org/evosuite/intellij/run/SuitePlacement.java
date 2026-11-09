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
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import org.evosuite.ide.EvoSuiteCommand;
import org.evosuite.ide.GenerationOptions;
import org.evosuite.ide.GenerationRun;
import org.evosuite.ide.GenerationTarget;
import org.evosuite.ide.SuiteAction;
import org.evosuite.ide.SuiteMerger;
import org.evosuite.intellij.settings.GeneratedSuiteRegistry;
import org.evosuite.intellij.ui.SuiteComparison;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SuitePlacement {
    public record Snapshot(String source, long stamp, Snapshot scaffolding) {}

    public record Placed(VirtualFile suite, List<SuiteComparison.Pair> comparisons, boolean written,
                         boolean skipped, int tests) {}

    private record SourceFile(VirtualFile file, Document document) {
        Snapshot snapshot(Snapshot scaffolding) {
            return new Snapshot(document.getText(), file.getModificationStamp(), scaffolding);
        }

        boolean changed(Snapshot before) {
            return before == null || before.stamp() != file.getModificationStamp()
                    || !before.source().equals(document.getText());
        }

        boolean writable() {
            return file.isWritable() && document.isWritable();
        }

        boolean dirty() {
            return FileDocumentManager.getInstance().isDocumentUnsaved(document);
        }

        SuiteComparison.Pair compare(String fresh) {
            return new SuiteComparison.Pair(file.getName(), document, fresh);
        }
    }

    private SuitePlacement() {}

    public static Snapshot snapshot(Path path) {
        SourceFile suite = find(path);
        if (suite == null) return null;
        SourceFile scaffolding = find(scaffoldingPath(path));
        return suite.snapshot(scaffolding == null ? null : scaffolding.snapshot(null));
    }

    public static Path scaffoldingPath(Path suite) {
        return suite.resolveSibling(suiteName(suite) + "_scaffolding.java");
    }

    static String suiteName(Path path) {
        return path.getFileName().toString().replaceFirst("\\.java$", "");
    }

    private static SourceFile find(Path path) {
        if (Files.isSymbolicLink(path)) throw new IllegalStateException("Test output is a symbolic link: " + path);
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file == null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        Document doc = file == null || file.isDirectory() ? null
                : FileDocumentManager.getInstance().getDocument(file);
        if (doc == null) throw new IllegalStateException("Cannot edit " + path);
        return new SourceFile(file, doc);
    }

    public static boolean generated(Project project, Path path, String source, String name) {
        return SuiteMerger.isGeneratedSuite(source, name) || GeneratedSuiteRegistry.getInstance(project).owns(path, source)
                && SuiteMerger.isValidSuite(source, name);
    }

    public static Placed place(Project project, GenerationTarget target, GenerationOptions options,
                               Path destination, GenerationRun.Result generated, SuiteAction requested,
                               Snapshot consent, Consumer<String> console) throws IOException {
        String source = Files.readString(generated.suite());
        String name = suiteName(destination);
        int tests = SuiteMerger.testCount(source, name);
        if (tests < 0) throw new IOException("Invalid generated suite: " + generated.suite());
        if (target.extendsTests() && tests == 0) {
            console.accept("No new tests for " + target.label() + ".");
            return new Placed(null, List.of(), false, false, tests);
        }
        var registry = GeneratedSuiteRegistry.getInstance(project);
        String fresh = SuiteMerger.isGeneratedSuite(source, name) ? source : registry.decorate(destination, source);
        String scaffold = Files.isRegularFile(generated.scaffolding())
                ? Files.readString(generated.scaffolding()) : null;
        return IdeThread.edt(() -> apply(project, target, options, destination, requested, consent,
                fresh, scaffold, tests, registry, console));
    }

    private static Placed apply(Project project, GenerationTarget target, GenerationOptions options,
                                Path destination, SuiteAction requested, Snapshot consent, String fresh,
                                String scaffold, int tests, GeneratedSuiteRegistry registry,
                                Consumer<String> console) {
        Path scaffoldPath = scaffoldingPath(destination);
        String name = suiteName(destination);
        SourceFile suite = find(destination);
        SourceFile helper = find(scaffoldPath);
        if (suite != null && !generated(project, destination, suite.document().getText(), name)) {
            console.accept(EvoSuiteCommand.notAnEvoSuiteSuite(target.className(), destination.toFile()));
            return new Placed(null, List.of(), false, true, tests);
        }

        boolean managedHelper = helper != null && isGeneratedScaffolding(helper, scaffoldPath);
        boolean obsolete = options.effectiveNoRuntimeDependency() && scaffold == null && managedHelper;
        boolean differs = scaffold != null && helper != null && !scaffold.equals(helper.document().getText());
        if (suite == null) {
            if (differs || obsolete) {
                console.accept(name + ": review the scaffolding before accepting the new suite.");
                return new Placed(null, List.of(helper.compare(obsolete ? "" : scaffold),
                        SuiteComparison.pending(destination, fresh)), false, false, tests);
            }
            VirtualFile[] created = new VirtualFile[1];
            WriteCommandAction.runWriteCommandAction(project, "EvoSuite: create " + name, null, () -> {
                VirtualFile newHelper = helper == null && scaffold != null
                        ? GenerationDocuments.createFile(scaffoldPath, scaffold) : null;
                try {
                    created[0] = GenerationDocuments.createFile(destination, fresh);
                } catch (RuntimeException ex) {
                    if (newHelper != null) GenerationDocuments.deleteFile(newHelper);
                    throw ex;
                }
            });
            registry.recordAccepted(destination, fresh);
            console.accept("Wrote " + name + " (" + tests + " tests).");
            return new Placed(created[0], List.of(), true, false, tests);
        }

        SuiteAction chosen = requested;
        if (consent == null) {
            boolean partial = !target.methods().isEmpty() || target.extendsTests();
            chosen = partial ? SuiteAction.ADD : SuiteAction.COMPARE;
            console.accept(name + " appeared during this run. "
                    + (partial ? "Adding new tests." : "Opening a comparison."));
        }
        if (chosen == SuiteAction.REPLACE) {
            boolean changedHelper = helper == null ? consent.scaffolding() != null
                    : helper.changed(consent.scaffolding());
            if (suite.changed(consent) || changedHelper) {
                console.accept(name + " or its scaffolding changed since the replacement question. Opening Compare.");
                chosen = SuiteAction.COMPARE;
            } else if (suite.dirty() || helper != null && helper.dirty()) {
                console.accept(name + " or its scaffolding has unsaved edits. Opening Compare.");
                chosen = SuiteAction.COMPARE;
            } else if ((differs || obsolete) && (!managedHelper || !helper.writable())) {
                console.accept("Scaffolding cannot be replaced automatically. Opening Compare.");
                chosen = SuiteAction.COMPARE;
            }
        }
        if (!suite.writable()) {
            console.accept(name + " is read-only. Opening Compare.");
            chosen = SuiteAction.COMPARE;
        }
        if (chosen == SuiteAction.ADD && (differs || obsolete)) {
            console.accept("Cannot add tests to " + name + " because its scaffolding differs. Opening Compare.");
            chosen = SuiteAction.COMPARE;
        }

        if (chosen == SuiteAction.ADD) {
            String existing = suite.document().getText();
            var merge = SuiteMerger.merge(existing, fresh, name, registry.owns(destination, existing));
            if (merge.isMerged()) {
                if (merge.added.isEmpty()) {
                    console.accept(name + ": no additional tests (" + merge.duplicates + " duplicates).");
                    return new Placed(suite.file(), List.of(), false, false, tests);
                }
                boolean dirty = suite.dirty();
                WriteCommandAction.runWriteCommandAction(project, "EvoSuite: add tests to " + name, null, () -> {
                    if (helper == null && scaffold != null) GenerationDocuments.createFile(scaffoldPath, scaffold);
                    suite.document().setText(merge.source);
                });
                if (!dirty) FileDocumentManager.getInstance().saveDocument(suite.document());
                console.accept(name + ": added " + merge.added.size() + " tests, skipped " + merge.duplicates
                        + " duplicates." + (dirty ? " The editor still has unsaved changes." : ""));
                return new Placed(suite.file(), List.of(), true, false, tests);
            }
            console.accept("Cannot add tests to " + name + ": " + merge.incompatibility + ". Opening Compare.");
        } else if (chosen == SuiteAction.REPLACE) {
            WriteCommandAction.runWriteCommandAction(project, "EvoSuite: replace " + name, null, () -> {
                if (obsolete) GenerationDocuments.deleteFile(helper.file());
                else if (scaffold != null && helper == null) GenerationDocuments.createFile(scaffoldPath, scaffold);
                else if (differs) helper.document().setText(scaffold);
                suite.document().setText(fresh);
            });
            var documents = FileDocumentManager.getInstance();
            if (differs) documents.saveDocument(helper.document());
            documents.saveDocument(suite.document());
            registry.recordAccepted(destination, fresh);
            console.accept("Replaced " + name + ".");
            return new Placed(suite.file(), List.of(), true, false, tests);
        }

        List<SuiteComparison.Pair> diffs = new ArrayList<>();
        diffs.add(suite.compare(fresh));
        if (differs || obsolete) diffs.add(helper.compare(obsolete ? "" : scaffold));
        if (scaffold != null && helper == null) diffs.add(SuiteComparison.pending(scaffoldPath, scaffold));
        return new Placed(null, List.copyOf(diffs), false, false, tests);
    }

    private static boolean isGeneratedScaffolding(SourceFile helper, Path path) {
        String source = helper.document().getText().stripLeading();
        int headerEnd = source.indexOf("*/");
        return source.startsWith("/**") && headerEnd > 0
                && source.substring(0, headerEnd).contains("tests automatically generated by EvoSuite")
                && SuiteMerger.isValidSuite(source, suiteName(path));
    }

}
