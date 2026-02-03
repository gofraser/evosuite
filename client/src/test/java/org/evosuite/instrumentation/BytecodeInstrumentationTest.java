package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BytecodeInstrumentationTest {

    @Before
    public void setUp() {
        Properties.getInstance().resetToDefaults();
    }

    @After
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void testShouldTransform_TT_False() {
        Properties.TT = false;
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        assertFalse(instrumentation.shouldTransform("com.example.MyClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_All() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.ALL;
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        assertTrue(instrumentation.shouldTransform("com.example.MyClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_Target() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.TARGET;
        Properties.TARGET_CLASS = "com.example.TargetClass";
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();

        assertTrue(instrumentation.shouldTransform("com.example.TargetClass"));
        assertTrue(instrumentation.shouldTransform("com.example.TargetClass$Inner"));
        assertFalse(instrumentation.shouldTransform("com.example.OtherClass"));
    }

    @Test
    public void testShouldTransform_TT_True_Scope_Prefix() {
        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.PREFIX;
        Properties.PROJECT_PREFIX = "com.example";
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();

        assertTrue(instrumentation.shouldTransform("com.example.MyClass"));
        assertTrue(instrumentation.shouldTransform("com.example.sub.MyClass"));
        assertFalse(instrumentation.shouldTransform("com.other.MyClass"));
    }

    @Test
    public void testCheckIfEvoSuitePackage() {
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("org.evosuite.runtime.Runtime"));
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("de.unisb.cs.st.evosuite.SomeClass"));
        assertTrue(BytecodeInstrumentation.checkIfEvoSuitePackage("org.exsyst.SomeClass"));

        assertFalse(BytecodeInstrumentation.checkIfEvoSuitePackage("com.example.MyClass"));
        assertFalse(BytecodeInstrumentation.checkIfEvoSuitePackage("java.lang.String"));
    }
}
