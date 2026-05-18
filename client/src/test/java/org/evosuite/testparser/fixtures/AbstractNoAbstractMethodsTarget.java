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
package org.evosuite.testparser.fixtures;

/**
 * Abstract class with no unimplemented abstract methods. Mirrors real-world
 * SUTs (e.g. fi.vtt.noen.mfw.bundle.common.BasePlugin) where {@code abstract}
 * is used purely as an "extend me" marker and every method is concrete. Such
 * classes are instantiable via an empty anonymous subclass body, and the
 * parser must preserve {@code new AbstractNoAbstractMethodsTarget() {}}
 * instead of substituting a typed null receiver.
 */
public abstract class AbstractNoAbstractMethodsTarget {

    public String describe() {
        return "AbstractNoAbstractMethodsTarget";
    }

    public int echo(int value) {
        return value;
    }
}
