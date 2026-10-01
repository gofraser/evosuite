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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Adding a component needs no native peer, so it must happen even while GUI mocking is on:
 * skipping it left the container empty and the SUT's next call failed. 10_water-simulator's
 * ProgressGui adds two tabs and then selects one, which threw "component not found in tabbed
 * pane" on every seed of the SF110 replication.
 */
class MockHeadlessSwingContainerAddTest {

    private boolean defaultMockGui;
    private boolean defaultMockFrameworkEnabled;

    @BeforeEach
    void enableGuiMocking() {
        defaultMockGui = RuntimeSettings.mockGUI;
        defaultMockFrameworkEnabled = MockFramework.isEnabled();
        RuntimeSettings.mockGUI = true;
        MockFramework.enable();
    }

    @AfterEach
    void restoreRuntimeState() {
        RuntimeSettings.mockGUI = defaultMockGui;
        if (defaultMockFrameworkEnabled) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
    }

    @Test
    void tabAddedWithConstraintsCanBeSelected() {
        JTabbedPane pane = new JTabbedPane();
        JPanel tab = new JPanel();
        JPanel other = new JPanel();

        MockHeadlessSwing.replacement_containerAdd(pane, tab, null);
        MockHeadlessSwing.replacement_containerAdd(pane, other, null);

        assertEquals(2, pane.getTabCount());
        pane.setSelectedComponent(tab); // threw IllegalArgumentException before
        assertSame(tab, pane.getSelectedComponent());
    }

    @Test
    void addTabIsVisibleToTheContainer() {
        JTabbedPane pane = new JTabbedPane();
        JPanel tab = new JPanel();

        MockHeadlessSwing.replacement_tabbedPaneAddTab(pane, "title", tab);

        assertEquals(1, pane.getTabCount());
        assertEquals("title", pane.getTitleAt(0));
    }

    @Test
    void aThreadBeingForceStoppedIsNotAbsorbed() {
        JPanel parent = new JPanel(new ThrowingLayout(new ThreadDeath()));

        // Swallowing this would leave the thread EvoSuite is force-stopping running.
        assertThrows(ThreadDeath.class,
                () -> MockHeadlessSwing.replacement_containerAdd(parent, new JPanel()));
    }

    @Test
    void anExhaustedJvmIsNotAbsorbed() {
        JPanel parent = new JPanel(new ThrowingLayout(new StackOverflowError()));

        assertThrows(StackOverflowError.class,
                () -> MockHeadlessSwing.replacement_containerAdd(parent, new JPanel()));
    }

    @Test
    void anAssertionErrorFromASutOverrideIsNotAbsorbed() {
        // Container.addImpl is invoked virtually, so a SUT override that throws AssertionError
        // (eg a self-check the SUT performs on add) must reach the caller, not be read as a
        // headless refusal and papered over.
        JPanel parent = new JPanel(new ThrowingLayout(new AssertionError("sut invariant violated")));

        assertThrows(AssertionError.class,
                () -> MockHeadlessSwing.replacement_containerAdd(parent, new JPanel()));
    }

    @Test
    void aGenuineHeadlessExceptionIsStillAbsorbed() {
        // A real headless refusal must still be swallowed and degrade to the mocked result,
        // exactly as before this was narrowed down from catching every Error. (Container.addImpl
        // already links the child into the parent's component array before it calls the layout
        // manager, so the component count is not a useful signal here -- only that the
        // HeadlessException itself does not escape, unlike the AssertionError case above.)
        JPanel parent = new JPanel(new ThrowingLayout(new java.awt.HeadlessException()));
        JPanel child = new JPanel();

        Object result = MockHeadlessSwing.replacement_containerAdd(parent, child);

        assertSame(child, result);
    }

    /** A layout manager that fails the way SUT code reached from {@code Container.add} can. */
    private static final class ThrowingLayout extends BorderLayout {
        private final Throwable failure;

        ThrowingLayout(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void addLayoutComponent(java.awt.Component comp, Object constraints) {
            sneakyThrow(failure);
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
            throw (T) t;
        }
    }

    @Test
    void aComponentTheMockLayerRemovedIsNotAdded() {
        // replacement_newLabel and its siblings return null in a headless environment, so the
        // rewritten add can be handed a null the SUT never created. Container.addImpl
        // dereferences it; 99_newzgrabber's CreateDirectoryDialog does
        // panel.add(new Label("Subdirectory:")) and threw on every seed of the SF110 replication.
        JPanel parent = new JPanel(new BorderLayout());

        assertNull(MockHeadlessSwing.replacement_containerAdd(parent, (Component) null));
        MockHeadlessSwing.replacement_containerAdd(parent, (Component) null, BorderLayout.CENTER);

        assertEquals(0, parent.getComponentCount());
    }

    @Test
    void aNullTheSutChoseStillFailsWhenGuiMockingIsOff() {
        // With -Dreplace_gui=false the null is the SUT's own, and hiding it would hide a real defect.
        RuntimeSettings.mockGUI = false;
        JPanel parent = new JPanel(new BorderLayout());

        assertThrows(NullPointerException.class,
                () -> MockHeadlessSwing.replacement_containerAdd(parent, (Component) null));
    }

    @Test
    void aNullTabComponentStillAddsItsTab() {
        // JTabbedPane.addTab(title, null) is legal and adds a tab, so the null guard must not
        // reach it: skipping the call here would bring back the "component not found" failure.
        JTabbedPane pane = new JTabbedPane();

        MockHeadlessSwing.replacement_tabbedPaneAddTab(pane, "title", null);

        assertEquals(1, pane.getTabCount());
        assertEquals("title", pane.getTitleAt(0));
    }

    /**
     * Container.remove reads comp.parent exactly as addImpl does, so the same null needs the same
     * guard. 102_squirrel-sql's ButtonTabComponent does remove(null) and threw on all ten seeds.
     */
    @Test
    void aComponentTheMockLayerRemovedIsNotRemovedEither() {
        JPanel parent = new JPanel(new BorderLayout());

        MockHeadlessSwing.replacement_containerRemove(parent, (Component) null);
        MockHeadlessSwing.replacement_removeComponentGeneric(parent, (Component) null);

        assertEquals(0, parent.getComponentCount());
    }

    @Test
    void realRemovalsStillReachTheContainer() {
        JPanel parent = new JPanel(new BorderLayout());
        JPanel child = new JPanel();
        parent.add(child);

        MockHeadlessSwing.replacement_containerRemove(parent, child);

        assertEquals(0, parent.getComponentCount());
    }

    @Test
    void aNullRemovalTheSutChoseStillFailsWhenGuiMockingIsOff() {
        RuntimeSettings.mockGUI = false;
        JPanel parent = new JPanel(new BorderLayout());

        assertThrows(NullPointerException.class,
                () -> MockHeadlessSwing.replacement_containerRemove(parent, (Component) null));
    }

    @Test
    void plainAddsReachTheContainer() {
        JPanel parent = new JPanel(new BorderLayout());
        JPanel north = new JPanel();
        JPanel centre = new JPanel();

        assertSame(north, MockHeadlessSwing.replacement_containerAdd(parent, north));
        MockHeadlessSwing.replacement_containerAdd(parent, centre, BorderLayout.CENTER);

        assertEquals(2, parent.getComponentCount());
        assertSame(centre, ((BorderLayout) parent.getLayout()).getLayoutComponent(BorderLayout.CENTER));
    }
}
