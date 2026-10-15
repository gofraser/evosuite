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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerPane;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
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

    @Test
    public void staysDirtyWhenTheViewerHandsOverEditsOnSwitchingSuites() throws Exception {
        IJavaProject project = TestProjects.create("compare-switch", "src", null);
        IFile first = TestProjects.addSource(project, "src", "demo.A_ESTest", "class A {}\n");
        IFile second = TestProjects.addSource(project, "src", "demo.B_ESTest", "class B {}\n");
        Scripted comparison = new Scripted(List.of(new SuiteComparison.Pair(first, "class A2 {}\n"),
                new SuiteComparison.Pair(second, "class B2 {}\n")));
        // Hands the edits over as the Yes of "Save changes?" does, without the dialog.
        comparison.getCompareConfiguration().setProperty(CONFIRM_SAVE, Boolean.FALSE);

        UiThread.run(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            CompareUI.openCompareEditor(comparison);
            UiThread.drain();
            IEditorPart editor = page.findEditor(comparison);
            assertNotNull(editor, "no compare editor opened");
            try {
                comparison.show(0);
                StyledText left = editableText(editor.getSite().getShell(), "class A {}\n");
                assertNotNull(left, "the existing suite is not shown");
                left.replaceTextRange(0, 0, "// edited\n");
                UiThread.drain();
                assertTrue(editor.isDirty());

                comparison.show(1);
                assertTrue(editor.isDirty(), "the edits handed over on switching suites are not saved on close");

                editor.doSave(new NullProgressMonitor());
                UiThread.drain();
                assertFalse(editor.isDirty());
                assertEquals("// edited\nclass A {}\n", read(first));
                assertEquals("class B {}\n", read(second));
            } finally {
                page.closeEditor(editor, false);
            }
        });
    }

    @Test
    public void asksBeforeOverwritingChangesSavedWhileTheViewerHeldEdits() throws Exception {
        IJavaProject project = TestProjects.create("compare-switch-stale", "src", null);
        IFile first = TestProjects.addSource(project, "src", "demo.A_ESTest", "class A {}\n");
        IFile second = TestProjects.addSource(project, "src", "demo.B_ESTest", "class B {}\n");
        Scripted comparison = new Scripted(List.of(new SuiteComparison.Pair(first, "class A2 {}\n"),
                new SuiteComparison.Pair(second, "class B2 {}\n")));
        comparison.getCompareConfiguration().setProperty(CONFIRM_SAVE, Boolean.FALSE);

        UiThread.run(() -> {
            IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            CompareUI.openCompareEditor(comparison);
            UiThread.drain();
            IEditorPart editor = page.findEditor(comparison);
            try {
                comparison.show(0);
                editableText(editor.getSite().getShell(), "class A {}\n").replaceTextRange(0, 0, "// edited\n");
                comparison.show(1);
                // Saved elsewhere meanwhile, for example by a merge into the same suite.
                first.setContents(new ByteArrayInputStream("class Newer {}\n".getBytes(StandardCharsets.UTF_8)),
                        IResource.KEEP_HISTORY, null);

                comparison.overwrite = false;
                assertThrows(OperationCanceledException.class, () -> comparison.saveChanges(null));
                assertEquals(List.of(first), comparison.asked);
                assertEquals("class Newer {}\n", read(first));
                assertTrue(editor.isDirty());

                comparison.overwrite = true;
                comparison.saveChanges(null);
                assertEquals("// edited\nclass A {}\n", read(first));
                assertFalse(comparison.isSaveNeeded());
            } finally {
                page.closeEditor(editor, false);
            }
        });
    }

    @Test
    public void staysDirtyWhenTheFileCannotBeWritten() throws Exception {
        IJavaProject project = TestProjects.create("compare-readonly", "src", null);
        IFile existing = TestProjects.addSource(project, "src", "demo.Calculator_ESTest", "class Old {}\n");
        Scripted comparison = new Scripted(List.of(new SuiteComparison.Pair(existing, "class New {}\n")));
        IEditableContent left = editableSide(comparison);
        left.setContent("class Edited {}\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(comparison.isSaveNeeded());

        setReadOnly(existing, true);
        try {
            assertThrows(CoreException.class, () -> UiThread.run(() -> comparison.saveChanges(null)));
            assertTrue(comparison.isSaveNeeded(), "the edits that were not written are lost on close");
            assertEquals("class Old {}\n", read(existing));
        } finally {
            setReadOnly(existing, false);
        }
        UiThread.run(() -> comparison.saveChanges(null));
        assertFalse(comparison.isSaveNeeded());
        assertEquals("class Edited {}\n", read(existing));
    }

    @Test
    public void asksBeforeOverwritingAFileThatChangedSinceItWasRead() throws Exception {
        IJavaProject project = TestProjects.create("compare-stale", "src", null);
        IFile existing = TestProjects.addSource(project, "src", "demo.Calculator_ESTest", "class Old {}\n");
        Scripted comparison = new Scripted(List.of(new SuiteComparison.Pair(existing, "class New {}\n")));
        IEditableContent left = editableSide(comparison);
        left.setContent("class Edited {}\n".getBytes(StandardCharsets.UTF_8));
        // Saved elsewhere meanwhile, for example by a merge into the same suite.
        existing.setContents(new ByteArrayInputStream("class Newer {}\n".getBytes(StandardCharsets.UTF_8)),
                IResource.KEEP_HISTORY, null);

        comparison.overwrite = false;
        assertThrows(OperationCanceledException.class, () -> UiThread.run(() -> comparison.saveChanges(null)));
        assertEquals(List.of(existing), comparison.asked);
        assertEquals("class Newer {}\n", read(existing));
        assertTrue(comparison.isSaveNeeded());

        comparison.overwrite = true;
        UiThread.run(() -> comparison.saveChanges(null));
        assertEquals("class Edited {}\n", read(existing));
        assertFalse(comparison.isSaveNeeded());
        // Written by the comparison itself: no question the next time.
        left.setContent("class Again {}\n".getBytes(StandardCharsets.UTF_8));
        UiThread.run(() -> comparison.saveChanges(null));
        assertEquals(2, comparison.asked.size());
        assertEquals("class Again {}\n", read(existing));
    }

    @Test
    public void leavesFilesWithUnsavedChangesInAnEditorAlone() throws Exception {
        IJavaProject project = TestProjects.create("compare-dirty", "src", null);
        IFile existing = TestProjects.addSource(project, "src", "demo.Calculator_ESTest", "class Old {}\n");
        Scripted comparison = new Scripted(List.of(new SuiteComparison.Pair(existing, "class New {}\n")));
        IEditableContent left = editableSide(comparison);
        left.setContent("class Edited {}\n".getBytes(StandardCharsets.UTF_8));
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        manager.connect(existing.getFullPath(), LocationKind.IFILE, null);
        try {
            manager.getTextFileBuffer(existing.getFullPath(), LocationKind.IFILE).getDocument()
                    .replace(0, 0, "// typed in the editor\n");

            CoreException refusal = assertThrows(CoreException.class,
                    () -> UiThread.run(() -> comparison.saveChanges(null)));
            assertTrue(refusal.getMessage().contains("unsaved changes"), refusal.getMessage());
            assertEquals("class Old {}\n", read(existing));
            assertTrue(comparison.isSaveNeeded());
        } finally {
            manager.disconnect(existing.getFullPath(), LocationKind.IFILE, null);
        }
    }

    /** {@code CompareEditor.CONFIRM_SAVE_PROPERTY}, which is internal. */
    private static final String CONFIRM_SAVE = "org.eclipse.compare.internal.CONFIRM_SAVE_PROPERTY";

    /** A comparison whose answers the test chooses and whose list of suites it can click. */
    private static final class Scripted extends SuiteComparison {
        final List<IFile> asked = new ArrayList<>();
        boolean overwrite;
        private CompareViewerPane structure;

        Scripted(List<Pair> pairs) {
            super(pairs);
        }

        @Override
        protected boolean confirmOverwrite(IFile file) {
            asked.add(file);
            return overwrite;
        }

        @Override
        protected CompareViewerPane createStructureInputPane(Composite parent) {
            structure = super.createStructureInputPane(parent);
            return structure;
        }

        /** Opens the {@code index}th suite of the list, as a double click does. */
        void show(int index) {
            TreeViewer viewer = (TreeViewer) ((CompareViewerSwitchingPane) structure).getViewer();
            Object suite = ((DiffNode) getCompareResult()).getChildren()[index];
            viewer.setSelection(new StructuredSelection(suite), true);
            Tree tree = viewer.getTree();
            Event event = new Event();
            event.item = tree.getSelection()[0];
            tree.notifyListeners(SWT.DefaultSelection, event);
            UiThread.drain();
        }
    }

    private static IEditableContent editableSide(SuiteComparison comparison) throws Exception {
        comparison.run(new NullProgressMonitor());
        DiffNode node = (DiffNode) comparison.getCompareResult();
        read((IStreamContentAccessor) node.getLeft());
        return (IEditableContent) node.getLeft();
    }

    private static void setReadOnly(IFile file, boolean readOnly) throws CoreException {
        var attributes = file.getResourceAttributes();
        attributes.setReadOnly(readOnly);
        file.setResourceAttributes(attributes);
    }

    /** The editable text widget below {@code control} that shows {@code text}. */
    private static StyledText editableText(Control control, String text) {
        if (control instanceof StyledText styled && styled.getEditable() && styled.getText().equals(text)) {
            return styled;
        }
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                StyledText found = editableText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
