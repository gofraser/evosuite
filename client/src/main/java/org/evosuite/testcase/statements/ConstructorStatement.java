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
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.dse.VM;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.UncompilableCodeException;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.objectweb.asm.Type;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * This statement represents a constructor call, generating a new instance of any given class, e.g.,.
 * {@code Stack stack = new Stack()}. Value and type of the of the statement are defined by the
 * object constructed in the call.
 *
 * @author Gordon Fraser
 */
public class ConstructorStatement extends EntityWithParametersStatement {

    private static final long serialVersionUID = -3035570485633271957L;

    private GenericConstructor constructor;

    private static final List<String> primitiveClasses = Arrays.asList("char", "int", "short",
            "long", "boolean",
            "float", "double",
            "byte");

    /**
     * <p>
     * Constructor for ConstructorStatement.
     * </p>
     *
     * @param tc          a {@link org.evosuite.testcase.TestCase} object.
     * @param constructor a {@link java.lang.reflect.Constructor} object.
     * @param parameters  a {@link java.util.List} object.
     */
    public ConstructorStatement(TestCase tc, GenericConstructor constructor,
                                List<VariableReference> parameters) {
        super(tc, new VariableReferenceImpl(tc, constructor.getOwnerClass()), parameters,
                constructor.getConstructor().getAnnotations(), constructor.getConstructor().getParameterAnnotations());
        this.constructor = constructor;
    }

    /**
     * This constructor allows you to use an already existing VariableReference
     * as retvar. This should only be done, iff an old statement is replaced
     * with this statement. And already existing objects should in the future
     * reference this object.
     *
     * @param tc          a {@link org.evosuite.testcase.TestCase} object.
     * @param constructor a {@link java.lang.reflect.Constructor} object.
     * @param retvar      a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param parameters  a {@link java.util.List} object.
     */
    public ConstructorStatement(TestCase tc, GenericConstructor constructor,
                                VariableReference retvar, List<VariableReference> parameters) {
        super(tc, retvar, parameters,
                constructor.getConstructor().getAnnotations(), constructor.getConstructor().getParameterAnnotations());
        assert (tc.size() > retvar.getStPosition()); //as an old statement should be replaced by this statement
        this.constructor = constructor;
    }

    /**
     * <p>
     * Constructor for ConstructorStatement.
     * </p>
     *
     * @param tc          a {@link org.evosuite.testcase.TestCase} object.
     * @param constructor a {@link java.lang.reflect.Constructor} object.
     * @param retvar      a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param parameters  a {@link java.util.List} object.
     * @param check       a boolean.
     */
    protected ConstructorStatement(TestCase tc, GenericConstructor constructor,
                                   VariableReference retvar, List<VariableReference> parameters, boolean check) {
        super(tc, retvar, parameters,
                constructor.getConstructor().getAnnotations(), constructor.getConstructor().getParameterAnnotations());
        assert !check;
        this.constructor = constructor;
    }

    /**
     * Getter for the field <code>constructor</code>.
     *
     * @return a {@link java.lang.reflect.Constructor} object.
     */
    public GenericConstructor getConstructor() {
        return constructor;
    }

    /**
     * Setter for the field <code>constructor</code>.
     *
     * @param constructor a {@link java.lang.reflect.Constructor} object.
     */
    public void setConstructor(GenericConstructor constructor) {
        this.constructor = constructor;
        retval.setType(constructor.getReturnType());
    }

    /**
     * getReturnType.
     *
     * @param clazz a {@link java.lang.Class} object.
     * @return a {@link java.lang.String} object.
     */
    public static String getReturnType(Class<?> clazz) {
        String retVal = ClassUtils.getShortClassName(clazz);
        if (primitiveClasses.contains(retVal)) {
            return clazz.getSimpleName();
        }

        return retVal;
    }

