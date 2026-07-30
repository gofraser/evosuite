package org.evosuite.testcase.synthesizer;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveFieldInitializerTest {

    @Test
    void appliesExactSwitchCaseValueToIntegerField() {
        IntPrimitiveStatement statement =
                new IntPrimitiveStatement(new DefaultTestCase());

        assertTrue(PrimitiveFieldInitializer.setIntegerHint(statement, "I", 448));
        assertEquals(448, statement.getValue());
    }

    @Test
    void convertsSwitchCaseValueToNarrowPrimitiveField() {
        ShortPrimitiveStatement statement =
                new ShortPrimitiveStatement(new DefaultTestCase());

        assertTrue(PrimitiveFieldInitializer.setIntegerHint(statement, "S", 123));
        assertEquals((short) 123, statement.getValue());
    }
}
