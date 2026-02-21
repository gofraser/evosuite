/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 * <p>
 * This file is part of EvoSuite.
 * <p>
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 * <p>
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.seeding;

import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static java.util.Comparator.comparingInt;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.*;


public class TestPrioritization {

    @Test
    public void testBasicOrdering() {
        Comparator<Integer> integerComparator = Comparator.comparingInt(x -> x % 10);
        Prioritization<Integer> pc = new Prioritization<>(integerComparator);
        pc.add(1, 1);
        pc.add(11, 3);
        pc.add(21, 2);
        pc.add(10, 10);
        List<Integer> integers = pc.toSortedList();
        assertThat(integers, equalTo(Arrays.asList(10, 1, 21, 11)));
    }

    @Test
    public void testWithGenericClasses() {
        Prioritization<GenericClass<?>> prioritization =
                new Prioritization<>(comparingInt(GenericClass::getNumParameters));
        prioritization.add(new GenericClassImpl(NoParams1.class), 1);
        prioritization.add(new GenericClassImpl(NoParams2.class), 2);
        prioritization.add(new GenericClassImpl(OneParam1.class), 1);
        prioritization.add(new GenericClassImpl(OneParam2.class), 2);
        prioritization.add(new GenericClassImpl(TwoParams1.class), 1);
        prioritization.add(new GenericClassImpl(TwoParams2.class), 2);

        List<GenericClass<?>> result = prioritization.toSortedList();
        List<Class<?>> expected = Arrays.asList(NoParams1.class, NoParams2.class, OneParam1.class, OneParam2.class, TwoParams1.class, TwoParams2.class);
        assertThat("Resulting size of Prioritizing collection is not correct", result.size() == expected.size());
        for (int i = 0; i < result.size(); i++) {
            GenericClass<?> genericClass = result.get(i);
            GenericClass<?> expectedGC = new GenericClassImpl(expected.get(i));
            assertThat("", genericClass.equals(expectedGC));
        }
    }

    @Test
    public void testClassesWithSamePriorities() {
        Prioritization<GenericClass<?>> prioritization =
                new Prioritization<>(comparingInt(GenericClass::getNumParameters));
        prioritization.add(new GenericClassImpl(Object.class), 0);
        prioritization.add(new GenericClassImpl(String.class), 1);
        prioritization.add(new GenericClassImpl(Integer.class), 0);

        List<GenericClass<?>> result = prioritization.toSortedList();
        Assertions.assertEquals(3, result.size());
    }

    // --- New tests below ---

    @Test
    public void testToSortedListWithFilter() {
        Prioritization<Integer> p = new Prioritization<>(Comparator.<Integer>naturalOrder());
        p.add(1, 10);
        p.add(2, 20);
        p.add(3, 30);
        p.add(4, 40);

        List<Integer> evenOnly = p.toSortedList(n -> n % 2 == 0);
        assertEquals(Arrays.asList(2, 4), evenOnly);
    }

    @Test
    public void testToSortedListWithFilterReturnsEmpty() {
        Prioritization<Integer> p = new Prioritization<>(Comparator.<Integer>naturalOrder());
        p.add(1, 10);
        p.add(3, 30);

        List<Integer> evenOnly = p.toSortedList(n -> n % 2 == 0);
        assertTrue(evenOnly.isEmpty());
    }

    @Test
    public void testSortByPriorityWhenBaseComparatorTies() {
        // All strings have same length -> base comparator ties -> sorted by priority
        Prioritization<String> p = new Prioritization<>(Comparator.comparingInt(String::length));
        p.add("aaa", 3);
        p.add("bbb", 1);
        p.add("ccc", 2);

        List<String> sorted = p.toSortedList();
        assertEquals(Arrays.asList("bbb", "ccc", "aaa"), sorted);
    }

    @Test
    public void testAnyMatchTrue() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        p.add("hello", 1);
        p.add("world", 2);

        assertTrue(p.anyMatch(s -> s.equals("hello")));
    }

    @Test
    public void testAnyMatchFalse() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        p.add("hello", 1);
        p.add("world", 2);

        assertFalse(p.anyMatch(s -> s.equals("missing")));
    }

    @Test
    public void testAnyMatchOnEmpty() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());

        assertFalse(p.anyMatch(s -> true));
    }

    @Test
    public void testClear() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        p.add("a", 1);
        p.add("b", 2);

        assertFalse(p.getElements().isEmpty());
        p.clear();
        assertTrue(p.getElements().isEmpty());
        assertTrue(p.toSortedList().isEmpty());
    }

    @Test
    public void testGetElementsReturnsUnmodifiableView() {
        assertThrows(UnsupportedOperationException.class, () -> {
            Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
            p.add("a", 1);

            Set<String> elements = p.getElements();
            elements.add("b");
        });
    }

    @Test
    public void testAddOverwritesPriority() {
        Prioritization<String> p = new Prioritization<>(Comparator.comparingInt(String::length));
        p.add("aa", 5);
        p.add("bb", 1);

        // Same length -> sorted by priority: bb(1) before aa(5)
        assertEquals(Arrays.asList("bb", "aa"), p.toSortedList());

        // Overwrite "aa" priority to be lower than "bb"
        p.add("aa", 0);
        assertEquals(Arrays.asList("aa", "bb"), p.toSortedList());
    }

    @Test
    public void testAddAll() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        Map<String, Integer> elements = new LinkedHashMap<>();
        elements.put("c", 3);
        elements.put("a", 1);
        elements.put("b", 2);

        p.addAll(elements);

        assertEquals(Arrays.asList("a", "b", "c"), p.toSortedList());
        assertEquals(3, p.getElements().size());
    }

    @Test
    public void testReversedComparator() {
        Prioritization<Integer> p = new Prioritization<>(Comparator.<Integer>naturalOrder(), true);
        p.add(1, 10);
        p.add(2, 20);
        p.add(3, 30);

        List<Integer> sorted = p.toSortedList();
        assertEquals(Arrays.asList(3, 2, 1), sorted);
    }

    @Test
    public void testSingleElement() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        p.add("only", 42);

        assertEquals(Collections.singletonList("only"), p.toSortedList());
        assertTrue(p.anyMatch(s -> s.equals("only")));
    }

    @Test
    public void testEmptyToSortedList() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        assertTrue(p.toSortedList().isEmpty());
    }

    @Test
    public void testEmptyGetElements() {
        Prioritization<String> p = new Prioritization<>(Comparator.<String>naturalOrder());
        assertTrue(p.getElements().isEmpty());
    }

    private static class NoParams2 {
    }

    private static class NoParams1 {
    }

    private static class OneParam1<T> {
    }

    private static class OneParam2<T> {
    }

    private static class TwoParams1<A, B> {
    }

    private static class TwoParams2<A, B> {
    }
}
