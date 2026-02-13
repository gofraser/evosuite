package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class TestFactoryMoreTest {

    private double originalFunctionalMockingPercent;
    private double originalPFunctionalMocking;
    private boolean originalMockIfNoGenerator;
    private TestClusterGenerator originalGenerator;

    @Before
    public void setUp() {
        originalFunctionalMockingPercent = Properties.FUNCTIONAL_MOCKING_PERCENT;
        originalPFunctionalMocking = Properties.P_FUNCTIONAL_MOCKING;
        originalMockIfNoGenerator = Properties.MOCK_IF_NO_GENERATOR;
        originalGenerator = TestGenerationContext.getInstance().getTestClusterGenerator();

        TestFactory.getInstance().reset();

        // Mock TestClusterGenerator to avoid NPE
        TestClusterGenerator mockGenerator = mock(TestClusterGenerator.class);
        TestGenerationContext.getInstance().setTestClusterGenerator(mockGenerator);
    }

    @After
    public void tearDown() {
        Properties.FUNCTIONAL_MOCKING_PERCENT = originalFunctionalMockingPercent;
        Properties.P_FUNCTIONAL_MOCKING = originalPFunctionalMocking;
        Properties.MOCK_IF_NO_GENERATOR = originalMockIfNoGenerator;
        TestGenerationContext.getInstance().setTestClusterGenerator(originalGenerator);
        TestFactory.getInstance().reset();
    }

    public interface MyInterface {
        void foo();
    }

    public static abstract class MyAbstractClass {
        public abstract void bar();
    }

    @Test
    public void testAddFunctionalMock() throws ConstructionFailedException {
        TestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();
        VariableReference ref = factory.addFunctionalMock(test, MyInterface.class, 0, 0);

        assertNotNull(ref);
        Statement stmt = test.getStatement(ref.getStPosition());
        assertTrue(stmt instanceof FunctionalMockStatement);
        assertEquals(MyInterface.class, ref.getVariableClass());
    }

    @Test
    public void testAddFunctionalMockForAbstractClass() throws ConstructionFailedException {
        TestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();
        VariableReference ref = factory.addFunctionalMockForAbstractClass(test, MyAbstractClass.class, 0, 0);

        assertNotNull(ref);
        Statement stmt = test.getStatement(ref.getStPosition());
        assertNotNull(stmt);
        assertEquals(MyAbstractClass.class, ref.getVariableClass());
    }

    @Test
    public void testCreateObjectWithMock() throws ConstructionFailedException {
        // Ensure mocking conditions are met
        Properties.FUNCTIONAL_MOCKING_PERCENT = -2.0; // Assume TimeController returns -1, -1 >= -2 is true
        Properties.P_FUNCTIONAL_MOCKING = 1.0;

        TestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();

        VariableReference ref = factory.createObject(test, MyInterface.class, 0, 0, null, true, true, true);

        assertNotNull(ref);
        Statement stmt = test.getStatement(ref.getStPosition());
        assertTrue(stmt instanceof FunctionalMockStatement);
    }

    @Test(expected = ConstructionFailedException.class)
    public void testCreateObjectNoGeneratorNoMock() throws ConstructionFailedException {
        Properties.P_FUNCTIONAL_MOCKING = 0.0;
        Properties.MOCK_IF_NO_GENERATOR = false;

        TestCase test = new DefaultTestCase();
        TestFactory factory = TestFactory.getInstance();

        // This should fail because there are no generators for MyInterface and mocking is disabled
        factory.createObject(test, MyInterface.class, 0, 0, null);
    }

    @Test
    public void testConstructionFailedExceptionChaining() {
        Throwable cause = new RuntimeException("Original Cause");
        ConstructionFailedException e = new ConstructionFailedException("Message", cause);
        assertEquals("Message", e.getMessage());
        assertEquals(cause, e.getCause());
    }
}
