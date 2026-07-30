package org.evosuite.testcase;

import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestFactoryAssignmentFailureTest {

    @Test
    public void incompatibleGeneratedVariableIsARecoverableConstructionFailure() {
        TestCase test = new DefaultTestCase();
        VariableReference string = test.addStatement(new StringPrimitiveStatement(test, "value"));

        String message = string + " cannot be assigned to " + Integer.class;
        TestFactory.logCannotAssignDiagnostic(message, test, test.size(), Integer.class, string);

        assertTrue(test.isValid());
    }

    @Test
    public void replacingStringNullAddsExactlyOneDefinition() {
        TestCase test = new DefaultTestCase();
        test.addStatement(new NullStatement(test, String.class));

        assertTrue(TestFactory.getInstance()
                .changeNullStatement(test, test.getStatement(0)));
        assertEquals(1, test.size());
        assertTrue(test.isValid());
        test.clone();
    }
}
