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
package org.evosuite.runtime.mock.java.awt;

import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.mock.OverrideMock;

import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;

/**
 * OverrideMock for {@link java.awt.FileDialog}. Uses mock owners so dialog
 * construction does not force native X11 peer initialization.
 */
public class MockFileDialog extends FileDialog implements OverrideMock {

    private static final long serialVersionUID = -3051106722503535364L;

    private static Frame prepareFrameOwner(Frame owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    private static Dialog prepareDialogOwner(Dialog owner) {
        GuiSupport.disableHeadlessForMockConstruction();
        return owner;
    }

    public MockFileDialog(Frame parent) {
        super(prepareFrameOwner(parent));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockFileDialog(Frame parent, String title) {
        super(prepareFrameOwner(parent), title);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockFileDialog(Frame parent, String title, int mode) {
        super(prepareFrameOwner(parent), title, mode);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockFileDialog(Dialog parent) {
        super(prepareDialogOwner(parent));
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockFileDialog(Dialog parent, String title) {
        super(prepareDialogOwner(parent), title);
        GuiSupport.restoreHeadlessAfterMockConstruction();
    }

    public MockFileDialog(Dialog parent, String title, int mode) {
        super(prepareDialogOwner(parent), title, mode);
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
    public void toFront() {
        // no-op
    }

    @Override
    public void toBack() {
        // no-op
    }
}
