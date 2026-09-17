package org.evosuite.testcase;

import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Reproduces two corruptions found on Defects4J Lang-15 (org.apache.commons.lang3.reflect.TypeUtils),
 * both in {@code TestMutator.deleteStatementGracefully}: repairing references to a statement
 * being deleted could pick an {@link AssignmentStatement}'s own retval as the replacement for its
 * parameter (or vice versa), aliasing retval and parameter to the very same
 * {@link VariableReference}. Such a statement can never be declared correctly: the retval's
 * defining position no longer matches the statement's own position, so
 * {@code TestCodeVisitor} treats it as a reassignment to an already-declared variable and omits
 * the declaration, emitting uncompilable code such as {@code "class0_1 = class0_1;"} followed by
 * a use of the never-declared {@code class0_1}.
 */
public class TestMutatorAssignmentSelfAliasTest {

    private static final Pattern SELF_ASSIGNMENT = Pattern.compile("(?m)^\\s*(\\w+) = \\1;");

    /**
     * First source: deleting a plain variable that is directly one side of an
     * {@link AssignmentStatement} (the "change all references" loop in
     * {@code deleteStatementGracefully}).
     */
    @Test
    public void deletingReferencedStatementNeverAliasesAssignmentRetvalAndParameter()
            throws ConstructionFailedException {
        DefaultTestCase test = new DefaultTestCase();

        // pos 0: the only compatible pre-existing variable -- also this assignment's retval.
        VariableReference retvalVar = test.addStatement(new StringPrimitiveStatement(test, "kept"));
        // pos 1: the variable that is about to be deleted.
        VariableReference victim = test.addStatement(new StringPrimitiveStatement(test, "victim"));
        // pos 2: retval = victim -- a plain-variable assignment whose only reference to a
        // pre-existing, type-compatible replacement is its own retval.
        AssignmentStatement assignment = new AssignmentStatement(test, retvalVar, victim);
        test.addStatement(assignment);

        assertTrue(test.isValid());

        TestFactory.getInstance().deleteStatementGracefully(test, victim.getStPosition());

        assertNoSelfAliasedAssignment(test);
    }

    /**
     * Second source: deleting an array whose elements are referenced by an
     * {@link ArrayIndex} that is one side of an {@link AssignmentStatement} (the
     * array-component-type "change all references" loop in
     * {@code deleteStatementGracefully}, guarded separately from the first).
     */
    @Test
    public void deletingArrayNeverAliasesAssignmentRetvalAndParameterViaArrayIndex()
            throws ConstructionFailedException {
        DefaultTestCase test = new DefaultTestCase();

        // pos 0: the only compatible pre-existing variable -- also this assignment's retval.
        VariableReference retvalVar = test.addStatement(new StringPrimitiveStatement(test, "kept"));
        // pos 1: the array about to be deleted.
        ArrayStatement arrayStatement = new ArrayStatement(test, String[].class, 2);
        VariableReference arrayRef = test.addStatement(arrayStatement);
        // pos 2: retval = array[0] -- the parameter is an ArrayIndex into the array being
        // deleted, and the only pre-existing, type-compatible replacement for that index is
        // this statement's own retval.
        ArrayIndex arrayIndex = new ArrayIndex(test, (ArrayReference) arrayRef, 0);
        AssignmentStatement assignment = new AssignmentStatement(test, retvalVar, arrayIndex);
        test.addStatement(assignment);

        assertTrue(test.isValid());

        TestFactory.getInstance().deleteStatementGracefully(test, arrayRef.getStPosition());

        assertNoSelfAliasedAssignment(test);
    }

    private static void assertNoSelfAliasedAssignment(TestCase test) {
        assertTrue(test.isValid());

        for (Statement statement : test) {
            if (statement instanceof AssignmentStatement) {
                AssignmentStatement remaining = (AssignmentStatement) statement;
                assertFalse("AssignmentStatement retval must never alias its own parameter",
                        remaining.getReturnValue() == remaining.getValue());
            }
        }

        // Guard the observable symptom too: the rendered code must declare every variable it
        // uses, i.e. it must never contain a self-assignment to an undeclared variable.
        String code = test.toCode();
        Matcher matcher = SELF_ASSIGNMENT.matcher(code);
        assertFalse("generated code must not contain a self-assignment like 'x = x;': " + code,
                matcher.find());
    }
}
