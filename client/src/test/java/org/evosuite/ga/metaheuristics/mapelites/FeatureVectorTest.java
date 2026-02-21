package org.evosuite.ga.metaheuristics.mapelites;

import org.evosuite.assertion.Inspector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

public class FeatureVectorTest {

    public static class TestClass {
        public int getInt() { return 1; }
        public boolean getBool() { return true; }
        public String getString() { return "test"; }
        public char getChar() { return 'a'; }
        public MyEnum getEnum() { return MyEnum.A; }
    }

    public enum MyEnum {
        A, B, C
    }

    @Test
    public void testPossibilityCount() throws NoSuchMethodException {
        Method mInt = TestClass.class.getMethod("getInt");
        Method mBool = TestClass.class.getMethod("getBool");
        Method mString = TestClass.class.getMethod("getString");
        Method mChar = TestClass.class.getMethod("getChar");
        Method mEnum = TestClass.class.getMethod("getEnum");

        Inspector iInt = new Inspector(TestClass.class, mInt);
        Inspector iBool = new Inspector(TestClass.class, mBool);
        Inspector iString = new Inspector(TestClass.class, mString);
        Inspector iChar = new Inspector(TestClass.class, mChar);
        Inspector iEnum = new Inspector(TestClass.class, mEnum);

        // Int: 3 (-1, 0, 1)
        // Bool: 2 (0, 1)
        // String: 2 (0, 1)
        // Char: 2 (0, 1)
        // Enum: 3 (A, B, C)

        double count = FeatureVector.getPossibilityCount(new Inspector[]{iInt, iBool, iString, iChar, iEnum});
        Assertions.assertEquals(3.0 * 2.0 * 2.0 * 2.0 * 3.0, count, 0.0001);
    }

    @Test
    public void testEqualsAndHashCode() throws NoSuchMethodException {
        Method mInt = TestClass.class.getMethod("getInt");
        Inspector iInt = new Inspector(TestClass.class, mInt);

        TestClass instance1 = new TestClass();
        TestClass instance2 = new TestClass();

        FeatureVector fv1 = new FeatureVector(new Inspector[]{iInt}, instance1);
        FeatureVector fv2 = new FeatureVector(new Inspector[]{iInt}, instance2);

        Assertions.assertEquals(fv1, fv2);
        Assertions.assertEquals(fv1.hashCode(), fv2.hashCode());
    }

    @Test
    public void testPossibilityCountOverflow() throws NoSuchMethodException {
        // Simulate large search space
        Method mInt = TestClass.class.getMethod("getInt");
        Inspector iInt = new Inspector(TestClass.class, mInt);

        // 3^40 exceeds Integer.MAX_VALUE
        int n = 40;
        Inspector[] inspectors = new Inspector[n];
        for (int i = 0; i < n; i++) {
            inspectors[i] = iInt;
        }

        double count = FeatureVector.getPossibilityCount(inspectors);
        Assertions.assertTrue(count > Integer.MAX_VALUE);
        Assertions.assertEquals(Math.pow(3.0, n), count, 1.0);
    }
}
