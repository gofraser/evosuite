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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.UiThread;
import org.evosuite.eclipse.launch.GenerationTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which methods qualify for method-level generation, how EvoSuite names them, and the method a
 * Java editor's caret is in.
 */
public class MethodSelectionTest {

    static final String SHAPES = "package m;\n"
            + "\n"
            + "import java.util.List;\n"
            + "import java.util.Map;\n"
            + "\n"
            + "public class Shapes<E extends Number> {\n"
            + "    private int count;\n"
            + "    public Shapes() {}\n"
            + "    Shapes(int size, String... names) {}\n"
            + "    public static class Inner { public void inInner() {} }\n"
            + "    public class Member { public void inMember() {} }\n"
            + "    public int area(int w, long h) { return 0; }\n"
            + "    public <T extends Comparable<T>> T first(List<T> list, String[][] names) { return null; }\n"
            + "    public E element(E value, Map<String, ? extends E> map) { return value; }\n"
            + "    protected Inner inner(Inner[] inners, Object... rest) { return null; }\n"
            + "    void process(Map.Entry<String, Integer> entry) {}\n"
            + "    private int hidden() { return 0; }\n"
            + "    public static boolean check(char c, byte b, short s, float f, double d) { return true; }\n"
            + "    public <T extends Object & Runnable> void run(T task) {}\n"
            + "    public void withLambda() { Runnable r = () -> {}; }\n"
            + "}\n";

    private static IJavaProject project;
    private static IType shapes;

    @BeforeAll
    public static void createProject() throws Exception {
        project = TestProjects.create("methods", "src", "test");
        TestProjects.addSource(project, "src", "m.Shapes", SHAPES);
        TestProjects.addSource(project, "src", "m.Base",
                "package m; public abstract class Base { protected Base() {} abstract void todo(); void done() {} }");
        TestProjects.addSource(project, "src", "m.Kind", "package m; public enum Kind { A; Kind() {} void m() {} }");
        TestProjects.addSource(project, "src", "m.Api", "package m; public interface Api { default void call() {} }");
        TestProjects.addSource(project, "test", "m.ShapesTest", "package m; public class ShapesTest { public void t() {} }");
        TestProjects.build(project);
        shapes = project.findType("m.Shapes");
    }

    static IMethod method(IType type, String name) throws Exception {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("no method " + name + " in " + type.getElementName());
    }

    /** The descriptors javap prints for the compiled class; EvoSuite matches against these. */
    @Test
    public void namesMethodsByTheirCompiledDescriptor() throws Exception {
        List<String> names = new ArrayList<>();
        for (IMethod method : shapes.getMethods()) {
            names.add(MethodDescriptors.of(method));
        }
        assertEquals(List.of(
                "<init>()V",
                "<init>(I[Ljava/lang/String;)V",
                "area(IJ)I",
                "first(Ljava/util/List;[[Ljava/lang/String;)Ljava/lang/Comparable;",
                "element(Ljava/lang/Number;Ljava/util/Map;)Ljava/lang/Number;",
                "inner([Lm/Shapes$Inner;[Ljava/lang/Object;)Lm/Shapes$Inner;",
                "process(Ljava/util/Map$Entry;)V",
                "hidden()I",
                "check(CBSFD)Z",
                "run(Ljava/lang/Object;)V",
                "withLambda()V"), names);
    }

    @Test
    public void acceptsMethodsAndConstructorsThatTestsCanCall() throws Exception {
        for (String name : List.of("Shapes", "area", "first", "element", "inner", "process", "check", "run")) {
            assertNull(MethodSelection.whyNotTestable(method(shapes, name)), name);
        }
        assertNull(MethodSelection.whyNotTestable(method(project.findType("m.Base"), "done")));
        assertNull(MethodSelection.whyNotTestable(method(project.findType("m.Kind"), "m")));
    }

    @Test
    public void refusesMethodsThatTestsCannotTarget() throws Exception {
        assertTrue(MethodSelection.whyNotTestable(method(shapes, "hidden")).startsWith("is private"));
        assertTrue(MethodSelection.whyNotTestable(method(project.findType("m.Shapes.Inner"), "inInner"))
                .startsWith("belongs to a nested class"));
        assertTrue(MethodSelection.whyNotTestable(method(project.findType("m.Shapes.Member"), "inMember"))
                .startsWith("belongs to a nested class"));
        assertEquals("is abstract", MethodSelection.whyNotTestable(method(project.findType("m.Base"), "todo")));
        assertEquals("is a constructor of an abstract class, which tests cannot call",
                MethodSelection.whyNotTestable(method(project.findType("m.Base"), "Base")));
        assertEquals("is a constructor of an enum, which tests cannot call",
                MethodSelection.whyNotTestable(method(project.findType("m.Kind"), "Kind")));
        assertEquals("belongs to an interface", MethodSelection.whyNotTestable(method(project.findType("m.Api"), "call")));
        assertEquals("is in a test source folder",
                MethodSelection.whyNotTestable(method(project.findType("m.ShapesTest"), "t")));
    }

    @Test
    public void groupsTheMethodsOfAClassIntoOneTarget() throws Exception {
        IType base = project.findType("m.Base");
        List<String> skipped = new ArrayList<>();
        Map<IJavaProject, List<GenerationTarget>> targets = GenerateMethodTestsHandler.targets(
                MethodSelection.collect(List.of(method(shapes, "area"), method(base, "done"), method(shapes, "hidden"),
                        method(shapes, "check"), method(shapes, "area"))), skipped);

        assertEquals(Map.of(project, List.of(
                new GenerationTarget("m.Shapes", List.of("area(IJ)I", "check(CBSFD)Z")),
                new GenerationTarget("m.Base", List.of("done()V")))), targets);
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).startsWith("Shapes.hidden() is private"), skipped.get(0));
    }

    @Test
    public void findsTheMethodAtTheCaret() throws Exception {
        UiThread.run(() -> {
            IEditorPart editor = JavaUI.openInEditor(shapes.getCompilationUnit());
            try {
                ITextEditor text = (ITextEditor) editor;
                text.selectAndReveal(SHAPES.indexOf("return 0; }"), 0);
                assertEquals(method(shapes, "area"), MethodSelection.methodAtCaret(editor));
                assertTrue(new MethodPropertyTester().test(editor, MethodPropertyTester.CARET_IN_TESTABLE_METHOD,
                        new Object[0], null));

                text.selectAndReveal(SHAPES.indexOf("private int count"), 0);
                assertNull(MethodSelection.methodAtCaret(editor), "a field");
                assertEquals(false, new MethodPropertyTester().test(editor,
                        MethodPropertyTester.CARET_IN_TESTABLE_METHOD, new Object[0], null));

                text.selectAndReveal(SHAPES.indexOf("return 0; }", SHAPES.indexOf("hidden")), 0);
                assertEquals(method(shapes, "hidden"), MethodSelection.methodAtCaret(editor));
                assertEquals(false, new MethodPropertyTester().test(editor,
                        MethodPropertyTester.CARET_IN_TESTABLE_METHOD, new Object[0], null), "a private method");
            } finally {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeEditor(editor, false);
            }
        });
    }
}
