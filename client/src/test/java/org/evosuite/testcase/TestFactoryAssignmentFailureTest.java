package org.evosuite.testcase;

import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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

    @Test
    public void replacingStringNullKeepsNewValueAndRemovesTheNull() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference callee = test.addStatement(new StringPrimitiveStatement(test, "a"));
        VariableReference nul = test.addStatement(new NullStatement(test, String.class));
        GenericMethod concat = new GenericMethod(String.class.getMethod("concat", String.class),
                String.class);
        test.addStatement(new MethodStatement(test, concat, callee,
                java.util.Collections.singletonList(nul)));
        Statement nullStatement = test.getStatement(1);

        assertTrue(TestFactory.getInstance().changeNullStatement(test, nullStatement));

        assertEquals(3, test.size());
        for (int i = 0; i < test.size(); i++) {
            assertFalse("null statement remains at " + i, test.getStatement(i) instanceof NullStatement);
        }
        assertTrue(test.getStatement(1) instanceof StringPrimitiveStatement);
        MethodStatement call = (MethodStatement) test.getStatement(2);
        assertSame(test.getStatement(1).getReturnValue(), call.getParameterReferences().get(0));
        assertTrue(test.isValid());
        test.clone();
    }
}
