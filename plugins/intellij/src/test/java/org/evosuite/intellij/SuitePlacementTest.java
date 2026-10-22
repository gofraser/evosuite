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
package org.evosuite.intellij;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.*;

import java.nio.file.*;
import java.util.*;

public class SuitePlacementTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected com.intellij.testFramework.fixtures.TempDirTestFixture getTempDirFixture() {
        return new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl();
    }

    private String suite(String name, int value) {
        return "package demo; import org.junit.jupiter.api.Test;"
                + " @org.evosuite.runtime.EvoRunnerParameters public class Vault_ESTest { @Test"
                + " public void "
                + name
                + "() { int n = "
                + value
                + "; } }";
    }

    private GenerationRun.Result generated(String source) throws Exception {
        Path root = Files.createTempDirectory("evosuite-placement-");
        Path file = root.resolve("Vault_ESTest.java");
        Files.writeString(file, source);
        return new GenerationRun.Result(
                0, file, root.resolve("Vault_ESTest_scaffolding.java"), null);
    }

    public void testAddMergesUnsavedBufferAndDoesNotReplaceIt() throws Exception {
        var file =
                myFixture
                        .addFileToProject("demo/Vault_ESTest.java", suite("test0", 1))
                        .getVirtualFile();
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        var consent = SuitePlacement.snapshot(file.toNioPath());
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> doc.setText(doc.getText() + "\n// my unsaved edit\n"));
        var result =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        new GenerationOptions(),
                        file.toNioPath(),
                        generated(suite("test0", 2)),
                        SuiteAction.ADD,
                        consent,
                        ignored -> {});
        assertTrue(result.written());
        assertTrue(result.comparisons().isEmpty());
        assertTrue(doc.getText().contains("my unsaved edit"));
        assertTrue(doc.getText().contains("test1"));
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc));
    }

    public void testUnexpectedSuiteAndDirtyReplacementOpenComparison() throws Exception {
        var file =
                myFixture
                        .addFileToProject("demo/Vault_ESTest.java", suite("test0", 1))
                        .getVirtualFile();
        var result =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        new GenerationOptions(),
                        file.toNioPath(),
                        generated(suite("test0", 2)),
                        SuiteAction.REPLACE,
                        null,
                        ignored -> {});
        assertFalse(result.written());
        assertEquals(1, result.comparisons().size());
        assertEquals(
                suite("test0", 1), FileDocumentManager.getInstance().getDocument(file).getText());
    }

    public void testHandwrittenSuiteIsNeverChangedEvenWithMarkerInComment() throws Exception {
        String original = "// EvoRunnerParameters\npackage demo; public class Vault_ESTest {}";
        var file = myFixture.addFileToProject("demo/Vault_ESTest.java", original).getVirtualFile();
        var result =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        new GenerationOptions(),
                        file.toNioPath(),
                        generated(suite("test0", 2)),
                        SuiteAction.REPLACE,
                        SuitePlacement.snapshot(file.toNioPath()),
                        ignored -> {});
        assertFalse(result.written());
        assertTrue(result.comparisons().isEmpty());
        assertEquals(original, FileDocumentManager.getInstance().getDocument(file).getText());
    }

    public void testNewSuiteAndScaffoldingConflictWaitForExplicitAcceptance() throws Exception {
        String old = "package demo; class Vault_ESTest_scaffolding { /* user edit */ }";
        var scaffold =
                myFixture
                        .addFileToProject("demo/Vault_ESTest_scaffolding.java", old)
                        .getVirtualFile();
        var fresh = generated(suite("test0", 2));
        Files.writeString(
                fresh.scaffolding(),
                "package demo; class Vault_ESTest_scaffolding { /* generated */ }");
        var result =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        new GenerationOptions(),
                        scaffold.toNioPath().resolveSibling("Vault_ESTest.java"),
                        fresh,
                        SuiteAction.REPLACE,
                        null,
                        ignored -> {});
        assertFalse(result.written());
        assertNull(result.suite());
        assertEquals(2, result.comparisons().size());
        var pending =
                result.comparisons().stream()
                        .filter(pair -> pair.destination() != null)
                        .findFirst()
                        .orElseThrow();
        assertFalse(Files.exists(pending.destination()));
        org.evosuite.intellij.ui.SuiteComparison.acceptNew(getProject(), pending);
        assertEquals(
                suite("test0", 2), Files.readString(pending.destination()));
        assertEquals(old, FileDocumentManager.getInstance().getDocument(scaffold).getText());
    }

    public void testRuntimeFreeComparisonAcceptanceRequiresMatchingGeneratedMarker() throws Exception {
        var registry=org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(getProject());
        Path path=Path.of(myFixture.getTempDirPath(),"review/Accepted_ESTest.java");
        String source=registry.decorate(path,"public class Accepted_ESTest {}");
        var file=myFixture.addFileToProject("review/Accepted_ESTest.java",source).getVirtualFile();
        var document=FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        assertFalse(
                SuitePlacement.generated(
                        getProject(), path, document.getText(), "Accepted_ESTest"));
        registry.recordAccepted(path,registry.decorate(path,"public class Accepted_ESTest {}"));
        assertFalse(registry.owns(path));
        registry.recordAccepted(path,source);
        assertTrue(
                SuitePlacement.generated(
                        getProject(), path, document.getText(), "Accepted_ESTest"));
        assertTrue(
                "Compare must keep the registered marker stable",
                registry.owns(path, registry.decorate(path, "public class Accepted_ESTest {}")));
    }

    public void testRuntimeFreeMarkerInsideTextBlockDoesNotEstablishOwnership() throws Exception {
        var registry =
                org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(getProject());
        Path path = Path.of(myFixture.getTempDirPath(), "demo/Block_ESTest.java");
        String generated = registry.decorate(path, "package demo; public class Block_ESTest {}");
        var file = myFixture.addFileToProject("demo/Block_ESTest.java", generated).getVirtualFile();
        FileDocumentManager.getInstance().getDocument(file);
        registry.record(path, true);
        String marker = generated.substring(0, generated.indexOf('\n'));
        String handwritten =
                "package demo; public class Block_ESTest { String example = \"\"\"\n"
                        + marker
                        + "\n\"\"\"; }";
        assertTrue(org.evosuite.ide.SuiteMerger.isValidSuite(handwritten, "Block_ESTest"));
        assertFalse(registry.owns(path, handwritten));
        assertFalse(SuitePlacement.generated(getProject(), path, handwritten, "Block_ESTest"));
    }

    public void testUndoRuntimeDependentReplacementRestoresRuntimeFreeOwnership() throws Exception {
        var options = new GenerationOptions();
        options.noRuntimeDependency = true;
        Path destination = Path.of(myFixture.getTempDirPath(), "demo/Vault_ESTest.java");
        String plain =
                "package demo; import org.junit.jupiter.api.Test; public class Vault_ESTest { @Test"
                        + " public void test0(){int n=1;} }";
        var created =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        options,
                        destination,
                        generated(plain),
                        SuiteAction.ADD,
                        null,
                        ignored -> {});
        myFixture.openFileInEditor(created.suite());
        var document = FileDocumentManager.getInstance().getDocument(created.suite());
        String original = document.getText();
        options.noRuntimeDependency = false;
        var replaced =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        options,
                        destination,
                        generated(suite("test1", 2)),
                        SuiteAction.REPLACE,
                        SuitePlacement.snapshot(destination),
                        ignored -> {});
        assertTrue(replaced.written());
        assertFalse(document.getText().equals(original));
        var manager = com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        var editor =
                com.intellij
                        .openapi
                        .fileEditor
                        .FileEditorManager
                        .getInstance(getProject())
                        .getSelectedEditor(created.suite());
        assertTrue(manager.isUndoAvailable(editor));
        manager.undo(editor);
        assertEquals(original, document.getText());
        assertTrue(
                SuitePlacement.generated(
                        getProject(), destination, document.getText(), "Vault_ESTest"));
        assertFalse(SuitePlacement.generated(getProject(), destination, plain, "Vault_ESTest"));
    }

    public void testRuntimeFreeOwnershipAllowsLaterAddButNeverAdoptsHandwrittenFiles()
            throws Exception {
        var options = new GenerationOptions();
        options.noRuntimeDependency = true;
        var destination = Path.of(myFixture.getTempDirPath(), "demo/Plain_ESTest.java");
        String source =
                "package demo; import org.junit.Test; public class Plain_ESTest { @Test public void"
                        + " test0() { int n = 1; } }";
        var first =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Plain"),
                        options,
                        destination,
                        generated(source),
                        SuiteAction.ADD,
                        null,
                        ignored -> {});
        assertTrue(first.written());
        assertTrue(
                org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(getProject())
                        .owns(destination));
        var added =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Plain"),
                        options,
                        destination,
                        generated(source.replace("n = 1", "n = 2")),
                        SuiteAction.ADD,
                        SuitePlacement.snapshot(destination),
                        ignored -> {});
        assertTrue(added.written());
        assertTrue(added.comparisons().isEmpty());
        assertTrue(Files.readString(destination).contains("test1"));
        var registry=org.evosuite.intellij.settings.GeneratedSuiteRegistry.getInstance(getProject());
        var restored=new org.evosuite.intellij.settings.GeneratedSuiteRegistry();
        restored.loadState(com.intellij.util.xmlb.XmlSerializer.deserialize(com.intellij.util.xmlb.XmlSerializer.serialize(registry.getState()),org.evosuite.intellij.settings.GeneratedSuiteRegistry.State.class));
        assertTrue(restored.owns(destination,FileDocumentManager.getInstance().getDocument(first.suite()).getText()));
        assertFalse(
                "Replacing a registered path with handwritten source must not adopt it",
                SuitePlacement.generated(getProject(), destination, source, "Plain_ESTest"));
        var handwritten =
                myFixture
                        .addFileToProject(
                                "demo/Other_ESTest.java",
                                source.replace("Plain_ESTest", "Other_ESTest"))
                        .getVirtualFile();
        var refused =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Other"),
                        options,
                        handwritten.toNioPath(),
                        generated(source.replace("Plain_ESTest", "Other_ESTest")),
                        SuiteAction.REPLACE,
                        SuitePlacement.snapshot(handwritten.toNioPath()),
                        ignored -> {});
        assertTrue(refused.skipped());
    }

    public void testDuplicateAddAndEmptyExtensionLeaveExistingTestsUnchanged() throws Exception {
        var original = suite("test0", 1);
        var file = myFixture.addFileToProject("demo/Vault_ESTest.java", original).getVirtualFile();
        var duplicate =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        new GenerationOptions(),
                        file.toNioPath(),
                        generated(original),
                        SuiteAction.ADD,
                        SuitePlacement.snapshot(file.toNioPath()),
                        ignored -> {});
        assertFalse(duplicate.written());
        assertFalse(duplicate.skipped());
        assertTrue(duplicate.comparisons().isEmpty());
        var extension = GenerationTarget.extending("demo.Vault", List.of(new GenerationTarget.Seed(
                "demo.VaultTest", file.toNioPath().getParent().getParent().toFile())));
        var empty = SuitePlacement.place(getProject(), extension, new GenerationOptions(), file.toNioPath(),
                generated("package demo; public class Vault_ESTest {}"), SuiteAction.ADD,
                SuitePlacement.snapshot(file.toNioPath()), ignored -> {});
        assertFalse(empty.written());
        assertFalse(empty.skipped());
        assertTrue(empty.comparisons().isEmpty());
        assertEquals(original, FileDocumentManager.getInstance().getDocument(file).getText());
    }

    public void testReplacementProtectsScaffoldingChangedWhileGenerationRuns() throws Exception {
        var file =
                myFixture
                        .addFileToProject("demo/Vault_ESTest.java", suite("test0", 1))
                        .getVirtualFile();
        var scaffold = myFixture.addFileToProject("demo/Vault_ESTest_scaffolding.java",
                "package demo; class Vault_ESTest_scaffolding { /* original */ }").getVirtualFile();
        var consent = SuitePlacement.snapshot(file.toNioPath());
        var scaffoldDoc = FileDocumentManager.getInstance().getDocument(scaffold);
        String edited = "package demo; class Vault_ESTest_scaffolding { /* saved user edit */ }";
        WriteCommandAction.runWriteCommandAction(getProject(), () -> scaffoldDoc.setText(edited));
        FileDocumentManager.getInstance().saveDocument(scaffoldDoc);
        var fresh = generated(suite("test0", 2));
        Files.writeString(fresh.scaffolding(), "package demo; class Vault_ESTest_scaffolding {} ");
        List<String> messages = new ArrayList<>();
        var placed = SuitePlacement.place(getProject(), GenerationTarget.wholeClass("demo.Vault"),
                new GenerationOptions(), file.toNioPath(), fresh, SuiteAction.REPLACE, consent, messages::add);
        assertFalse(placed.written());
        assertEquals(2, placed.comparisons().size());
        assertEquals(edited, scaffoldDoc.getText());
        assertEquals(
                suite("test0", 1), FileDocumentManager.getInstance().getDocument(file).getText());
        assertTrue(
                messages.toString(),
                messages.stream().anyMatch(m -> m.contains("scaffolding changed")));
    }

    public void testComparisonUsesLiveDocumentAndHonorsReadOnlyFiles() throws Exception {
        var file =
                myFixture
                        .addFileToProject("demo/Vault_ESTest.java", suite("test0", 1))
                        .getVirtualFile();
        var document = FileDocumentManager.getInstance().getDocument(file);
        var pair =
                new org.evosuite.intellij.ui.SuiteComparison.Pair(
                        "Vault_ESTest.java", document, suite("test0", 2));
        var request = org.evosuite.intellij.ui.SuiteComparison.request(getProject(), pair);
        assertTrue(request.getTitle(), request.getTitle().contains(file.getPresentableUrl()));
        assertSame(document, ((com.intellij.diff.contents.DocumentContent)request.getContents().getFirst()).getDocument());
        assertTrue(request.getUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)[1]);
        WriteCommandAction.runWriteCommandAction(
                getProject(), () -> document.setText(suite("test0", 3)));
        assertTrue(
                ((com.intellij.diff.contents.DocumentContent) request.getContents().getFirst())
                        .getDocument()
                        .getText()
                        .contains("= 3"));
        try {
            com.intellij.openapi.application.WriteAction.run(() -> file.setWritable(false));
            var readOnly = org.evosuite.intellij.ui.SuiteComparison.request(getProject(), pair);
            assertTrue(readOnly.getUserData(com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)[0]);
            assertEquals("Existing file (read-only)", readOnly.getContentTitles().getFirst());
        } finally { com.intellij.openapi.application.WriteAction.run(() -> file.setWritable(true)); }
    }

    public void testRuntimeFreeReplacementProtectsChangedDirtyAndReadOnlyScaffolding()
            throws Exception {
        for (String mode : List.of("changed", "dirty", "readOnly")) {
            var file =
                    myFixture
                            .addFileToProject(mode + "/demo/Vault_ESTest.java", suite("test0", 1))
                            .getVirtualFile();
            String originalScaffold = "package demo; class Vault_ESTest_scaffolding {}";
            var scaffold =
                    myFixture
                            .addFileToProject(
                                    mode + "/demo/Vault_ESTest_scaffolding.java", originalScaffold)
                            .getVirtualFile();
            var document = FileDocumentManager.getInstance().getDocument(scaffold);
            var consent = SuitePlacement.snapshot(file.toNioPath());
            String current = originalScaffold;
            if (!mode.equals("readOnly")) {
                current += "\n// user edit";
                String edit = current;
                WriteCommandAction.runWriteCommandAction(
                        getProject(), () -> document.setText(edit));
                if (mode.equals("changed"))
                    FileDocumentManager.getInstance().saveDocument(document);
            } else
                com.intellij.openapi.application.WriteAction.run(() -> scaffold.setWritable(false));
            try {
                var options = new GenerationOptions();
                options.noRuntimeDependency = true;
                var result =
                        SuitePlacement.place(
                                getProject(),
                                GenerationTarget.wholeClass("demo.Vault"),
                                options,
                                file.toNioPath(),
                                generated(
                                        suite("test1", 2)
                                                .replace(
                                                        "@org.evosuite.runtime.EvoRunnerParameters"
                                                            + " ",
                                                        "")),
                                SuiteAction.REPLACE,
                                consent,
                                ignored -> {});
                assertFalse(mode, result.written());
                assertEquals(mode, 2, result.comparisons().size());
                assertEquals(mode, "", result.comparisons().get(1).generated());
                assertEquals(mode, current, document.getText());
                assertTrue(mode, scaffold.isValid());
                assertEquals(
                        mode,
                        suite("test0", 1),
                        FileDocumentManager.getInstance().getDocument(file).getText());
            } finally {
                if (mode.equals("readOnly"))
                    com.intellij.openapi.application.WriteAction.run(
                            () -> scaffold.setWritable(true));
            }
        }
    }

    public void testEmptyRuntimeFreeExtensionDoesNotRemoveExistingScaffolding() throws Exception {
        var original = suite("test0", 1);
        var file = myFixture.addFileToProject("demo/Vault_ESTest.java", original).getVirtualFile();
        String scaffoldSource = "package demo; class Vault_ESTest_scaffolding {}";
        var scaffold =
                myFixture
                        .addFileToProject("demo/Vault_ESTest_scaffolding.java", scaffoldSource)
                        .getVirtualFile();
        var options = new GenerationOptions();
        options.noRuntimeDependency = true;
        var target =
                GenerationTarget.extending(
                        "demo.Vault",
                        List.of(
                                new GenerationTarget.Seed(
                                        "demo.Vault_ESTest",
                                        file.toNioPath().getParent().getParent().toFile())));
        var result =
                SuitePlacement.place(
                        getProject(),
                        target,
                        options,
                        file.toNioPath(),
                        generated(
                                "package demo; public class Vault_ESTest { @org.junit.Test public"
                                    + " void notGeneratedAnyTest() {} }"),
                        SuiteAction.REPLACE,
                        SuitePlacement.snapshot(file.toNioPath()),
                        ignored -> {});
        assertFalse(result.written());
        assertTrue(result.comparisons().isEmpty());
        assertEquals(original, FileDocumentManager.getInstance().getDocument(file).getText());
        assertEquals(scaffoldSource, Files.readString(scaffold.toNioPath()));
    }

    public void testAddingRuntimeFreeTestsDoesNotSilentlyConvertInlineRuntimeSuite()
            throws Exception {
        var original = suite("test0", 1);
        var file = myFixture.addFileToProject("demo/Vault_ESTest.java", original).getVirtualFile();
        var options = new GenerationOptions();
        options.noRuntimeDependency = true;
        var result =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass("demo.Vault"),
                        options,
                        file.toNioPath(),
                        generated(
                                suite("test1", 2)
                                        .replace("@org.evosuite.runtime.EvoRunnerParameters ", "")),
                        SuiteAction.ADD,
                        SuitePlacement.snapshot(file.toNioPath()),
                        ignored -> {});
        assertFalse(result.written());
        assertEquals(1, result.comparisons().size());
        assertEquals(original, FileDocumentManager.getInstance().getDocument(file).getText());
    }

    public void testReadOnlySuiteFallsBackToComparison() throws Exception {
        var file =
                myFixture
                        .addFileToProject("demo/Vault_ESTest.java", suite("test0", 1))
                        .getVirtualFile();
        var consent = SuitePlacement.snapshot(file.toNioPath());
        try {
            com.intellij.openapi.application.WriteAction.run(() -> file.setWritable(false));
            var placed =
                    SuitePlacement.place(
                            getProject(),
                            GenerationTarget.wholeClass("demo.Vault"),
                            new GenerationOptions(),
                            file.toNioPath(),
                            generated(suite("test0", 2)),
                            SuiteAction.ADD,
                            consent,
                            ignored -> {});
            assertFalse(placed.written());
            assertEquals(1, placed.comparisons().size());
            assertEquals(
                    suite("test0", 1),
                    FileDocumentManager.getInstance().getDocument(file).getText());
        } finally {
            com.intellij.openapi.application.WriteAction.run(() -> file.setWritable(true));
        }
    }
}
