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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.UiThread;
import org.evosuite.eclipse.launch.TestGenerationJob;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the three commands act on, and what they say when that is nothing. */
public class HandlerSelectionTest {

    private static final String FOO = "package a;\npublic class Foo {\n    public void run() {\n    }\n}\n";

    private final List<String> messages = new ArrayList<>();
    private final List<ExistingSuitesQuestion> questions = new ArrayList<>();
    private IJavaProject project;

    @BeforeEach
    public void createProject() throws Exception {
        project = TestProjects.create("handler-selection", "src/main/java", "src/test/java");
        TestProjects.addSource(project, "src/main/java", "a.Foo", FOO);
        TestProjects.addSource(project, "src/main/java", "a.Bar", FOO.replace("Foo", "Bar"));
        TestProjects.addSource(project, "src/test/java", "a.FooTest", "package a; public class FooTest {}");
        TestProjects.addSource(project, "src/test/java", "a.BarTest", "package a; public class BarTest {}");
        // So that the commands ask about it and start nothing when the question is cancelled.
        TestProjects.addSource(project, "src/test/evosuite", "a.Foo_ESTest",
                "package a; @org.evosuite.runtime.EvoRunnerParameters public class Foo_ESTest {}");
        GenerateTestsHandler.informer = (shell, message) -> messages.add(message);
        GenerateTestsHandler.chooser = (shell, question) -> {
            questions.add(question);
            return null;
        };
    }

    @AfterEach
    public void restore() {
        GenerateTestsHandler.informer = GenerateTestsHandler::showInformation;
        GenerateTestsHandler.chooser = ExistingSuitesDialog::ask;
        GenerateTestsHandler.settings = p -> EvoSuitePreferences.resolve(p, null);
    }

    private IType type(String name) throws Exception {
        return project.findType("a." + name);
    }

    /** A selection of nothing but EvoSuite's own suites does not end silently. */
    @Test
    public void saysSoWhenOnlyGeneratedSuitesAreSelected() throws Exception {
        IJavaProject other = TestProjects.create("only-suites", "src", null);
        TestProjects.addSource(other, "src", "g.Foo_ESTest",
                "package g; @org.evosuite.runtime.EvoRunnerParameters public class Foo_ESTest {}");

        GenerateTestsHandler.generate(null, List.of(other.findType("g.Foo_ESTest")));

        assertEquals(List.of(GenerateTestsHandler.NO_CLASSES), messages);
        assertEquals(List.of(), questions);
        assertEquals(0, Job.getJobManager().find(TestGenerationJob.FAMILY).length, "generation started");
    }

    /** Resolving the settings can mean reading Maven's model, so each command does it once per project. */
    @Test
    public void resolvesTheSettingsOncePerProject() throws Exception {
        AtomicInteger resolved = new AtomicInteger();
        GenerateTestsHandler.settings = p -> {
            resolved.incrementAndGet();
            return EvoSuitePreferences.resolve(p, null);
        };

        GenerateTestsHandler.generate(null, List.of(type("Foo"), type("Bar")));
        assertEquals(1, resolved.getAndSet(0), "Generate Tests");

        GenerateMethodTestsHandler.generate(null, Map.of(type("Foo"), List.of(type("Foo").getMethod("run",
                new String[0])), type("Bar"), List.of(type("Bar").getMethod("run", new String[0]))));
        assertEquals(1, resolved.getAndSet(0), "Generate Tests for Method");

        ExtendTestsHandler.extend(null, List.of(type("FooTest"), type("BarTest")));
        assertEquals(1, resolved.getAndSet(0), "Extend Tests");

        assertEquals(3, questions.size());
        assertEquals(List.of(), messages);
    }

    /**
     * Invoked by a key binding with a view active and nothing selected in it, a command does not
     * act on the editor that happens to be open; with the editor active, it does.
     */
    @Test
    public void actsOnTheEditorOnlyWhenItIsTheActivePart() throws Exception {
        IFile file = project.getProject().getFile("src/main/java/a/Foo.java");
        UiThread.run(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            IEditorPart editor = IDE.openEditor(page, file, true);
            try {
                ((ITextEditor) editor).selectAndReveal(FOO.indexOf("run()"), 0);
                EvaluationContext context = new EvaluationContext(null, new Object());
                context.addVariable(ISources.ACTIVE_EDITOR_NAME, editor);
                context.addVariable(ISources.ACTIVE_EDITOR_INPUT_NAME, editor.getEditorInput());
                ExecutionEvent viewActive = new ExecutionEvent(null, Map.of(), null, context);

                assertEquals(List.of(), GenerateTestsHandler.selectedElements(viewActive));
                assertEquals(Map.of(), GenerateMethodTestsHandler.selectedMethods(viewActive));

                context.addVariable(ISources.ACTIVE_PART_NAME, editor);
                ExecutionEvent editorActive = new ExecutionEvent(null, Map.of(), null, context);

                assertEquals(List.of(type("Foo").getCompilationUnit()),
                        GenerateTestsHandler.selectedElements(editorActive));
                IMethod run = type("Foo").getMethod("run", new String[0]);
                assertEquals(Map.of(type("Foo"), List.of(run)), GenerateMethodTestsHandler.selectedMethods(editorActive));
            } finally {
                page.closeEditor(editor, false);
            }
        });
    }
}
