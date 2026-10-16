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
package org.evosuite.runtime;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.awt.Toolkit;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.InvalidDnDOperationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * {@code sun.awt.ComponentFactory#createDragSourceContextPeer} only gained a default
 * implementation in JDK 9 -- confirmed with {@code javap} against both Corretto 8 and Corretto
 * 17: Java 8's {@code ComponentFactory} has no default methods at all, every one of them (this
 * one included) is abstract. {@link PeerCreationGuardToolkit} used to rely on inheriting that
 * default, which compiles fine against this module's JDK 17 build but throws
 * {@link AbstractMethodError} the first time a SUT reaches it on a Java 8 JVM (the class itself
 * still loads -- only that one call fails). It is now overridden explicitly, matching JDK 9+'s
 * own default body exactly, so the behaviour no longer depends on which JDK the class happens to
 * run on. This test runs on a modern JDK and pins that the explicit override reproduces the
 * JDK 9+ default precisely; see {@code runtime/pom.xml}'s {@code java8-api-check} execution and
 * the Corretto 8 smoke run recorded for this fix for coverage of the Java 8 case itself, which
 * (the override now being self-contained) behaves identically and so cannot be told apart from
 * the JDK 17 case by a unit test.
 *
 * <p>Reached only through reflection, by class name: a compile-time reference to
 * {@link PeerCreationGuardToolkit} (a {@code sun.awt.ComponentFactory} implementor) makes
 * surefire's pre-fork JUnit Vintage class scan -- which runs in Maven's own JVM, without the
 * {@code --add-exports java.desktop/sun.awt=ALL-UNNAMED} this module's test JVM is launched
 * with -- try to link the class and fail with {@link IllegalAccessError} before any test runs.
 */
public class PeerCreationGuardToolkitTest {

    @Test
    public void createDragSourceContextPeerThrowsInvalidDnDOperationExceptionNotAbstractMethodError()
            throws ReflectiveOperationException {
        Toolkit real = Toolkit.getDefaultToolkit();

        Class<?> guardClass = Class.forName("org.evosuite.runtime.PeerCreationGuardToolkit");
        Constructor<?> constructor = guardClass.getDeclaredConstructor(Toolkit.class, Toolkit.class);
        constructor.setAccessible(true);
        Object guard = constructor.newInstance(real, real);

        Method createDragSourceContextPeer =
                guardClass.getMethod("createDragSourceContextPeer", DragGestureEvent.class);
        createDragSourceContextPeer.setAccessible(true);

        InvocationTargetException ite = Assertions.assertThrows(InvocationTargetException.class,
                () -> createDragSourceContextPeer.invoke(guard, (Object) null));
        Assertions.assertInstanceOf(InvalidDnDOperationException.class, ite.getCause());
        Assertions.assertEquals("Headless environment", ite.getCause().getMessage());
    }
}
