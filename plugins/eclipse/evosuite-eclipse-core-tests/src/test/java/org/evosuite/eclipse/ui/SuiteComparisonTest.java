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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.UiThread;
import org.evosuite.eclipse.handlers.ExistingSuitesQuestionTest;
import org.junit.jupiter.api.Test;

/** The compare editor that shows existing suites next to new ones. */
public class SuiteComparisonTest {

    @Test
    public void showsOneSuiteDirectlyAndSavesTheEditedSide() throws Exception {
        IJavaProject project = TestProjects.create("compare-one", "src", null);
        IFile existing = TestProjects.addSource(project, "src", "demo.Calculator_ESTest", "class Old {}\n");
        SuiteComparison comparison = new SuiteComparison(List.of(new SuiteComparison.Pair(existing, "class New {}\n")));

        openAndClose(List.of(new SuiteComparison.Pair(existing, "class New {}\n")),
                "EvoSuite: Calculator_ESTest.java", "suite-comparison.png");

        comparison.run(new NullProgressMonitor());
        DiffNode node = (DiffNode) comparison.getCompareResult();
        assertEquals("class Old {}\n", read((IStreamContentAccessor) node.getLeft()));
        assertEquals("class New {}\n", read((IStreamContentAccessor) node.getRight()));
        assertTrue(((IEditableContent) node.getLeft()).isEditable());
        assertFalse(node.getRight() instanceof IEditableContent, "the new suite is editable");

        // What the viewer hands over when the user has copied the new class across.
        ((IEditableContent) node.getLeft()).setContent("class Old {}\nclass New {}\n".getBytes(StandardCharsets.UTF_8));
        UiThread.run(() -> comparison.saveChanges(null));
        assertEquals("class Old {}\nclass New {}\n", read(existing));
        assertEquals(1, existing.getHistory(null).length, "the previous content is not in the local history");
    }

    @Test
    public void listsSeveralSuitesInOneComparison() throws Exception {
        IJavaProject project = TestProjects.create("compare-many", "src", null);
        IFile first = TestProjects.addSource(project, "src", "demo.A_ESTest", "class A {}\n");
        IFile second = TestProjects.addSource(project, "src", "demo.B_ESTest", "class B {}\n");
        SuiteComparison comparison = new SuiteComparison(List.of(new SuiteComparison.Pair(first, "class A2 {}\n"),
                new SuiteComparison.Pair(second, "class B2 {}\n")));

        openAndClose(List.of(new SuiteComparison.Pair(first, "class A2 {}\n"),
                new SuiteComparison.Pair(second, "class B2 {}\n")), "EvoSuite: 2 test suites",
                "suite-comparison-list.png");
        comparison.run(new NullProgressMonitor());

        DiffNode root = (DiffNode) comparison.getCompareResult();
        IDiffElement[] children = root.getChildren();
        assertEquals(2, children.length);
        assertEquals("A_ESTest.java", children[0].getName());
        assertEquals("B_ESTest.java", children[1].getName());
        assertEquals("EvoSuite: 2 test suites", comparison.getTitle());
        // Saving without edits leaves the files alone.
        UiThread.run(() -> comparison.saveChanges(null));
        assertEquals(0, first.getHistory(null).length);
    }

    /**
     * Opens a comparison in an editor and closes it again. Set {@code EVOSUITE_SCREENSHOTS} to a
     * folder to get an image of it.
     */
    private static void openAndClose(List<SuiteComparison.Pair> pairs, String title, String screenshot)
            throws Exception {
        UiThread.run(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            SuiteComparison.open(pairs);
            UiThread.drain();
            IEditorPart editor = null;
            for (IEditorReference reference : page.getEditorReferences()) {
                if (reference.getEditorInput() instanceof SuiteComparison) {
                    editor = reference.getEditor(true);
                }
            }
            assertNotNull(editor, "no compare editor opened");
            assertEquals(title, editor.getEditorInput().getName());
            String folder = System.getenv("EVOSUITE_SCREENSHOTS");
            if (folder != null && !folder.isEmpty()) {
                UiThread.drain(1000);
                ExistingSuitesQuestionTest.save(editor.getSite().getShell(), new File(folder, screenshot));
            }
            page.closeEditor(editor, false);
        });
    }

    private static String read(IStreamContentAccessor side) throws Exception {
        try (InputStream in = side.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String read(IFile file) throws Exception {
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
