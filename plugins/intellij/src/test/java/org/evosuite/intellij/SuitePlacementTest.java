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

    public void testNewSuiteSurvivesScaffoldingConflict() throws Exception {
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
        assertTrue(result.written());
        assertNotNull(result.suite());
        assertEquals(
                suite("test0", 2),
                FileDocumentManager.getInstance().getDocument(result.suite()).getText());
        assertEquals(1, result.comparisons().size());
        assertEquals(old, FileDocumentManager.getInstance().getDocument(scaffold).getText());
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
