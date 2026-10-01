/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase.statements;

import org.apache.commons.lang3.ClassUtils;
import org.evosuite.Properties;
import org.evosuite.setup.TestClusterGenerator;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.*;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.evosuite.utils.generic.GenericClassUtils;
import org.evosuite.utils.generic.GenericField;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An assignment statement assigns values to variables. This is only used to assign to array indices.
 * or to public member variables of objects, e.g., {@code var1[0] = 42} or {@code var2.maxSize =
 * 1337}. Assignment statements do not define new values.
 *
 * @author Gordon Fraser
 */
public class AssignmentStatement extends AbstractStatement {

    private static final long serialVersionUID = 2051431241124468349L;

    protected VariableReference parameter;

    /**
     * <p>
     * Constructor for AssignmentStatement.
     * </p>
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param var   a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param value a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public AssignmentStatement(TestCase tc, VariableReference var, VariableReference value) {
        super(tc, var);
        rejectFinalFieldTarget(var);
        this.parameter = value;

        // TODO:
        // Assignment of an "unassignable" type may happen if we have no generator for
        // the target class, as we then attempt generating a superclass and try to case
        // down to the actual class
        //
    }

    /**
     * Refuses to let an assignment statement target a final field.
     *
     * <p>
     * A direct write to a final field is never legal Java, whether or not EvoSuite's
     * instrumentation has stripped {@code ACC_FINAL} from the bytecode to support the
     * bytecode-level {@code __STATIC_RESET()} static-state machinery: that machinery
     * never has to compile as Java source, but a generated test statement does. This is
     * the single choke point for statement construction: {@link StatementFactory} and
     * DMoN's field selectors ({@code DmonInjectionDiscovery},
     * {@code DmonPromotionOperator}) already screen their own generation paths before
     * doing the (possibly expensive) work of building the callee/value, but several
     * other callers (test carving, symbolic-execution test building, the test parser,
     * object-construction synthesis) construct {@code AssignmentStatement}s directly
     * and have no such pre-check, so this backstop still matters.
     * </p>
     *
     * <p>
     * Also reused by {@link #setRetval(VariableReference)}, which repoints an
     * already-constructed statement's target and so needs the identical check: here
     * {@code retval} is unambiguously the assignment target (unlike, say,
     * {@code FieldStatement}'s retval, which is a freshly declared local that merely
     * holds the value read from a field), so guarding it narrowly in this subclass is
     * safe without touching {@link AbstractStatement#setRetval}, which other statement
     * types also use and whose retval does not always mean "assignment target".
     * </p>
     *
     * @param target the statement's assignment target
     */
    private static void rejectFinalFieldTarget(VariableReference target) {
        if (!(target instanceof FieldReference)) {
            return;
        }
        GenericField field = ((FieldReference) target).getField();
        if (field == null || !field.isFinal()) {
            return;
        }
        String message = "Refusing to construct an assignment statement targeting final field "
                + field.getDeclaringClass().getName() + "." + field.getName()
                + "; direct writes to final fields are never legal Java";
        logger.error(message);
        throw new IllegalArgumentException(message);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Closes the gap the constructor guard alone leaves open: {@link #setRetval} is a
     * public API that can repoint an already-constructed statement's target without
     * ever going through {@link #AssignmentStatement(TestCase, VariableReference,
     * VariableReference) the constructor}, so it needs the same
     * {@link #rejectFinalFieldTarget(VariableReference)} check, thrown the same way
     * (logged at ERROR, {@link IllegalArgumentException}) for the same reason: a direct
     * write to a final field is never legal Java.
     * </p>
     */
    @Override
    public void setRetval(VariableReference newRetVal) {
        rejectFinalFieldTarget(newRetVal);
        super.setRetval(newRetVal);
    }

    /**
     * getValue.
     *
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference getValue() {
        return this.parameter;
    }

    /**
     * setValue.
     *
     * @param value the value.
     */
    public void setValue(VariableReference value) {
        this.parameter = value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        try {
            VariableReference newParam = parameter.copy(newTestCase, offset);
            VariableReference newTarget;

            // FIXXME: Return value should always be an existing variable
            newTarget = retval.copy(newTestCase, offset);

            // If retval is already an illegal final-field target (it should never be -
            // both known creation paths, StatementFactory and DMoN promotion, are
            // guarded, and a deserialized offender is rejected at the classloader-
            // reattachment boundary in changeClassLoader() before it can reach a live
            // population), this constructor call rethrows via rejectFinalFieldTarget()
            // and is deliberately NOT caught/softened here: DefaultTestCase.clone()
            // explicitly relies on AssignmentStatement.copy() preserving retval's
            // identity with the pre-seeded placeholder at the defining position (see
            // its comment about "wrong position N, total N"), so synthesizing some
            // other statement in its place to avoid the crash would risk silently
            // corrupting the clone's statement graph instead - a loud, unconditional
            // failure here is safer than a quiet, wrong one.
            AssignmentStatement copy = new AssignmentStatement(newTestCase, newTarget,
                    newParam);
            copyProvenanceFrom(copy, this);

            return copy;
        } catch (Exception e) {
            logger.info("Error cloning statement " + getCode());
            logger.info("In test: " + this.tc.toCode());
            logger.info("New test: " + newTestCase.toCode());
            throw new EvosuiteError(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable execute(final Scope scope, PrintStream out)
            throws InvocationTargetException, IllegalArgumentException,
            IllegalAccessException, InstantiationException {

        return super.exceptionHandler(new Executer() {

            @Override
            public void execute() throws InvocationTargetException,
                    IllegalArgumentException, IllegalAccessException,
                    InstantiationException, CodeUnderTestException {
                try {
                    final Object value = parameter.getObject(scope);

                    if (checkNullDereference(scope)) {
                        throw new CodeUnderTestException(new NullPointerException());
                    }

                    retval.setObject(scope, value);
                } catch (IllegalArgumentException e) {
                    logger.error("Error assigning value of type "
                            + parameter.getSimpleClassName() + " defined at statement "
                            + tc.getStatement(parameter.getStPosition()).getCode()
                            + ", assignment statement: "
                            + tc.getStatement(retval.getStPosition()).getCode()
                            + "; SUT=" + Properties.TARGET_CLASS);

                    // FIXXME: IllegalArgumentException may happen when we only have generators
                    // for an abstract supertype and not the concrete type that we need!
                    // We rethrow it so that the execution framework can handle it
                    // (e.g., by marking the test as failing or invalid)
                    throw e;
                } catch (CodeUnderTestException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new EvosuiteError(e);
                }
            }

            /**
             * Returns true of the retval of the assignment is a field reference (i.e. expr.f)
             * such that expr==null.
             *
             * @param scope the scope.
             * @return .
             * @throws CodeUnderTestException (cause is NullPointerException)
             */
            private boolean checkNullDereference(final Scope scope) throws CodeUnderTestException {
                if (retval instanceof FieldReference) {
                    FieldReference fieldRef = (FieldReference) retval;

                    if (fieldRef.getField().isStatic()) {
                        return false;
                    }

                    VariableReference source = fieldRef.getSource();
                    Object sourceValue = source.getObject(scope);
                    return sourceValue == null;
                }
                return false;
            }

            @Override
            public Set<Class<? extends Throwable>> throwableExceptions() {
                Set<Class<? extends Throwable>> t = new LinkedHashSet<>();
                t.add(AssertionError.class);
                return t;
            }
        });

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<VariableReference> getVariableReferences() {
        Set<VariableReference> vars = new LinkedHashSet<>();
        vars.add(retval);
        vars.add(parameter);

        if (retval.getAdditionalVariableReference() != null) {
            vars.add(retval.getAdditionalVariableReference());
        }
        if (parameter.getAdditionalVariableReference() != null) {
            vars.add(parameter.getAdditionalVariableReference());
        }
        vars.addAll(getAssertionReferences());

        return vars;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.StatementInterface#replace(VariableReference, VariableReference)
     */

    @Override
    public void replace(VariableReference var1, VariableReference var2) {
        if (retval.equals(var1)) {
            if (isAssignable(var2, parameter)) {
                retval = var2;
            }
        }
        if (parameter.equals(var1)) {
            if (isAssignable(retval, var2)) {
                parameter = var2;
            }
        } else {
            parameter.replaceAdditionalVariableReference(var1, var2);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = prime + retval.hashCode()
                + +((parameter == null) ? 0 : parameter.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AssignmentStatement other = (AssignmentStatement) obj;
        if (parameter == null) {
            if (other.parameter != null) {
                return false;
            }
        } else {
            if (!parameter.equals(other.parameter)) {
                return false;
            }
        }
        if (retval == null) {
            return other.retval == null;
        } else {
            return retval.equals(other.retval);
        }
    }



    /* (non-Javadoc)
     * @see org.evosuite.testcase.StatementInterface#isValid()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isValid() {
        assert (super.isValid());
        if (retval instanceof ArrayIndex) {
            ArrayIndex arrayIndex = (ArrayIndex) retval;
            ArrayReference arrayRef = arrayIndex.getArray();

            List<Integer> lengths = arrayRef.getStructuralLengths();
            List<Integer> indices = arrayIndex.getArrayIndices();
            if (indices.isEmpty() || lengths.isEmpty()) {
                return false;
            }

            // Upper-bound checks are only reliable when the array comes from an
            // explicit ArrayStatement declaration in the test model. Arrays returned
            // by method calls/fields do not carry trustworthy structural lengths and
            // default to 0, which would incorrectly invalidate legitimate assignments
            // such as enum values()[0] = ....
            boolean hasKnownStructuralBounds = hasArrayStatementDefinition(arrayRef);

            // Validate each indexed dimension against known structural lengths.
            // For jagged arrays we may not have complete nested lengths; in that case
            // we still enforce non-negative indices and only check upper bounds for
            // dimensions with known lengths.
            int comparableDims = Math.min(indices.size(), lengths.size());
            for (int dim = 0; dim < indices.size(); dim++) {
                int index = indices.get(dim);
                if (index < 0) {
                    return false;
                }
                if (hasKnownStructuralBounds && dim < comparableDims) {
                    int length = lengths.get(dim);
                    if (index >= length) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean hasArrayStatementDefinition(ArrayReference arrayRef) {
        for (Statement statement : tc) {
            if (statement.getReturnValue().equals(arrayRef)) {
                return statement instanceof ArrayStatement;
            }
        }
        return false;
    }

    private static boolean isAssignable(VariableReference target, VariableReference value) {
        return GenericClassUtils.isAssignable(target.getType(), value.getType());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean same(Statement s) {
        if (this == s) {
            return true;
        }
        if (s == null) {
            return false;
        }
        if (getClass() != s.getClass()) {
            return false;
        }

        AssignmentStatement other = (AssignmentStatement) s;
        if (parameter == null) {
            if (other.parameter != null) {
                return false;
            }
        } else {
            if (!parameter.same(other.parameter)) {
                return false;
            }
        }
        if (retval == null) {
            return other.retval == null;
        } else {
            return retval.same(other.retval);
        }
    }

    /**
     * Retrieve the set of FieldReference and ArrayIndex variables that can
     * serve as a replacement for retval.
     *
     * @return .
     */
    private Set<VariableReference> getSourceReplacements() {
        Set<VariableReference> variables = new LinkedHashSet<>();
        for (int i = 0; i < retval.getStPosition() && i < tc.size(); i++) {
            VariableReference value = tc.getReturnValue(i);
            if (value == null) {
                continue;
            }
            if (value instanceof ArrayReference) {
                if (GenericClassUtils.isAssignable(value.getComponentType(),
                        parameter.getType())) {
                    // Use the structural length from the ArrayStatement rather than the
                    // runtime ArrayReference length, which Scope.setObject() can corrupt
                    // during test execution (e.g. via array reassignment).
                    int length = getStructuralArrayLength((ArrayReference) value);
                    for (int index = 0; index < length; index++) {
                        variables.add(new ArrayIndex(tc, (ArrayReference) value, index));
                    }
                }
            } else if (value instanceof ArrayIndex) {
                // Don't need to add this because array indices are created for array statement?
                if (GenericClassUtils.isAssignable(value.getType(), parameter.getType())) {
                    variables.add(value);
                }
            } else {
                if (!value.isPrimitive() && !(value instanceof NullReference)) {
                    // add fields of this object to list
                    for (Field field : TestClusterUtils.getAccessibleFields(value.getVariableClass())) {
                        if (TestClusterGenerator.isFinalField(field)) {
                            continue;
                        }
                        FieldReference f = new FieldReference(tc, new GenericField(field,
                                value.getGenericClass()), value);
                        if (f.getDepth() <= 2) {
                            if (GenericClassUtils.isAssignable(f.getType(), parameter.getType())) {
                                variables.add(f);
                            }
                        }
                    }
                }
            }
        }
        return variables;
    }

    /**
     * Get the structural array length from the corresponding {@link ArrayStatement},
     * which is immune to runtime corruption by {@code Scope.setObject()}.
     * Falls back to the runtime length if no matching ArrayStatement is found.
     */
    private int getStructuralArrayLength(ArrayReference arrayRef) {
        return arrayRef.getStructuralArrayLength();
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.StatementInterface#mutate(org.evosuite.testcase.TestCase)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean mutate(TestCase test, TestFactory factory) {
        assert (isValid());

        // Either mutate parameter, or source
        if (Randomness.nextDouble() < 0.5) {
            Set<VariableReference> objects = getSourceReplacements();
            objects.remove(retval);
            objects.remove(parameter);

            if (!objects.isEmpty()) {
                VariableReference newRetVal = Randomness.choice(objects);
                // Need to double check, because we might try to replace e.g.
                // a long with an int, which is assignable
                // but if the long is assigned to a Long field, then it is not!
                if (GenericClassUtils.isAssignable(newRetVal.getType(), parameter.getType())) {

                    // Need to check array status because commons lang
                    // is sometimes confused about what is assignable
                    if (parameter.isArray() == newRetVal.isArray()) {
                        // Check that no later statement depends on the old retval
                        VariableReference oldRetval = retval;
                        boolean hasDownstreamUse = false;
                        for (int i = oldRetval.getStPosition() + 1; i < tc.size(); i++) {
                            if (tc.getStatement(i).references(oldRetval)) {
                                hasDownstreamUse = true;
                                break;
                            }
                        }
                        if (hasDownstreamUse) {
                            return false; // Cannot safely change target
                        }
                        retval = newRetVal;
                        assert (isValid());
                        return true;
                    }
                }
            }

        } else {

            List<VariableReference> objects = test.getObjects(parameter.getType(),
                    retval.getStPosition());
            objects.remove(retval);
            objects.remove(parameter);
            if (!objects.isEmpty()) {
                VariableReference choice = Randomness.choice(objects);
                if (GenericClassUtils.isAssignable(retval.getType(), choice.getType())) {

                    // Need special care if it is a wrapper class
                    if (retval.getGenericClass().isWrapperType()) {
                        Class<?> rawClass = ClassUtils.wrapperToPrimitive(retval.getVariableClass());
                        if (!retval.getVariableClass().equals(rawClass)
                                && !retval.getVariableClass().equals(choice.getVariableClass())) {
                            return false;
                        }
                    }

                    parameter = choice;
                    assert (isValid());

                    return true;
                }
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@link GenericField#readObject} re-resolves its target {@link Field} from the
     * archived declaring-class name and field name on every deserialization, but never
     * re-checks finality: the field it resolves to may now be final even though the
     * live field was not when this statement was constructed (or may always have been
     * final, if this statement reached a serialized artifact by some path other than
     * the guarded constructor). {@link #changeClassLoader} is where every deserialized
     * test case gets its statements reattached to a concrete classloader (see
     * {@code TestSuiteSerialization#loadTests} and the replay rebind in
     * {@code TestSuiteGenerator}), so it is the natural choke point to revalidate a
     * reconstructed field-write statement: fail loudly here rather than let the
     * renderer silently rebuild an illegal direct assignment. The caller
     * ({@code TestSuiteSerialization#loadTests}) already catches a
     * {@link RuntimeException} per test case and drops just that one, so one corrupted
     * statement costs a single test rather than the whole stream.
     * </p>
     */
    @Override
    public void changeClassLoader(ClassLoader loader) {
        super.changeClassLoader(loader);
        if (retval instanceof FieldReference) {
            GenericField field = ((FieldReference) retval).getField();
            if (field != null && field.isFinal()) {
                String message = "Rejecting a reconstructed assignment statement that targets "
                        + "final field " + field.getDeclaringClass().getName() + "."
                        + field.getName() + " after a classloader change; direct writes to "
                        + "final fields are never legal Java";
                logger.error(message);
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GenericAccessibleObject<?> getAccessibleObject() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAssignmentStatement() {
        return true;
    }

    @Override
    public int getPosition() {
        int pos = 0;
        for (Statement s : getTestCase()) {
            if (this == s) {
                return pos;
            }
            pos++;
        }
        throw new RuntimeException("Could not find position of assignment statement");
    }
}
