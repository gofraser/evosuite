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

import org.eclipse.core.commands.*;
import org.eclipse.jdt.core.*;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.evosuite.eclipse.launch.*;

import java.util.*;

/** Measures selected existing tests with the engine, without generating tests or changing setup. */
public final class MeasureCoverageHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            measure(
                    HandlerUtil.getActiveShell(event),
                    GenerateTestsHandler.selectedElements(event));
        } catch (JavaModelException e) {
            throw new ExecutionException("Cannot read test selection", e);
        }
        return null;
    }

    static void measure(Shell shell, Iterable<?> selection) throws JavaModelException {
        List<IType> tests = TestClassSelection.collect(selection);
        if (tests.isEmpty()) {
            GenerateTestsHandler.informer.accept(
                    shell, "Select existing test classes or a test source directory.");
            return;
        }
        Map<IJavaProject, GenerationOptions> options = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        var targets = ExtendTestsHandler.targets(shell, tests, options, skipped);
        if (!skipped.isEmpty())
            GenerateTestsHandler.informer.accept(shell, String.join("\n", skipped));
        if (targets.isEmpty()
                || !GenerateTestsHandler.saveEditors(
                        targets.keySet().stream().map(IJavaProject::getProject).toList())) return;
        for (var entry : targets.entrySet()) {
            if (!RunHistory.hasActiveSelection(entry.getKey(), entry.getValue(), true))
                new TestGenerationJob(
                                entry.getKey(),
                                entry.getValue(),
                                options.get(entry.getKey()),
                                SuiteAction.COMPARE,
                                true)
                        .schedule();
        }
        org.evosuite.eclipse.ui.RunResultsView.show();
    }
}
