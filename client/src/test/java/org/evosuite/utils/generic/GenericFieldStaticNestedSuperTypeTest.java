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

import org.apache.commons.lang3.reflect.TypeUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * gentyref treats a static nested class of a generic outer class as raw and erases its
 * supertypes. {@link GenericUtils#getExactSuperType} works around that for methods; an inherited
 * field typed by the superclass's type variable, and the superclass itself, must resolve the
 * same way.
 */
public class GenericFieldStaticNestedSuperTypeTest {

    public static class Base<P> {
        public P value;

        public P get() {
            return value;
        }
    }

    public static class Outer<T> {
        public static class Child extends Base<String> {
        }
    }

    @Test
    public void inheritedMethodResolvesTheSuperclassTypeArgument() throws Exception {
        GenericMethod get = new GenericMethod(Base.class.getMethod("get"), Outer.Child.class);
        assertEquals(String.class, get.getGeneratedType());
    }

    @Test
    public void inheritedFieldResolvesTheSuperclassTypeArgument() throws Exception {
        GenericField value = new GenericField(Base.class.getField("value"), Outer.Child.class);
        assertEquals(String.class, value.getFieldType(),
                "the inherited field must be typed like the inherited getter, not erased");
    }

    @Test
    public void superClassKeepsTheTypeArgument() {
        GenericClass<?> superClass = GenericClassFactory.get(Outer.Child.class).getSuperClass();
        assertEquals(TypeUtils.parameterize(Base.class, String.class), superClass.getType());
    }
}
