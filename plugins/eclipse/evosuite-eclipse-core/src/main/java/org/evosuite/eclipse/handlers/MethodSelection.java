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
import java.util.Map;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Which methods tests can be generated for, and the method a Java editor's caret is in.
 *
 * <p>EvoSuite tests one top-level class at a time, and with a method list its tests call only
 * those methods, plus constructors to create the object. So a method qualifies if it is declared
 * directly in a top-level class of a main source folder and tests can call it: private methods
 * would leave tests that only construct objects, abstract ones have no code to cover, and
 * constructors of enums and abstract classes cannot be called. Constructors otherwise qualify.
 */
public final class MethodSelection {

    private MethodSelection() {
    }

    /**
     * Why tests cannot be generated for {@code method}, as the end of a sentence that starts
     * with its name, or {@code null} if they can.
     */
    public static String whyNotTestable(IMethod method) throws JavaModelException {
        if (!method.exists() || method.isLambdaMethod()) {
            return "is not a method of a class";
        }
        IType type = method.getDeclaringType();
        if (type == null || type.isBinary()) {
            return "is not in the project's sources";
        }
        if (type.getDeclaringType() != null || type.isLocal() || type.isAnonymous()) {
            return "belongs to a nested class; EvoSuite tests top-level classes, so generate tests for "
                    + "the class that contains it";
        }
        if (type.isInterface() || type.isAnnotation()) {
            return "belongs to an interface";
        }
        IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root == null || !TargetSelection.isMainSource(root)) {
            return "is in a test source folder";
        }
        int flags = method.getFlags();
        if (Flags.isPrivate(flags)) {
            return "is private, so tests can only reach it through other methods; generate tests for "
                    + "those or for the class";
        }
        if (Flags.isAbstract(flags)) {
            return "is abstract";
        }
        if (method.isConstructor() && (type.isEnum() || Flags.isAbstract(type.getFlags()))) {
            return "is a constructor of " + (type.isEnum() ? "an enum" : "an abstract class")
                    + ", which tests cannot call";
        }
        return null;
    }

    /** The methods among the selected elements, grouped by their class. */
    public static Map<IType, List<IMethod>> collect(Iterable<?> selection) {
        Map<IType, List<IMethod>> methods = new LinkedHashMap<>();
        for (Object item : selection) {
            IMethod method = Adapters.adapt(item, IMethod.class);
            if (method != null && method.getDeclaringType() != null) {
                List<IMethod> ofType = methods.computeIfAbsent(method.getDeclaringType(), t -> new ArrayList<>());
                if (!ofType.contains(method)) {
                    ofType.add(method);
                }
            }
        }
        return methods;
    }

    /** The method the caret of a Java editor is in, or {@code null}. */
    public static IMethod methodAtCaret(IEditorPart editor) throws JavaModelException {
        ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
        if (textEditor == null || textEditor.getSelectionProvider() == null) {
            return null;
        }
        ITypeRoot root = JavaUI.getEditorInputTypeRoot(textEditor.getEditorInput());
        ISelection selection = textEditor.getSelectionProvider().getSelection();
        if (root == null || !(selection instanceof ITextSelection text) || text.getOffset() < 0) {
            return null;
        }
        IJavaElement element = root.getElementAt(text.getOffset());
        return element instanceof IMethod method ? method : null;
    }
}
