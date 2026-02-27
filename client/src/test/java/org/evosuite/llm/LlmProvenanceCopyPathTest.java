package org.evosuite.llm;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6b regression tests: verifies that direct {@code copy(TestCase, int)}
 * calls preserve {@code parsedFromLlm} provenance across all statement types.
 * <p>
 * These tests call {@code copy()} directly (not via {@code clone()}) to ensure
 * provenance is retained through mutation/crossover/copy-heavy evolutionary paths.
 */
class LlmProvenanceCopyPathTest {

    // --- Helper class used as a target for reflection-based statement creation ---
    @SuppressWarnings("unused")
    public static class Target {
        public int field;
        public static int staticField;
        public Target() {}
        public Target(int x) { this.field = x; }
        public int doSomething(int x) { return x; }
    }

    // ----------------------------------------------------------------
    // 1. PrimitiveStatement variants
    // ----------------------------------------------------------------

    @Test
    void intPrimitiveCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(tc, 42);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on IntPrimitiveStatement");
    }

    @Test
    void intPrimitiveCopyPreservesFalseProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement stmt = new IntPrimitiveStatement(tc, 7);
        tc.addStatement(stmt);
        assertFalse(stmt.isParsedFromLlm());

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertFalse(copied.isParsedFromLlm(),
                "Unmarked statement should remain false after direct copy()");
    }

    @Test
    void booleanPrimitiveCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        BooleanPrimitiveStatement stmt = new BooleanPrimitiveStatement(tc, true);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on BooleanPrimitiveStatement");
    }

    @Test
    void stringPrimitiveCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        StringPrimitiveStatement stmt = new StringPrimitiveStatement(tc, "hello");
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on StringPrimitiveStatement");
    }

    // ----------------------------------------------------------------
    // 2. NullStatement
    // ----------------------------------------------------------------

    @Test
    void nullStatementCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        NullStatement stmt = new NullStatement(tc, Object.class);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on NullStatement");
    }

    // ----------------------------------------------------------------
    // 3. ArrayStatement
    // ----------------------------------------------------------------

    @Test
    void arrayStatementCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        ArrayStatement stmt = new ArrayStatement(tc, int[].class, new int[]{5});
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on ArrayStatement");
    }

    // ----------------------------------------------------------------
    // 4. ConstructorStatement
    // ----------------------------------------------------------------

    @Test
    void constructorStatementCopyPreservesProvenance() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        Constructor<?> ctor = Target.class.getConstructor();
        GenericConstructor gc = new GenericConstructor(ctor, Target.class);
        ConstructorStatement stmt = new ConstructorStatement(tc, gc, Collections.emptyList());
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on ConstructorStatement");
    }

    // ----------------------------------------------------------------
    // 5. MethodStatement
    // ----------------------------------------------------------------

    @Test
    void methodStatementCopyPreservesProvenance() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // Create a callee (constructor for Target)
        Constructor<?> ctor = Target.class.getConstructor();
        GenericConstructor gc = new GenericConstructor(ctor, Target.class);
        ConstructorStatement ctorStmt = new ConstructorStatement(tc, gc, Collections.emptyList());
        tc.addStatement(ctorStmt);
        VariableReference callee = ctorStmt.getReturnValue();

        // Create an int parameter
        IntPrimitiveStatement paramStmt = new IntPrimitiveStatement(tc, 1);
        tc.addStatement(paramStmt);

        // Create MethodStatement
        Method m = Target.class.getMethod("doSomething", int.class);
        GenericMethod gm = new GenericMethod(m, Target.class);
        List<VariableReference> params = new ArrayList<>();
        params.add(paramStmt.getReturnValue());
        MethodStatement stmt = new MethodStatement(tc, gm, callee, params);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        // Copy prerequisites first so variable references resolve
        target.addStatement(ctorStmt.copy(target, 0));
        target.addStatement(paramStmt.copy(target, 0));
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on MethodStatement");
    }

    // ----------------------------------------------------------------
    // 6. FieldStatement
    // ----------------------------------------------------------------

    @Test
    void fieldStatementCopyPreservesProvenance() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        // Create a callee
        Constructor<?> ctor = Target.class.getConstructor();
        GenericConstructor gc = new GenericConstructor(ctor, Target.class);
        ConstructorStatement ctorStmt = new ConstructorStatement(tc, gc, Collections.emptyList());
        tc.addStatement(ctorStmt);
        VariableReference callee = ctorStmt.getReturnValue();

        // Create FieldStatement
        Field f = Target.class.getField("field");
        GenericField gf = new GenericField(f, Target.class);
        FieldStatement stmt = new FieldStatement(tc, gf, callee);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        target.addStatement(ctorStmt.copy(target, 0));
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on FieldStatement (instance)");
    }

    @Test
    void staticFieldStatementCopyPreservesProvenance() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        Field f = Target.class.getField("staticField");
        GenericField gf = new GenericField(f, Target.class);
        FieldStatement stmt = new FieldStatement(tc, gf, null);
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on FieldStatement (static)");
    }

    // ----------------------------------------------------------------
    // 7. AssignmentStatement
    // ----------------------------------------------------------------

    @Test
    void assignmentStatementCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();

        IntPrimitiveStatement src = new IntPrimitiveStatement(tc, 10);
        tc.addStatement(src);
        IntPrimitiveStatement dst = new IntPrimitiveStatement(tc, 0);
        tc.addStatement(dst);

        AssignmentStatement stmt = new AssignmentStatement(tc,
                dst.getReturnValue(), src.getReturnValue());
        tc.addStatement(stmt);
        stmt.setParsedFromLlm(true);

        DefaultTestCase target = new DefaultTestCase();
        target.addStatement(src.copy(target, 0));
        target.addStatement(dst.copy(target, 0));
        Statement copied = stmt.copy(target, 0);
        assertTrue(copied.isParsedFromLlm(),
                "parsedFromLlm must survive direct copy() on AssignmentStatement");
    }

    // ----------------------------------------------------------------
    // 8. Higher-level: clone preserves copy-path provenance
    //    (exercises TestCase.clone() which uses copy() internally)
    // ----------------------------------------------------------------

    @Test
    void testCaseClonePreservesCopyPathProvenance() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();

        Constructor<?> ctor = Target.class.getConstructor();
        GenericConstructor gc = new GenericConstructor(ctor, Target.class);
        ConstructorStatement ctorStmt = new ConstructorStatement(tc, gc, Collections.emptyList());
        ctorStmt.setParsedFromLlm(true);
        tc.addStatement(ctorStmt);

        IntPrimitiveStatement intStmt = new IntPrimitiveStatement(tc, 5);
        intStmt.setParsedFromLlm(true);
        tc.addStatement(intStmt);

        NullStatement nullStmt = new NullStatement(tc, String.class);
        // Intentionally NOT marked - should stay false
        tc.addStatement(nullStmt);

        ArrayStatement arrStmt = new ArrayStatement(tc, int[].class, new int[]{3});
        arrStmt.setParsedFromLlm(true);
        tc.addStatement(arrStmt);

        DefaultTestCase cloned = tc.clone();
        assertEquals(4, cloned.size());
        assertTrue(cloned.getStatement(0).isParsedFromLlm(), "ConstructorStatement provenance");
        assertTrue(cloned.getStatement(1).isParsedFromLlm(), "IntPrimitiveStatement provenance");
        assertFalse(cloned.getStatement(2).isParsedFromLlm(), "NullStatement should remain unmarked");
        assertTrue(cloned.getStatement(3).isParsedFromLlm(), "ArrayStatement provenance");
    }

    // ----------------------------------------------------------------
    // 9. Evolutionary path simulation: copy-based crossover
    // ----------------------------------------------------------------

    @Test
    void simulatedCrossoverPreservesProvenance() throws Exception {
        // Simulate crossover: take statements from one test and copy them into another
        DefaultTestCase parent1 = new DefaultTestCase();
        IntPrimitiveStatement s1 = new IntPrimitiveStatement(parent1, 100);
        s1.setParsedFromLlm(true);
        parent1.addStatement(s1);

        StringPrimitiveStatement s2 = new StringPrimitiveStatement(parent1, "llm");
        s2.setParsedFromLlm(true);
        parent1.addStatement(s2);

        NullStatement s3 = new NullStatement(parent1, Object.class);
        s3.setParsedFromLlm(true);
        parent1.addStatement(s3);

        // "Crossover" - copy statements into a new test case using direct copy()
        DefaultTestCase offspring = new DefaultTestCase();
        for (int i = 0; i < parent1.size(); i++) {
            Statement copied = parent1.getStatement(i).copy(offspring, 0);
            offspring.addStatement(copied);
        }

        assertEquals(3, offspring.size());
        for (int i = 0; i < offspring.size(); i++) {
            assertTrue(offspring.getStatement(i).isParsedFromLlm(),
                    "Statement " + i + " provenance lost during simulated crossover");
        }
    }

    @Test
    void simulatedMutationPreservesProvenance() throws Exception {
        // Simulate mutation: clone a chromosome and verify provenance on all statements
        DefaultTestCase tc = new DefaultTestCase();

        IntPrimitiveStatement s1 = new IntPrimitiveStatement(tc, 42);
        s1.setParsedFromLlm(true);
        tc.addStatement(s1);

        BooleanPrimitiveStatement s2 = new BooleanPrimitiveStatement(tc, false);
        s2.setParsedFromLlm(true);
        tc.addStatement(s2);

        ArrayStatement s3 = new ArrayStatement(tc, String[].class, new int[]{2});
        s3.setParsedFromLlm(true);
        tc.addStatement(s3);

        // Wrap in chromosome and clone (mimics what GA does)
        TestChromosome parent = new TestChromosome();
        parent.setTestCase(tc);

        TestChromosome mutant = parent.clone();
        TestCase mutantTest = mutant.getTestCase();

        assertEquals(3, mutantTest.size());
        for (int i = 0; i < mutantTest.size(); i++) {
            assertTrue(mutantTest.getStatement(i).isParsedFromLlm(),
                    "Statement " + i + " provenance lost during simulated mutation (chromosome clone)");
        }
    }

    // ----------------------------------------------------------------
    // 10. Transitive direct copy
    // ----------------------------------------------------------------

    @Test
    void transitiveCopyPreservesProvenance() {
        DefaultTestCase tc = new DefaultTestCase();
        IntPrimitiveStatement original = new IntPrimitiveStatement(tc, 1);
        original.setParsedFromLlm(true);
        tc.addStatement(original);

        // copy -> copy -> copy chain
        DefaultTestCase tc2 = new DefaultTestCase();
        Statement copy1 = original.copy(tc2, 0);
        tc2.addStatement(copy1);

        DefaultTestCase tc3 = new DefaultTestCase();
        Statement copy2 = copy1.copy(tc3, 0);
        tc3.addStatement(copy2);

        DefaultTestCase tc4 = new DefaultTestCase();
        Statement copy3 = copy2.copy(tc4, 0);

        assertTrue(copy3.isParsedFromLlm(),
                "parsedFromLlm must survive transitive direct copy() chain");
    }
}
