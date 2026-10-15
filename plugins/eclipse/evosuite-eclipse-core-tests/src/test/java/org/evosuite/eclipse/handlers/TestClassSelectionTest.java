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
package org.evosuite.eclipse.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.evosuite.eclipse.TestProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Which classes Extend Tests applies to, and which class each one tests. */
public class TestClassSelectionTest {

    private IJavaProject project;

    @BeforeEach
    public void createProject() throws Exception {
        project = TestProjects.create("extend-selection", "src/main/java", "src/test/java");
        for (String name : List.of("Vault", "Parser", "Lexer", "TestLexer", "Order")) {
            TestProjects.addSource(project, "src/main/java", "a." + name, "package a; public class " + name + " {}");
        }
        TestProjects.addSource(project, "src/main/java", "a.Shape", "package a; public interface Shape {}");
        TestProjects.addSource(project, "src/main/java", "a.PlainTest", "package a; public class PlainTest {}");
        for (String name : List.of("VaultTest", "TestParser", "ParserTests", "VaultIT", "OrderServiceTest",
                "VaultJ5Test", "TestLexerTest", "ShapeTest", "UnrelatedChecks", "Vault_ESTest",
                "Vault_ESTest_scaffolding")) {
            TestProjects.addSource(project, "src/test/java", "a." + name, "package a; public class " + name + " {}");
        }
        TestProjects.addSource(project, "src/test/java", "a.AbstractVaultTest",
                "package a; public abstract class AbstractVaultTest {}");
        TestProjects.addSource(project, "src/test/java", "a.Fixtures", "package a; class Fixtures {}");
        TestProjects.addSource(project, "src/test/java", "a.Helper", "package a; public interface Helper {}");
    }

    private IType type(String name) throws Exception {
        return project.findType(name);
    }

    private String inferred(String test) throws Exception {
        IType classUnderTest = TestClassSelection.infer(type("a." + test), "_ESTest").classUnderTest();
        return classUnderTest == null ? null : classUnderTest.getFullyQualifiedName();
    }

    @Test
    public void namingConventionsTellTheClassUnderTest() throws Exception {
        assertEquals("a.Vault", inferred("VaultTest"));
        assertEquals("a.Vault", inferred("VaultIT"));
        assertEquals("a.Vault", inferred("Vault_ESTest"));
        assertEquals("a.Parser", inferred("TestParser"));
        assertEquals("a.Parser", inferred("ParserTests"));
    }

    @Test
    public void theConfiguredSuiteSuffixCounts() throws Exception {
        TestProjects.addSource(project, "src/test/java", "a.VaultGenerated",
                "package a; public class VaultGenerated {}");
        assertEquals("a.Vault", TestClassSelection.infer(type("a.VaultGenerated"), "Generated").classUnderTest()
                .getFullyQualifiedName());
    }

    @Test
    public void anAmbiguousNameLeavesTheChoiceToTheUser() throws Exception {
        // TestLexerTest: TestLexer by its suffix, Lexer by its prefix.
        TestClassSelection.Inference inference = TestClassSelection.infer(type("a.TestLexerTest"), "_ESTest");
        assertNull(inference.classUnderTest());
        assertEquals(List.of("a.TestLexer", "a.Lexer"), inference.candidates().stream()
                .map(IType::getFullyQualifiedName).collect(Collectors.toList()));
        assertEquals("TestLexer", inference.guess());
    }

    @Test
    public void aClassThatBeginsTheNameIsOnlyAGuess() throws Exception {
        TestClassSelection.Inference inference = TestClassSelection.infer(type("a.VaultJ5Test"), "_ESTest");
        assertNull(inference.classUnderTest());
        assertEquals(List.of(), inference.candidates());
        assertEquals("Vault", inference.guess());
        assertEquals("Order", TestClassSelection.infer(type("a.OrderServiceTest"), "_ESTest").guess());
        assertEquals("", TestClassSelection.infer(type("a.UnrelatedChecks"), "_ESTest").guess());
    }

    @Test
    public void onlyClassesInMainSourceFoldersAreUnderTest() throws Exception {
        // ShapeTest names an interface, PlainTest's own name is taken by a main class.
        TestClassSelection.Inference shape = TestClassSelection.infer(type("a.ShapeTest"), "_ESTest");
        assertNull(shape.classUnderTest());
        assertEquals("Shape", shape.guess());
        assertNull(inferred("UnrelatedChecks"));
    }

    @Test
    public void testClassesAreTopLevelConcreteClassesInTestSourceFolders() throws Exception {
        assertTrue(TestClassSelection.isTestClass(type("a.VaultTest")));
        assertTrue(TestClassSelection.isTestClass(type("a.Vault_ESTest")));
        assertTrue(TestClassSelection.isTestClass(type("a.UnrelatedChecks")));
        assertFalse(TestClassSelection.isTestClass(type("a.Vault")), "a main class");
        assertFalse(TestClassSelection.isTestClass(type("a.PlainTest")), "a main class named like a test");
        assertFalse(TestClassSelection.isTestClass(type("a.Vault_ESTest_scaffolding")));
        assertFalse(TestClassSelection.isTestClass(type("a.AbstractVaultTest")));
        assertFalse(TestClassSelection.isTestClass(type("a.Helper")), "an interface");
    }

    @Test
    public void collectsTestClassesFromTypesFilesAndCompilationUnits() throws Exception {
        List<IType> tests = TestClassSelection.collect(List.of(type("a.VaultTest"),
                project.getProject().getFile("src/test/java/a/VaultIT.java"),
                type("a.VaultTest").getCompilationUnit(), type("a.Vault"), type("a.Vault_ESTest_scaffolding")));
        assertEquals(List.of("a.VaultTest", "a.VaultIT"), tests.stream().map(IType::getFullyQualifiedName)
                .collect(Collectors.toList()));
    }
}
