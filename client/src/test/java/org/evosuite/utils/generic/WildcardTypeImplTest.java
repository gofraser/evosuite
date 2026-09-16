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
import org.evosuite.utils.ParameterizedTypeImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Substituting a wildcard for a type variable inside a wildcard bound yields
 * "? extends ?", which is not a Java type and which gentyref's erase() rejects
 * ("not supported: WildcardTypeImpl") -- seen on commons-collections4 ListUtils,
 * CompositeCollection and CompositeSet, where it aborted the search. The
 * constructor must collapse such nesting.
 */
public class WildcardTypeImplTest {

    private static final Type UNBOUNDED = new WildcardTypeImpl(new Type[]{Object.class}, new Type[0]);

    @Test
    public void extendsOfUnboundedWildcardIsUnbounded() {
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{UNBOUNDED}, new Type[0]);
        assertArrayEquals(new Type[]{Object.class}, w.getUpperBounds());
        assertEquals(0, w.getLowerBounds().length);
        assertEquals("?", w.toString());
    }

    @Test
    public void extendsOfExtendsCollapsesToInnerUpperBound() {
        Type extendsNumber = new WildcardTypeImpl(new Type[]{Number.class}, new Type[0]);
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{extendsNumber}, new Type[0]);
        assertArrayEquals(new Type[]{Number.class}, w.getUpperBounds());
        assertEquals("? extends java.lang.Number", w.toString());
    }

    @Test
    public void extendsOfSuperIsUnbounded() {
        Type superNumber = new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{Number.class});
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{superNumber}, new Type[0]);
        assertArrayEquals(new Type[]{Object.class}, w.getUpperBounds());
        assertEquals(0, w.getLowerBounds().length);
    }

    @Test
    public void superOfSuperCollapsesToInnerLowerBound() {
        Type superNumber = new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{Number.class});
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{superNumber});
        assertArrayEquals(new Type[]{Number.class}, w.getLowerBounds());
    }

    @Test
    public void superOfExtendsDropsTheLowerBound() {
        Type extendsNumber = new WildcardTypeImpl(new Type[]{Number.class}, new Type[0]);
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{extendsNumber});
        assertEquals(0, w.getLowerBounds().length);
        assertEquals("?", w.toString());
    }

    @Test
    public void plainBoundsAreUntouched() {
        TypeVariable<?> e = List.class.getTypeParameters()[0];
        WildcardTypeImpl w = new WildcardTypeImpl(new Type[]{e}, new Type[0]);
        assertArrayEquals(new Type[]{e}, w.getUpperBounds());
    }

    @Test
    public void replacingTypeVariableInsideBoundWithWildcardKeepsBoundsErasable() {
        // List<? extends E> with E := ? -- the ListUtils.union(List<? extends E>, ...) case.
        // gentyref's erase() rejects every wildcard, so the renderer erases the
        // *bounds* of a wildcard; those must therefore never be wildcards themselves.
        TypeVariable<?> e = List.class.getTypeParameters()[0];
        Type listOfExtendsE = new ParameterizedTypeImpl(List.class,
                new Type[]{new WildcardTypeImpl(new Type[]{e}, new Type[0])}, null);
        Type substituted = GenericUtils.replaceTypeVariable(listOfExtendsE, e, UNBOUNDED);
        WildcardType arg = (WildcardType) ((java.lang.reflect.ParameterizedType) substituted)
                .getActualTypeArguments()[0];
        for (Type bound : arg.getUpperBounds()) {
            assertEquals(Object.class, GenericTypeReflector.erase(bound));
        }
        assertEquals(0, arg.getLowerBounds().length);
    }

    @Test
    public void arrayOfWildcardComponentUsesTheBound() {
        // E[] with E := ? (CompositeCollection.toArray(T[])): "?[]" is not a type
        // and gentyref cannot erase it, so the array is built over the bound.
        TypeVariable<?> e = List.class.getTypeParameters()[0];
        Type arrayOfE = GenericArrayTypeImpl.createArrayType(e);
        assertEquals(Object[].class, GenericUtils.replaceTypeVariable(arrayOfE, e, UNBOUNDED));
        Type extendsNumber = new WildcardTypeImpl(new Type[]{Number.class}, new Type[0]);
        assertEquals(Number[].class, GenericUtils.replaceTypeVariable(arrayOfE, e, extendsNumber));
        assertEquals(Object[].class, GenericArrayTypeImpl.createArrayType(
                new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{Number.class})));
    }
}
