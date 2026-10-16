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
package org.evosuite.testcase;

import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;

/**
 * {@link TestPresentationMetadata} keys variable names, trailing comments and section
 * breaks by statement position. Removing, inserting or truncating statements in a
 * {@link DefaultTestCase} shifts every later position, so the metadata has to be re-keyed
 * in lockstep or it silently attaches to the wrong statement. These tests cover the
 * re-keying arithmetic directly on {@link TestPresentationMetadata}, the three
 * {@link DefaultTestCase} mutators that now drive it ({@code remove}, {@code addStatement}
 * with a position, {@code chop}), and the user-visible symptom through
 * {@link TestCodeVisitor}.
 */
public class TestPresentationMetadataPositionShiftTest {

    // --- TestPresentationMetadata#removePosition -----------------------------------

    @Test
    public void removePositionShiftsEntriesAcrossAllCollections() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.putVariableName(0, "a");
        metadata.putVariableName(1, "b");
        metadata.putVariableName(2, "c");
        metadata.putVariableName(3, "d");

        metadata.addCommentAfter(0, "comment0");
        metadata.addCommentAfter(2, "comment2");

        metadata.addSectionBreakAfter(1);
        metadata.addSectionBreakAfter(3);

        metadata.removePosition(1);

        // Entries before the removed position are untouched.
        Assertions.assertEquals("a", metadata.getVariableName(0));
        Assertions.assertEquals(Collections.singletonList("comment0"), metadata.getCommentsAfter(0));

        // Entries after the removed position shift down by one.
        Assertions.assertEquals("c", metadata.getVariableName(1));
        Assertions.assertEquals("d", metadata.getVariableName(2));
        Assertions.assertEquals(Collections.singletonList("comment2"), metadata.getCommentsAfter(1));
        Assertions.assertTrue(metadata.hasSectionBreakAfter(2));