    // TODO: Handle inner classes (need instance parameter for newInstance)

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable execute(final Scope scope, PrintStream out)
            throws InvocationTargetException, IllegalArgumentException,
            InstantiationException, IllegalAccessException {
        logger.trace("Executing constructor " + constructor.toString());
        final Object[] inputs = new Object[parameters.size()];
        Throwable exceptionThrown = null;

        try {
            return super.exceptionHandler(new Executer() {

                @Override
                public void execute() throws InvocationTargetException,
                        IllegalArgumentException, IllegalAccessException,
                        InstantiationException, CodeUnderTestException {

                    java.lang.reflect.Type[] parameterTypes = constructor.getParameterTypes();
                    for (int i = 0; i < parameters.size(); i++) {
                        VariableReference parameterVar = parameters.get(i);
                        try {
                            inputs[i] = parameterVar.getObject(scope);
                        } catch (CodeUnderTestException e) {
                            throw e;
                        } catch (Throwable e) {
                            //FIXME: this does not seem to propagate to client root. Is this normal behavior?
                            logger.error("Class " + Properties.TARGET_CLASS
                                    + ". Error encountered: " + e);
                            throw new EvosuiteError(e);
                        }
                        if (inputs[i] != null && !TypeUtils.isAssignable(inputs[i].getClass(),
                                parameterTypes[i])) {
                            // TODO: This used to be a check of the declared type, but the problem is that
                            //       Generic types are not updated during execution, so this may fail:
                            //!parameterVar.isAssignableTo(parameterTypes[i])) {
                            throw new CodeUnderTestException(
                                    new UncompilableCodeException("Cannot assign "
                                            + parameterVar.getVariableClass().getName()
                                            + " to " + parameterTypes[i]));
                        }
                        if (inputs[i] == null && constructor.getConstructor().getParameterTypes()[i].isPrimitive()) {
                            throw new CodeUnderTestException(new NullPointerException());
                        }

                    }

                    // If this is a non-static member class, the first parameter must not be null
                    if (constructor.getConstructor().getDeclaringClass().isMemberClass()
                            && !Modifier.isStatic(constructor.getConstructor().getDeclaringClass()
                            .getModifiers())) {
                        if (inputs[0] == null) {
                            // throw new NullPointerException();
                            throw new CodeUnderTestException(new NullPointerException());
                        }
                    }

                    rejectAbsurdIntInputs(inputs);
                    rejectLargeIntInputs(inputs);
                    rejectDynamicMethodThreshold(inputs);

                    Object ret = constructor.getConstructor().newInstance(inputs);

                    try {
                        retval.setObject(scope, ret);
                    } catch (CodeUnderTestException e) {
                        throw e;
                    } catch (Throwable e) {
                        throw new EvosuiteError(e);
                    }
                }

                @Override
                public Set<Class<? extends Throwable>> throwableExceptions() {
                    Set<Class<? extends Throwable>> t = new LinkedHashSet<>();
                    t.add(InvocationTargetException.class);
                    return t;
                }
            });

        } catch (InvocationTargetException e) {
            VM.disableCallBacks();
            exceptionThrown = e.getCause();
            if (logger.isDebugEnabled()) {
                try {
                    logger.debug("Exception thrown in constructor: " + e.getCause());
                } catch (Exception ex) {
                    //this can happen if SUT throws exception on toString
                    logger.debug("Exception thrown in constructor and SUT gives issue "
                            + "when calling e.getCause()", ex);
                }
            }
        }
        return exceptionThrown;
    }

    /**
     * Sanity check for constructors of allocation-sensitive classes: rejects
     * individual int args ≥ ABSURD_INT_THRESHOLD, and rejects when the product
     * of all positive int args exceeds the threshold. Only applies to classes
     * known to allocate memory proportional to int arguments. This catches
     * multi-dimensional allocations like DefaultTableModel(34775, 34775) = 1.2B.
     */
    private static final long ABSURD_INT_THRESHOLD = Integer.MAX_VALUE / 2L;

    private void rejectAbsurdIntInputs(Object[] inputs) throws CodeUnderTestException {
        if (!org.evosuite.testcase.StatementFactory.isAllocationSensitive(
                constructor.getRawGeneratedType())) {
            return;
        }
        Class<?>[] paramTypes = constructor.getConstructor().getParameterTypes();
        long product = 1;
        boolean hasPositiveInt = false;
        for (int i = 0; i < paramTypes.length && i < inputs.length; i++) {
            if (paramTypes[i].equals(int.class) && inputs[i] instanceof Integer) {
                int val = (Integer) inputs[i];
                if (val >= ABSURD_INT_THRESHOLD || val <= -ABSURD_INT_THRESHOLD) {
                    logger.info("Rejecting constructor {} with absurd int arg {} = {}",
                            constructor.getDeclaringClass().getName(), i, val);
                    throw new CodeUnderTestException(
                            new TestCaseExecutor.TimeoutExceeded());
                }
                if (val > 0) {
                    product *= val;
                    hasPositiveInt = true;
                }
            }
        }
        if (hasPositiveInt && product > ABSURD_INT_THRESHOLD) {
            logger.info("Rejecting constructor {}: product of int args = {} exceeds {}",
                    constructor.getDeclaringClass().getName(), product, ABSURD_INT_THRESHOLD);
            throw new CodeUnderTestException(
                    new TestCaseExecutor.TimeoutExceeded());
        }
    }

