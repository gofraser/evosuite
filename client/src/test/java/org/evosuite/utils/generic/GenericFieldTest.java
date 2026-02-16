package org.evosuite.utils.generic;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

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
            Assume.assumeNoException(e);
            return;
        }

        GenericField genericField = new GenericField(field, declaringClass);
        Assert.assertNotNull(genericField);
    }
}
