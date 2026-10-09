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
package org.evosuite.instrumentation.testability;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Created by Andrea Arcuri on 26/03/15.
 */
public class ContainerHelpTest {

    @Test
    public void testContainsTransformation() {
        Set<Integer> firstSet = new HashSet<>();
        firstSet.add(17);
        firstSet.add(626);
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, 17));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, 626));
        Assertions.assertTrue(ContainerHelper.collectionContains(firstSet, 100) < 0);
    }

    @Test
    public void testContainsTransformationList() {
        List<Integer> list = new LinkedList<>();
        list.add(17);
        list.add(626);
        list.add(17);
        Assertions.assertEquals(2, ContainerHelper.collectionContains(list, 17));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(list, 626));
        Assertions.assertTrue(ContainerHelper.collectionContains(list, 100) < 0);
    }

    @Test
    public void testContainsStringTransformation() {
        Set<String> firstSet = new HashSet<>();
        firstSet.add("foo");
        firstSet.add("bar");
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, "foo"));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, "bar"));
        Assertions.assertTrue(ContainerHelper.collectionContains(firstSet, "zoo") < 0);
    }

    @Test
    public void testContainsStringTransformationList() {
        List<String> list = new LinkedList<>();
        list.add("foo");
        list.add("bar");
        list.add("foo");
        Assertions.assertEquals(2, ContainerHelper.collectionContains(list, "foo"));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(list, "bar"));
        Assertions.assertTrue(ContainerHelper.collectionContains(list, "zoo") < 0);
    }

    @Test
    public void testContainsAllTransformation() {
        Set<Integer> firstSet = new HashSet<>();
        firstSet.add(17);
        firstSet.add(626);
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, 17));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(firstSet, 626));
        Assertions.assertTrue(ContainerHelper.collectionContains(firstSet, 100) < 0);
    }

    @Test
    public void testContainsAllTransformationList() {
        List<Integer> list = new LinkedList<>();
        list.add(17);
        list.add(626);
        list.add(17);
        Assertions.assertEquals(2, ContainerHelper.collectionContains(list, 17));
        Assertions.assertEquals(1, ContainerHelper.collectionContains(list, 626));
        Assertions.assertTrue(ContainerHelper.collectionContains(list, 100) < 0);
    }

    @Test
    public void testCollectionEmptyTransformation2() {
        Set<Integer> firstSet = new HashSet<>();
        firstSet.add(17);
        firstSet.add(626);
        Assertions.assertEquals(-2, ContainerHelper.collectionIsEmpty(firstSet));
    }

    @Test
    public void testCollectionEmptyTransformation1() {
        List<String> list = new LinkedList<>();
        list.add("test");
        Assertions.assertEquals(-1, ContainerHelper.collectionIsEmpty(list));
    }

    @Test
    public void testCollectionEmptyTransformation0() {
        Set<Integer> firstSet = new HashSet<>();
        Assertions.assertEquals(Integer.MAX_VALUE - 2, ContainerHelper.collectionIsEmpty(firstSet));
    }

    @Test
    public void testMapContainsKeyTransformation() {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(17, 235235);
        map.put(23, 233);
        Assertions.assertEquals(1, ContainerHelper.mapContainsKey(map, 17));
        Assertions.assertEquals(1, ContainerHelper.mapContainsKey(map, 23));
        Assertions.assertTrue(ContainerHelper.mapContainsKey(map, 24) < 0);
    }

    @Test
    public void testMapContainsValueTransformation() {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(17, 235235);
        map.put(23, 233);
        Assertions.assertEquals(1, ContainerHelper.mapContainsValue(map, 235235));
        Assertions.assertEquals(1, ContainerHelper.mapContainsValue(map, 233));
        Assertions.assertTrue(ContainerHelper.mapContainsValue(map, 24) < 0);
    }

    /** Equal to every other instance, but hashed by identity as the contract allows to be broken. */
    private static final class EqualButHashedApart {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualButHashedApart;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    @Test
    public void testContainsKeepsTheOutcomeOfTheRealCall() {
        Set<Object> set = new HashSet<>();
        set.add(new EqualButHashedApart());
        EqualButHashedApart probe = new EqualButHashedApart();
        Assertions.assertFalse(set.contains(probe));
        Assertions.assertTrue(ContainerHelper.collectionContains(set, probe) < 0);
    }

    @Test
    public void testContainsLetsTheContainerRejectTheElement() {
        Set<Object> sorted = new TreeSet<>();
        Object notComparable = new Object();
        Assertions.assertThrows(ClassCastException.class, () -> sorted.contains(notComparable));
        Assertions.assertThrows(ClassCastException.class,
                () -> ContainerHelper.collectionContains(sorted, notComparable));
    }

    @Test
    public void testContainsLetsTheContainerOverflowTheStack() {
        Set<Object> set = new LinkedHashSet<>();
        set.add(set);
        Assertions.assertThrows(StackOverflowError.class, () -> set.contains(set));
        Assertions.assertThrows(StackOverflowError.class,
                () -> ContainerHelper.collectionContains(set, set));
    }

    @Test
    public void testContainsAllKeepsTheOutcomeOfTheRealCall() {
        Set<Object> sorted = new TreeSet<>();
        List<Object> notComparable = Collections.singletonList(new Object());
        Assertions.assertThrows(ClassCastException.class, () -> sorted.containsAll(notComparable));
        Assertions.assertThrows(ClassCastException.class,
                () -> ContainerHelper.collectionContainsAll(sorted, notComparable));
    }

    @Test
    public void testContainsAllBeyondTheScanLimit() {
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            big.add(i);
        }
        Set<Integer> all = new HashSet<>(big);
        all.remove(19_999);
        Assertions.assertFalse(all.containsAll(big));
        Assertions.assertTrue(ContainerHelper.collectionContainsAll(all, big) < 0);
    }

    @Test
    public void testMapContainsKeyLetsTheMapRejectTheKey() {
        Map<Object, Object> sorted = new TreeMap<>();
        Object notComparable = new Object();
        Assertions.assertThrows(ClassCastException.class, () -> sorted.containsKey(notComparable));
        Assertions.assertThrows(ClassCastException.class,
                () -> ContainerHelper.mapContainsKey(sorted, notComparable));
    }

    @Test
    public void testMapContainsKeyHonoursTheMapsOwnEquivalence() {
        Map<String, Integer> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.put("Foo", 1);
        Assertions.assertTrue(map.containsKey("FOO"));
        Assertions.assertTrue(ContainerHelper.mapContainsKey(map, "FOO") > 0);
    }

    /** Throws EvoSuite's loop limit from equals, as a looping equals of the code under test would. */
    private static final class LimitInEquals {
        private final int hash;

        LimitInEquals(int hash) {
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            throw new org.evosuite.runtime.TooManyResourcesException("Loop has been executed more times than the allowed 10000");
        }
    }

    /**
     * A hash set never calls equals for an element of another hash, but the distance scan does;
     * the loop limit it hits there must reach the test, not be taken for an untraversable element.
     */
    @Test
    public void theLoopLimitHitWhileScanningForTheDistanceIsNotSwallowed() {
        Set<Object> set = new HashSet<>();
        set.add(new LimitInEquals(1));

        Assertions.assertThrows(org.evosuite.runtime.TooManyResourcesException.class,
                () -> ContainerHelper.collectionContains(set, new LimitInEquals(2)));
    }
}
