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
package org.evosuite.testcase;

import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.WildcardTypeImpl;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.LinkedList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestFactoryClassLiteralNormalizationTest {

    @Test
    public void normalizeClassLiteralParameterizedArgumentByErasure() throws Exception {
        Type listOfString = new ParameterizedTypeImpl(LinkedList.class, new Type[]{String.class}, null);
        Type classOfListOfString = new ParameterizedTypeImpl(Class.class, new Type[]{listOfString}, null);

        Type normalized = normalizeClassLiteralTypeArgumentByErasure(classOfListOfString);
        ParameterizedType normalizedType = (ParameterizedType) normalized;

        assertEquals(Class.class, normalizedType.getRawType());
        assertEquals(LinkedList.class, GenericClassFactory.get(normalizedType.getActualTypeArguments()[0]).getRawClass());
    }

    @Test
    public void doNotNormalizeWildcardBoundedClassType() throws Exception {
        WildcardType wildcardExtendsNumber = new WildcardTypeImpl(new Type[]{Number.class}, new Type[]{});
        Type classOfWildcard = new ParameterizedTypeImpl(Class.class, new Type[]{wildcardExtendsNumber}, null);

        Type normalized = normalizeClassLiteralTypeArgumentByErasure(classOfWildcard);
        ParameterizedType normalizedType = (ParameterizedType) normalized;

        assertEquals(Class.class, normalizedType.getRawType());
        assertTrue(normalizedType.getActualTypeArguments()[0] instanceof WildcardType);
    }

    private static Type normalizeClassLiteralTypeArgumentByErasure(Type type) throws Exception {
        Method method = TestFactory.class
                .getDeclaredMethod("normalizeClassLiteralTypeArgumentByErasure", Type.class);
        method.setAccessible(true);
        return (Type) method.invoke(null, type);
    }
}
