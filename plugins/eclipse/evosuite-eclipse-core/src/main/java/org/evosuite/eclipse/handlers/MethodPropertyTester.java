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
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Decides when the menus offer Generate Tests for Method with EvoSuite:
 * {@code testableMethod} holds for a method that tests can be generated for, and
 * {@code caretInTestableMethod} for a workbench part that is a Java editor whose caret is in
 * such a method.
 */
public class MethodPropertyTester extends PropertyTester {

    public static final String TESTABLE_METHOD = "testableMethod";
    public static final String CARET_IN_TESTABLE_METHOD = "caretInTestableMethod";

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        try {
            IMethod method = null;
            if (TESTABLE_METHOD.equals(property) && receiver instanceof IMethod selected) {
                method = selected;
            } else if (CARET_IN_TESTABLE_METHOD.equals(property) && receiver instanceof IWorkbenchPart part) {
                method = part instanceof IEditorPart editor ? MethodSelection.methodAtCaret(editor) : null;
            }
            return method != null && MethodSelection.whyNotTestable(method) == null;
        } catch (JavaModelException e) {
            return false;
        }
    }
}
