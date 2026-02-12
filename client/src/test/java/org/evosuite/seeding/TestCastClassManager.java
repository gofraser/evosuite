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

import org.evosuite.Properties;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.*;


public class TestCastClassManager {

    @After
    public void tearDown() {
        CastClassManager.getInstance().clear();
    }

    // --- selectClass tests ---

    @Test
    public void testSelectClassSingleElement() {
        GenericClass<?> intClass = GenericClassFactory.get(int.class);
        List<GenericClass<?>> ts = Collections.singletonList(intClass);
        GenericClass<?> genericClass = CastClassManager.selectClass(ts);
        assertThat(genericClass, equalTo(intClass));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSelectClassEmptyListThrowsException() {
        CastClassManager.selectClass(Collections.emptyList());
    }

    @Test
    public void testSelectClassAlwaysReturnsValidIndex() {
        List<GenericClass<?>> candidates = new ArrayList<>();
        candidates.add(GenericClassFactory.get(int.class));
        candidates.add(GenericClassFactory.get(String.class));
        candidates.add(GenericClassFactory.get(Double.class));

        // Run many iterations to exercise different random values
        for (int i = 0; i < 1000; i++) {
            GenericClass<?> selected = CastClassManager.selectClass(candidates);
            assertNotNull(selected);
            assertTrue("Selected class should be one of the candidates",
                    candidates.contains(selected));
        }
    }

    @Test
    public void testSelectClassWithTwoElements() {
        List<GenericClass<?>> candidates = new ArrayList<>();
        candidates.add(GenericClassFactory.get(int.class));
        candidates.add(GenericClassFactory.get(String.class));

        // Should always return a valid element
        for (int i = 0; i < 100; i++) {
            GenericClass<?> selected = CastClassManager.selectClass(candidates);
            assertTrue(candidates.contains(selected));
        }
    }

    @Test
    public void testSelectClassIndexClampedForLargeList() {
        // With a larger list, exercise the rank-bias formula more thoroughly
        List<GenericClass<?>> candidates = new ArrayList<>();
        candidates.add(GenericClassFactory.get(int.class));
        candidates.add(GenericClassFactory.get(String.class));
        candidates.add(GenericClassFactory.get(Double.class));
        candidates.add(GenericClassFactory.get(Float.class));
        candidates.add(GenericClassFactory.get(Long.class));
        candidates.add(GenericClassFactory.get(Boolean.class));
        candidates.add(GenericClassFactory.get(Byte.class));
        candidates.add(GenericClassFactory.get(Short.class));
        candidates.add(GenericClassFactory.get(Character.class));
        candidates.add(GenericClassFactory.get(Object.class));

        double savedBias = Properties.RANK_BIAS;
        try {
            Properties.RANK_BIAS = 1.7;
            for (int i = 0; i < 1000; i++) {
                GenericClass<?> selected = CastClassManager.selectClass(candidates);
                assertNotNull(selected);
                assertTrue(candidates.contains(selected));
            }
        } finally {
            Properties.RANK_BIAS = savedBias;
        }
    }

    // --- addCastClass tests ---

    @Test
    public void testAddCastClassByType() {
        CastClassManager instance = CastClassManager.getInstance();
        Type t = Integer.class;
        instance.addCastClass(t, 5);
        Set<GenericClass<?>> castClasses = instance.getCastClasses();
        assertThat(castClasses, hasItem(GenericClassFactory.get(Integer.class)));
    }

    @Test
    public void testAddCastClassByGenericClass() {
        CastClassManager instance = CastClassManager.getInstance();
        GenericClass<?> gc = GenericClassFactory.get(Integer.class);
        instance.addCastClass(gc, 5);
        Set<GenericClass<?>> castClasses = instance.getCastClasses();
        assertThat(castClasses, hasItem(GenericClassFactory.get(Integer.class)));
    }

    @Test
    public void testAddCastClassIdempotent() {
        CastClassManager instance = CastClassManager.getInstance();
        int sizeBefore = instance.getCastClasses().size();

        // Adding the same class twice should not duplicate it
        instance.addCastClass(GenericClassFactory.get(Integer.class), 5);
        instance.addCastClass(GenericClassFactory.get(Integer.class), 3);

        Set<GenericClass<?>> castClasses = instance.getCastClasses();
        assertThat(castClasses, hasItem(GenericClassFactory.get(Integer.class)));
        // Integer is already a default, so size should stay the same
        assertEquals(sizeBefore, castClasses.size());
    }

    // --- clear tests ---

    @Test
    public void testClearResetsToDefaults() {
        CastClassManager instance = CastClassManager.getInstance();
        Set<GenericClass<?>> defaultClasses = new HashSet<>(instance.getCastClasses());

        // Add some extra classes
        instance.addCastClass(GenericClassFactory.get(Double.class), 5);
        instance.addCastClass(GenericClassFactory.get(Float.class), 5);

        // Should have more classes now
        assertTrue(instance.getCastClasses().size() > defaultClasses.size());

        // After clear, should return to defaults
        instance.clear();
        assertEquals(defaultClasses, instance.getCastClasses());
    }

    @Test
    public void testDefaultClassesPresent() {
        CastClassManager instance = CastClassManager.getInstance();
        Set<GenericClass<?>> castClasses = instance.getCastClasses();

        assertThat(castClasses, hasItem(GenericClassFactory.get(Object.class)));
        assertThat(castClasses, hasItem(GenericClassFactory.get(String.class)));
        assertThat(castClasses, hasItem(GenericClassFactory.get(Integer.class)));
        assertThat(castClasses, hasItem(GenericClassFactory.get(LinkedList.class)));
        assertThat(castClasses, hasItem(GenericClassFactory.get(ArrayList.class)));
        assertEquals(5, castClasses.size());
    }

    // --- getCastClasses immutability ---

    @Test(expected = UnsupportedOperationException.class)
    public void testGetCastClassesReturnsUnmodifiableView() {
        CastClassManager instance = CastClassManager.getInstance();
        Set<GenericClass<?>> castClasses = instance.getCastClasses();
        castClasses.add(GenericClassFactory.get(Double.class));
    }
}
