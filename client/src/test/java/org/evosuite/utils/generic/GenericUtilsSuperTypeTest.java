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
package org.evosuite.utils.generic;

import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GenericUtils.isSuperType} is the only way to ask gentyref whether one type is a
 * supertype of another: gentyref throws on a generic array without a component type.
 */
public class GenericUtilsSuperTypeTest {

    private static final GenericArrayType MALFORMED_ARRAY = () -> null;

    @Test
    public void gentyrefThrowsOnGenericArrayWithoutComponentType() {
        assertThrows(RuntimeException.class,
                () -> GenericTypeReflector.isSuperType(Object[].class, MALFORMED_ARRAY));
    }

    @Test
    public void malformedGenericArrayIsNoSupertypeRelation() {
        assertFalse(GenericUtils.isSuperType(Object[].class, MALFORMED_ARRAY));
        assertFalse(GenericUtils.isSuperType(MALFORMED_ARRAY, Object[].class));
    }

    @Test
    public void wellFormedTypesAreAnsweredByGentyref() {
        Type listOfString = TypeUtils.parameterize(List.class, String.class);
        Type arrayListOfString = TypeUtils.parameterize(ArrayList.class, String.class);
        assertTrue(GenericUtils.isSuperType(listOfString, arrayListOfString));
        assertFalse(GenericUtils.isSuperType(arrayListOfString, listOfString));
    }
}
