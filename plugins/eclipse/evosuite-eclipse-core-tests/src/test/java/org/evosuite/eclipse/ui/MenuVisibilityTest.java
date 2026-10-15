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
 * When the context menus offer Generate Tests with EvoSuite and Generate Tests for Method with
 * EvoSuite.
 */
public class MenuVisibilityTest {

    private static final String METHOD_COMMAND = "org.evosuite.eclipse.generateMethodTests";

    private Expression visibleWhen;
    private Expression methodVisibleWhen;
    private Expression editorMethodVisibleWhen;
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
            } else if (Activator.PLUGIN_ID.equals(contribution.getContributor().getName())
                    && contribution.getAttribute("locationURI").startsWith("popup:#CompilationUnitEditorContext")) {
                editorMethodVisibleWhen = condition(contribution, METHOD_COMMAND);
            }
        }
        assertNotNull(visibleWhen, "no explorer menu contribution");
        assertNotNull(methodVisibleWhen, "no explorer menu contribution for methods");
        assertNotNull(editorMethodVisibleWhen, "no editor menu contribution for methods");
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
}