    /**
     * Rejects execution of allocation-sensitive constructors when the effective
     * allocation size would exceed the capacity limit. Computes effective size as
     * the product of all "dimension" factors: int args and collection/array sizes.
     *
     * <p>This handles cases like DefaultTableModel(Vector columnNames, int rowCount)
     * where allocation = rowCount × columnNames.size().</p>
     */
    private void rejectLargeIntInputs(Object[] inputs) throws CodeUnderTestException {
        if (!org.evosuite.testcase.StatementFactory.isAllocationSensitive(
                constructor.getRawGeneratedType())) {
            logger.debug("rejectLargeIntInputs: {} is NOT allocation-sensitive, skipping",
                    constructor.getRawGeneratedType().getName());
            return;
        }
        logger.debug("rejectLargeIntInputs: checking {} with {} inputs",
                constructor.getRawGeneratedType().getName(), inputs.length);
        Class<?>[] paramTypes = constructor.getConstructor().getParameterTypes();
        int limit = Properties.COLLECTION_CAPACITY_LIMIT;

        // Compute effective allocation size as the product of all dimension factors:
        // - int args contribute their value
        // - Collection args contribute their size
        // - array args contribute their length
        long product = 1;
        boolean hasFactor = false;
        for (int i = 0; i < paramTypes.length && i < inputs.length; i++) {
            long factor = 0;
            if (paramTypes[i].equals(int.class) && inputs[i] instanceof Integer) {
                int val = (Integer) inputs[i];
                if (val > limit || val < -limit) {
                    logger.info("Rejecting allocation-sensitive constructor {}: "
                            + "int arg {} = {} exceeds limit {}",
                            constructor.getDeclaringClass().getName(), i, val, limit);
                    throw new CodeUnderTestException(
                            new TestCaseExecutor.TimeoutExceeded());
                }
                if (val > 0) {
                    factor = val;
                }
            } else if (inputs[i] instanceof java.util.Collection) {
                factor = ((java.util.Collection<?>) inputs[i]).size();
            } else if (inputs[i] != null && inputs[i].getClass().isArray()) {
                factor = java.lang.reflect.Array.getLength(inputs[i]);
            }
            if (factor > 0) {
                product *= factor;
                hasFactor = true;
                if (product > limit) {
                    rejectConstructor("effective allocation size", product, limit);
                }
            }
        }

        // Check TableModel arguments — JTable(TableModel) and similar constructors
        // query the model's dimensions during construction, so a mock returning
        // huge values from getRowCount()/getColumnCount() causes OOM.
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] instanceof javax.swing.table.TableModel) {
                javax.swing.table.TableModel model = (javax.swing.table.TableModel) inputs[i];
                try {
                    long rows = model.getRowCount();
                    long cols = model.getColumnCount();
                    logger.debug("TableModel guard: rows={}, cols={}, limit={}", rows, cols, limit);
                    if (rows > limit || cols > limit) {
                        rejectConstructor("TableModel dimension", Math.max(rows, cols), limit);
                    }
                    if (rows > 0 && cols > 0 && rows * cols > limit) {
                        rejectConstructor("TableModel size (rows × cols)", rows * cols, limit);
                    }
                } catch (Throwable e) {
                    logger.warn("TableModel dimension query failed for {}: {}",
                            inputs[i].getClass().getName(), e.toString());
                }
            } else if (inputs[i] != null) {
                // Check if the input implements TableModel via a different classloader
                for (Class<?> iface : inputs[i].getClass().getInterfaces()) {
                    if (iface.getName().equals("javax.swing.table.TableModel")) {
                        logger.warn("Input {} implements TableModel by name but not by identity "
                                + "(classloader mismatch?): input CL={}, expected CL={}",
                                i, iface.getClassLoader(),
                                javax.swing.table.TableModel.class.getClassLoader());
                        break;
                    }
                }
            }
        }
    }

    private void rejectConstructor(String reason, long value, int limit)
            throws CodeUnderTestException {
        logger.info("Rejecting allocation-sensitive constructor {}: {} = {} exceeds limit {}",
                constructor.getDeclaringClass().getName(), reason, value, limit);
        throw new CodeUnderTestException(new TestCaseExecutor.TimeoutExceeded());
    }

    /**
     * Rejects constructor calls whose int args exceed a dynamically learned threshold.
     * Uses the same mechanism as MethodStatement — the key is className.&lt;init&gt;.
     */
    private void rejectDynamicMethodThreshold(Object[] inputs) throws CodeUnderTestException {
        String className = constructor.getDeclaringClass().getName();
        int threshold = org.evosuite.testcase.StatementFactory
                .getAllocationSensitiveMethodThreshold(className, "<init>");
        if (threshold < 0) {
            return;
        }
        Class<?>[] paramTypes = constructor.getConstructor().getParameterTypes();
        for (int i = 0; i < paramTypes.length && i < inputs.length; i++) {
            if (paramTypes[i].equals(int.class) && inputs[i] instanceof Integer) {
                int val = (Integer) inputs[i];
                if (val > threshold) {
                    logger.info("Rejecting constructor {}: int arg {} = {} exceeds learned threshold {}",
                            className, i, val, threshold);
                    throw new CodeUnderTestException(
                            new TestCaseExecutor.TimeoutExceeded());
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        ArrayList<VariableReference> newParams = new ArrayList<>();
        for (VariableReference r : parameters) {
            newParams.add(r.copy(newTestCase, offset));
        }

        AbstractStatement copy = new ConstructorStatement(newTestCase,
                constructor.copy(), newParams);
        copyProvenanceFrom(copy, this);

        return copy;
    }


    /**
     * getParameterReferences.
     *
     * @return a {@link java.util.List} object.
     */
    public List<VariableReference> getParameterReferences() {
        return parameters;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.StatementInterface#getNumParameters()
     */
    @Override
    public int getNumParameters() {
        return parameters.size();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object s) {
        if (this == s) {
            return true;
        }
        if (s == null) {
            return false;
        }
        if (getClass() != s.getClass()) {
            return false;
        }

        ConstructorStatement ms = (ConstructorStatement) s;
        if (ms.parameters.size() != parameters.size()) {
            return false;
        }

        if (!this.constructor.equals(ms.constructor)) {
            return false;
        }

        for (int i = 0; i < parameters.size(); i++) {
            if (!parameters.get(i).equals(ms.parameters.get(i))) {
                return false;
            }
        }

        return retval.equals(ms.retval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 41;
        int result = 1;
        result = prime * result + ((constructor == null) ? 0 : constructor.hashCode());
        result = prime * result + ((parameters == null) ? 0 : parameters.hashCode());
        return result;
    }



    /*
     * (non-Javadoc)
     *
     * @see org.evosuite.testcase.Statement#getDeclaredExceptions()
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Class<?>> getDeclaredExceptions() {
        Set<Class<?>> ex = super.getDeclaredExceptions();
        ex.addAll(Arrays.asList(constructor.getConstructor().getExceptionTypes()));
        return ex;
    }


    /**
     * Go through parameters of constructor call and apply local search.
     *
     * @param test the test case.
     * @param factory the factory.
     */
    /* (non-Javadoc)
     * @see org.evosuite.testcase.AbstractStatement#mutate(TestCase, TestFactory)
     */
    @Override
    public boolean mutate(TestCase test, TestFactory factory) {

        if (Randomness.nextDouble() >= Properties.P_CHANGE_PARAMETER) {
            return false;
        }

        List<VariableReference> parameters = getParameterReferences();
        if (parameters.isEmpty()) {
            return false;
        }
        double parameterProbability = 1.0 / parameters.size();
        boolean changed = false;
        for (int numParameter = 0; numParameter < parameters.size(); numParameter++) {
            if (Randomness.nextDouble() < parameterProbability) {
                if (mutateParameter(test, numParameter)) {
                    changed = true;
                }
            }
        }
        return changed;
    }


    @Override
    public boolean isAccessible() {
        if (!constructor.isAccessible()) {
            return false;
        }

        return super.isAccessible();
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
        for (VariableReference v : parameters) {
            v.getStPosition();
        }
        return true;
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

        ConstructorStatement ms = (ConstructorStatement) s;
        if (ms.parameters.size() != parameters.size()) {
            return false;
        }

        if (!this.constructor.equals(ms.constructor)) {
            return false;
        }

        for (int i = 0; i < parameters.size(); i++) {
            if (!parameters.get(i).same(ms.parameters.get(i))) {
                return false;
            }
        }

        return retval.same(ms.retval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GenericConstructor getAccessibleObject() {
        return constructor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAssignmentStatement() {
        return false;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.StatementInterface#changeClassLoader(java.lang.ClassLoader)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeClassLoader(ClassLoader loader) {
        constructor.changeClassLoader(loader);
        super.changeClassLoader(loader);
    }

    @Override
    public String toString() {
        return constructor.getName() + Type.getConstructorDescriptor(constructor.getConstructor());
    }

    @Override
    public String getDescriptor() {
        return constructor.getDescriptor();
    }

    @Override
    public String getDeclaringClassName() {
        return constructor.getDeclaringClass().getCanonicalName();
    }

    @Override
    public String getMethodName() {
        return "<init>";
    }

    /**
     * Returns a list of the parameter names of a method using reflection. The list is in order of
     * declaration in the method.
     *
     * @return {@code List<String>}
     */
    public List<String> obtainParameterNameListInOrder() {
        final Parameter[] parameters = this.constructor.getParameters();
        final List<String> names = new ArrayList<String>();
        for (final Parameter p : parameters) {
            names.add(p.getName());
        }
        return names;
    }

}
