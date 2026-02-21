package org.evosuite.utils.generic;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

public class GenericFieldTest {

    @Test
    public void testConstructorHandlesInaccessibleJdkField() throws Exception {
        Class<?> declaringClass;
        Field field;
        try {
            declaringClass = Class.forName("sun.print.RasterPrinterJob");
            field = declaringClass.getDeclaredField("debugPrint");
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            Assumptions.assumeTrue(false, "Assume failed: " + e.getMessage());
            return;
        }

        GenericField genericField = new GenericField(field, declaringClass);
        Assertions.assertNotNull(genericField);
    }
}
