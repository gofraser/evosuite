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

import java.lang.reflect.AccessibleObject;

/**
 * The module-graph half of {@link ReflectionAccess}, kept behind an interface so that
 * nothing in this package names a type that only exists from Java 9 on.
 *
 * <p>The runtime module is compiled at source and target 1.8 and is shipped to users, who
 * may well run the generated tests on a Java 8 JVM. {@code java.lang.Module} and
 * {@link java.lang.reflect.InaccessibleObjectException} do not exist there, and a method
 * that mentions either of them fails verification as soon as it is linked. The
 * implementation therefore lives in {@code src/main/java11}, is only loaded on a JVM that
 * has a module system, and is reached exclusively through this interface, whose signatures
 * are Java 8 types throughout ({@code InaccessibleObjectException} is a
 * {@link RuntimeException}, so {@link #denied} can return one without naming it).
 */
interface ModuleAccessPolicy {

    /**
     * @param ao the accessible object a caller in the unnamed module wants to open up
     * @return whether a JVM without EvoSuite's own {@code --add-opens} would allow it
     */
    boolean isAccessAllowed(AccessibleObject ao);

    /**
     * @param ao the accessible object whose access was refused
     * @return the {@code InaccessibleObjectException} a stock JVM would have thrown
     */
    RuntimeException denied(AccessibleObject ao);

    /**
     * Performs the real {@code AccessibleObject.trySetAccessible()}, which like the module
     * API itself only exists from Java 9 on and so cannot be named by the caller.
     *
     * @param ao the accessible object, already checked by {@link #isAccessAllowed}
     * @return whether the accessible flag was set
     */
    boolean trySetAccessible(AccessibleObject ao);
}