        // The entry at the removed position itself is dropped, not just overwritten.
        Assertions.assertNull(metadata.getVariableName(3));
        Assertions.assertFalse(metadata.hasSectionBreakAfter(1));
    }

    @Test
    public void removePositionZeroDropsFirstEntryAndShiftsTheRest() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.putVariableName(0, "a");
        metadata.putVariableName(1, "b");
        metadata.putVariableName(2, "c");

        metadata.removePosition(0);

        Assertions.assertEquals("b", metadata.getVariableName(0));
        Assertions.assertEquals("c", metadata.getVariableName(1));
        Assertions.assertNull(metadata.getVariableName(2));
    }

    @Test
    public void removeLastPositionDropsItsEntryWithoutShiftingEarlierOnes() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.putVariableName(0, "a");
        metadata.putVariableName(1, "b");
        metadata.putVariableName(2, "c");

        metadata.removePosition(2);

        Assertions.assertEquals("a", metadata.getVariableName(0));
        Assertions.assertEquals("b", metadata.getVariableName(1));
        Assertions.assertNull(metadata.getVariableName(2));
    }

    // --- TestPresentationMetadata#insertPosition ------------------------------------

    @Test
    public void insertPositionShiftsEntriesAtOrAfterTheInsertionPointUp() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.putVariableName(0, "a");
        metadata.putVariableName(1, "b");
        metadata.addCommentAfter(1, "c1");
        metadata.addSectionBreakAfter(0);
        metadata.addSectionBreakAfter(1);

        metadata.insertPosition(1);

        // Entry before the insertion point is untouched.
        Assertions.assertEquals("a", metadata.getVariableName(0));
        Assertions.assertTrue(metadata.hasSectionBreakAfter(0));

        // Entries at or after the insertion point shift up by one, making room for the
        // new statement without being overwritten by it.
        Assertions.assertNull(metadata.getVariableName(1));
        Assertions.assertFalse(metadata.hasSectionBreakAfter(1));
        Assertions.assertEquals("b", metadata.getVariableName(2));
        Assertions.assertEquals(Collections.singletonList("c1"), metadata.getCommentsAfter(2));
        Assertions.assertTrue(metadata.hasSectionBreakAfter(2));
    }

    // --- TestPresentationMetadata#truncateFrom ----------------------------------------

    @Test
    public void truncateFromDropsEntriesAtOrBeyondTheNewLength() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.putVariableName(0, "a");
        metadata.putVariableName(1, "b");
        metadata.putVariableName(2, "c");
        metadata.addCommentAfter(2, "c2");
        metadata.addSectionBreakAfter(1);

        metadata.truncateFrom(2);

        Assertions.assertEquals("a", metadata.getVariableName(0));
        Assertions.assertEquals("b", metadata.getVariableName(1));
        Assertions.assertNull(metadata.getVariableName(2));
        Assertions.assertTrue(metadata.getCommentsAfter(2).isEmpty());
        // Surviving positions below the truncation length do not move.
        Assertions.assertTrue(metadata.hasSectionBreakAfter(1));
    }

    // --- DefaultTestCase#remove(int) --------------------------------------------------

    @Test
    public void defaultTestCaseRemoveShiftsPresentationMetadata() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 2));
        tc.addStatement(new IntPrimitiveStatement(tc, 3));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(0, "first");
        metadata.putVariableName(1, "second");
        metadata.putVariableName(2, "third");

        tc.remove(1);

        Assertions.assertEquals("first", metadata.getVariableName(0));
        Assertions.assertEquals("third", metadata.getVariableName(1));
        Assertions.assertNull(metadata.getVariableName(2));
    }

    @Test
    public void defaultTestCaseRemovePositionZeroShiftsDown() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 2));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(0, "first");
        metadata.putVariableName(1, "second");

        tc.remove(0);

        Assertions.assertEquals("second", metadata.getVariableName(0));
        Assertions.assertNull(metadata.getVariableName(1));
    }

    @Test
    public void defaultTestCaseRemoveLastStatementDropsOnlyItsEntry() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 2));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(0, "first");
        metadata.putVariableName(1, "second");

        tc.remove(1);

        Assertions.assertEquals("first", metadata.getVariableName(0));
        Assertions.assertNull(metadata.getVariableName(1));
    }

    @Test
    public void defaultTestCaseRemoveWithoutMetadataDoesNotThrow() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 2));

        Assertions.assertNull(tc.getPresentationMetadata());
        Assertions.assertDoesNotThrow(() -> tc.remove(0));
        Assertions.assertEquals(1, tc.size());
    }

    // --- DefaultTestCase#addStatement(Statement, int) ---------------------------------

    @Test
    public void defaultTestCaseInsertShiftsPresentationMetadataUp() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 3));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(0, "first");
        metadata.putVariableName(1, "second");

        tc.addStatement(new IntPrimitiveStatement(tc, 2), 1);

        Assertions.assertEquals("first", metadata.getVariableName(0));
        Assertions.assertNull(metadata.getVariableName(1));
        Assertions.assertEquals("second", metadata.getVariableName(2));
    }

    // --- DefaultTestCase#chop(int) -----------------------------------------------------

    @Test
    public void defaultTestCaseChopTruncatesPresentationMetadata() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1));
        tc.addStatement(new IntPrimitiveStatement(tc, 2));
        tc.addStatement(new IntPrimitiveStatement(tc, 3));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(0, "first");
        metadata.putVariableName(1, "second");
        metadata.putVariableName(2, "third");

        tc.chop(1);

        Assertions.assertEquals(1, tc.size());
        Assertions.assertEquals("first", metadata.getVariableName(0));
        Assertions.assertNull(metadata.getVariableName(1));
        Assertions.assertNull(metadata.getVariableName(2));
    }

    // --- End-to-end: the actual user-visible symptom -----------------------------------

    @Test
    public void testCodeVisitorAttachesPostProcessedNameToCorrectStatementAfterRemoval() {
        DefaultTestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 1)); // removed below
        tc.addStatement(new IntPrimitiveStatement(tc, 2));
        tc.addStatement(new IntPrimitiveStatement(tc, 3));

        TestPresentationMetadata metadata = TestPresentationMetadata.getOrCreate(tc);
        metadata.putVariableName(1, "second");
        metadata.putVariableName(2, "third");

        tc.remove(0);

        String code = tc.toCode();

        // "second" must land on the statement holding 2, "third" on the one holding 3 -
        // not shifted onto the wrong value because the metadata positions were not
        // re-keyed when the leading statement was removed.
        Assertions.assertTrue(code.contains("second = 2"), code);
        Assertions.assertTrue(code.contains("third = 3"), code);
    }
}
