package org.evosuite.testcase.statements;

import org.evosuite.utils.generic.GenericMethod;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;

public class MethodStatementCompatibilityTest {

    private static class DeclaringType {
        public void ping() {
            // no-op
        }
    }

    private static class UnrelatedType {
        // no-op
    }

    @Test
    public void testIncompatibleCalleeRejectedEvenWithBroadOwnerType() throws Exception {
        Method method = DeclaringType.class.getDeclaredMethod("ping");
        GenericMethod genericMethod = new GenericMethod(method, Object.class);

        Assert.assertFalse(MethodStatement.isCompatibleCalleeType(genericMethod, UnrelatedType.class));
    }
}
