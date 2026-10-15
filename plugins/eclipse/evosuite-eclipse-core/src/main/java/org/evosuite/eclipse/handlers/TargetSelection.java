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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.Adapters;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Turns a selection of Java elements into the top-level classes to generate tests for, grouped
 * by project. Packages, source folders and projects stand for every class they contain; classes
 * in test source folders, interfaces and annotations are left out, since EvoSuite has nothing to
 * test in them.
 */
public final class TargetSelection {

    private TargetSelection() {
    }

    public static Map<IJavaProject, Set<IType>> collect(Iterable<?> selection) throws JavaModelException {
        Map<IJavaProject, Set<IType>> targets = new LinkedHashMap<>();
        for (Object item : selection) {
            IJavaElement element = Adapters.adapt(item, IJavaElement.class);
            if (element != null && element.exists()) {
                collect(element, targets);
            }
        }
        targets.values().removeIf(Set::isEmpty);
        return targets;
    }

    private static void collect(IJavaElement element, Map<IJavaProject, Set<IType>> targets)
            throws JavaModelException {
        switch (element.getElementType()) {
            case IJavaElement.JAVA_PROJECT:
                for (IPackageFragmentRoot root : ((IJavaProject) element).getPackageFragmentRoots()) {
                    if (root.getJavaProject().equals(element)) {
                        collect(root, targets);
                    }
                }
                break;
            case IJavaElement.PACKAGE_FRAGMENT_ROOT:
                IPackageFragmentRoot root = (IPackageFragmentRoot) element;
                if (isMainSource(root)) {
                    for (IJavaElement child : root.getChildren()) {
                        collect(child, targets);
                    }
                }
                break;
            case IJavaElement.PACKAGE_FRAGMENT:
                IPackageFragment pkg = (IPackageFragment) element;
                if (pkg.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (ICompilationUnit unit : pkg.getCompilationUnits()) {
                        collect(unit, targets);
                    }
                }
                break;
            case IJavaElement.COMPILATION_UNIT:
                for (IType type : ((ICompilationUnit) element).getTypes()) {
                    add(type, targets);
                }
                break;
            default:
                // A member, method or field stands for the class it belongs to.
                IJavaElement type = element.getAncestor(IJavaElement.TYPE);
                if (type != null) {
                    add(topLevel((IType) type), targets);
                }
                break;
        }
    }

    private static void add(IType type, Map<IJavaProject, Set<IType>> targets) throws JavaModelException {
        if (type.isBinary() || type.isInterface() || type.isAnnotation()) {
            return;
        }
        IPackageFragmentRoot root = (IPackageFragmentRoot) type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root == null || !isMainSource(root)) {
            return;
        }
        targets.computeIfAbsent(type.getJavaProject(), p -> new LinkedHashSet<>()).add(type);
    }

    private static IType topLevel(IType type) {
        IType current = type;
        while (current.getDeclaringType() != null) {
            current = current.getDeclaringType();
        }
        return current;
    }

    private static boolean isMainSource(IPackageFragmentRoot root) throws JavaModelException {
        if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
            return false;
        }
        IClasspathEntry entry = root.getRawClasspathEntry();
        return entry == null || !entry.isTest();
    }
}
