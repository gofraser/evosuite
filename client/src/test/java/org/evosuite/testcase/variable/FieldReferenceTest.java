package org.evosuite.testcase.variable;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.utils.generic.GenericField;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class FieldReferenceTest {

    @Test(expected = CodeUnderTestException.class)
    public void testGetObjectHandlesInaccessibleStaticField() throws Exception {
        Class<?> clazz;
        Field staticField = null;
        try {
            clazz = Class.forName("sun.print.RasterPrinterJob");
            for (Field candidate : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers())) {
                    staticField = candidate;
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            Assume.assumeNoException(e);
            return;
        }

        if (staticField == null) {
            Assume.assumeTrue("No static field available", false);
            return;
        }

        FieldReference ref = new FieldReference(new DefaultTestCase(), new GenericField(staticField, clazz));
        ref.getObject(new Scope());
    }
}
