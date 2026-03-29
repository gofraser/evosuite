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
package org.evosuite.runtime.mock.javax.swing;

import org.evosuite.runtime.mock.MockFramework;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Shape;
import java.awt.datatransfer.FlavorMap;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Headless-safe replacements for Swing DnD-related APIs that can throw
 * HeadlessException when GUI setup is executed in headless CI.
 */
public final class MockHeadlessSwing {
    private MockHeadlessSwing() {
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JList}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JList<?> source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Generic replacement for {@code setDragEnabled}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabledGeneric(Object source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JList) {
            replacement_setDragEnabled((JList<?>) source, enabled);
            return;
        }
        if (source instanceof JTree) {
            replacement_setDragEnabled((JTree) source, enabled);
            return;
        }
        if (source instanceof JTable) {
            replacement_setDragEnabled((JTable) source, enabled);
            return;
        }
        invokeSetDragEnabledReflective(source, enabled);
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JTree}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JTree source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Replacement for {@code setDragEnabled} on {@link JTable}.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    public static void replacement_setDragEnabled(JTable source, boolean enabled) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setDragEnabled(enabled);
            return;
        }
        // Headless and mocking enabled: skip DnD activation.
    }

    /**
     * Replacement for {@code setDropTarget} on {@link JComponent}.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    public static void replacement_setDropTarget(JComponent source, DropTarget dropTarget) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setDropTarget(dropTarget);
            return;
        }
        // Headless and mocking enabled: skip DropTarget wiring.
    }

    /**
     * Generic replacement for {@code setDropTarget}.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    public static void replacement_setDropTargetGeneric(Object source, DropTarget dropTarget) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof JComponent) {
            replacement_setDropTarget((JComponent) source, dropTarget);
            return;
        }
        invokeSetDropTargetReflective(source, dropTarget);
    }

    /**
     * Replacement for {@code setMixingCutoutShape} on {@link Component}.
     *
     * @param source the source component
     * @param shape  the shape
     */
    public static void replacement_setMixingCutoutShape(Component source, Shape shape) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (!MockFramework.isEnabled() || !GraphicsEnvironment.isHeadless()) {
            source.setMixingCutoutShape(shape);
            return;
        }
        // Headless and mocking enabled: skip expensive shape mixing computation.
    }

    /**
     * Generic replacement for {@code setMixingCutoutShape}.
     *
     * @param source the source component
     * @param shape  the shape
     */
    public static void replacement_setMixingCutoutShapeGeneric(Object source, Shape shape) {
        if (source == null) {
            throw new NullPointerException();
        }
        if (source instanceof Component) {
            replacement_setMixingCutoutShape((Component) source, shape);
            return;
        }
        invokeSetMixingCutoutShapeReflective(source, shape);
    }

    /**
     * Replacement for {@code new DropTarget()}.
     *
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget() {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return new DropTarget();
    }

    /**
     * Replacement for {@code new DropTarget(Component, DropTargetListener)}.
     *
     * @param c   the component
     * @param dtl the drop target listener
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, DropTargetListener dtl) {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return new DropTarget(c, dtl);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener)}.
     *
     * @param c   the component
     * @param ops the operations
     * @param dtl the drop target listener
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl) {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return new DropTarget(c, ops, dtl);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener, boolean)}.
     *
     * @param c   the component
     * @param ops the operations
     * @param dtl the drop target listener
     * @param act the active state
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl, boolean act) {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return new DropTarget(c, ops, dtl, act);
    }

    /**
     * Replacement for {@code new DropTarget(Component, int, DropTargetListener, boolean, FlavorMap)}.
     *
     * @param c         the component
     * @param ops       the operations
     * @param dtl       the drop target listener
     * @param act       the active state
     * @param flavorMap the flavor map
     * @return a new drop target or null if headless
     */
    public static DropTarget replacement_newDropTarget(Component c, int ops, DropTargetListener dtl, boolean act,
                                                       FlavorMap flavorMap) {
        if (MockFramework.isEnabled() && GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return new DropTarget(c, ops, dtl, act, flavorMap);
    }

    /**
     * Invoke {@code setDragEnabled} reflectively.
     *
     * @param source  the source component
     * @param enabled the enabled state
     */
    private static void invokeSetDragEnabledReflective(Object source, boolean enabled) {
        try {
            Method method = source.getClass().getMethod("setDragEnabled", boolean.class);
            method.invoke(source, enabled);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setDragEnabled(boolean) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setDragEnabled(boolean) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Invoke {@code setDropTarget} reflectively.
     *
     * @param source     the source component
     * @param dropTarget the drop target
     */
    private static void invokeSetDropTargetReflective(Object source, DropTarget dropTarget) {
        try {
            Method method = source.getClass().getMethod("setDropTarget", DropTarget.class);
            method.invoke(source, dropTarget);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setDropTarget(DropTarget) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setDropTarget(DropTarget) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Invoke {@code setMixingCutoutShape} reflectively.
     *
     * @param source the source component
     * @param shape  the shape
     */
    private static void invokeSetMixingCutoutShapeReflective(Object source, Shape shape) {
        try {
            Method method = source.getClass().getMethod("setMixingCutoutShape", Shape.class);
            method.invoke(source, shape);
        } catch (InvocationTargetException e) {
            rethrowCause(e);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No setMixingCutoutShape(Shape) on " + source.getClass().getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access setMixingCutoutShape(Shape) on "
                    + source.getClass().getName(), e);
        }
    }

    /**
     * Rethrow cause of {@link InvocationTargetException}.
     *
     * @param e the exception
     */
    private static void rethrowCause(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            throw new RuntimeException(e);
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw new RuntimeException(cause);
    }
}
