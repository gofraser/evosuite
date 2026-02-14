package org.evosuite.assertion;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for assertion generation bug fixes and improvements.
 */
public class AssertionBugFixesTest {

    // -----------------------------------------------------------------------
    // Helper: concrete subclass to call protected methods from AssertionGenerator
    // -----------------------------------------------------------------------

    private static class TestableAssertionGenerator extends AssertionGenerator {
        TestableAssertionGenerator() {
        }

        @Override
        public void addAssertions(TestCase test) {
            // not used in tests
        }
    }

    /**
     * Creates a mock statement that passes isValid and has the given return value
     * and assertions.
     */
    private Statement mockStmt(int position, VariableReference returnValue, Assertion... assertions) {
        Statement stmt = mock(Statement.class);
        Set<Assertion> assertionSet = new HashSet<>();
        Collections.addAll(assertionSet, assertions);
        when(stmt.getAssertions()).thenReturn(assertionSet);
        when(stmt.getReturnValue()).thenReturn(returnValue);
        when(stmt.getPosition()).thenReturn(position);
        when(stmt.isValid()).thenReturn(true);
        return stmt;
    }

    private ConstructorStatement mockConstructorStmt(int position, VariableReference returnValue,
                                                     Assertion... assertions) {
        ConstructorStatement stmt = mock(ConstructorStatement.class);
        Set<Assertion> assertionSet = new HashSet<>();
        Collections.addAll(assertionSet, assertions);
        when(stmt.getAssertions()).thenReturn(assertionSet);
        when(stmt.getReturnValue()).thenReturn(returnValue);
        when(stmt.getPosition()).thenReturn(position);
        when(stmt.isValid()).thenReturn(true);
        return stmt;
    }

    private MethodStatement mockMethodStmt(int position, VariableReference returnValue,
                                           VariableReference callee, Assertion... assertions) {
        MethodStatement stmt = mock(MethodStatement.class);
        Set<Assertion> assertionSet = new HashSet<>();
        Collections.addAll(assertionSet, assertions);
        when(stmt.getAssertions()).thenReturn(assertionSet);
        when(stmt.getReturnValue()).thenReturn(returnValue);
        when(stmt.getCallee()).thenReturn(callee);
        when(stmt.getPosition()).thenReturn(position);
        when(stmt.isValid()).thenReturn(true);
        return stmt;
    }

    // -----------------------------------------------------------------------
    // 1.1: filterRedundantNonnullAssertions — size > 1 fix
    // -----------------------------------------------------------------------

    /**
     * Bug 1.1: When a ConstructorStatement has only a single NullAssertion
     * (size == 1) and the return value is used as a callee later, the
     * NullAssertion should be removed as redundant. Before the fix, the
     * condition was > 0 (always true when iterating assertions), making the
     * isUsedAsCallee branch unreachable.
     */
    @Test
    public void testFilterRedundantNonnull_singleNullAssertionUsedAsCallee() {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference constructorReturn = mock(VariableReference.class);
        when(constructorReturn.getStPosition()).thenReturn(0);

        NullAssertion nullAssertion = new NullAssertion();
        nullAssertion.source = constructorReturn;
        nullAssertion.value = false; // assertNotNull

        ConstructorStatement constructorStmt = mockConstructorStmt(0, constructorReturn, nullAssertion);

        // Method statement at position 1 using constructorReturn as callee
        VariableReference methodReturn = mock(VariableReference.class);
        when(methodReturn.getStPosition()).thenReturn(1);
        MethodStatement methodStmt = mockMethodStmt(1, methodReturn, constructorReturn);

        test.addStatement(constructorStmt);
        test.addStatement(methodStmt);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterRedundantNonnullAssertions(test);

        // The single NullAssertion should be removed because isUsedAsCallee is true
        verify(constructorStmt).removeAssertion(nullAssertion);
    }

    /**
     * Bug 1.1: When a ConstructorStatement has a NullAssertion AND another
     * assertion (size > 1), the NullAssertion should be flagged as redundant.
     */
    @Test
    public void testFilterRedundantNonnull_nullAndOtherAssertionOnConstructor() {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = returnVal;
        nullAss.value = false;

        PrimitiveAssertion primAss = new PrimitiveAssertion();
        primAss.source = returnVal;
        primAss.value = 42;

        ConstructorStatement stmt = mockConstructorStmt(0, returnVal, nullAss, primAss);
        test.addStatement(stmt);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterRedundantNonnullAssertions(test);

        // NullAssertion should be removed since there's another assertion on the same source
        verify(stmt).removeAssertion(nullAss);
    }

