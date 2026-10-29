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

import org.evosuite.assertion.Assertion;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import static java.util.stream.Collectors.joining;

/**
 * Abstract superclass of test case statements.
 */
public abstract class AbstractStatement implements Statement, Serializable {

    /**
     * Compares variables by {@code equals}, as {@code equals} on statements does.
     */
    protected static final BiPredicate<VariableReference, VariableReference> EQUAL_VARIABLES = Objects::equals;

    /**
     * Compares variables by {@code same}, as {@code same} on statements does.
     */
    protected static final BiPredicate<VariableReference, VariableReference> SAME_VARIABLES = VariableReference::same;

    /**
     * Whether {@code other} is a statement of exactly the same class as this one.
     */
    protected final boolean isSameKind(Object other) {
        return other != null && getClass() == other.getClass();
    }

    /**
     * Compares two variables that may be absent: absent only matches absent.
     */
    protected static boolean matches(VariableReference first, VariableReference second,
                                     BiPredicate<VariableReference, VariableReference> eq) {
        return first == null ? second == null : eq.test(first, second);
    }

    /**
     * An interface to enable the concrete statements to use the executer/1
     * method.
     **/
    protected abstract class Executer {
        /**
         * The execute statement should, when called only execute exactly one
         * statement. For example executing java.reflect.Field.get()/1 could be
         * the responsibility of the execute method. Execute SHOULD NOT catch
         * any exceptions. Exception handling SHOULD be done by
         * AbstractStatement.executer()/1.
         */
        public abstract void execute() throws InvocationTargetException,
                IllegalArgumentException, IllegalAccessException, InstantiationException,
                CodeUnderTestException;

        /**
         * A call to this method should return a set of throwables.
         * AbstractStatement.executer()/1 will catch all exceptions thrown by
         * Executer.execute()/1. All exception in the returned set will be
         * thrown to a higher layer. If the others are thrown or returned by
         * AbstractStatement.executer()/1 is to be defined by executer()/1.
         *
         * @return .
         */
        public Set<Class<? extends Throwable>> throwableExceptions() {
            return new HashSet<>();
        }
    }

    private static final long serialVersionUID = 8993506743384548704L;

    /**
     * Constant <code>logger</code>.
     */
    protected static final Logger logger = LoggerFactory.getLogger(AbstractStatement.class);

    /**
     * The return value of this statement. Should never be null.
     */
    protected VariableReference retval;

    /**
     * Reference of the test case this statement belongs to. Should never be null.
     */
    protected final TestCase tc;

    protected Set<Assertion> assertions = new LinkedHashSet<>();

    protected String comment = "";

    /**
     * Whether this statement was originally parsed from LLM-generated code.
     * This flag is preserved through clone/copy operations so that provenance
     * tracking survives evolutionary operators (mutation, crossover).
     */
    protected boolean parsedFromLlm = false;

    /**
     * Copies provenance metadata from the given source statement to this statement.
     * Subclass {@code copy(TestCase, int)} implementations should call this on
     * the newly created statement so that provenance survives direct-copy paths
     * (mutation, crossover) that bypass {@link #clone(TestCase)}.
     *
     * @param source the statement to copy provenance from
     */
    protected static void copyProvenanceFrom(Statement target, AbstractStatement source) {
        target.setParsedFromLlm(source.parsedFromLlm);
    }

    /**
     * Constructor for AbstractStatement.
     */
    protected AbstractStatement(TestCase tc, VariableReference retval) throws IllegalArgumentException {
        if (tc == null) {
            throw new IllegalArgumentException("tc cannot be null");
        }
        if (retval == null) {
            throw new IllegalArgumentException("retval cannot be null");
        }
        this.retval = retval;
        this.tc = tc;
    }

