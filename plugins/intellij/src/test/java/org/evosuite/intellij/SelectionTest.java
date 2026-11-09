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

import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.evosuite.intellij.actions.*;

import java.util.*;

public class SelectionTest extends LightJavaCodeInsightFixtureTestCase {
    public void testEditorReferenceNeverChangesTheEditedClassOrMethod() {
        PsiClass helper =
                myFixture.addClass(
                        "package demo; public class Helper { public static void work(){} }");
        myFixture.configureByText(
                "Subject.java",
                "package demo; class Subject { void edited() { Helper.<caret>work(); } } class"
                    + " Other {} ");
        var context =
                com.intellij
                        .openapi
                        .actionSystem
                        .impl
                        .SimpleDataContext
                        .builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject())
                        .add(
                                com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR,
                                myFixture.getEditor())
                        .add(
                                com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE,
                                myFixture.getFile())
                        .add(
                                com.intellij.openapi.actionSystem.LangDataKeys.PSI_ELEMENT_ARRAY,
                                new PsiElement[] {helper.getMethods()[0]})
                        .build();
        var event =
                com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                        com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP, null, context);
        var classes =
                TargetCollector.collect(
                        getProject(),
                        TargetCollector.snapshot(event, TargetCollector.Mode.CLASSES),
                        TargetCollector.Mode.CLASSES);
        assertEquals(
                List.of("demo.Subject", "demo.Other"),
                classes.targets().get(getModule()).stream().map(t -> t.className()).toList());
        var selection = TargetCollector.snapshot(event, TargetCollector.Mode.METHODS);
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                getProject(),
                () -> {
                    var document = myFixture.getEditor().getDocument();
                    document.insertString(0, "// reparse after the save dialog\n");
                    PsiDocumentManager.getInstance(getProject()).commitDocument(document);
                });
        var methods =
                TargetCollector.collect(getProject(), selection, TargetCollector.Mode.METHODS);
        assertEquals(List.of("edited()V"), methods.targets().get(getModule()).getFirst().methods());
    }

    public void testUnresolvedMethodDoesNotDiscardOtherSelectedMethods() {
        var file =
                (PsiJavaFile)
                        myFixture.addFileToProject(
                                "demo/Unresolved.java",
                                "package demo; class Unresolved { void bad(MissingType p) {} void"
                                    + " good(int p) {} }");
        var collected =
                TargetCollector.collect(
                        getProject(),
                        new TargetCollector.Selection(
                                List.of(file.getClasses()[0].getMethods()), List.of(), List.of()),
                        TargetCollector.Mode.METHODS);
        assertEquals(
                List.of("good(I)V"), collected.targets().get(getModule()).getFirst().methods());
    }

    public void testClassSelectionAndSkipRules() {
        PsiJavaFile file =
                (PsiJavaFile)
                        myFixture.addFileToProject(
                                "demo/Vault.java",
                                "package demo; public class Vault { public int sign(int n) { return"
                                        + " n; } } interface Hidden {} @interface Marker {}");
        var result =
                TargetCollector.collect(
                        getProject(),
                        new TargetCollector.Selection(List.of(file), List.of(), List.of()),
                        TargetCollector.Mode.CLASSES);
        assertEquals(
                List.of("demo.Vault"),
                result.targets().get(getModule()).stream().map(t -> t.className()).toList());
    }

    public void testActionEligibilityMatchesCollectionForTestMethodsAndDirectories() {
        var production =
                (PsiJavaFile)
                        myFixture.addFileToProject(
                                "main/demo/Subject.java",
                                "package demo; public class Subject { public void call(){} }");
        var tests =
                (PsiJavaFile)
                        myFixture.addFileToProject(
                                "tests/demo/SubjectTest.java",
                                "package demo; public class SubjectTest { public void testCall(){}"
                                        + " }");
        var testRoot = tests.getVirtualFile().getParent().getParent();
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                getProject(),
                () ->
                        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(
                                getModule(),
                                model -> {
                                    var content = model.getContentEntries()[0];
                                    content.addSourceFolder(testRoot, true);
                                }));
        var method = tests.getClasses()[0].getMethods()[0];
        var selection = new TargetCollector.Selection(List.of(method), List.of(), List.of());
        assertFalse(
                TargetCollector.hasEligibleTarget(
                        getProject(), selection, TargetCollector.Mode.METHODS));
        assertTrue(
                TargetCollector.collect(getProject(), selection, TargetCollector.Mode.METHODS)
                        .targets()
                        .isEmpty());
        var directory = PsiManager.getInstance(getProject()).findDirectory(testRoot);
        selection = new TargetCollector.Selection(List.of(directory), List.of(), List.of());
        for (var mode : List.of(TargetCollector.Mode.EXTEND, TargetCollector.Mode.COVERAGE)) {
            assertTrue(TargetCollector.hasEligibleTarget(getProject(), selection, mode));
            assertEquals(1, TargetCollector.collect(getProject(), selection, mode).tests().size());
        }
        assertFalse(
                TargetCollector.hasEligibleTarget(
                        getProject(), selection, TargetCollector.Mode.CLASSES));
        assertTrue(
                TargetCollector.hasEligibleTarget(
                        getProject(),
                        new TargetCollector.Selection(
                                List.of(production.getClasses()[0].getMethods()[0]),
                                List.of(),
                                List.of()),
                        TargetCollector.Mode.METHODS));
    }

    public void testMethodDescriptorsAndGroupedOverloads() {
        PsiJavaFile file =
                (PsiJavaFile)
                        myFixture.addFileToProject(
                                "demo/Vault.java",
                                "package demo; public class Vault { public Vault(int n) {} public"
                                    + " long sign(int[] n, boolean flag) { return 0; } public int"
                                    + " sign(int n) { return n; } private void hidden() {} public"
                                    + " static class Nested { public void bad() {} } }");
        PsiClass type = file.getClasses()[0];
        assertEquals("<init>(I)V", MethodRules.descriptor(type.getConstructors()[0]));
        PsiMethod[] methods = type.findMethodsByName("sign", false);
        assertEquals("sign([IZ)J", MethodRules.descriptor(methods[0]));
        assertFalse(MethodRules.isTestable(type.findMethodsByName("hidden", false)[0]));
        assertFalse(MethodRules.isTestable(type.getInnerClasses()[0].getMethods()[0]));
        var result =
                TargetCollector.collect(
                        getProject(),
                        new TargetCollector.Selection(List.of(methods), List.of(), List.of()),
                        TargetCollector.Mode.METHODS);
        assertEquals(
                List.of("sign([IZ)J", "sign(I)I"),
                result.targets().get(getModule()).getFirst().methods());
    }

    public void testExtensionInferenceMatchesConventionsAndOffersLongestProductionPrefix() {
        for (String name : List.of("Vault", "VaultEdge", "TestVault"))
            myFixture.addClass("package demo; public class " + name + " {}");
        myFixture.addClass("package demo; public interface VaultEdgeCases {}");
        var root = myFixture.findFileInTempDir(".");
        for (String name :
                List.of(
                        "VaultTest",
                        "VaultTests",
                        "VaultTestCase",
                        "VaultIT",
                        "VaultITCase",
                        "TestVault",
                        "Vault_ESTest")) {
            var test = new TargetCollector.TestClass(getModule(), "demo." + name, root);
            assertEquals(
                    name,
                    List.of("demo.Vault"),
                    TestClassInference.infer(getProject(), test, "_ESTest").candidates());
        }
        var ambiguous =
                TestClassInference.infer(
                        getProject(),
                        new TargetCollector.TestClass(getModule(), "demo.TestVaultTest", root),
                        "_ESTest");
        assertEquals(List.of("demo.TestVault", "demo.Vault"), ambiguous.candidates());
        var guess =
                TestClassInference.infer(
                        getProject(),
                        new TargetCollector.TestClass(getModule(), "demo.VaultEdgeCasesTest", root),
                        "_ESTest");
        assertTrue(guess.candidates().isEmpty());
        assertEquals("demo.VaultEdge", guess.guess());
    }
}
