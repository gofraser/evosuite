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
package org.evosuite.symbolic.vm.wrappers;

import org.objectweb.asm.Type;

import static org.objectweb.asm.Type.*;

public interface Types {

    // wrapper types
    String JAVA_LANG_LONG = Long.class.getName()
            .replace('.', '/');
    String JAVA_LANG_FLOAT = Float.class.getName().replace(".",
            "/");
    String JAVA_LANG_DOUBLE = Double.class.getName().replace(".",
            "/");
    String JAVA_LANG_SHORT = Short.class.getName().replace(".",
            "/");
    String JAVA_LANG_BYTE = Byte.class.getName()
            .replace('.', '/');
    String JAVA_LANG_CHARACTER = Character.class.getName()
            .replace('.', '/');
    String JAVA_LANG_BOOLEAN = Boolean.class.getName().replace(
            ".", "/");
    String JAVA_LANG_INTEGER = Integer.class.getName().replace(
            ".", "/");
    String JAVA_LANG_STRING = String.class.getName().replace(".",
            "/");

    Type STRING_TYPE = Type.getType(String.class);
    String STR_TO_INT_DESCRIPTOR = Type.getMethodDescriptor(
            INT_TYPE, STRING_TYPE);

    String INIT = "<init>";

    String C_TO_I = Type.getMethodDescriptor(INT_TYPE, CHAR_TYPE);

    String C_TO_Z = Type.getMethodDescriptor(BOOLEAN_TYPE,
            CHAR_TYPE);
}
