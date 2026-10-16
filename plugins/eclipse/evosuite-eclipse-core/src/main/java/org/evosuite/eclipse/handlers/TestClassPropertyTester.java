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

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Decides when the menus offer Extend Tests with EvoSuite: {@code testClass} holds for a test
 * class, or a compilation unit that contains one, and {@code editsTestClass} for a workbench part
 * that is a Java editor of such a compilation unit. See {@link TestClassSelection#isTestClass}.
 */
public class TestClassPropertyTester extends PropertyTester {

    public static final String TEST_CLASS = "testClass";
    public static final String EDITS_TEST_CLASS = "editsTestClass";

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        try {
            IJavaElement element = null;
            if (TEST_CLASS.equals(property) && receiver instanceof IJavaElement selected) {
                element = selected;
            } else if (EDITS_TEST_CLASS.equals(property) && receiver instanceof IWorkbenchPart part) {
                element = part instanceof IEditorPart editor && editor.getEditorInput() != null
                        ? JavaUI.getEditorInputJavaElement(editor.getEditorInput()) : null;
            }
            return element != null && TestClassSelection.containsTestClass(element);
        } catch (JavaModelException e) {
            return false;
        }
    }
}
