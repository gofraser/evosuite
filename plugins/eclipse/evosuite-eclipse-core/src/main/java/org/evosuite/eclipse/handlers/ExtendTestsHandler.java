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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.ui.IJavaElementSearchConstants;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.SelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.GenerationTarget;

/**
 * Extend Tests with EvoSuite: for the test classes selected in a view, or the one open in the
 * active Java editor. EvoSuite parses their tests from source to start its search from them,
 * runs them to leave out what they already cover, and the new tests go into the EvoSuite suite
 * of the class under test. The test classes themselves are never changed.
 *
 * <p>The test classes of one class under test share one run. Where a test's name does not tell
 * which class it tests, the user chooses the class.
 */
public class ExtendTestsHandler extends AbstractHandler {

    /** Asks for the class a test class tests; tests replace it to answer without a dialog. */
    interface ClassChooser {
        /** @return the chosen class, or {@code null} if the user cancelled */
        IType choose(Shell shell, IType test, TestClassSelection.Inference inference) throws JavaModelException;
    }

    static ClassChooser chooser = ExtendTestsHandler::askForClassUnderTest;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            extend(HandlerUtil.getActiveShell(event), GenerateTestsHandler.selectedElements(event));
        } catch (JavaModelException e) {
            throw new ExecutionException("Cannot read the selected Java elements", e);
        }
        return null;
    }

    /** Extends the tests of the test classes among {@code selection}. */
    static void extend(Shell shell, Iterable<?> selection) throws JavaModelException {
        List<IType> tests = TestClassSelection.collect(selection);
        if (tests.isEmpty()) {
            GenerateTestsHandler.informer.accept(shell, "Select a test class in a test source folder, such as "
                    + "FooTest or Foo_ESTest, or open one in the Java editor.");
            return;
        }
        List<String> skipped = new ArrayList<>();
        Map<IJavaProject, GenerationOptions> options = new LinkedHashMap<>();
        Map<IJavaProject, List<GenerationTarget>> requests = targets(shell, tests, options, skipped);
        if (!skipped.isEmpty()) {
            GenerateTestsHandler.informer.accept(shell, (requests.isEmpty() ? "" : "Some of the test classes "
                    + "are left out:\n\n") + String.join("\n", skipped));
        }
        if (!requests.isEmpty() && GenerateTestsHandler.saveEditors(files(requests))) {
            GenerateTestsHandler.launch(shell, requests, options, ExistingSuitesQuestion.Scope.EXTENSION);
        }
    }

    /**
     * The files a run reads: EvoSuite parses the test classes from their saved sources, runs
     * them compiled and generates tests for the compiled class under test.
     */
    private static Set<IResource> files(Map<IJavaProject, List<GenerationTarget>> requests)
            throws JavaModelException {
        Set<IResource> files = new LinkedHashSet<>();
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        for (Map.Entry<IJavaProject, List<GenerationTarget>> entry : requests.entrySet()) {
            for (GenerationTarget target : entry.getValue()) {
                for (GenerationTarget.Seed seed : target.seeds()) {
                    IFile file = root.getFileForLocation(IPath.fromFile(seed.sourceFile()));
                    if (file != null) {
                        files.add(file);
                    }
                }
                IType classUnderTest = entry.getKey().findType(target.className());
                if (classUnderTest != null && classUnderTest.getResource() != null) {
                    files.add(classUnderTest.getResource());
                }
            }
        }
        return files;
    }

    /**
     * One target per class under test, with all its selected test classes, grouped by the
     * project of the test classes: their runs need its test code on the classpath.
     *
     * @param skipped receives a line for each test class left out, saying why
     */
    static Map<IJavaProject, List<GenerationTarget>> targets(Shell shell, List<IType> tests, List<String> skipped)
            throws JavaModelException {
        return targets(shell, tests, new LinkedHashMap<>(), skipped);
    }

    /** @param resolved the settings of the projects, which receives those it resolves */
    static Map<IJavaProject, List<GenerationTarget>> targets(Shell shell, List<IType> tests,
                                                             Map<IJavaProject, GenerationOptions> resolved,
                                                             List<String> skipped) throws JavaModelException {
        Map<IJavaProject, Map<String, List<GenerationTarget.Seed>>> seeds = new LinkedHashMap<>();
        for (IType test : tests) {
            IJavaProject project = test.getJavaProject();
            GenerationOptions options = GenerateTestsHandler.options(resolved, project);
            TestClassSelection.Inference inference = TestClassSelection.infer(test, options.testSuffix);
            IType classUnderTest = inference.classUnderTest() != null ? inference.classUnderTest()
                    : chooser.choose(shell, test, inference);
            if (classUnderTest == null) {
                continue;
            }
            while (classUnderTest.getDeclaringType() != null) {
                classUnderTest = classUnderTest.getDeclaringType();
            }
            IPackageFragmentRoot root = (IPackageFragmentRoot) test.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            IPath location = root.getResource() == null ? null : root.getResource().getLocation();
            if (location == null) {
                skipped.add(test.getElementName() + " is not on the local file system, where EvoSuite reads it.");
                continue;
            }
            GenerationTarget.Seed seed = new GenerationTarget.Seed(test.getFullyQualifiedName(), location.toFile());
            seeds.computeIfAbsent(project, p -> new LinkedHashMap<>())
                    .computeIfAbsent(classUnderTest.getFullyQualifiedName(), c -> new ArrayList<>()).add(seed);
        }
        Map<IJavaProject, List<GenerationTarget>> requests = new LinkedHashMap<>();
        seeds.forEach((project, byClass) -> byClass.forEach((className, classSeeds) -> requests
                .computeIfAbsent(project, p -> new ArrayList<>())
                .add(GenerationTarget.extending(className, classSeeds))));
        return requests;
    }

    /** Asks with JDT's type dialog, limited to the classes in main source folders. */
    static IType askForClassUnderTest(Shell shell, IType test, TestClassSelection.Inference inference)
            throws JavaModelException {
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
                TestClassSelection.mainSourceRoots(test.getJavaProject()).toArray(IJavaElement[]::new),
                IJavaSearchScope.SOURCES);
        SelectionDialog dialog = JavaUI.createTypeDialog(shell, PlatformUI.getWorkbench().getProgressService(), scope,
                IJavaElementSearchConstants.CONSIDER_CLASSES_AND_ENUMS, false, inference.guess());
        dialog.setTitle("Class Under Test");
        dialog.setMessage((inference.candidates().isEmpty()
                ? "The name of " + test.getElementName() + " does not tell which class it tests."
                : "Several classes match the name of " + test.getElementName() + ".")
                + " Choose the class to extend its tests for:");
        if (dialog.open() != Window.OK || dialog.getResult() == null || dialog.getResult().length == 0) {
            return null;
        }
        return dialog.getResult()[0] instanceof IType type ? type : null;
    }
}
