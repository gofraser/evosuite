package org.evosuite.testcase.variable;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.utils.ParameterizedTypeImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericField;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;

import static org.junit.Assert.assertTrue;

public class FieldReferenceTest {

    private static class BaseLexem {}
    private static class ExecLexem extends BaseLexem {}
    private static class AdvLexem extends ExecLexem {}
    private static class Holder<T extends ExecLexem> {
        public ArrayList<T> fieldScope = new ArrayList<>();
    }

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

    @Test
    public void testReplacingSourceRebindsGenericFieldType() throws Exception {
        DefaultTestCase testCase = new DefaultTestCase();
        Field field = Holder.class.getField("fieldScope");

        VariableReference advHolder = new VariableReferenceImpl(testCase, holderOf(AdvLexem.class));
        VariableReference execHolder = new VariableReferenceImpl(testCase, holderOf(ExecLexem.class));

        FieldReference ref = new FieldReference(testCase, new GenericField(field, advHolder.getGenericClass()), advHolder);
        assertTrue(ref.getType().getTypeName().contains("AdvLexem"));

        ref.setAdditionalVariableReference(execHolder);
        assertTrue(ref.getType().getTypeName().contains("ExecLexem"));
    }

    private static Type holderOf(Class<?> typeArgument) {
        return new ParameterizedTypeImpl(Holder.class, new Type[]{typeArgument}, null);
    }
}