    /**
     * Constructor for AbstractStatement.
     */
    protected AbstractStatement(TestCase tc, Type type) throws IllegalArgumentException {
        if (tc == null) {
            throw new IllegalArgumentException("tc cannot be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }

        GenericClass<?> c = GenericClassFactory.get(type);
        if (c.isArray()) {
            this.retval = new ArrayReference(tc, c, 0);
        } else {
            this.retval = new VariableReferenceImpl(tc, type);
        }
        this.tc = tc;
    }

    /**
     * This method abstracts the exception handling away from the concrete
     * statements. Thereby hopefully enabling us to have a more consistent
     * approach to exceptions.
     */
    protected Throwable exceptionHandler(Executer code) throws InvocationTargetException,
            IllegalArgumentException, IllegalAccessException, InstantiationException {
        try {
            code.execute();
        } catch (CodeUnderTestException e) {
            return e;
        } catch (EvosuiteError e) {
            /*
             * Signal an error in evosuite code and are therefore always thrown
             */
            throw e;
        } catch (OutOfMemoryError e) {
            // Force immediate GC to reclaim the failed allocation (e.g. a
            // DefaultTableModel that tried to create 86M Vector objects).
            // Without this, the dead objects remain on the heap until the next
            // allocation triggers GC — by which time the Linux OOM killer may
            // have already terminated the process.
            System.gc();
            return e;
        } catch (Error | RuntimeException | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            if (isAssignableFrom(e, code.throwableExceptions())) {
                throw e;
            } else {
                return e;
            }
        }

        return null;
    }

    /**
     * Tests if concreteThrowable.getClass is assignable to any of the classes
     * in throwableClasses.
     *
     * @param concreteThrowable true if concreteThrowable is assignable
     * @param throwableClasses  the throwable classes.
     * @return true if assignable
     */
    private boolean isAssignableFrom(Throwable concreteThrowable,
                                     Set<Class<? extends Throwable>> throwableClasses) {
        final Class<? extends Throwable> clazz = concreteThrowable.getClass();
        return throwableClasses.stream().anyMatch(t -> t.isAssignableFrom(clazz));
    }

    @Override
    public boolean references(VariableReference var) {
        return getVariableReferences().contains(var);
    }

    /**
     * getAssertionReferences.
     */
    protected Set<VariableReference> getAssertionReferences() {
        Set<VariableReference> variables = new LinkedHashSet<>();
        for (Assertion assertion : assertions) {
            variables.addAll(assertion.getReferencedVariables());
        }
        return variables;
    }

    @Override
    public void setRetval(VariableReference newRetVal) {
        this.retval = Objects.requireNonNull(newRetVal, "newRetVal cannot be null");
    }

    @Override
    public List<VariableReference> getUniqueVariableReferences() {
        return new ArrayList<>(getVariableReferences());
    }

    @Override
    public String getCode() {
        return getCode(null);
    }

    @Override
    public String getCode(Throwable exception) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        visitor.setException(this, exception);
        visitor.visitStatement(this);
        String code = visitor.getCode();
        // A statement can legitimately render to nothing at all (e.g. an assignment
        // whose target turned out to be a final field, which TestCodeVisitor drops
        // rather than emit as illegal code); don't assume a trailing newline to strip.
        return code.length() >= 2 ? code.substring(0, code.length() - 2) : code;
    }

    @Override
    public void addComment(String comment) {
        this.comment += comment;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public final Statement clone() {
        throw new UnsupportedOperationException("Statement.clone() is not supported."
                + " Use Statement.clone(TestCase) instead.");
    }

    @Override
    public Type getReturnType() {
        return retval.getType();
    }

    @Override
    public Class<?> getReturnClass() {
        return retval.getVariableClass();
    }

    @Override
    public VariableReference getReturnValue() {
        return retval;
    }

    @Override
    public int getNumParameters() {
        return 0;
    }

    @Override
    public TestCase getTestCase() {
        return tc;
    }

    /**
     * Create copies of all attached assertions.
     *
     * @param newTestCase the testcase in which this statement will be inserted
     */
    @Override
    public Set<Assertion> copyAssertions(TestCase newTestCase, int offset) {
        Set<Assertion> copy = new LinkedHashSet<>();
        for (Assertion a : assertions) {
            if (a != null) {
                copy.add(a.copy(newTestCase, offset));
            }
        }
        return copy;
    }

    @Override
    public boolean hasAssertions() {
        return !assertions.isEmpty();
    }

    @Override
    public void addAssertion(Assertion assertion) {
        if (assertion == null) {
            logger.warn("Trying to add null assertion!");
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Adding assertion " + assertion.getCode());
            }
            assert (assertion.isValid()) : "Invalid assertion detected: "
                    + assertion.getCode() + ", " + assertion.getSource() + ", "
                    + assertion.getValue();
            assertion.setStatement(this);
            assertions.add(assertion);
        }
    }

    @Override
    public void setAssertions(Set<Assertion> assertions) {
        assertions.forEach(a -> a.setStatement(this));
        this.assertions = assertions;
    }

    @Override
    public void removeAssertions() {
        assertions.clear();
    }

    @Override
    public void removeAssertion(Assertion assertion) {
        assertions.remove(assertion);
    }

    @Override
    public Set<Assertion> getAssertions() {
        return assertions;
    }

    @Override
    public Set<Class<?>> getDeclaredExceptions() {
        return new HashSet<>();
    }

    /**
     * getExceptionClass.
     */
    public static Class<?> getExceptionClass(Throwable t) {
        Class<?> clazz = t.getClass();
        while (!Modifier.isPublic(clazz.getModifiers())) {
            clazz = clazz.getSuperclass();
        }
        return clazz;
    }

    @Override
    public int getPosition() {
        return retval.getStPosition();
    }

    @Override
    public boolean isAccessible() {
        return getVariableReferences().stream().allMatch(VariableReference::isAccessible);
    }

    @Override
    public boolean isValid() {
        retval.getStPosition();
        return true;
    }

    @Override
    public boolean isDeclaredException(Throwable t) {
        return false;
    }

    @Override
    public boolean mutate(TestCase test, TestFactory factory) {
        return false;
    }

    @Override
    public Statement clone(TestCase newTestCase) {
        Statement result = copy(newTestCase, 0);
        result.getReturnValue().setOriginalCode(retval.getOriginalCode());
        result.addComment(getComment());
        copyProvenanceFrom(result, this);
        return result;
    }

    @Override
    public void changeClassLoader(ClassLoader loader) {
        getVariableReferences().forEach(v -> v.changeClassLoader(loader));
        assertions.forEach(a -> a.changeClassLoader(loader));
    }

    @Override
    public boolean isReflectionStatement() {
        return false;
    }

    @Override
    public boolean isParsedFromLlm() {
        return parsedFromLlm;
    }

    @Override
    public void setParsedFromLlm(boolean parsedFromLlm) {
        this.parsedFromLlm = parsedFromLlm;
    }
}