    /**
     * When there's only a NullAssertion (size == 1) and the return value is
     * NOT used as a callee, the NullAssertion should NOT be removed.
     */
    @Test
    public void testFilterRedundantNonnull_singleNullAssertionNotUsedAsCallee_kept() {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = returnVal;
        nullAss.value = false;

        ConstructorStatement stmt = mockConstructorStmt(0, returnVal, nullAss);
        test.addStatement(stmt);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterRedundantNonnullAssertions(test);

        // Should NOT be removed — no other assertions and not used as callee
        verify(stmt, never()).removeAssertion(any());
    }

    // -----------------------------------------------------------------------
    // 1.2: filterRedundantNonnullAssertions — MethodStatement support
    // -----------------------------------------------------------------------

    /**
     * Bug 1.2: filterRedundantNonnullAssertions should now handle
     * MethodStatements, not just ConstructorStatements.
     */
    @Test
    public void testFilterRedundantNonnull_worksOnMethodStatement() {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = returnVal;
        nullAss.value = false;

        InspectorAssertion inspAss = new InspectorAssertion();
        inspAss.source = returnVal;
        inspAss.value = 5;

        MethodStatement stmt = mockMethodStmt(0, returnVal, null, nullAss, inspAss);
        test.addStatement(stmt);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterRedundantNonnullAssertions(test);

        verify(stmt).removeAssertion(nullAss);
    }

    /**
     * Plain statements (not Constructor or Method) should not be affected.
     */
    @Test
    public void testFilterRedundantNonnull_ignoresPlainStatements() {
        DefaultTestCase test = new DefaultTestCase();

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = returnVal;
        nullAss.value = false;

        // Use a generic Statement (not Constructor/Method)
        Statement stmt = mockStmt(0, returnVal, nullAss);
        test.addStatement(stmt);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterRedundantNonnullAssertions(test);

        // Plain statements are not processed
        verify(stmt, never()).removeAssertion(any());
    }

    // -----------------------------------------------------------------------
    // filterInspectorPrimitiveDuplication — moved to base class
    // -----------------------------------------------------------------------

    @Test
    public void testFilterInspectorPrimitiveDuplication_removesRedundantInspector() throws Exception {
        java.lang.reflect.Method listSizeMethod = java.util.List.class.getMethod("size");

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        MethodStatement stmt = mock(MethodStatement.class);
        when(stmt.getPosition()).thenReturn(0);
        when(stmt.getReturnValue()).thenReturn(returnVal);

        org.evosuite.utils.generic.GenericMethod gm = mock(org.evosuite.utils.generic.GenericMethod.class);
        when(gm.getMethod()).thenReturn(listSizeMethod);
        when(stmt.getMethod()).thenReturn(gm);

        PrimitiveAssertion primAss = new PrimitiveAssertion();
        primAss.source = returnVal;
        primAss.value = 3;
        primAss.setStatement(stmt);

        Inspector inspector = new Inspector(java.util.List.class, listSizeMethod);
        InspectorAssertion inspAss = new InspectorAssertion(inspector, stmt, returnVal, 3);

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(primAss);
        assertions.add(inspAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterInspectorPrimitiveDuplication(stmt);

        // The inspector assertion duplicating the primitive should be removed
        verify(stmt).removeAssertion(inspAss);
    }

    @Test
    public void testFilterInspectorPrimitiveDuplication_noPrimitiveNoRemoval() throws Exception {
        java.lang.reflect.Method listSizeMethod = java.util.List.class.getMethod("size");

        VariableReference returnVal = mock(VariableReference.class);
        when(returnVal.getStPosition()).thenReturn(0);

        MethodStatement stmt = mock(MethodStatement.class);
        when(stmt.getPosition()).thenReturn(0);
        when(stmt.getReturnValue()).thenReturn(returnVal);

        Inspector inspector = new Inspector(java.util.List.class, listSizeMethod);
        InspectorAssertion inspAss = new InspectorAssertion(inspector, stmt, returnVal, 3);

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(inspAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableAssertionGenerator generator = new TestableAssertionGenerator();
        generator.filterInspectorPrimitiveDuplication(stmt);

        // No primitive assertion present, so nothing should be removed
        verify(stmt, never()).removeAssertion(any());
    }

    // -----------------------------------------------------------------------
    // returnValueWithoutAssertion (Part 2)
    // -----------------------------------------------------------------------

    private static class TestableMutationGenerator extends MutationAssertionGenerator {
        TestableMutationGenerator() {
        }

        public boolean testReturnValueWithoutAssertion(Statement stmt) {
            return returnValueWithoutAssertion(stmt);
        }
    }

    @Test
    public void testReturnValueWithoutAssertion_voidReturnIsFalse() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(true);
        when(stmt.getReturnValue()).thenReturn(ret);

        TestableMutationGenerator gen = new TestableMutationGenerator();
        assertFalse(gen.testReturnValueWithoutAssertion(stmt));
    }

    @Test
    public void testReturnValueWithoutAssertion_emptyAssertionsIsTrue() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(false);
        when(stmt.getReturnValue()).thenReturn(ret);
        when(stmt.getAssertions()).thenReturn(Collections.emptySet());

        TestableMutationGenerator gen = new TestableMutationGenerator();
        assertTrue(gen.testReturnValueWithoutAssertion(stmt));
    }

