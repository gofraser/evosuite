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

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.launch.EvoSuiteCommand;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.GenerationTarget;
import org.evosuite.eclipse.launch.ProjectSetup;
import org.evosuite.eclipse.launch.SuiteAction;
import org.evosuite.eclipse.launch.TestGenerationJob;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;

/**
 * Generate Tests with EvoSuite: for the classes selected in a view, or for the class open in the
 * active Java editor.
 */
public class GenerateTestsHandler extends AbstractHandler {

    /** Asks what to do with existing suites; tests replace it to answer without a dialog. */
    static BiFunction<Shell, ExistingSuitesQuestion, SuiteAction> chooser = ExistingSuitesDialog::ask;
    /** Shows a message; tests replace it to read the message without a dialog. */
    static BiConsumer<Shell, String> informer = GenerateTestsHandler::showInformation;

    static void showInformation(Shell shell, String message) {
        MessageDialog.openInformation(shell, "EvoSuite", message);
    }

    /**
     * Saves the editors with unsaved changes to some of the files, after asking which; false if
     * the user cancelled. Tests replace it to answer without a dialog.
     */
    static Predicate<IResource[]> editorSaver = resources -> IDE.saveAllEditors(resources, true);

    /**
     * Has the user save the editors of {@code files} that have unsaved changes: a run reads the
     * saved sources and the classes compiled from them, and with an editor's changes left out it
     * would test something other than what the user sees.
     *
     * @return false if the user cancelled, and nothing must be started
     */
    static boolean saveEditors(Collection<? extends IResource> files) {
        return files.isEmpty() || editorSaver.test(files.toArray(IResource[]::new));
    }

    /** The settings of a project; tests replace it to count how often they are resolved. */
    static Function<IProject, GenerationOptions> settings = GenerateTestsHandler::resolveAwayFromUiThread;

