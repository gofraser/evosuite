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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.setup;

import com.examples.with.different.packagename.DiamondInterfaceA;
import com.examples.with.different.packagename.DiamondInterfaceB;
import com.examples.with.different.packagename.DiamondInterfaceImpl;
import com.examples.with.different.packagename.FieldHidingChild;
import com.examples.with.different.packagename.FieldHidingParent;
import com.examples.with.different.packagename.MultiFieldOrderingExample;
import org.evosuite.runtime.Reflection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TestClusterUtils#getFields(Class)} merges a class's own declared fields with those of
 * its superclasses and interfaces. A subclass field that hides an inherited one of a different,
 * incompatible type (JLS 8.3) - like ANTLR3's {@code arrayElement_return.tree} hiding
 * {@code ParserRuleReturnScope.tree} - must not leave both fields usable: the inherited one is
 * never reachable as {@code Object} through a reference typed as the subclass, so offering it
 * produced generated test code that does not compile.
 */
public class TestClusterUtilsFieldHidingTest {

    @Test
    public void hidingFieldOfIncompatibleTypeShadowsInheritedField() {
        Set<Field> fields = TestClusterUtils.getFields(FieldHidingChild.class);

        long matchingName = fields.stream().filter(f -> f.getName().equals("f")).count();
        assertEquals(1, matchingName, "only the hiding field should be offered, not both");

        Field f = fields.stream().filter(field -> field.getName().equals("f")).findFirst()
                .orElseThrow(() -> new AssertionError("field f not found"));
        assertEquals(FieldHidingChild.class, f.getDeclaringClass(),
                "the surviving field must be the child's declaration, not the parent's");
        assertEquals(String.class, f.getType(),
                "the surviving field must have the hiding (child) type, not the hidden (parent) type");
    }

    @Test
    public void parentFieldAloneStillOffersItsOwnObjectTypedField() {
        // Sanity check on the fixture: the parent class on its own still exposes its field
        // normally: this is purely about what happens when a subclass hides it.
        Set<Field> parentFields = TestClusterUtils.getFields(FieldHidingParent.class);
        Field f = parentFields.stream().filter(field -> field.getName().equals("f")).findFirst()
                .orElseThrow(() -> new AssertionError("field f not found"));
        assertEquals(Object.class, f.getType());
    }

    /** Stands in for one unrelated class with a field named {@code value}. */
    private static class UnrelatedHolderA {
        public int value;
    }

    /** Stands in for a second, unrelated class with a same-named field of a different type. */
    private static class UnrelatedHolderB {
        public String value;
    }

    @Test
    public void unrelatedSameNamedFieldsInDistinctClassesAreBothStillAvailable() {
        // Two classes that share neither inheritance nor an interface must not collapse into
        // each other just because a field in each happens to be named the same: the merge map
        // in getFields() is local to a single call, keyed within one class's own hierarchy.
        Field fieldA = TestClusterUtils.getFields(UnrelatedHolderA.class).stream()
                .filter(f -> f.getName().equals("value")).findFirst()
                .orElseThrow(() -> new AssertionError("value not found in UnrelatedHolderA"));
        Field fieldB = TestClusterUtils.getFields(UnrelatedHolderB.class).stream()
                .filter(f -> f.getName().equals("value")).findFirst()
                .orElseThrow(() -> new AssertionError("value not found in UnrelatedHolderB"));

        assertEquals(int.class, fieldA.getType());
        assertEquals(String.class, fieldB.getType());
        assertTrue(fieldA.getDeclaringClass() == UnrelatedHolderA.class);
        assertTrue(fieldB.getDeclaringClass() == UnrelatedHolderB.class);
    }