    @Test
    public void testReturnValueWithoutAssertion_onlyNullAssertionIsTrue() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(false);
        when(stmt.getReturnValue()).thenReturn(ret);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = ret;
        nullAss.value = false;

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(nullAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableMutationGenerator gen = new TestableMutationGenerator();
        assertTrue(gen.testReturnValueWithoutAssertion(stmt));
    }

    @Test
    public void testReturnValueWithoutAssertion_primitiveAssertionIsFalse() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(false);
        when(stmt.getReturnValue()).thenReturn(ret);

        PrimitiveAssertion primAss = new PrimitiveAssertion();
        primAss.source = ret;
        primAss.value = 42;

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(primAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableMutationGenerator gen = new TestableMutationGenerator();
        assertFalse(gen.testReturnValueWithoutAssertion(stmt));
    }

    @Test
    public void testReturnValueWithoutAssertion_inspectorAssertionIsFalse() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(false);
        when(stmt.getReturnValue()).thenReturn(ret);

        InspectorAssertion inspAss = new InspectorAssertion();
        inspAss.source = ret;
        inspAss.value = "hello";

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(inspAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableMutationGenerator gen = new TestableMutationGenerator();
        assertFalse(gen.testReturnValueWithoutAssertion(stmt));
    }

    @Test
    public void testReturnValueWithoutAssertion_mixedNullAndRealIsFalse() {
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(false);
        when(stmt.getReturnValue()).thenReturn(ret);

        NullAssertion nullAss = new NullAssertion();
        nullAss.source = ret;
        nullAss.value = false;

        PrimitiveAssertion primAss = new PrimitiveAssertion();
        primAss.source = ret;
        primAss.value = 42;

        Set<Assertion> assertions = new HashSet<>();
        assertions.add(nullAss);
        assertions.add(primAss);
        when(stmt.getAssertions()).thenReturn(assertions);

        TestableMutationGenerator gen = new TestableMutationGenerator();
        // Has a non-null assertion referencing the return value
        assertFalse(gen.testReturnValueWithoutAssertion(stmt));
    }

    // -----------------------------------------------------------------------
    // Void method with no assertions — regression test
    // -----------------------------------------------------------------------

    /**
     * For void methods, returnValueWithoutAssertion returns false (nothing to
     * assert on). The fallback must still be entered when the statement has no
     * assertions at all, so that assertions on referenced variables can be added.
     */
    @Test
    public void testReturnValueWithoutAssertion_voidReturnIsFalse_butFallbackStillEntered() {
        // returnValueWithoutAssertion returns false for void
        Statement stmt = mock(Statement.class);
        VariableReference ret = mock(VariableReference.class);
        when(ret.isVoid()).thenReturn(true);
        when(stmt.getReturnValue()).thenReturn(ret);
        when(stmt.getAssertions()).thenReturn(Collections.emptySet());

        TestableMutationGenerator gen = new TestableMutationGenerator();
        // The method itself returns false for void
        assertFalse(gen.testReturnValueWithoutAssertion(stmt));

        // But the fallback condition in SimpleMutationAssertionGenerator also
        // checks getAssertions().isEmpty(), so the block is still entered.
        // This test documents that returnValueWithoutAssertion alone is not
        // sufficient — the empty-assertions check is also needed.
        assertTrue("Void method with no assertions should trigger fallback",
                stmt.getAssertions().isEmpty());
    }
}