    /**
     * Resolves a project's settings under a busy cursor in a background thread. On the UI thread
     * m2e only offers a pom it has already loaded, and a run must not start without the settings
     * of the EvoSuite Maven plug-in just because m2e had not loaded the pom yet.
     */
    static GenerationOptions resolveAwayFromUiThread(IProject project) {
        if (Display.getCurrent() == null || !PlatformUI.isWorkbenchRunning()) {
            return resolver.apply(project);
        }
        AtomicReference<GenerationOptions> resolved = new AtomicReference<>();
        try {
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(
                    monitor -> resolved.set(resolver.apply(project)));
        } catch (InvocationTargetException e) {
            Activator.logError("Cannot read the EvoSuite settings of " + project.getName(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return resolved.get() != null ? resolved.get() : resolver.apply(project);
    }

    /** Resolves a project's settings in the calling thread; tests replace it to see which thread. */
    static Function<IProject, GenerationOptions> resolver = project -> EvoSuitePreferences.resolve(project, null);

    static final String NO_CLASSES = "The selection contains no classes to generate tests for. Select classes, "
            + "packages, source folders or projects; test source folders, interfaces, annotations and "
            + "EvoSuite's own suites are skipped.";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            generate(HandlerUtil.getActiveShell(event), selectedElements(event));
        } catch (JavaModelException e) {
            throw new ExecutionException("Cannot read the selected Java elements", e);
        }
        return null;
    }

    /** Generates tests for the classes among {@code selection}. */
    static void generate(Shell shell, Iterable<?> selection) throws JavaModelException {
        Map<IJavaProject, Set<IType>> targets = TargetSelection.collect(selection);
        // Resolved once per project: with a pom, that reads Maven's model.
        Map<IJavaProject, GenerationOptions> options = new LinkedHashMap<>();
        Map<IJavaProject, List<GenerationTarget>> requests = new LinkedHashMap<>();
        for (Map.Entry<IJavaProject, Set<IType>> entry : targets.entrySet()) {
            GenerationOptions projectOptions = options(options, entry.getKey());
            for (IType type : entry.getValue()) {
                if (!isGeneratedTest(type, projectOptions)) {
                    requests.computeIfAbsent(entry.getKey(), p -> new ArrayList<>())
                            .add(GenerationTarget.wholeClass(type.getFullyQualifiedName()));
                }
            }
        }
        if (requests.isEmpty()) {
            informer.accept(shell, NO_CLASSES);
            return;
        }
        launch(shell, requests, options, ExistingSuitesQuestion.Scope.CLASSES);
    }

    /** The settings of {@code project}, from {@code resolved} or resolved and added to it. */
    static GenerationOptions options(Map<IJavaProject, GenerationOptions> resolved, IJavaProject project) {
        return resolved.computeIfAbsent(project, p -> settings.apply(p.getProject()));
    }

    static void launch(Shell shell, Map<IJavaProject, List<GenerationTarget>> requests,
                       ExistingSuitesQuestion.Scope scope) {
        launch(shell, requests, new LinkedHashMap<>(), scope);
    }

    /**
     * Asks once, for all projects, what to do with the suites that exist already, and schedules
     * a generation job per project. A class whose suite file is a test class that EvoSuite did
     * not generate, as when the suite suffix and folder are those of the project's own tests, is
     * left out: that file is never replaced or merged into.
     *
     * @param resolved the settings of the projects, as far as they have been resolved already
     * @param scope    what the targets cover; for part of a class, adding to existing suites is
     *                 the preselected answer
     */
    static void launch(Shell shell, Map<IJavaProject, List<GenerationTarget>> requests,
                       Map<IJavaProject, GenerationOptions> resolved, ExistingSuitesQuestion.Scope scope) {
        Map<IJavaProject, GenerationOptions> options = new LinkedHashMap<>();
        Map<IJavaProject, List<GenerationTarget>> runs = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> withSuites = new ArrayList<>();
        Set<String> testFolders = new HashSet<>();
        int classCount = 0;
        for (Map.Entry<IJavaProject, List<GenerationTarget>> entry : requests.entrySet()) {
            IJavaProject project = entry.getKey();
            GenerationOptions projectOptions = options(resolved, project);
            File testDir;
            try {
                testDir = ProjectSetup.testFolder(project.getProject(), projectOptions).getLocation().toFile();
            } catch (CoreException e) {
                MessageDialog.openError(shell, "EvoSuite", e.getStatus().getMessage());
                continue;
            }
            for (GenerationTarget target : entry.getValue()) {
                File suite = EvoSuiteCommand.suiteFile(testDir, target.className(), projectOptions.testSuffix);
                if (suite.isFile() && !EvoSuiteCommand.isEvoSuiteSuite(suite)) {
                    skipped.add(EvoSuiteCommand.notAnEvoSuiteSuite(target.className(), suite));
                    continue;
                }
                classCount++;
                runs.computeIfAbsent(project, p -> new ArrayList<>()).add(target);
                if (suite.isFile()) {
                    withSuites.add(target.className().substring(target.className().lastIndexOf('.') + 1));
                }
            }
            if (runs.containsKey(project)) {
                options.put(project, projectOptions);
                testFolders.add(projectOptions.testFolder);
            }
        }
        if (!skipped.isEmpty()) {
            informer.accept(shell, (runs.isEmpty() ? "No tests are generated:" : "Some of the classes are left out:")
                    + "\n\n" + String.join("\n", skipped));
        }
        SuiteAction action = SuiteAction.REPLACE;
        if (!withSuites.isEmpty()) {
            action = chooser.apply(shell, ExistingSuitesQuestion.of(withSuites, classCount,
                    testFolders.size() == 1 ? testFolders.iterator().next() : null, scope));
            if (action == null) {
                return;
            }
        }
        for (Map.Entry<IJavaProject, GenerationOptions> entry : options.entrySet()) {
            new TestGenerationJob(entry.getKey(), runs.get(entry.getKey()), entry.getValue(), action).schedule();
        }
    }

    /**
     * The elements selected in a view, or, with no such selection, the element open in the Java
     * editor if that is the active part.
     */
    static Iterable<?> selectedElements(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (!(selection instanceof IStructuredSelection)) {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
            return ((IStructuredSelection) selection).toList();
        }
        // Invoked from a Java editor, where the selection is text.
        IEditorPart editor = activeEditor(event);
        IJavaElement element = editor == null ? null : JavaUI.getEditorInputJavaElement(editor.getEditorInput());
        return element == null ? Collections.emptyList() : List.of(element);
    }

    /**
     * The active editor if it is also the active part. With a view active and nothing selected
     * in it, a key binding must not act on whatever editor was last active.
     */
    static IEditorPart activeEditor(ExecutionEvent event) {
        return HandlerUtil.getActivePart(event) instanceof IEditorPart editor ? editor : null;
    }

    /**
     * EvoSuite's own output in a folder that is not marked as test code, such as the
     * {@code evosuite-tests} folder of earlier plug-in versions: a suite carries
     * {@code @EvoRunnerParameters}, and its scaffolding is named after it. The name alone would
     * also match production classes once the suffix is something like {@code Test}.
     */
    static boolean isGeneratedTest(IType type, GenerationOptions options) throws JavaModelException {
        if (type.getAnnotation("EvoRunnerParameters").exists()
                || type.getAnnotation("org.evosuite.runtime.EvoRunnerParameters").exists()) {
            return true;
        }
        String name = type.getElementName();
        String suite = name.endsWith("_scaffolding") ? name.substring(0, name.length() - "_scaffolding".length()) : null;
        return suite != null && suite.endsWith(options.testSuffix)
                && type.getCompilationUnit().getParent() instanceof IPackageFragment pkg
                && pkg.getCompilationUnit(suite + ".java").exists();
    }
}
