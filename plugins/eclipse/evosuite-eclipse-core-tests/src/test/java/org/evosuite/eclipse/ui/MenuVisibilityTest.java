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
package org.evosuite.eclipse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.UiThread;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When the context menus offer Generate Tests with EvoSuite, Generate Tests for Method with
 * EvoSuite and Extend Tests with EvoSuite.
 */
public class MenuVisibilityTest {

    private static final String METHOD_COMMAND = "org.evosuite.eclipse.generateMethodTests";
    private static final String EXTEND_COMMAND = "org.evosuite.eclipse.extendTests";

    private Expression visibleWhen;
    private Expression methodVisibleWhen;
    private Expression editorMethodVisibleWhen;
    private Expression extendVisibleWhen;
    private Expression editorExtendVisibleWhen;
    private IJavaProject project;

    @BeforeEach
    public void setUp() throws Exception {
        for (IConfigurationElement contribution : Platform.getExtensionRegistry()
                .getConfigurationElementsFor("org.eclipse.ui.menus")) {
            if (Activator.PLUGIN_ID.equals(contribution.getContributor().getName())
                    && contribution.getAttribute("locationURI").startsWith("popup:org.eclipse.ui.popup.any")) {
                IConfigurationElement condition = contribution.getChildren("command")[0].getChildren("visibleWhen")[0];
                visibleWhen = ExpressionConverter.getDefault().perform(condition.getChildren()[0]);
                methodVisibleWhen = condition(contribution, METHOD_COMMAND);
                extendVisibleWhen = condition(contribution, EXTEND_COMMAND);
            } else if (Activator.PLUGIN_ID.equals(contribution.getContributor().getName())
                    && contribution.getAttribute("locationURI").startsWith("popup:#CompilationUnitEditorContext")) {
                editorMethodVisibleWhen = condition(contribution, METHOD_COMMAND);
                editorExtendVisibleWhen = condition(contribution, EXTEND_COMMAND);
            }
        }
        assertNotNull(visibleWhen, "no explorer menu contribution");
        assertNotNull(methodVisibleWhen, "no explorer menu contribution for methods");
        assertNotNull(editorMethodVisibleWhen, "no editor menu contribution for methods");
        assertNotNull(extendVisibleWhen, "no explorer menu contribution for extending tests");
        assertNotNull(editorExtendVisibleWhen, "no editor menu contribution for extending tests");
        project = TestProjects.create("menu", "src", null);
        TestProjects.addSource(project, "src", "m.Plain", "package m; public class Plain { int field; "
                + "public int twice(int x) { return 2 * x; } private void hidden() {} }");
        project.getProject().getFile("notes.txt").create(new java.io.ByteArrayInputStream(new byte[0]), true, null);
    }

    private static Expression condition(IConfigurationElement contribution, String commandId) throws Exception {
        for (IConfigurationElement command : contribution.getChildren("command")) {
            if (commandId.equals(command.getAttribute("commandId"))) {
                return ExpressionConverter.getDefault().perform(command.getChildren("visibleWhen")[0].getChildren()[0]);
            }
        }
        return null;
    }

    private boolean visibleFor(Object... selected) throws Exception {
        return evaluate(visibleWhen, selected);
    }

    private boolean methodVisibleFor(Object... selected) throws Exception {
        return evaluate(methodVisibleWhen, selected);
    }

    private static boolean evaluate(Expression expression, Object... selected) throws Exception {
        EvaluationContext context = new EvaluationContext(null, new Object());
        context.addVariable("activeMenuSelection", new StructuredSelection(List.of(selected)));
        return expression.evaluate(context) == EvaluationResult.TRUE;
    }

    @Test
    public void showsForClassesPackagesFoldersAndProjects() throws Exception {
        assertEquals(true, visibleFor(project.findType("m.Plain").getCompilationUnit()));
        assertEquals(true, visibleFor(project.findType("m.Plain")));
        assertEquals(true, visibleFor(project.findType("m.Plain").getPackageFragment()));
        assertEquals(true, visibleFor(project.getPackageFragmentRoot(project.getProject().getFolder("src"))));
        assertEquals(true, visibleFor(project));
    }

    @Test
    public void showsForResourcesInTheProjectExplorer() throws Exception {
        assertEquals(true, visibleFor(project.getProject().getFile("src/m/Plain.java")));
        assertEquals(true, visibleFor(project.getProject()));
    }

    @Test
    public void staysHiddenForOtherSelections() throws Exception {
        assertEquals(false, visibleFor(project.getProject().getFile("notes.txt")));
        assertEquals(false, visibleFor(project.findType("m.Plain").getField("field")));
        assertEquals(false, visibleFor("text"));
    }