    /**
     * {@link DiamondInterfaceImpl} implements two unrelated interfaces that each declare a
     * same-named {@code VALUE} constant of a different, incompatible type, and declares no
     * {@code VALUE} field of its own. Unlike the superclass/subclass case above, this is not
     * JLS 8.3 hiding - {@code DiamondInterfaceA} and {@code DiamondInterfaceB} are siblings,
     * so neither's field hides the other's - and a real javac confirms both survive as members
     * of {@code DiamondInterfaceImpl}, but any *unqualified* reference to either
     * ({@code DiamondInterfaceImpl.VALUE}, or through an instance) is ambiguous and is a
     * compile-time error; only the qualified forms {@code DiamondInterfaceA.VALUE} /
     * {@code DiamondInterfaceB.VALUE} compile.
     *
     * <p>{@code getFields()}'s by-name keying collapses the pair to whichever one
     * {@link Reflection#getInterfaces(Class)} - itself sorted deterministically by
     * {@code Class::toString()}, not JVM-dependent declaration order - visits last, since a
     * later {@code put()} for the same name overwrites the earlier one. That is a defensible,
     * if conservative, choice rather than a bug to fix here: for a *static* field (which every
     * interface constant is), {@code TestCodeVisitor} never renders access through the
     * implementing class's name in the first place - a static field's {@code FieldReference}
     * always has a {@code null} source, so rendering uses the {@code Field}'s own
     * {@code getDeclaringClass()} (the interface that actually declared it), never
     * {@code DiamondInterfaceImpl}. So offering only one of the two constants never risks
     * generating the ambiguous, uncompilable form; it simply leaves one safely-renderable
     * constant unoffered as a candidate value. That is a minor loss of candidate values, not a
     * correctness defect, so it does not warrant a production change here.
     */
    @Test
    public void diamondInterfaceFieldCollisionOffersExactlyOneFieldDeterministically() {
        Set<Field> fields = TestClusterUtils.getFields(DiamondInterfaceImpl.class);

        long matchingName = fields.stream().filter(f -> f.getName().equals("VALUE")).count();
        assertEquals(1, matchingName,
                "an unqualified reference to either sibling-interface constant is ambiguous, "
                        + "so only one should be offered");

        Field value = fields.stream().filter(f -> f.getName().equals("VALUE")).findFirst()
                .orElseThrow(() -> new AssertionError("VALUE not found"));
        assertTrue(value.getDeclaringClass() == DiamondInterfaceA.class
                        || value.getDeclaringClass() == DiamondInterfaceB.class,
                "the surviving field must still come from one of the two interfaces");

        // The winner is whichever interface Reflection.getInterfaces() visits last, since the
        // merge loop in getFields() does `helper.put(f.getName(), f)` for each interface in
        // turn and a later put() for the same key overwrites the earlier one.
        Class<?>[] interfaces = Reflection.getInterfaces(DiamondInterfaceImpl.class);
        Class<?> expectedWinner = interfaces[interfaces.length - 1];
        assertEquals(expectedWinner, value.getDeclaringClass(),
                "the surviving field must match Reflection.getInterfaces()'s deterministic "
                        + "(sorted-by-toString) ordering, not be a coin flip");

        // Reflection.getInterfaces() sorts explicitly (Reflection#sortArrayInPlace, by
        // Class::toString()), so this is stable across repeated calls, not just this one.
        Field valueAgain = TestClusterUtils.getFields(DiamondInterfaceImpl.class).stream()
                .filter(f -> f.getName().equals("VALUE")).findFirst()
                .orElseThrow(() -> new AssertionError("VALUE not found on repeat call"));
        assertEquals(value.getDeclaringClass(), valueAgain.getDeclaringClass(),
                "which interface's field wins must be stable across repeated calls");
    }

    @Test
    public void fieldIterationOrderIsDeterministicAndStableAcrossRepeatedCalls() {
        // getFields()'s merge map is a TreeMap<String, Field> (keyed by field name), so its
        // Set<Field> result is expected to always iterate in name-sorted order - not an
        // accident of declaration order, hash order, or JVM-dependent reflection order. Pin
        // that so a future change to the map type (e.g. to a HashMap) cannot silently
        // reintroduce nondeterminism into the order in which fields are offered to the search.
        List<String> firstCall = TestClusterUtils.getFields(MultiFieldOrderingExample.class)
                .stream().map(Field::getName).collect(Collectors.toList());
        List<String> secondCall = TestClusterUtils.getFields(MultiFieldOrderingExample.class)
                .stream().map(Field::getName).collect(Collectors.toList());

        assertEquals(firstCall, secondCall,
                "iteration order must be stable across repeated calls on the same class");

        List<String> sortedByName = new ArrayList<>(firstCall);
        Collections.sort(sortedByName);
        assertEquals(sortedByName, firstCall,
                "fields are keyed by name in a TreeMap, so iteration order is name-sorted");
    }
}
