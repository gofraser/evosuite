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
package org.evosuite.eclipse.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.evosuite.eclipse.UiThread;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.List;

public class RunResultsViewTest {
    @Test
    public void showsGenerationAndResultActions() throws Exception {
        UiThread.run(
                () -> {
                    var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    try {
                        var view = page.showView(RunResultsView.ID);
                        assertTrue(view instanceof RunResultsView);
                        var parent = view.getSite().getShell();
                        Set<String> buttons = new LinkedHashSet<>();
                        collect(parent, buttons);
                        assertTrue(
                                buttons.containsAll(
                                        List.of(
                                                "Generate class",
                                                "Generate method",
                                                "Extend tests",
                                                "Measure coverage",
                                                "Open tests",
                                                "Run tests",
                                                "Compare",
                                                "Repeat saved run",
                                                "Cancel selected",
                                                "Retention")),
                                buttons.toString());
                        page.hideView(view);
                    } catch (org.eclipse.ui.PartInitException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    private void collect(Composite parent, Set<String> buttons) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Button button) buttons.add(button.getText());
            else if (child instanceof Composite composite) collect(composite, buttons);
        }
    }
}