    @Test
    public void offersMethodGenerationForMethodsThatTestsCanTarget() throws Exception {
        IType plain = project.findType("m.Plain");
        IMethod twice = plain.getMethod("twice", new String[]{"I"});
        IMethod hidden = plain.getMethod("hidden", new String[0]);
        assertEquals(true, methodVisibleFor(twice));
        assertEquals(false, methodVisibleFor(hidden), "a private method");
        assertEquals(false, methodVisibleFor(twice, hidden), "a private method among others");
        assertEquals(false, methodVisibleFor(twice, plain), "a class among the methods");
        assertEquals(false, methodVisibleFor(plain));
        assertEquals(false, methodVisibleFor(plain.getField("field")));
        // The class command stays out of the way for methods.
        assertEquals(false, visibleFor(twice));
    }

    @Test
    public void offersMethodGenerationInTheEditorWhileTheCaretIsInAMethod() throws Exception {
        String source = "package m; public class Plain { int field; public int twice(int x) { return 2 * x; } "
                + "private void hidden() {} }";
        UiThread.run(() -> {
            IEditorPart editor = JavaUI.openInEditor(project.findType("m.Plain").getCompilationUnit());
            try {
                ITextEditor text = (ITextEditor) editor;
                EvaluationContext context = new EvaluationContext(null, new Object());
                context.addVariable("activePart", editor);
                text.selectAndReveal(source.indexOf("2 * x"), 0);
                assertEquals(EvaluationResult.TRUE, editorMethodVisibleWhen.evaluate(context));
                text.selectAndReveal(source.indexOf("int field"), 0);
                assertEquals(EvaluationResult.FALSE, editorMethodVisibleWhen.evaluate(context));
                text.selectAndReveal(source.indexOf("{}"), 0);
                assertEquals(EvaluationResult.FALSE, editorMethodVisibleWhen.evaluate(context), "a private method");
            } finally {
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeEditor(editor, false);
            }
        });
    }

    @Test
    public void offersExtendingTestsForTestClassesOnly() throws Exception {
        IJavaProject tested = TestProjects.create("menu-tests", "src/main/java", "src/test/java");
        TestProjects.addSource(tested, "src/main/java", "m.Plain", "package m; public class Plain {}");
        TestProjects.addSource(tested, "src/test/java", "m.PlainTest", "package m; public class PlainTest {}");
        TestProjects.addSource(tested, "src/test/java", "m.Plain_ESTest_scaffolding",
                "package m; public class Plain_ESTest_scaffolding {}");
        IType test = tested.findType("m.PlainTest");
        IType plain = tested.findType("m.Plain");

        assertEquals(true, evaluate(extendVisibleWhen, test));
        assertEquals(true, evaluate(extendVisibleWhen, test.getCompilationUnit()));
        assertEquals(true, evaluate(extendVisibleWhen, tested.getProject().getFile("src/test/java/m/PlainTest.java")));
        assertEquals(false, evaluate(extendVisibleWhen, plain), "a main class");
        assertEquals(false, evaluate(extendVisibleWhen, plain.getCompilationUnit()), "a main class");
        assertEquals(false, evaluate(extendVisibleWhen, test, plain), "a main class among the tests");
        assertEquals(false, evaluate(extendVisibleWhen, tested.findType("m.Plain_ESTest_scaffolding")));
        assertEquals(false, evaluate(extendVisibleWhen, test.getPackageFragment()), "a package");
        assertEquals(false, evaluate(extendVisibleWhen, tested));
    }

    @Test
    public void offersExtendingTestsInTheEditorOfATestClass() throws Exception {
        IJavaProject tested = TestProjects.create("menu-editor-tests", "src/main/java", "src/test/java");
        TestProjects.addSource(tested, "src/main/java", "m.Plain", "package m; public class Plain {}");
        TestProjects.addSource(tested, "src/test/java", "m.PlainTest", "package m; public class PlainTest {}");
        UiThread.run(() -> {
            for (String type : List.of("m.PlainTest", "m.Plain")) {
                IEditorPart editor = JavaUI.openInEditor(tested.findType(type).getCompilationUnit());
                try {
                    EvaluationContext context = new EvaluationContext(null, new Object());
                    context.addVariable("activePart", editor);
                    assertEquals(type.equals("m.PlainTest") ? EvaluationResult.TRUE : EvaluationResult.FALSE,
                            editorExtendVisibleWhen.evaluate(context), type);
                } finally {
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeEditor(editor, false);
                }
            }
        });
    }
}
