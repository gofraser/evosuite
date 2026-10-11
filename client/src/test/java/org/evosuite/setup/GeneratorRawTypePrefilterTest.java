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
package org.evosuite.setup;

import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator cache asks, for every requested type, which generated types can be
 * instantiated to it. The raw-type pre-check must discard only pairs the full
 * generic check rejects anyway.
 */
public class GeneratorRawTypePrefilterTest {

    @SuppressWarnings("unused")
    private static class Shapes<T> {
        List<String> listOfString;
        ArrayList<String> arrayListOfString;
        ArrayList<Integer> arrayListOfInteger;
        Collection<? extends Number> collectionOfNumber;
        Map<String, T> mapWithVariable;
        HashMap<String, Integer> hashMap;
        List<T> listOfVariable;
        T[] arrayOfVariable;
        List<String>[] arrayOfLists;
        T variable;
    }

    private static GenericClass<?> of(Type type) {
        return GenericClassFactory.get(type);
    }

    private static GenericClass<?> field(String name) throws Exception {
        return of(Shapes.class.getDeclaredField(name).getGenericType());
    }

    @Test
    public void unrelatedClassesAreIncompatible() {
        assertTrue(TestCluster.isRawTypeIncompatible(of(String.class), of(Integer.class)));
        assertTrue(TestCluster.isRawTypeIncompatible(of(Object.class), of(String.class)));
        assertTrue(TestCluster.isRawTypeIncompatible(of(ArrayList.class), of(HashMap.class)));
    }

    @Test
    public void subtypesAreNotDiscarded() {
        assertFalse(TestCluster.isRawTypeIncompatible(of(String.class), of(CharSequence.class)));
        assertFalse(TestCluster.isRawTypeIncompatible(of(String.class), of(String.class)));
        assertFalse(TestCluster.isRawTypeIncompatible(of(Integer.class), of(Number.class)));
    }

    @Test
    public void primitivesArraysAndVariablesAreLeftToTheFullCheck() throws Exception {
        assertFalse(TestCluster.isRawTypeIncompatible(of(int.class), of(Integer.class)));
        assertFalse(TestCluster.isRawTypeIncompatible(of(Integer.class), of(int.class)));
        assertFalse(TestCluster.isRawTypeIncompatible(of(String[].class), of(Object[].class)));
        assertFalse(TestCluster.isRawTypeIncompatible(of(String.class), field("variable")));
        assertFalse(TestCluster.isRawTypeIncompatible(field("variable"), of(String.class)));
        assertFalse(TestCluster.isRawTypeIncompatible(field("arrayOfVariable"), field("arrayOfLists")));
    }

    @Test
    public void parameterizedTypesAreComparedByErasure() throws Exception {
        assertFalse(TestCluster.isRawTypeIncompatible(field("arrayListOfString"), field("listOfString")));
        assertTrue(TestCluster.isRawTypeIncompatible(field("listOfString"), field("arrayListOfString")));
        assertTrue(TestCluster.isRawTypeIncompatible(field("hashMap"), field("listOfString")));
    }

    @Test
    public void neverDiscardsAPairTheFullCheckAccepts() throws Exception {
        List<GenericClass<?>> types = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{String.class, Integer.class, Number.class, Object.class,
                CharSequence.class, Serializable.class, int.class, long.class, Comparable.class,
                ArrayList.class, List.class, Collection.class, HashMap.class, Map.class,
                String[].class, Object[].class, int[].class, Runnable.class, Thread.class}) {
            types.add(of(c));
        }
        for (String name : new String[]{"listOfString", "arrayListOfString", "arrayListOfInteger",
                "collectionOfNumber", "mapWithVariable", "hashMap", "listOfVariable",
                "arrayOfVariable", "arrayOfLists", "variable"}) {
            types.add(field(name));
        }
        int discarded = 0;
        for (GenericClass<?> generated : types) {
            for (GenericClass<?> requested : types) {
                if (TestCluster.isRawTypeIncompatible(generated, requested)) {
                    discarded++;
                    assertFalse(generated.canBeInstantiatedTo(requested),
                            generated + " can be instantiated to " + requested);
                }
            }
        }
        assertTrue(discarded > 50, "the pre-check should discard most pairs, discarded " + discarded);
    }
}
