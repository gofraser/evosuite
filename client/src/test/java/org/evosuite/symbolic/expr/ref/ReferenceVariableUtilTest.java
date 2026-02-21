package org.evosuite.symbolic.expr.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ReferenceVariableUtilTest {

    @Test
    public void getReferenceVariableNameGeneratesValidNames() {
        String testName = ReferenceVariableUtil.getReferenceVariableName("test");
        assertTrue(ReferenceVariableUtil.isReferenceVariableName(testName));
    }

    @Test
    public void isReferenceVariableName() {
        // Null should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName(null));

        // non prefixes should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("test"));
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("test_test"));

        // Prefix but not separator should not be valid
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("referencetest"));
        assertFalse(ReferenceVariableUtil.isReferenceVariableName("referencetest_name"));
    }
}