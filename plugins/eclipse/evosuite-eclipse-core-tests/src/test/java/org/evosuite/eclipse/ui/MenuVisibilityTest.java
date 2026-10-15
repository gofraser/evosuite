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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.TestProjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** When the explorer context menu offers Generate Tests with EvoSuite. */
public class MenuVisibilityTest {

    private Expression visibleWhen;
    private IJavaProject project;

    @BeforeEach
    public void setUp() throws Exception {
        for (IConfigurationElement contribution : Platform.getExtensionRegistry()
                .getConfigurationElementsFor("org.eclipse.ui.menus")) {
            if (Activator.PLUGIN_ID.equals(contribution.getContributor().getName())
                    && contribution.getAttribute("locationURI").startsWith("popup:org.eclipse.ui.popup.any")) {
                IConfigurationElement condition = contribution.getChildren("command")[0].getChildren("visibleWhen")[0];
                visibleWhen = ExpressionConverter.getDefault().perform(condition.getChildren()[0]);
            }
        }
        assertNotNull(visibleWhen, "no explorer menu contribution");
        project = TestProjects.create("menu", "src", null);
        TestProjects.addSource(project, "src", "m.Plain", "package m; public class Plain { int field; }");
        project.getProject().getFile("notes.txt").create(new java.io.ByteArrayInputStream(new byte[0]), true, null);
    }

    private boolean visibleFor(Object... selected) throws Exception {
        EvaluationContext context = new EvaluationContext(null, new Object());
        context.addVariable("activeMenuSelection", new StructuredSelection(List.of(selected)));
        return visibleWhen.evaluate(context) == EvaluationResult.TRUE;
    }

    @Test
    public void showsForClassesPackagesFoldersAndProjects() throws Exception {
        assertEquals(true, visibleFor(project.findType("m.Plain").getCompilationUnit()));
        assertEquals(true, visibleFor(project.findType("m.Plain")));
        assertEquals(true, visibleFor(project.findType("m.Plain").getPackageFragment()));
        assertEquals(true, visibleFor(project.getPackageFragmentRoot(project.getProject().getFolder("src"))));
        assertEquals(true, visibleFor(project));
    }

    @Test
    public void showsForResourcesInTheProjectExplorer() throws Exception {
        assertEquals(true, visibleFor(project.getProject().getFile("src/m/Plain.java")));
        assertEquals(true, visibleFor(project.getProject()));
    }

    @Test
    public void staysHiddenForOtherSelections() throws Exception {
        assertEquals(false, visibleFor(project.getProject().getFile("notes.txt")));
        assertEquals(false, visibleFor(project.findType("m.Plain").getField("field")));
        assertEquals(false, visibleFor("text"));
    }
}
