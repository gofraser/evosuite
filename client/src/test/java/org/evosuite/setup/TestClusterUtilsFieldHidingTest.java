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

import com.examples.with.different.packagename.FieldHidingChild;
import com.examples.with.different.packagename.FieldHidingParent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

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
}
