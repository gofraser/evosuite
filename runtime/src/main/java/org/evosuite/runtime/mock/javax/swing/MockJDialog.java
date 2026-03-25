/**
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

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.OverrideMock;

import javax.swing.JDialog;
import java.awt.Dialog;
import java.awt.Dialog.ModalityType;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.HeadlessException;
import java.awt.Window;

/**
 * OverrideMock for {@link javax.swing.JDialog}. This avoids headless failures
 * in constructor chains while keeping dialog display operations as no-ops.
 */
public class MockJDialog extends JDialog implements OverrideMock {

    private static final long serialVersionUID = 7519384748043296025L;

    private static Frame prepareFrame(Frame owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    private static Dialog prepareDialog(Dialog owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    private static Window prepareWindow(Window owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    public MockJDialog() throws HeadlessException {
        super(prepareFrame((Frame) null));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Frame owner) throws HeadlessException {
        super(prepareFrame(owner));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Frame owner, boolean modal) throws HeadlessException {
        super(prepareFrame(owner), modal);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Frame owner, String title) throws HeadlessException {
        super(prepareFrame(owner), title);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Frame owner, String title, boolean modal) throws HeadlessException {
        super(prepareFrame(owner), title, modal);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Frame owner, String title, boolean modal, GraphicsConfiguration gc) {
        super(prepareFrame(owner), title, modal, gc);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Dialog owner) throws HeadlessException {
        super(prepareDialog(owner));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Dialog owner, boolean modal) throws HeadlessException {
        super(prepareDialog(owner), modal);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Dialog owner, String title) throws HeadlessException {
        super(prepareDialog(owner), title);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Dialog owner, String title, boolean modal) throws HeadlessException {
        super(prepareDialog(owner), title, modal);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Dialog owner, String title, boolean modal, GraphicsConfiguration gc) {
        super(prepareDialog(owner), title, modal, gc);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Window owner) {
        super(prepareWindow(owner));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Window owner, ModalityType modalityType) {
        super(prepareWindow(owner), modalityType);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Window owner, String title) {
        super(prepareWindow(owner), title);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Window owner, String title, ModalityType modalityType) {
        super(prepareWindow(owner), title, modalityType);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockJDialog(Window owner, String title, ModalityType modalityType, GraphicsConfiguration gc) {
        super(prepareWindow(owner), title, modalityType, gc);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    @Override
    public void setVisible(boolean b) {
        // no-op: prevent actual dialog display
    }

    @SuppressWarnings("deprecation")
    @Override
    public void show() {
        // no-op
    }

    @Override
    public void pack() {
        // no-op: avoid native peer creation
    }

    @Override
    public void toFront() {
        // no-op
    }

    @Override
    public void toBack() {
        // no-op
    }
}
