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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.GenerationTarget;

/**
 * Generate Tests for Method with EvoSuite: for the methods selected in a view, or the method the
 * caret of the active Java editor is in. The methods of one class go into one EvoSuite run, whose
 * tests call only those methods.
 *
 * <p>Such a suite covers part of the class, so by default its tests are added to an existing
 * suite of the class rather than replacing it.
 */
public class GenerateMethodTestsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            generate(HandlerUtil.getActiveShell(event), selectedMethods(event));
        } catch (JavaModelException e) {
            throw new ExecutionException("Cannot read the selected Java elements", e);
        }
        return null;
    }

    /** Generates tests for {@code methods}, grouped by their classes. */
    static void generate(Shell shell, Map<IType, List<IMethod>> methods) throws JavaModelException {
        // The descriptors come from the saved source, the run from the classes compiled from it.
        Set<IResource> files = new LinkedHashSet<>();
        for (IType type : methods.keySet()) {
            if (type.getResource() != null) {
                files.add(type.getResource());
            }
        }
        if (!GenerateTestsHandler.saveEditors(files)) {
            return;
        }
        List<String> skipped = new ArrayList<>();
        Map<IJavaProject, GenerationOptions> options = new LinkedHashMap<>();
        Map<IJavaProject, List<GenerationTarget>> requests = targets(methods, options, skipped);
        if (requests.isEmpty()) {
            GenerateTestsHandler.informer.accept(shell, skipped.isEmpty()
                    ? "Select a method in the Package Explorer or the Outline, or place the caret in a method "
                            + "in the Java editor."
                    : "No tests can be generated for the selection:\n\n" + String.join("\n", skipped));
            return;
        }
        if (!skipped.isEmpty()) {
            GenerateTestsHandler.informer.accept(shell, "Some of the selected methods are left out:\n\n"
                    + String.join("\n", skipped));
        }
        GenerateTestsHandler.launch(shell, requests, options, ExistingSuitesQuestion.Scope.METHODS);
    }

    /**
     * The methods grouped into one target per class, and per project.
     *
     * @param skipped receives a line for each method left out, saying why
     */
    static Map<IJavaProject, List<GenerationTarget>> targets(Map<IType, List<IMethod>> methods, List<String> skipped)
            throws JavaModelException {
        return targets(methods, new LinkedHashMap<>(), skipped);
    }

    /** @param resolved the settings of the projects, which receives those it resolves */
    static Map<IJavaProject, List<GenerationTarget>> targets(Map<IType, List<IMethod>> methods,
                                                             Map<IJavaProject, GenerationOptions> resolved,
                                                             List<String> skipped) throws JavaModelException {
        Map<IJavaProject, List<GenerationTarget>> requests = new LinkedHashMap<>();
        for (Map.Entry<IType, List<IMethod>> entry : methods.entrySet()) {
            IType type = entry.getKey();
            GenerationOptions options = GenerateTestsHandler.options(resolved, type.getJavaProject());
            List<String> names = new ArrayList<>();
            for (IMethod method : entry.getValue()) {
                String label = type.getElementName() + "." + method.getElementName() + "()";
                String reason = MethodSelection.whyNotTestable(method);
                if (reason == null && GenerateTestsHandler.isGeneratedTest(type, options)) {
                    reason = "belongs to a generated test suite";
                }
                String descriptor = reason == null ? MethodDescriptors.of(method) : null;
                if (reason == null && descriptor == null) {
                    reason = "uses types that cannot be resolved; does the class compile?";
                }
                if (reason != null) {
                    skipped.add(label + " " + reason + ".");
                } else if (!names.contains(descriptor)) {
                    names.add(descriptor);
                }
            }
            if (!names.isEmpty()) {
                requests.computeIfAbsent(type.getJavaProject(), p -> new ArrayList<>())
                        .add(new GenerationTarget(type.getFullyQualifiedName(), names));
            }
        }
        return requests;
    }

    static Map<IType, List<IMethod>> selectedMethods(ExecutionEvent event) throws JavaModelException {
        ISelection selection = HandlerUtil.getActiveMenuSelection(event);
        if (!(selection instanceof IStructuredSelection)) {
            selection = HandlerUtil.getCurrentSelection(event);
        }
        if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
            return MethodSelection.collect(structured.toList());
        }
        // Invoked from a Java editor, where the selection is text.
        IEditorPart editor = GenerateTestsHandler.activeEditor(event);
        IMethod method = editor == null ? null : MethodSelection.methodAtCaret(editor);
        return method == null ? Map.of() : MethodSelection.collect(List.of(method));
    }
}
