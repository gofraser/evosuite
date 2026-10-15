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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.evosuite.eclipse.launch.EvoSuiteCommand;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.ProjectSetup;
import org.evosuite.eclipse.launch.TestGenerationJob;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;

/**
 * Generate Tests with EvoSuite: for the classes selected in a view, or for the class open in the
 * active Java editor.
 */
public class GenerateTestsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        Map<IJavaProject, Set<IType>> targets;
        try {
            targets = TargetSelection.collect(selectedElements(event));
        } catch (JavaModelException e) {
            throw new ExecutionException("Cannot read the selected Java elements", e);
        }
        if (targets.isEmpty()) {
            MessageDialog.openInformation(shell, "EvoSuite",
                    "The selection contains no classes to generate tests for. Select classes, packages, "
                            + "source folders or projects; test source folders, interfaces and annotations "
                            + "are skipped.");
            return null;
        }
        for (Map.Entry<IJavaProject, Set<IType>> entry : targets.entrySet()) {
            IJavaProject project = entry.getKey();
            GenerationOptions options = EvoSuitePreferences.resolve(project.getProject(), null);
            List<String> classes = new ArrayList<>();
            try {
                for (IType type : entry.getValue()) {
                    if (!isGeneratedTest(type, options)) {
                        classes.add(type.getFullyQualifiedName());
                    }
                }
            } catch (JavaModelException e) {
                throw new ExecutionException("Cannot read the selected Java elements", e);
            }
            if (classes.isEmpty() || !confirmOverwrite(shell, project, classes, options)) {
                continue;
            }
            new TestGenerationJob(project, classes, options).schedule();
        }
        return null;
    }

    private static Iterable<?> selectedElements(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (!(selection instanceof IStructuredSelection)) {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        if (selection instanceof IStructuredSelection && !selection.isEmpty()) {
            return ((IStructuredSelection) selection).toList();
        }
        // Invoked from a Java editor, where the selection is text.
        IEditorInput input = HandlerUtil.getActiveEditorInput(event);
        IJavaElement element = input == null ? null : JavaUI.getEditorInputJavaElement(input);
        return element == null ? Collections.emptyList() : List.of(element);
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

    private static boolean confirmOverwrite(Shell shell, IJavaProject project, List<String> classes,
                                            GenerationOptions options) {
        File testDir;
        try {
            testDir = ProjectSetup.testFolder(project.getProject(), options).getLocation().toFile();
        } catch (CoreException e) {
            MessageDialog.openError(shell, "EvoSuite", e.getStatus().getMessage());
            return false;
        }
        List<String> existing = new ArrayList<>();
        for (String className : classes) {
            if (EvoSuiteCommand.suiteFile(testDir, className, options.testSuffix).exists()) {
                existing.add(className);
            }
        }
        if (existing.isEmpty()) {
            return true;
        }
        String which = existing.size() == 1
                ? "A test suite for " + existing.get(0) + " already exists"
                : existing.size() + " of the selected classes already have test suites";
        return MessageDialog.openConfirm(shell, "EvoSuite",
                which + " in " + options.testFolder + ". Generating tests replaces "
                        + (existing.size() == 1 ? "it" : "them") + ". Continue?");
    }
}
