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
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

import org.evosuite.intellij.actions.TargetCollector;
import org.evosuite.intellij.actions.TestClassInference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class GenerationWorkflowTest extends JavaCodeInsightFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        var marker =
                myFixture
                        .addFileToProject("demo/Root.java", "package demo; class Root {} ")
                        .getVirtualFile();
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(getModule(), model -> {
                    var root = marker.getParent().getParent();
                    var entry = model.getContentEntries().length == 0 ? model.addContentEntry(root) : model.getContentEntries()[0];
                    entry.addSourceFolder(root, false);
                }));
    }

    public void testMaskPreservesFollowingDiagnosticFlags() {
        assertEquals(
                "From pom: -Dllm_api_key=**** -Dtimeout=12",
                GenerationService.mask("From pom: -Dllm_api_key=secret -Dtimeout=12"));
        assertEquals(
                "-Dllm_api_key=**** -Dx=1",
                GenerationService.mask("-Dllm_api_key=\"secret with spaces\" -Dx=1"));
    }

    public void testDelegatedMavenOutputsAreVisibleThroughVfs() throws Exception {
        Path root = java.nio.file.Path.of(myFixture.getTempDirPath()).resolve("exported");
        Path suite = root.resolve("demo/Vault_ESTest.java");
        Files.createDirectories(suite.getParent());
        Files.writeString(
                suite,
                "package demo; @org.evosuite.runtime.EvoRunnerParameters public class Vault_ESTest"
                    + " {} ");
        var outputs =
                GenerationService.refreshDelegatedOutputs(
                        getProject(),
                        root,
                        List.of(org.evosuite.ide.GenerationTarget.wholeClass("demo.Vault")),
                        new org.evosuite.ide.GenerationOptions());
        assertEquals(1, outputs.size());
        assertEquals(suite, outputs.getFirst().toNioPath());
    }

    public void testSaveIsProjectScopedAndCancelKeepsEdits() throws Exception {
        var local =
                myFixture
                        .addFileToProject("demo/Vault.java", "package demo; class Vault {} ")
                        .getVirtualFile();
        var outside = Files.createTempFile("evosuite-other-project-", ".java");
        Files.writeString(outside, "class Other {} ");
        var other = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside);
        var files = FileDocumentManager.getInstance();
        var document = files.getDocument(local);
        var unrelated = files.getDocument(other);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(document.getText() + "// project edit");
            unrelated.setText(unrelated.getText() + "// unrelated edit");
        });
        var previous = TestDialogManager.setTestDialog(message -> 1);
        try {
            assertEquals(List.of(document), GenerationDocuments.modified(getProject()));
            assertFalse(GenerationDocuments.saveBeforeGeneration(getProject()));
            assertTrue(files.isDocumentUnsaved(document));
            TestDialogManager.setTestDialog(message -> 0);
            assertTrue(GenerationDocuments.saveBeforeGeneration(getProject()));
            assertFalse(files.isDocumentUnsaved(document));
            assertTrue(
                    "Other projects' edits must stay unsaved", files.isDocumentUnsaved(unrelated));
            assertFalse(Files.readString(outside).contains("unrelated edit"));
        } finally {
            TestDialogManager.setTestDialog(previous);
            WriteCommandAction.runWriteCommandAction(
                    getProject(), () -> unrelated.setText("class Other {} "));
            files.saveDocument(unrelated);
            com.intellij.openapi.application.WriteAction.run(() -> other.delete(this));
        }
    }

    public void testExtensionValidatesProductionClassAndCancelsEntireRequest() {
        myFixture.addFileToProject("demo/Vault.java", "package demo; public class Vault {} ");
        myFixture.addFileToProject(
                "demo/Interface.java", "package demo; public interface Interface {} ");
        var test =
                new TargetCollector.TestClass(
                        getModule(), "demo.VaultEdgeTest", myFixture.findFileInTempDir("."));
        var validator = ExtensionTargetDialog.validator(getProject(), test);
        assertTrue(validator.canClose(" demo.Vault "));
        assertFalse(validator.canClose("demo.Missing"));
        assertFalse(validator.canClose("demo.Interface"));
        assertFalse(validator.canClose(""));
        assertTrue(validator.getErrorText("").contains("fully qualified"));
        var previous = TestDialogManager.setTestInputDialog(message -> null);
        try {
            try {
                ExtensionTargetDialog.choose(
                        getProject(),
                        test,
                        new TestClassInference.Inference(List.of(), "demo.Vault"));
                fail("Cancel must abort the request instead of skipping one seed");
            } catch (ProcessCanceledException expected) { }
            TestDialogManager.setTestInputDialog(message -> " demo.Vault ");
            assertEquals("demo.Vault", ExtensionTargetDialog.choose(getProject(), test,
                    new TestClassInference.Inference(List.of(), "demo.Vault")));
        } finally { TestDialogManager.setTestInputDialog(previous); }
    }

    public void testQuestionsFitSmallDisplaysEvenWithLongNamesAndPaths() {
        String text =
                "Replace: " + "a".repeat(230) + "\n\nCompare: " + "review changes ".repeat(50);
        String wrapped = GenerationDialogs.message(text);
        assertTrue(wrapped.contains("\n\nCompare:"));
        for (String line : wrapped.split("\n")) assertTrue(line, line.length() <= 88);
    }
}
