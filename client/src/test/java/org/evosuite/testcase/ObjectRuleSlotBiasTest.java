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

import org.evosuite.Properties;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.Randomness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification for first-class object-rule mutation slots: the slot flag lives on the
 * {@link PrimitiveStatement} and survives structural operations (it is propagated through the single
 * {@code copy(TestCase, int)} path), and when {@code object_rule_slot_bias} is on, mutation picks
 * flagged statements far more often than uniform.
 */
public class ObjectRuleSlotBiasTest {

    private double oldChange;
    private double oldDelete;
    private double oldInsert;
    private double oldConcolic;
    private boolean oldSlotBias;

    @BeforeEach
    public void setUp() {
        oldChange = Properties.P_TEST_CHANGE;
        oldDelete = Properties.P_TEST_DELETE;
        oldInsert = Properties.P_TEST_INSERT;
        oldConcolic = Properties.CONCOLIC_MUTATION;
        oldSlotBias = Properties.OBJECT_RULE_SLOT_BIAS;
        Randomness.setSeed(42L);
    }

    @AfterEach
    public void tearDown() {
        Properties.P_TEST_CHANGE = oldChange;
        Properties.P_TEST_DELETE = oldDelete;
        Properties.P_TEST_INSERT = oldInsert;
        Properties.CONCOLIC_MUTATION = oldConcolic;
        Properties.OBJECT_RULE_SLOT_BIAS = oldSlotBias;
    }

    private static IntPrimitiveStatement intStmt(TestCase test, int value, boolean slot) {
        IntPrimitiveStatement s = new IntPrimitiveStatement(test, value);
        s.setObjectRuleSlot(slot);
        return s;
    }

    private static PrimitiveStatement<?> byValue(TestCase test, int value) {
        for (int i = 0; i < test.size(); i++) {
            Statement s = test.getStatement(i);
            if (s instanceof PrimitiveStatement
                    && Integer.valueOf(value).equals(((PrimitiveStatement<?>) s).getValue())) {
                return (PrimitiveStatement<?>) s;
            }
        }
        throw new IllegalStateException("no primitive with value " + value);
    }

    @Test
    public void slotFlagSurvivesCloneInsertAndDelete() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(intStmt(test, 10, false), 0);
        test.addStatement(intStmt(test, 20, true), 1);   // the slot
        test.addStatement(intStmt(test, 30, false), 2);

        TestCase clone = test.clone();
        assertTrue(byValue(clone, 20).isObjectRuleSlot(), "the slot flag must survive clone");
        assertFalse(byValue(clone, 10).isObjectRuleSlot());
        assertFalse(byValue(clone, 30).isObjectRuleSlot());

        // Insert shifts positions; the flag must track the statement, not the position.
        clone.addStatement(intStmt(clone, 99, false), 0);
        assertTrue(byValue(clone, 20).isObjectRuleSlot(), "the slot flag must survive an insertion");

        // Delete an unflagged statement; the slot must remain flagged.
        clone.remove(byValue(clone, 10).getPosition());
        assertTrue(byValue(clone, 20).isObjectRuleSlot(), "the slot flag must survive a deletion");
        assertFalse(byValue(clone, 30).isObjectRuleSlot());
    }

    @Test
    public void mutationPrefersFlaggedSlots() {
        // Isolate the "change" mutation.
        Properties.P_TEST_CHANGE = 1.0;
        Properties.P_TEST_DELETE = 0.0;
        Properties.P_TEST_INSERT = 0.0;
        Properties.CONCOLIC_MUTATION = 0.0;
        Properties.OBJECT_RULE_SLOT_BIAS = true;

        // 2 flagged slots (values 100, 200), 3 unflagged (0, 1, 2).
        int[] flaggedValues = { 100, 200 };
        int[] plainValues = { 0, 1, 2 };

        int flaggedChanges = 0;
        int plainChanges = 0;
        int trials = 3000;
        for (int t = 0; t < trials; t++) {
            DefaultTestCase base = new DefaultTestCase();
            for (int v : plainValues) {
                base.addStatement(intStmt(base, v, false), base.size());
            }
            for (int v : flaggedValues) {
                base.addStatement(intStmt(base, v, true), base.size());
            }

            TestChromosome chromosome = new TestChromosome();
            chromosome.setTestCase(base.clone());
            TestCase before = chromosome.getTestCase().clone();
            chromosome.mutate();
            TestCase after = chromosome.getTestCase();

            for (int v : flaggedValues) {
                if (changed(before, after, v)) {
                    flaggedChanges++;
                }
            }
            for (int v : plainValues) {
                if (changed(before, after, v)) {
                    plainChanges++;
                }
            }
        }

        double flaggedRate = flaggedChanges / (double) (trials * flaggedValues.length);
        double plainRate = plainChanges / (double) (trials * plainValues.length);
        assertTrue(flaggedRate > 2 * plainRate,
                "flagged slots should be mutated far more often than unflagged primitives "
                        + "(flagged=" + flaggedRate + ", plain=" + plainRate + ")");
    }

    /**
     * Whether the primitive that started with {@code originalValue} has a different value after
     * mutation. Slots are matched by their starting value across the two test copies (in-place
     * primitive mutation keeps the statement at the same position).
     */
    private static boolean changed(TestCase before, TestCase after, int originalValue) {
        for (int i = 0; i < before.size() && i < after.size(); i++) {
            Statement b = before.getStatement(i);
            if (b instanceof PrimitiveStatement
                    && Integer.valueOf(originalValue).equals(((PrimitiveStatement<?>) b).getValue())) {
                Statement a = after.getStatement(i);
                if (!(a instanceof PrimitiveStatement)) {
                    return true; // replaced by another statement type
                }
                return !Integer.valueOf(originalValue).equals(((PrimitiveStatement<?>) a).getValue());
            }
        }
        return false;
    }
}
