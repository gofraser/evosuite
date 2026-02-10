/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

package org.evosuite.runtime.instrumentation;

import org.objectweb.asm.Label;
import org.objectweb.asm.tree.LabelNode;

/**
 * Annotated labels are used to identify instrumented code
 * such that EvoSuite knows how to deal with.
 *
 * @author fraser
 */
public class AnnotatedLabel extends Label {

    private boolean isStart = false;

    private boolean ignore = false;

    private boolean ignoreFalse = false;

    private LabelNode parent = null;

    /**
     * <p>Constructor for AnnotatedLabel.</p>
     *
     * @param ignore a boolean.
     * @param start  a boolean.
     */
    public AnnotatedLabel(boolean ignore, boolean start) {
        this.ignore = ignore;
        this.isStart = start;
    }

    /**
     * <p>Constructor for AnnotatedLabel.</p>
     *
     * @param ignore a boolean.
     * @param start  a boolean.
     * @param parent a {@link org.objectweb.asm.tree.LabelNode} object.
     */
    public AnnotatedLabel(boolean ignore, boolean start, LabelNode parent) {
        this.ignore = ignore;
        this.isStart = start;
        this.parent = parent;
    }

    /**
     * <p>isStartTag.</p>
     *
     * @return a boolean.
     */
    public boolean isStartTag() {
        return isStart;
    }

    /**
     * <p>shouldIgnore.</p>
     *
     * @return a boolean.
     */
    public boolean shouldIgnore() {
        return ignore;
    }

    /**
     * <p>setIgnoreFalse.</p>
     *
     * @param value a boolean.
     */
    public void setIgnoreFalse(boolean value) {
        ignoreFalse = value;
    }

    /**
     * <p>shouldIgnoreFalse.</p>
     *
     * @return a boolean.
     */
    public boolean shouldIgnoreFalse() {
        return ignoreFalse;
    }

    /**
     * <p>getParent.</p>
     *
     * @return a {@link org.objectweb.asm.tree.LabelNode} object.
     */
    public LabelNode getParent() {
        return parent;
    }

    /**
     * <p>setParent.</p>
     *
     * @param parent a {@link org.objectweb.asm.tree.LabelNode} object.
     */
    public void setParent(LabelNode parent) {
        this.parent = parent;
    }
}
