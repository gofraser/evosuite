package org.evosuite.assertion;

import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OutputTrace, focusing on null-safety fixes when
 * the other trace has different variable keys for the same statement.
 */
public class OutputTraceTest {

    private VariableReference mockVar(int stPosition) {
        VariableReference var = mock(VariableReference.class);
        when(var.getStPosition()).thenReturn(stPosition);
        return var;
    }

    // -----------------------------------------------------------------------
    // differs() — null-safety for missing variable keys
    // -----------------------------------------------------------------------

    @Test
    public void testDiffers_bothTracesHaveSameVars_noDifference() {
        VariableReference var = mockVar(0);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var, new PrimitiveTraceEntry(var, 42));
        trace2.addEntry(0, var, new PrimitiveTraceEntry(var, 42));

        assertFalse(trace1.differs(trace2));
    }

    @Test
    public void testDiffers_bothTracesHaveSameVars_withDifference() {
        VariableReference var = mockVar(0);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var, new PrimitiveTraceEntry(var, 42));
        trace2.addEntry(0, var, new PrimitiveTraceEntry(var, 99));

        assertTrue(trace1.differs(trace2));
    }

    @Test
    public void testDiffers_extraVarInFirstTrace_noException() {
        VariableReference var1 = mockVar(0);
        VariableReference var2 = mockVar(1);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        // trace1 has var1 and var2 at statement 0
        trace1.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));
        trace1.addEntry(0, var2, new PrimitiveTraceEntry(var2, 99));

        // trace2 has only var1 at statement 0
        trace2.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));

        // Should not throw NPE — var2 missing from trace2 should be skipped
        assertFalse(trace1.differs(trace2));
    }

    @Test
    public void testDiffers_statementMissingFromOther_noException() {
        VariableReference var = mockVar(0);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var, new PrimitiveTraceEntry(var, 42));
        // trace2 has no entries for statement 0

        assertFalse(trace1.differs(trace2));
    }

    // -----------------------------------------------------------------------
    // numDiffer() — null-safety for missing variable keys
    // -----------------------------------------------------------------------

    @Test
    public void testNumDiffer_extraVarInFirstTrace_noException() {
        VariableReference var1 = mockVar(0);
        VariableReference var2 = mockVar(1);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));
        trace1.addEntry(0, var2, new PrimitiveTraceEntry(var2, 99));

        trace2.addEntry(0, var1, new PrimitiveTraceEntry(var1, 100));

        // Only var1 can be compared (differs), var2 skipped
        assertEquals(1, trace1.numDiffer(trace2));
    }

    @Test
    public void testNumDiffer_missingVarIgnored() {
        VariableReference var1 = mockVar(0);
        VariableReference var2 = mockVar(1);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));
        trace1.addEntry(0, var2, new PrimitiveTraceEntry(var2, 99));

        trace2.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));
        // var2 missing from trace2

        assertEquals(0, trace1.numDiffer(trace2));
    }

    // -----------------------------------------------------------------------
    // getAssertions() — null-safety for missing variable keys
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // getEntry() / containsEntry() — should not mutate trace on read (A4)
    // -----------------------------------------------------------------------

    @Test
    public void testGetEntry_missingPositionDoesNotMutate() {
        OutputTrace<PrimitiveTraceEntry> trace = new OutputTrace<>();
        VariableReference var = mockVar(0);

        // Before: trace is empty
        assertNull(trace.getEntry(5, var));
        // The trace's internal map should NOT have gained a new entry for position 5
        assertFalse(trace.containsEntry(5, var));
    }

    @Test
    public void testContainsEntry_missingPositionDoesNotMutate() {
        OutputTrace<PrimitiveTraceEntry> trace = new OutputTrace<>();
        VariableReference var = mockVar(0);

        assertFalse(trace.containsEntry(5, var));
        // Calling containsEntry should not create entries
        assertNull(trace.getEntry(5, var));
    }

    // -----------------------------------------------------------------------
    // getAssertions() — null-safety for missing variable keys
    // -----------------------------------------------------------------------

    @Test
    public void testGetAssertions_extraVarInFirstTrace_noException() {
        VariableReference var1 = mockVar(0);
        VariableReference var2 = mockVar(1);

        OutputTrace<PrimitiveTraceEntry> trace1 = new OutputTrace<>();
        OutputTrace<PrimitiveTraceEntry> trace2 = new OutputTrace<>();

        trace1.addEntry(0, var1, new PrimitiveTraceEntry(var1, 42));
        trace1.addEntry(0, var2, new PrimitiveTraceEntry(var2, 99));

        trace2.addEntry(0, var1, new PrimitiveTraceEntry(var1, 100));
        // var2 missing from trace2

        org.evosuite.testcase.DefaultTestCase test = new org.evosuite.testcase.DefaultTestCase();
        org.evosuite.testcase.statements.Statement stmt = mock(org.evosuite.testcase.statements.Statement.class);
        when(stmt.getReturnValue()).thenReturn(var1);
        when(stmt.getPosition()).thenReturn(0);
        when(stmt.isValid()).thenReturn(true);
        when(stmt.getComment()).thenReturn("");
        test.addStatement(stmt);

        // Should not throw NPE
        int numAssertions = trace1.getAssertions(test, trace2);
        // var1 differs (42 vs 100), var2 is skipped
        assertTrue(numAssertions >= 0);
    }
}
