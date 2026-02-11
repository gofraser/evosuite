/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.symbolic;

import org.evosuite.Properties;
import org.evosuite.dse.VM;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.runtime.testdata.EvoSuiteLocalAddress;
import org.evosuite.runtime.testdata.EvoSuiteRemoteAddress;
import org.evosuite.runtime.testdata.EvoSuiteURL;
import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.bv.IntegerVariable;
import org.evosuite.symbolic.expr.bv.RealToIntegerCast;
import org.evosuite.symbolic.expr.fp.IntegerToRealCast;
import org.evosuite.symbolic.expr.fp.RealConstant;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.fp.RealVariable;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.ref.ReferenceVariableUtil;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.expr.str.StringVariable;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.SymbolicEnvironment;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.symbolic.vm.wrappers.Types;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testcase.execution.ExecutionObserver;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ClassPrimitiveStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EnumPrimitiveStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveExpression;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.environment.FileNamePrimitiveStatement;
import org.evosuite.testcase.statements.environment.LocalAddressPrimitiveStatement;
import org.evosuite.testcase.statements.environment.RemoteAddressPrimitiveStatement;
import org.evosuite.testcase.statements.environment.UrlPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BytePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.CharPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.DoublePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.FloatPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.LongPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayLengthSymbolicUtil;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.ArraySymbolicLengthName;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.TypeUtil;
import org.objectweb.asm.Type;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.evosuite.symbolic.vm.HeapVM.ARRAY_LENGTH;
import static org.evosuite.testcase.variable.ArrayLengthSymbolicUtil.UNIDIMENSIONAL_ARRAY_VALUE;

/**
 * Observes the execution of a test case and records the symbolic constraints.
 *
 * @author jgaleotti
 */
public class SymbolicObserver extends ExecutionObserver {

    private static final String INIT = "<init>";
    private static final String TEST_CLASS = "SymbolicObserver";
    private static final String TEST_METHOD = "TestCreation";

    private final SymbolicEnvironment env;

    /**
     * Constructor.
     *
     * @param env a {@link org.evosuite.symbolic.vm.SymbolicEnvironment} object.
     */
    public SymbolicObserver(SymbolicEnvironment env) {
        this.env = env;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void output(int position, String output) {
        // TODO Auto-generated method stub

    }

    private void before(ConstructorStatement stmt, Scope scope) {
        String className = stmt.getConstructor().getDeclaringClass().getName();
        VM.NEW(className);
        VM.DUP();
        String desc = Type.getConstructorDescriptor(stmt.getConstructor().getConstructor());
        pushParameterList(stmt.getParameterReferences(), scope, desc);
        String owner = className.replace('.', '/');
        /* indicates if the following code is instrumented or not */
        VM.INVOKESPECIAL(owner, INIT, desc);
        boolean needThis = true;
        callVmCallerStackParams(needThis, stmt.getParameterReferences(), scope, desc);
    }

    private void after(ConstructorStatement stmt, Scope scope) {
        String className = stmt.getConstructor().getDeclaringClass().getName();
        String desc = Type.getConstructorDescriptor(stmt.getConstructor().getConstructor());
        /* pops operands if previous code was not instrumented */
        // constructor return type is always VOID
        String owner = className.replace('.', '/');
        VM.CALL_RESULT(owner, INIT, desc);
        VariableReference varRef = stmt.getReturnValue();

        ReferenceExpression nonNullRef = env.topFrame().operandStack.popRef();
        String varName = varRef.getName();

        // We upgrade the expression to an ExpressionVariable.
        if (Properties.IS_DSE_OBJECTS_SUPPORT_ENABLED) {
            // TODO (ilebrero): avoid recreating the object on the symbolic heap to use less instance ids
            String referenceVariableName = ReferenceVariableUtil.getReferenceVariableName(varName);
            nonNullRef = env.heap.buildNewClassReferenceVariable(nonNullRef.getConcreteValue(), referenceVariableName);

        }

        symbReferences.put(varName, nonNullRef);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeStatement(Statement s, Scope scope) {
        if (VM.getInstance().isStopped()) {
            return;
        }
        VM.enableCallBacks();

        try {
            if (env.isEmpty()) {
                env.prepareStack(null);
            }

            if (s instanceof NullStatement) {
                before((NullStatement) s, scope);
            } else if (s instanceof AssignmentStatement) {
                before((AssignmentStatement) s, scope);

            } else if (s instanceof EnumPrimitiveStatement<?>) {
                before((EnumPrimitiveStatement<?>) s, scope);

            } else if (s instanceof ArrayStatement) {
                before((ArrayStatement) s, scope);

            } else if (s instanceof FieldStatement) {
                before((FieldStatement) s, scope);

            } else if (s instanceof ConstructorStatement) {
                before((ConstructorStatement) s, scope);

            } else if (s instanceof MethodStatement) {
                before((MethodStatement) s, scope);

            } else if (s instanceof FunctionalMockStatement) {
                before((FunctionalMockStatement) s, scope);
            } else if (s instanceof BooleanPrimitiveStatement) {
                before((BooleanPrimitiveStatement) s, scope);

            } else if (s instanceof BytePrimitiveStatement) {
                before((BytePrimitiveStatement) s, scope);

            } else if (s instanceof CharPrimitiveStatement) {
                before((CharPrimitiveStatement) s, scope);

            } else if (s instanceof DoublePrimitiveStatement) {
                before((DoublePrimitiveStatement) s, scope);

            } else if (s instanceof FloatPrimitiveStatement) {
                before((FloatPrimitiveStatement) s, scope);

            } else if (s instanceof IntPrimitiveStatement) {
                before((IntPrimitiveStatement) s, scope);

            } else if (s instanceof LongPrimitiveStatement) {
                before((LongPrimitiveStatement) s, scope);

            } else if (s instanceof ShortPrimitiveStatement) {
                before((ShortPrimitiveStatement) s, scope);

            } else if (s instanceof StringPrimitiveStatement) {
                before((StringPrimitiveStatement) s, scope);

            } else if (s instanceof ClassPrimitiveStatement) {
                before((ClassPrimitiveStatement) s, scope);

            } else if (s instanceof FileNamePrimitiveStatement) {
                before((FileNamePrimitiveStatement) s, scope);

            } else if (s instanceof LocalAddressPrimitiveStatement) {
                before((LocalAddressPrimitiveStatement) s, scope);

            } else if (s instanceof RemoteAddressPrimitiveStatement) {
                before((RemoteAddressPrimitiveStatement) s, scope);

            } else if (s instanceof UrlPrimitiveStatement) {
                before((UrlPrimitiveStatement) s, scope);

            } else if (s instanceof PrimitiveExpression) {
                before((PrimitiveExpression) s, scope);

            } else {
                throw new UnsupportedOperationException("Cannot handle statement of type " + s.getClass());
            }
        } catch (Throwable t) {
            throw new EvosuiteError(t);
        }

    }

    private void before(PrimitiveExpression s, Scope scope) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("This method should be implemented!");
    }

    private void before(ClassPrimitiveStatement s, Scope scope) {
        /* do nothing */
    }

    private static final int COMPONENT_TYPE_BOOLEAN = 4;
    private static final int COMPONENT_TYPE_CHAR = 5;
    private static final int COMPONENT_TYPE_FLOAT = 6;
    private static final int COMPONENT_TYPE_DOUBLE = 7;
    private static final int COMPONENT_TYPE_BYTE = 8;
    private static final int COMPONENT_TYPE_SHORT = 9;
    private static final int COMPONENT_TYPE_INT = 10;
    private static final int COMPONENT_TYPE_LONG = 11;

    private void after(ArrayStatement s, Scope scope) {
        try {
            ArrayReference arrayRef = (ArrayReference) s.getReturnValue();
            Object concArray;
            concArray = arrayRef.getObject(scope);
            Class<?> componentClass = arrayRef.getComponentClass();

            if (arrayRef.getArrayDimensions() == 1) {
                int length = arrayRef.getArrayLength();
                IntegerValue lengthExpression;

                ArraySymbolicLengthName arraySymbolicLengthName = new ArraySymbolicLengthName(arrayRef.getName(),
                        UNIDIMENSIONAL_ARRAY_VALUE);
                lengthExpression = ArrayLengthSymbolicUtil.buildArraySymbolicLengthExpression(length,
                        arraySymbolicLengthName);

                if (lengthExpression.containsSymbolicVariable()) {
                    symbExpressions.put(arraySymbolicLengthName.getSymbolicName(), lengthExpression);
                }

                env.topFrame().operandStack.pushBv32(lengthExpression);
                if (componentClass.equals(int.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_INT, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(char.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_CHAR, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(short.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_SHORT, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(byte.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_BYTE, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(float.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_FLOAT, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(long.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_LONG, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(boolean.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_BOOLEAN, TEST_CLASS, TEST_METHOD);
                } else if (componentClass.equals(double.class)) {
                    VM.NEWARRAY(length, COMPONENT_TYPE_DOUBLE, TEST_CLASS, TEST_METHOD);
                } else {
                    // push arguments
                    String componentTypeName = componentClass.getName().replace('.', '/');
                    VM.ANEWARRAY(length, componentTypeName, TEST_CLASS, TEST_METHOD);
                }
            } else {
                // push dimensions
                List<Integer> dimensions = arrayRef.getLengths();
                for (int dimension = 0; dimension < arrayRef.getArrayDimensions(); dimension++) {
                    int length = dimensions.get(dimension);
                    ArraySymbolicLengthName dimensionSymbolicLengthName = new ArraySymbolicLengthName(
                            arrayRef.getName(), dimension);
                    IntegerValue lengthExpression = ArrayLengthSymbolicUtil
                            .buildArraySymbolicLengthExpression(length, dimensionSymbolicLengthName);

                    if (lengthExpression.containsSymbolicVariable()) {
                        symbExpressions.put(dimensionSymbolicLengthName.getSymbolicName(), lengthExpression);
                    }

                    env.topFrame().operandStack.pushBv32(lengthExpression);
                }
                String arrayTypeDesc = Type.getDescriptor(concArray.getClass());
                VM.MULTIANEWARRAY(arrayTypeDesc, arrayRef.getArrayDimensions(), TEST_CLASS, TEST_METHOD);
            }
            env.topFrame().operandStack.popRef();

            // The reference should be variable as this is symbolic, so we re create it with the concrete array.
            upgradeSymbolicArrayLiteralToVariable(arrayRef, concArray);

        } catch (CodeUnderTestException e) {
            throw new RuntimeException(e);
        }

    }

    private void before(EnumPrimitiveStatement<?> s, Scope scope) {
        /* do nothing */
    }

    private void before(NullStatement s, Scope scope) {
        /* do nothing */
    }

    private void before(FieldStatement s, Scope scope) {
        /* do nothing */
    }

    private static class ReferenceExpressionPair {
        private final ReferenceExpression ref;
        private final Expression<?> expr;

        public ReferenceExpressionPair(ReferenceExpression ref, Expression<?> expr) {
            this.ref = ref;
            this.expr = expr;
        }

        public ReferenceExpression getReference() {
            return ref;
        }

        public Expression<?> getExpression() {
            return expr;
        }

    }

    private void after(AssignmentStatement s, Scope scope) {
        VariableReference lhs = s.getReturnValue();
        VariableReference rhs = s.getValue();

        ReferenceExpressionPair readResult = read(rhs, scope);

        if (lhs instanceof FieldReference) {
            writeField((FieldReference) lhs, readResult, scope);
        } else if (lhs instanceof ArrayIndex) {
            writeArray((ArrayIndex) lhs, readResult, scope);
        } else {
            writeVariable(lhs, readResult);
        }
    }

    private ReferenceExpressionPair read(VariableReference rhs, Scope scope) {
        if (rhs instanceof FieldReference) {
            return readField((FieldReference) rhs, scope);
        } else if (rhs instanceof ArrayIndex) {
            return readArray((ArrayIndex) rhs, scope);
        } else {
            return readVariable(rhs, scope);
        }
    }

    /**
     * Reads a VariableReference from the stored symbolic references and
     * symbolic expressions.
     *
     * @param rhs a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param scope a {@link org.evosuite.testcase.execution.Scope} object.
     * @return a {@link org.evosuite.symbolic.SymbolicObserver.ReferenceExpressionPair} object.
     * @throws java.lang.IllegalArgumentException if no value was found.
     */
    private ReferenceExpressionPair readVariable(VariableReference rhs, Scope scope) {
        String rhsName = rhs.getName();
        ReferenceExpression symbRef = symbReferences.get(rhsName);
        Expression<?> symbExpr = symbExpressions.get(rhsName);
        return new ReferenceExpressionPair(symbRef, symbExpr);

    }

    private ReferenceExpressionPair readArray(ArrayIndex rhs, Scope scope) {
        ArrayReference arrayReference = rhs.getArray();
        ReferenceExpression symbArray = symbReferences.get(arrayReference.getName());
        int concIndex = rhs.getArrayIndex();
        IntegerConstant symbIndex = new IntegerConstant(concIndex);
        Class<?> componentClass = arrayReference.getComponentClass();

        try {
            Object concArray = arrayReference.getObject(scope);

            if (componentClass.equals(int.class)) {
                int concValue = Array.getInt(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue));
                ReferenceConstant newIntegerRef = newIntegerReference(concValue, expr);
                return new ReferenceExpressionPair(newIntegerRef, expr);
            } else if (componentClass.equals(char.class)) {
                char concValue = Array.getChar(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue));
                ReferenceConstant newCharacterRef = newCharacterReference(concValue, expr);
                return new ReferenceExpressionPair(newCharacterRef, expr);
            } else if (componentClass.equals(boolean.class)) {
                boolean concValue = Array.getBoolean(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue ? 1 : 0));
                ReferenceConstant newBooleanRef = newBooleanReference(concValue, expr);
                return new ReferenceExpressionPair(newBooleanRef, expr);
            } else if (componentClass.equals(byte.class)) {
                byte concValue = Array.getByte(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue));
                ReferenceConstant newByteRef = newByteReference(concValue, expr);
                return new ReferenceExpressionPair(newByteRef, expr);
            } else if (componentClass.equals(short.class)) {
                short concValue = Array.getShort(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue));
                ReferenceConstant newShortRef = newShortReference(concValue, expr);
                return new ReferenceExpressionPair(newShortRef, expr);
            } else if (componentClass.equals(long.class)) {
                long concValue = Array.getLong(concArray, concIndex);
                IntegerValue expr = env.heap.arrayLoad(symbArray, symbIndex, new IntegerConstant(concValue));
                ReferenceConstant newLongRef = newLongReference(concValue, expr);
                return new ReferenceExpressionPair(newLongRef, expr);
            } else if (componentClass.equals(float.class)) {
                float concValue = Array.getFloat(concArray, concIndex);
                RealValue expr = env.heap.arrayLoad(symbArray, symbIndex, new RealConstant(concValue));
                ReferenceConstant newFloatRef = newFloatReference(concValue, expr);
                return new ReferenceExpressionPair(newFloatRef, expr);
            } else if (componentClass.equals(double.class)) {
                double concValue = Array.getDouble(concArray, concIndex);
                RealValue expr = env.heap.arrayLoad(symbArray, symbIndex, new RealConstant(concValue));
                ReferenceConstant newDoubleRef = newDoubleReference(concValue, expr);
                return new ReferenceExpressionPair(newDoubleRef, expr);
            } else {
                Object concValue = Array.get(concArray, concIndex);
                if (concValue instanceof String) {
                    StringValue expr = env.heap.arrayLoad(symbArray, symbIndex, new StringConstant((String) concValue));
                    ReferenceConstant newStringRef = newStringReference((String) concValue, expr);
                    return new ReferenceExpressionPair(newStringRef, expr);
                } else {
                    ReferenceExpression ref = env.heap.getReference(concValue);

                    if (concValue != null && isWrapper(concValue)) {
                        ReferenceConstant nonNullRef = (ReferenceConstant) ref;
                        Expression<?> expr = findOrCreate(concValue, nonNullRef);
                        return new ReferenceExpressionPair(ref, expr);
                    } else {
                        return new ReferenceExpressionPair(ref, null);
                    }
                }
            }
        } catch (CodeUnderTestException e) {
            throw new RuntimeException(e);
        }
    }

    private ReferenceExpressionPair readField(FieldReference rhs, Scope scope) {

        if (rhs.getSource() != null) {
            /* instance field */
            return readInstanceField(rhs.getSource(), rhs.getField().getField(), scope);
        } else {
            /* static field */
            return readStaticField(rhs.getField().getField());
        }

    }

    private ReferenceExpressionPair readStaticField(Field field) {

        String owner = field.getDeclaringClass().getName().replace('.', '/');
        String name = field.getName();

        Class<?> fieldClazz = field.getType();

        try {

            if (fieldClazz.equals(int.class)) {
                int concValue = field.getInt(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newIntegerRef = newIntegerReference(concValue, expr);
                return new ReferenceExpressionPair(newIntegerRef, expr);

            } else if (fieldClazz.equals(char.class)) {
                char concValue = field.getChar(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newCharacterRef = newCharacterReference(concValue, expr);
                return new ReferenceExpressionPair(newCharacterRef, expr);

            } else if (fieldClazz.equals(long.class)) {
                long concValue = field.getLong(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newLongRef = newLongReference(concValue, expr);
                return new ReferenceExpressionPair(newLongRef, expr);

            } else if (fieldClazz.equals(short.class)) {
                short concValue = field.getShort(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newShortRef = newShortReference(concValue, expr);
                return new ReferenceExpressionPair(newShortRef, expr);

            } else if (fieldClazz.equals(byte.class)) {
                byte concValue = field.getByte(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newByteRef = newByteReference(concValue, expr);
                return new ReferenceExpressionPair(newByteRef, expr);

            } else if (fieldClazz.equals(boolean.class)) {
                boolean concValue = field.getBoolean(null);
                IntegerValue expr = env.heap.getStaticField(owner, name, concValue ? 1 : 0);
                ReferenceConstant newBooleanRef = newBooleanReference(concValue, expr);
                return new ReferenceExpressionPair(newBooleanRef, expr);

            } else if (fieldClazz.equals(float.class)) {
                float concValue = field.getFloat(null);
                RealValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newFloatRef = newFloatReference(concValue, expr);
                return new ReferenceExpressionPair(newFloatRef, expr);

            } else if (fieldClazz.equals(double.class)) {
                double concValue = field.getDouble(null);
                RealValue expr = env.heap.getStaticField(owner, name, concValue);
                ReferenceConstant newDoubleRef = newDoubleReference(concValue, expr);
                return new ReferenceExpressionPair(newDoubleRef, expr);

            } else {
                Object concValue = field.get(null);
                if (concValue instanceof String) {
                    String string = (String) concValue;
                    StringValue expr = env.heap.getStaticField(owner, name, string);
                    ReferenceConstant newStringRef = newStringReference(string, expr);
                    return new ReferenceExpressionPair(newStringRef, expr);
                } else {
                    ReferenceExpression ref = env.heap.getReference(concValue);
                    if (concValue != null && isWrapper(concValue)) {
                        ReferenceConstant nonNullRef = (ReferenceConstant) ref;
                        Expression<?> expr = findOrCreate(concValue, nonNullRef);
                        return new ReferenceExpressionPair(ref, expr);
                    } else {
                        return new ReferenceExpressionPair(ref, null);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private ReferenceExpressionPair readInstanceField(VariableReference source, Field field, Scope scope) {

        String owner = field.getDeclaringClass().getName().replace('.', '/');
        String name = field.getName();

        Class<?> fieldClazz = field.getType();

        String sourceName = source.getName();
        ReferenceConstant symbReceiver = (ReferenceConstant) symbReferences.get(sourceName);

        try {
            Object concReceiver = source.getObject(scope);

            if (fieldClazz.equals(int.class)) {
                int concValue = field.getInt(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newIntegerRef = newIntegerReference(concValue, expr);
                return new ReferenceExpressionPair(newIntegerRef, expr);
            } else if (fieldClazz.equals(char.class)) {
                char concValue = field.getChar(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newCharacterRef = newCharacterReference(concValue, expr);
                return new ReferenceExpressionPair(newCharacterRef, expr);
            } else if (fieldClazz.equals(long.class)) {
                long concValue = field.getLong(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newLongRef = newLongReference(concValue, expr);
                return new ReferenceExpressionPair(newLongRef, expr);
            } else if (fieldClazz.equals(short.class)) {
                short concValue = field.getShort(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newShortRef = newShortReference(concValue, expr);
                return new ReferenceExpressionPair(newShortRef, expr);
            } else if (fieldClazz.equals(byte.class)) {
                byte concValue = field.getByte(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newByteRef = newByteReference(concValue, expr);
                return new ReferenceExpressionPair(newByteRef, expr);
            } else if (fieldClazz.equals(boolean.class)) {
                boolean concValue = field.getBoolean(concReceiver);
                IntegerValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue ? 1 : 0);
                ReferenceConstant newBooleanRef = newBooleanReference(concValue, expr);
                return new ReferenceExpressionPair(newBooleanRef, expr);
            } else if (fieldClazz.equals(float.class)) {
                float concValue = field.getFloat(concReceiver);
                RealValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newFloatRef = newFloatReference(concValue, expr);
                return new ReferenceExpressionPair(newFloatRef, expr);
            } else if (fieldClazz.equals(double.class)) {
                double concValue = field.getDouble(concReceiver);
                RealValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, concValue);
                ReferenceConstant newDoubleRef = newDoubleReference(concValue, expr);
                return new ReferenceExpressionPair(newDoubleRef, expr);
            } else {
                Object concValue = field.get(concReceiver);
                if (concValue instanceof String) {
                    String string = (String) concValue;
                    StringValue expr = env.heap.getField(owner, name, concReceiver, symbReceiver, string);
                    ReferenceConstant newStringRef = newStringReference(string, expr);
                    return new ReferenceExpressionPair(newStringRef, expr);
                } else {
                    ReferenceExpression ref = env.heap.getReference(concValue);

                    if (concValue != null && isWrapper(concValue)) {
                        ReferenceConstant nonNullRef = (ReferenceConstant) ref;
                        Expression<?> expr = findOrCreate(concValue, nonNullRef);
                        return new ReferenceExpressionPair(ref, expr);
                    } else {
                        return new ReferenceExpressionPair(ref, null);
                    }
                }
            }
        } catch (IllegalArgumentException | IllegalAccessException | CodeUnderTestException e) {
            throw new RuntimeException(e);
        }
    }

    private void before(AssignmentStatement s, Scope scope) {
        /* do nothing */
    }

    private void writeVariable(VariableReference lhs, ReferenceExpressionPair readResult) {
        String lhsName = lhs.getName();
        Expression<?> expr = readResult.getExpression();
        if (expr != null) {
            symbExpressions.put(lhsName, expr);
        }

        ReferenceExpression ref = readResult.getReference();
        if (ref != null) {
            symbReferences.put(lhsName, ref);
        }

    }

    private void writeArray(ArrayIndex lhs, ReferenceExpressionPair readResult, Scope scope) {

        ArrayReference arrayReference = lhs.getArray();

        Object concArray;
        try {
            concArray = arrayReference.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }

        Type arrayType = Type.getType(concArray.getClass());
        Type elementType = arrayType.getElementType();
        if (TypeUtil.isValue(elementType) || elementType.equals(Type.getType(String.class))) {
            // Expression<?> symbValue = readResult.getExpression();

            // String arrayName = arrayReference.getName();
            // ReferenceExpression symbArray = symbReferences.get(arrayName);

            // NOTE (ilebrero): we only want to update the symbolic environment outside the SUT.
            // if (symbValue instanceof IntegerValue) {
            // env.heap.arrayStore(concArray, symbArray, symbIndex,
            // new IntegerConstant(((IntegerValue) symbValue).getConcreteValue()));
            // } else if (symbValue instanceof RealValue) {
            // env.heap.arrayStore(concArray, symbArray, symbIndex,
            // new RealConstant(((RealValue) symbValue).getConcreteValue()));
            // } else if (symbValue instanceof StringValue) {
            // env.heap.array_store(concArray, symbArray, concIndex, (StringValue) symbValue);
            // }
        } else {
            /* ignore storing references (we use objects to find them) */
        }
    }

    private Expression<?> castIfNeeded(Type elementType, Expression<?> symbValue) {
        // cast integer to real if needed
        if ((TypeUtil.isFp32(elementType) || TypeUtil.isFp64(elementType)) && symbValue instanceof IntegerValue) {
            IntegerValue intExpr = (IntegerValue) symbValue;
            double concValue = intExpr.getConcreteValue().doubleValue();
            return new IntegerToRealCast(intExpr, concValue);
        } else if ((TypeUtil.isBv32(elementType) || TypeUtil.isBv64(elementType)) && symbValue instanceof RealValue) {
            RealValue realExpr = (RealValue) symbValue;
            long concValue = realExpr.getConcreteValue().longValue();
            return new RealToIntegerCast(realExpr, concValue);
        }
        return symbValue;
    }

    private void writeField(FieldReference lhs, ReferenceExpressionPair readResult, Scope scope) {
        Field field = lhs.getField().getField();
        String className = field.getDeclaringClass().getName().replace('.', '/');
        String fieldName = field.getName();

        Class<?> fieldClass = field.getType();

        Type fieldType = Type.getType(fieldClass);
        if (TypeUtil.isValue(fieldType) || fieldType.equals(Type.getType(String.class))) {
            Expression<?> symbValue = readResult.getExpression();
            symbValue = castIfNeeded(fieldType, symbValue);

            VariableReference source = lhs.getSource();
            if (source != null) {
                /* write symbolic expression to instance field */
                String sourceName = source.getName();
                Object concReceiver;
                try {
                    concReceiver = source.getObject(scope);
                } catch (CodeUnderTestException e) {
                    throw new RuntimeException(e);
                }
                ReferenceConstant symbReceiver = (ReferenceConstant) symbReferences.get(sourceName);
                env.heap.putField(className, fieldName, concReceiver, symbReceiver, symbValue);
            } else {
                /* write symbolic expression to static field */
                env.heap.putStaticField(className, fieldName, symbValue);
            }
        } else {
            /*
             * ignore writing of references (DSE does not store Reference
             * fields)
             */
        }
    }

    private void before(ShortPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(LongPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(FloatPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(CharPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(BytePrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(BooleanPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(DoublePrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(MethodStatement statement, Scope scope) {
        Method method = statement.getMethod().getMethod();

        String owner = method.getDeclaringClass().getName().replace('.', '/');
        String name = method.getName();
        String desc = Type.getMethodDescriptor(method);

        boolean needThis = statement.getCallee() != null;

        if (needThis) {
            VariableReference callee = statement.getCallee();
            ReferenceExpressionPair refExprPair = read(callee, scope);

            ReferenceExpression ref = refExprPair.getReference();
            this.env.topFrame().operandStack.pushRef(ref);
        }

        List<VariableReference> parameters = statement.getParameterReferences();
        pushParameterList(parameters, scope, desc);

        if (needThis) {
            VariableReference callee = statement.getCallee();
            Object receiver;
            try {
                receiver = callee.getObject(scope);
            } catch (CodeUnderTestException e) {
                throw new RuntimeException(e);
            }

            Class<?> ownerClass = env.ensurePrepared(owner);
            if (ownerClass.isInterface()) {
                VM.INVOKEINTERFACE(receiver, owner, name, desc);

            } else {
                VM.INVOKEVIRTUAL(receiver, owner, name, desc);

            }
        } else {
            VM.INVOKESTATIC(owner, name, desc);
        }

        callVmCallerStackParams(needThis, parameters, scope, desc);

    }

    private void callVmCallerStackParams(boolean needThis, List<VariableReference> parameters, Scope scope,
                                         String desc) {
        int calleeLocalsIndex = 0;
        if (needThis) {
            calleeLocalsIndex++;
        }

        for (VariableReference p : parameters) {
            calleeLocalsIndex += getSize(p.getType());
        }

        Type[] argTypes = Type.getArgumentTypes(desc);

        for (int i = parameters.size() - 1; i >= 0; i--) {
            Type argType = argTypes[i];
            VariableReference p = parameters.get(i);
            try {
                Object paramObject = p.getObject(scope);
                calleeLocalsIndex -= getSize(p.getType());
                if (argType.equals(Type.INT_TYPE)) {
                    int intValue = getIntValue(paramObject);
                    VM.CALLER_STACK_PARAM(intValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.CHAR_TYPE)) {
                    char charValue = getCharValue(paramObject);
                    VM.CALLER_STACK_PARAM(charValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.BYTE_TYPE)) {
                    byte byteValue = getByteValue(paramObject);
                    VM.CALLER_STACK_PARAM(byteValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.BOOLEAN_TYPE)) {
                    boolean booleanValue = getBooleanValue(paramObject);
                    VM.CALLER_STACK_PARAM(booleanValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.SHORT_TYPE)) {
                    short shortValue = getShortValue(paramObject);
                    VM.CALLER_STACK_PARAM(shortValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.LONG_TYPE)) {
                    long longValue = getLongValue(paramObject);
                    VM.CALLER_STACK_PARAM(longValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.FLOAT_TYPE)) {
                    float floatValue = getFloatValue(paramObject);
                    VM.CALLER_STACK_PARAM(floatValue, i, calleeLocalsIndex);
                } else if (argType.equals(Type.DOUBLE_TYPE)) {
                    double doubleValue = getDoubleValue(paramObject);
                    VM.CALLER_STACK_PARAM(doubleValue, i, calleeLocalsIndex);
                } else {
                    VM.CALLER_STACK_PARAM(paramObject, i, calleeLocalsIndex);
                }
            } catch (CodeUnderTestException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static double getDoubleValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        } else if (o instanceof Short) {
            return (Short) o;
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (Character) o;
        } else if (o instanceof Integer) {
            return (Integer) o;
        } else if (o instanceof Long) {
            return (Long) o;
        } else if (o instanceof Float) {
            return (Float) o;
        } else if (o instanceof Double) {
            return (Double) o;
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static float getFloatValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        } else if (o instanceof Short) {
            return (Short) o;
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (Character) o;
        } else if (o instanceof Integer) {
            return (Integer) o;
        } else if (o instanceof Long) {
            return (Long) o;
        } else if (o instanceof Float) {
            return (Float) o;
        } else if (o instanceof Double) {
            return (float) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static long getLongValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        } else if (o instanceof Short) {
            return (Short) o;
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (Character) o;
        } else if (o instanceof Integer) {
            return (Integer) o;
        } else if (o instanceof Long) {
            return (Long) o;
        } else if (o instanceof Float) {
            return (long) ((Float) o).floatValue();
        } else if (o instanceof Double) {
            return (long) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static int getIntValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (Boolean) o ? 1 : 0;
        } else if (o instanceof Short) {
            return (Short) o;
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (Character) o;
        } else if (o instanceof Integer) {
            return (Integer) o;
        } else if (o instanceof Long) {
            return (int) ((Long) o).longValue();
        } else if (o instanceof Float) {
            return (int) ((Float) o).floatValue();
        } else if (o instanceof Double) {
            return (int) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static short getShortValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (short) ((Boolean) o ? 1 : 0);
        } else if (o instanceof Short) {
            return (Short) o;
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (short) ((Character) o).charValue();
        } else if (o instanceof Integer) {
            return (short) ((Integer) o).intValue();
        } else if (o instanceof Long) {
            return (short) ((Long) o).longValue();
        } else if (o instanceof Float) {
            return (short) ((Float) o).floatValue();
        } else if (o instanceof Double) {
            return (short) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static byte getByteValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (byte) ((Boolean) o ? 1 : 0);
        } else if (o instanceof Short) {
            return (byte) ((Short) o).shortValue();
        } else if (o instanceof Byte) {
            return (Byte) o;
        } else if (o instanceof Character) {
            return (byte) ((Character) o).charValue();
        } else if (o instanceof Integer) {
            return (byte) ((Integer) o).intValue();
        } else if (o instanceof Long) {
            return (byte) ((Long) o).longValue();
        } else if (o instanceof Float) {
            return (byte) ((Float) o).floatValue();
        } else if (o instanceof Double) {
            return (byte) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static char getCharValue(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Boolean) {
            return (char) ((Boolean) o ? 1 : 0);
        } else if (o instanceof Short) {
            return (char) ((Short) o).shortValue();
        } else if (o instanceof Byte) {
            return (char) ((Byte) o).byteValue();
        } else if (o instanceof Character) {
            return (Character) o;
        } else if (o instanceof Integer) {
            return (char) ((Integer) o).intValue();
        } else if (o instanceof Long) {
            return (char) ((Long) o).longValue();
        } else if (o instanceof Float) {
            return (char) ((Float) o).floatValue();
        } else if (o instanceof Double) {
            return (char) ((Double) o).doubleValue();
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static boolean getBooleanValue(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        } else if (o instanceof Short) {
            return (Short) o == 1;
        } else if (o instanceof Byte) {
            return (Byte) o == 1;
        } else if (o instanceof Character) {
            return (Character) o == 1;
        } else if (o instanceof Integer) {
            return (Integer) o == 1;
        } else if (o instanceof Long) {
            return (Long) o == 1;
        } else if (o instanceof Float) {
            return (Float) o == 1;
        } else if (o instanceof Double) {
            return (Double) o == 1;
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private int getSize(java.lang.reflect.Type type) {
        if (type.equals(double.class)) {
            return 2;
        } else if (type.equals(long.class)) {
            return 2;
        } else {
            return 1;
        }
    }

    private void pushParameterList(List<VariableReference> parameters, Scope scope, String desc) {

        Type[] argTypes = Type.getArgumentTypes(desc);

        for (int i = 0; i < parameters.size(); i++) {

            VariableReference varRef = parameters.get(i);
            Type argType = argTypes[i];
            ReferenceExpressionPair readResult = this.read(varRef, scope);
            Expression<?> symbExpr = readResult.getExpression();
            ReferenceExpression symbRef = readResult.getReference();

            if (TypeUtil.isValue(argType)) {

                if (symbExpr instanceof RealValue) {
                    RealValue realExpr = (RealValue) symbExpr;
                    if (TypeUtil.isFp32(argType)) {
                        env.topFrame().operandStack.pushFp32(realExpr);
                    } else if (TypeUtil.isFp64(argType)) {
                        env.topFrame().operandStack.pushFp64(realExpr);
                    } else if (TypeUtil.isBv32(argType)) {
                        int concV = realExpr.getConcreteValue().intValue();
                        RealToIntegerCast castExpr = new RealToIntegerCast(realExpr, (long) concV);
                        env.topFrame().operandStack.pushBv32(castExpr);
                    } else if (TypeUtil.isBv64(argType)) {
                        long concV = realExpr.getConcreteValue().longValue();
                        RealToIntegerCast castExpr = new RealToIntegerCast(realExpr, concV);
                        env.topFrame().operandStack.pushBv64(castExpr);
                    }
                } else if (symbExpr instanceof IntegerValue) {
                    IntegerValue integerExpr = (IntegerValue) symbExpr;
                    if (TypeUtil.isBv32(argType)) {
                        env.topFrame().operandStack.pushBv32(integerExpr);
                    } else if (TypeUtil.isBv64(argType)) {
                        env.topFrame().operandStack.pushBv64(integerExpr);
                    } else if (TypeUtil.isFp32(argType)) {
                        float concV = integerExpr.getConcreteValue().floatValue();
                        IntegerToRealCast castExpr = new IntegerToRealCast(integerExpr, (double) concV);
                        env.topFrame().operandStack.pushFp32(castExpr);
                    } else if (TypeUtil.isFp64(argType)) {
                        double concV = integerExpr.getConcreteValue().doubleValue();
                        IntegerToRealCast castExpr = new IntegerToRealCast(integerExpr, concV);
                        env.topFrame().operandStack.pushFp64(castExpr);
                    }

                } else {

                    if (symbRef.getConcreteValue() == null) {
                        // although this will lead in the JVM to a NPE, we push
                        // a dummy
                        // value to prevent the DSE VM from crashing
                        pushDummyValue(argType);

                    } else {
                        // auto unboxing reference
                        ReferenceConstant nonNullSymbRef = (ReferenceConstant) symbRef;
                        Object concObject = scope.getObject(varRef);
                        Expression<?> unboxedExpr = unboxReference(argType, concObject, nonNullSymbRef);
                        pushValue(argType, unboxedExpr);

                    }
                }
            } else {

                ReferenceExpression ref = readResult.getReference();
                env.topFrame().operandStack.pushRef(ref);
            }

        }
    }

    private Expression<?> unboxReference(Type argType, Object concObject, ReferenceConstant nonNullSymbRef) {
        switch (argType.getSort()) {
            case Type.BOOLEAN: {
                return env.heap.getField(Types.JAVA_LANG_BOOLEAN, SymbolicHeap.$BOOLEAN_VALUE,
                        concObject, nonNullSymbRef);
            }
            case Type.BYTE: {
                return env.heap.getField(Types.JAVA_LANG_BYTE, SymbolicHeap.$BYTE_VALUE, concObject,
                        nonNullSymbRef);
            }
            case Type.CHAR: {
                return env.heap.getField(Types.JAVA_LANG_CHARACTER, SymbolicHeap.$CHAR_VALUE,
                        concObject, nonNullSymbRef);
            }
            case Type.DOUBLE: {
                return env.heap.getField(Types.JAVA_LANG_DOUBLE, SymbolicHeap.$DOUBLE_VALUE, concObject,
                        nonNullSymbRef);
            }
            case Type.FLOAT: {
                return env.heap.getField(Types.JAVA_LANG_FLOAT, SymbolicHeap.$FLOAT_VALUE, concObject,
                        nonNullSymbRef);
            }
            case Type.INT: {
                return env.heap.getField(Types.JAVA_LANG_INTEGER, SymbolicHeap.$INT_VALUE, concObject,
                        nonNullSymbRef);
            }
            case Type.LONG: {
                return env.heap.getField(Types.JAVA_LANG_LONG, SymbolicHeap.$LONG_VALUE, concObject,
                        nonNullSymbRef);
            }
            case Type.SHORT: {
                return env.heap.getField(Types.JAVA_LANG_SHORT, SymbolicHeap.$SHORT_VALUE, concObject,
                        nonNullSymbRef);
            }
            default: {
                throw new EvosuiteError(argType + " cannot be automatically unboxed");

            }
        }

    }

    private void pushValue(Type argType, Expression<?> symbExpr) {

        if (TypeUtil.isBv32(argType)) {
            IntegerValue booleanExpr = (IntegerValue) symbExpr;
            env.topFrame().operandStack.pushBv32(booleanExpr);
        } else if (TypeUtil.isBv64(argType)) {
            IntegerValue longExpr = (IntegerValue) symbExpr;
            env.topFrame().operandStack.pushBv64(longExpr);
        } else if (TypeUtil.isFp32(argType)) {
            RealValue realExpr = (RealValue) symbExpr;
            env.topFrame().operandStack.pushFp32(realExpr);
        } else if (TypeUtil.isFp64(argType)) {
            RealValue realExpr = (RealValue) symbExpr;
            env.topFrame().operandStack.pushFp64(realExpr);
        } else {
            throw new EvosuiteError(argType + " is not a value type!");
        }

    }

    private void pushDummyValue(Type argType) {
        if (TypeUtil.isBv32(argType)) {
            IntegerValue integerExpr = ExpressionFactory.buildNewIntegerConstant(0);
            env.topFrame().operandStack.pushBv32(integerExpr);
        } else if (TypeUtil.isBv64(argType)) {
            IntegerValue integerExpr = ExpressionFactory.buildNewIntegerConstant(0);
            env.topFrame().operandStack.pushBv64(integerExpr);
        } else if (TypeUtil.isFp32(argType)) {
            RealValue realExpr = ExpressionFactory.buildNewRealConstant(0);
            env.topFrame().operandStack.pushFp32(realExpr);
        } else if (TypeUtil.isFp64(argType)) {
            RealValue realExpr = ExpressionFactory.buildNewRealConstant(0);
            env.topFrame().operandStack.pushFp64(realExpr);
        } else {
            throw new EvosuiteError(argType + " is not a value type!");
        }
    }

    /**
     * This method forbids using the same interning String in two separate
     * string primitive statements.
     *
     * @param statement a {@link org.evosuite.testcase.statements.StringPrimitiveStatement} object.
     * @param scope a {@link org.evosuite.testcase.execution.Scope} object.
     */
    private void before(StringPrimitiveStatement statement, Scope scope) {
        /* do nothing */

    }

    private void before(FileNamePrimitiveStatement statement, Scope scope) {
        /* do nothing */

    }

    private void before(LocalAddressPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(RemoteAddressPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(UrlPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(FunctionalMockStatement statement, Scope scope) {
        /* do nothing */
    }

    private void before(IntPrimitiveStatement statement, Scope scope) {
        /* do nothing */
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterStatement(Statement s, Scope scope, Throwable exception) {

        if (exception != null) {
            return;
        }

        if (VM.getInstance().isStopped()) {
            return;
        }

        try {
            if (s instanceof NullStatement) {
                after((NullStatement) s, scope);

            } else if (s instanceof EnumPrimitiveStatement<?>) {
                after((EnumPrimitiveStatement<?>) s, scope);

            } else if (s instanceof ArrayStatement) {
                after((ArrayStatement) s, scope);

            } else if (s instanceof AssignmentStatement) {
                after((AssignmentStatement) s, scope);

            } else if (s instanceof FieldStatement) {
                after((FieldStatement) s, scope);

            } else if (s instanceof ConstructorStatement) {
                after((ConstructorStatement) s, scope);
            } else if (s instanceof BooleanPrimitiveStatement) {
                after((BooleanPrimitiveStatement) s, scope);

            } else if (s instanceof MethodStatement) {
                after((MethodStatement) s, scope);

            } else if (s instanceof BytePrimitiveStatement) {
                after((BytePrimitiveStatement) s, scope);

            } else if (s instanceof CharPrimitiveStatement) {
                after((CharPrimitiveStatement) s, scope);

            } else if (s instanceof DoublePrimitiveStatement) {
                after((DoublePrimitiveStatement) s, scope);

            } else if (s instanceof FloatPrimitiveStatement) {
                after((FloatPrimitiveStatement) s, scope);

            } else if (s instanceof IntPrimitiveStatement) {
                after((IntPrimitiveStatement) s, scope);

            } else if (s instanceof LongPrimitiveStatement) {
                after((LongPrimitiveStatement) s, scope);

            } else if (s instanceof ShortPrimitiveStatement) {
                after((ShortPrimitiveStatement) s, scope);

            } else if (s instanceof StringPrimitiveStatement) {
                after((StringPrimitiveStatement) s, scope);

            } else if (s instanceof ClassPrimitiveStatement) {
                after((ClassPrimitiveStatement) s, scope);

            } else if (s instanceof FileNamePrimitiveStatement) {
                after((FileNamePrimitiveStatement) s, scope);

            } else if (s instanceof LocalAddressPrimitiveStatement) {
                after((LocalAddressPrimitiveStatement) s, scope);

            } else if (s instanceof RemoteAddressPrimitiveStatement) {
                after((RemoteAddressPrimitiveStatement) s, scope);

            } else if (s instanceof UrlPrimitiveStatement) {
                after((UrlPrimitiveStatement) s, scope);

            } else if (s instanceof PrimitiveExpression) {
                after((PrimitiveExpression) s, scope);

            } else if (s instanceof FunctionalMockStatement) {
                after((FunctionalMockStatement) s, scope);

            } else {
                throw new UnsupportedOperationException("Cannot handle statement of type " + s.getClass());
            }
        } catch (Throwable t) {
            throw new EvosuiteError(t);
        }
    }

    private void after(UrlPrimitiveStatement s, Scope scope) {
        EvoSuiteURL concUrl = s.getValue();
        VariableReference varRef = s.getReturnValue();
        String varRefName = varRef.getName();

        ReferenceExpression urlRef;
        if (concUrl == null) {
            urlRef = env.heap.getReference(null);
            if (urlRef.getConcreteValue() != null) {
                throw new IllegalStateException("Expected null concrete value");
            }
        } else {
            urlRef = env.heap.getReference(concUrl);
            if (urlRef.getConcreteValue() == null) {
                throw new IllegalStateException("Expected non-null concrete value");
            }
        }

        symbReferences.put(varRefName, urlRef);

    }

    private void after(RemoteAddressPrimitiveStatement s, Scope scope) {
        EvoSuiteRemoteAddress concRemoteAddress = s.getValue();

        VariableReference varRef = s.getReturnValue();
        String varRefName = varRef.getName();

        ReferenceExpression addressRef;
        if (concRemoteAddress == null) {
            addressRef = env.heap.getReference(null);
            if (addressRef.getConcreteValue() != null) {
                throw new IllegalStateException("Expected null concrete value");
            }
        } else {
            addressRef = env.heap.getReference(concRemoteAddress);
            if (addressRef.getConcreteValue() == null) {
                throw new IllegalStateException("Expected non-null concrete value");
            }
        }

        symbReferences.put(varRefName, addressRef);

    }

    private void after(LocalAddressPrimitiveStatement s, Scope scope) {
        EvoSuiteLocalAddress concLocalAddress = s.getValue();
        VariableReference varRef = s.getReturnValue();
        String varRefName = varRef.getName();

        ReferenceExpression addressRef;
        if (concLocalAddress == null) {
            addressRef = env.heap.getReference(null);
            if (addressRef.getConcreteValue() != null) {
                throw new IllegalStateException("Expected null concrete object");
            }
        } else {
            addressRef = env.heap.getReference(concLocalAddress);
            if (addressRef.getConcreteValue() == null) {
                throw new IllegalStateException("Expected non-null concrete object");
            }
        }

        symbReferences.put(varRefName, addressRef);

    }

    private void after(PrimitiveExpression s, Scope scope) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("This method should be implemented!");
    }

    private void after(ClassPrimitiveStatement s, Scope scope) {
        VariableReference varRef = s.getReturnValue();
        Class<?> concreteReference = s.getValue();
        String varName = varRef.getName();
        ReferenceExpression symbRef = env.heap.getReference(concreteReference);
        symbReferences.put(varName, symbRef);
    }

    private void before(ArrayStatement s, Scope scope) {
        /* do nothing */
    }

    private void after(EnumPrimitiveStatement<?> s, Scope scope) {
        VariableReference varRef = s.getReturnValue();
        String varName = varRef.getName();
        Object concValue = s.getValue();
        ReferenceExpression symbValue = env.heap.getReference(concValue);
        symbReferences.put(varName, symbValue);
    }

    private void after(NullStatement s, Scope scope) {
        VariableReference lhs = s.getReturnValue();
        String lhsName = lhs.getName();
        ReferenceExpression nullReference = ExpressionFactory.NULL_REFERENCE;

        if (Properties.IS_DSE_OBJECTS_SUPPORT_ENABLED) {
            String referenceVariableName = ReferenceVariableUtil.getReferenceVariableName(lhs.getName());
            // Shouldn't a null reference be constant? or is this a reference to a variable which its current
            // value is null?
            nullReference = env.heap.buildNewClassReferenceVariable(nullReference.getConcreteValue(),
                    referenceVariableName);
        }

        symbReferences.put(lhsName, nullReference);
    }

    private void after(FunctionalMockStatement statement, Scope scope) {

        Type returnType = Type.getType(statement.getReturnClass());

        VariableReference varRef = statement.getReturnValue();
        String varName = varRef.getName();
        try {

            if (varRef.getType().equals(void.class)) {
                // do nothing
            } else if (returnType.equals(Type.INT_TYPE) || returnType.equals(Type.BOOLEAN_TYPE)
                    || returnType.equals(Type.DOUBLE_TYPE) || returnType.equals(Type.FLOAT_TYPE)
                    || returnType.equals(Type.LONG_TYPE) || returnType.equals(Type.SHORT_TYPE)
                    || returnType.equals(Type.BYTE_TYPE) || returnType.equals(Type.CHAR_TYPE)) {

                throw new EvosuiteError("mocking of primitive types is not supported!");

            } else {
                Object res = varRef.getObject(scope);

                ReferenceExpression ref = env.heap.getReference(res);

                if (res != null && res instanceof String) {
                    String string = (String) res;
                    ReferenceConstant newStringRef = (ReferenceConstant) env.heap.getReference(string);
                    StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE, string,
                            newStringRef, string);
                    symbReferences.put(varName, newStringRef);
                    symbExpressions.put(varName, strExpr);
                } else {
                    symbReferences.put(varName, ref);
                    if (res != null && isWrapper(res)) {
                        ReferenceConstant nonNullRef = (ReferenceConstant) ref;
                        Expression<?> expr = findOrCreate(res, nonNullRef);
                        symbExpressions.put(varName, expr);
                    }
                }

            }
        } catch (CodeUnderTestException e) {
            throw new RuntimeException(e);
        }

    }

    private void after(FieldStatement s, Scope scope) {
        ReferenceExpressionPair readResult;
        if (s.getSource() != null) {
            readResult = readInstanceField(s.getSource(), s.getField().getField(), scope);
        } else {
            readResult = readStaticField(s.getField().getField());
        }

        String lhsName = s.getReturnValue().getName();

        Expression<?> expr = readResult.getExpression();
        ReferenceExpression ref = readResult.getReference();

        if (expr != null) {
            symbExpressions.put(lhsName, expr);
        }

        if (ref != null) {
            symbReferences.put(lhsName, ref);
        }
    }

    private void after(ShortPrimitiveStatement statement, Scope scope) {
        short valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf, Short.MIN_VALUE, Short.MAX_VALUE);
        symbExpressions.put(varRefName, integerVariable);

        Short shortInstance;
        try {
            shortInstance = (Short) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant shortRef = newShortReference(shortInstance, integerVariable);
        symbReferences.put(varRefName, shortRef);
    }

    private ReferenceConstant newShortReference(Short concShort, IntegerValue symbValue) {
        ReferenceConstant shortRef = (ReferenceConstant) env.heap.getReference(concShort);
        env.heap.putField(Types.JAVA_LANG_SHORT, SymbolicHeap.$SHORT_VALUE, concShort, shortRef, symbValue);
        return shortRef;
    }

    private void after(LongPrimitiveStatement statement, Scope scope) {
        long valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf, Long.MIN_VALUE, Long.MAX_VALUE);
        symbExpressions.put(varRefName, integerVariable);

        Long longInstance;
        try {
            longInstance = (Long) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant longRef = newLongReference(longInstance, integerVariable);
        symbReferences.put(varRefName, longRef);
    }

    private ReferenceConstant newLongReference(Long concLong, IntegerValue symbValue) {
        ReferenceConstant longRef = (ReferenceConstant) env.heap.getReference(concLong);
        env.heap.putField(Types.JAVA_LANG_LONG, SymbolicHeap.$LONG_VALUE, concLong, longRef, symbValue);
        return longRef;
    }

    private void after(FloatPrimitiveStatement statement, Scope scope) {
        float valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        RealVariable realVariable = buildRealVariable(varRefName, valueOf, -Float.MAX_VALUE, Float.MAX_VALUE);
        symbExpressions.put(varRefName, realVariable);

        Float floatInstance;
        try {
            floatInstance = (Float) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant floatRef = newFloatReference(floatInstance, realVariable);
        symbReferences.put(varRefName, floatRef);
    }

    private ReferenceConstant newFloatReference(Float concFloat, RealValue symbValue) {
        ReferenceConstant floatRef = (ReferenceConstant) env.heap.getReference(concFloat);
        env.heap.putField(Types.JAVA_LANG_FLOAT, SymbolicHeap.$FLOAT_VALUE, concFloat, floatRef, symbValue);
        return floatRef;
    }

    private void after(CharPrimitiveStatement statement, Scope scope) {
        char valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf, Character.MIN_VALUE,
                Character.MAX_VALUE);
        symbExpressions.put(varRefName, integerVariable);

        Character character0;
        try {
            character0 = (Character) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant charRef = newCharacterReference(character0, integerVariable);
        symbReferences.put(varRefName, charRef);
    }

    private ReferenceConstant newCharacterReference(Character concChar, IntegerValue symbValue) {
        ReferenceConstant charRef = (ReferenceConstant) env.heap.getReference(concChar);
        env.heap.putField(Types.JAVA_LANG_CHARACTER, SymbolicHeap.$CHAR_VALUE, concChar, charRef, symbValue);
        return charRef;
    }

    private void after(BytePrimitiveStatement statement, Scope scope) {
        byte valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf, Byte.MIN_VALUE, Byte.MAX_VALUE);
        symbExpressions.put(varRefName, integerVariable);
        Byte byteInstance;
        try {
            byteInstance = (Byte) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }

        ReferenceConstant byteRef = newByteReference(byteInstance, integerVariable);

        symbReferences.put(varRefName, byteRef);
    }

    private ReferenceConstant newByteReference(Byte concByte, IntegerValue symbValue) {
        ReferenceConstant byteRef = (ReferenceConstant) env.heap.getReference(concByte);
        env.heap.putField(Types.JAVA_LANG_BYTE, SymbolicHeap.$BYTE_VALUE, concByte, byteRef, symbValue);
        return byteRef;
    }

    private void after(BooleanPrimitiveStatement statement, Scope scope) {
        boolean valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf ? 1 : 0, 0, 1);
        Boolean booleanInstance;
        try {
            booleanInstance = (Boolean) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }

        symbExpressions.put(varRefName, integerVariable);
        ReferenceConstant booleanRef = newBooleanReference(booleanInstance, integerVariable);
        symbReferences.put(varRefName, booleanRef);
    }

    private ReferenceConstant newBooleanReference(Boolean concBoolean, IntegerValue symbValue) {
        ReferenceConstant booleanRef = (ReferenceConstant) env.heap.getReference(concBoolean);
        env.heap.putField(Types.JAVA_LANG_BOOLEAN, SymbolicHeap.$BOOLEAN_VALUE, concBoolean, booleanRef, symbValue);
        return booleanRef;
    }

    private void after(DoublePrimitiveStatement statement, Scope scope) {
        double valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        RealVariable realVariable = buildRealVariable(varRefName, valueOf, -Double.MAX_VALUE, Double.MAX_VALUE);
        symbExpressions.put(varRefName, realVariable);

        Double doubleInstance;
        try {
            doubleInstance = (Double) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant doubleRef = newDoubleReference(doubleInstance, realVariable);
        symbReferences.put(varRefName, doubleRef);
    }

    private ReferenceConstant newDoubleReference(Double concDouble, RealValue symbValue) {
        ReferenceConstant doubleRef = (ReferenceConstant) env.heap.getReference(concDouble);
        env.heap.putField(Types.JAVA_LANG_DOUBLE, SymbolicHeap.$DOUBLE_VALUE, concDouble, doubleRef, symbValue);
        return doubleRef;
    }

    private void after(MethodStatement statement, Scope scope) {
        String owner = statement.getMethod().getDeclaringClass().getName().replace('.', '/');
        String name = statement.getMethod().getName();
        String desc = Type.getMethodDescriptor(statement.getMethod().getMethod());

        Type returnType = Type.getReturnType(statement.getMethod().getMethod());

        VariableReference varRef = statement.getReturnValue();
        String varName = varRef.getName();
        try {
            if (varRef.getType().equals(void.class)) {
                VM.CALL_RESULT(owner, name, desc);

            } else if (returnType.equals(Type.INT_TYPE)) {
                Integer res = (Integer) varRef.getObject(scope);
                VM.CALL_RESULT(res.intValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv32();
                ReferenceConstant newIntegerRef = newIntegerReference(res, intExpr);
                symbReferences.put(varName, newIntegerRef);
                symbExpressions.put(varName, intExpr);

            } else if (returnType.equals(Type.BOOLEAN_TYPE)) {
                Boolean res = (Boolean) varRef.getObject(scope);
                VM.CALL_RESULT(res.booleanValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv32();
                ReferenceConstant newBooleanRef = newBooleanReference(res, intExpr);
                symbReferences.put(varName, newBooleanRef);
                symbExpressions.put(varName, intExpr);

            } else if (returnType.equals(Type.DOUBLE_TYPE)) {
                Double res = (Double) varRef.getObject(scope);
                VM.CALL_RESULT(res.doubleValue(), owner, name, desc);
                RealValue realExpr = env.topFrame().operandStack.popFp64();
                ReferenceConstant newDoubleRef = newDoubleReference(res, realExpr);
                symbReferences.put(varName, newDoubleRef);
                symbExpressions.put(varName, realExpr);

            } else if (returnType.equals(Type.FLOAT_TYPE)) {
                Float res = (Float) varRef.getObject(scope);
                VM.CALL_RESULT(res.floatValue(), owner, name, desc);
                RealValue realExpr = env.topFrame().operandStack.popFp32();
                ReferenceConstant newFloatRef = newFloatReference(res, realExpr);
                symbReferences.put(varName, newFloatRef);
                symbExpressions.put(varName, realExpr);

            } else if (returnType.equals(Type.LONG_TYPE)) {
                Long res = (Long) varRef.getObject(scope);
                VM.CALL_RESULT(res.longValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv64();
                ReferenceConstant newBooleanRef = newLongReference(res, intExpr);
                symbReferences.put(varName, newBooleanRef);
                symbExpressions.put(varName, intExpr);

            } else if (returnType.equals(Type.SHORT_TYPE)) {
                Short res = (Short) varRef.getObject(scope);
                VM.CALL_RESULT(res.shortValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv32();
                ReferenceConstant newShortRef = newShortReference(res, intExpr);
                symbReferences.put(varName, newShortRef);
                symbExpressions.put(varName, intExpr);

            } else if (returnType.equals(Type.BYTE_TYPE)) {
                Byte res = (Byte) varRef.getObject(scope);
                VM.CALL_RESULT(res.byteValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv32();
                ReferenceConstant newByteRef = newByteReference(res, intExpr);
                symbReferences.put(varName, newByteRef);
                symbExpressions.put(varName, intExpr);

            } else if (returnType.equals(Type.CHAR_TYPE)) {
                Character res = (Character) varRef.getObject(scope);
                VM.CALL_RESULT(res.charValue(), owner, name, desc);
                IntegerValue intExpr = env.topFrame().operandStack.popBv32();
                ReferenceConstant newCharacterRef = newCharacterReference(res, intExpr);
                symbReferences.put(varName, newCharacterRef);
                symbExpressions.put(varName, intExpr);

            } else {
                Object res = varRef.getObject(scope);
                VM.CALL_RESULT(res, owner, name, desc);

                ReferenceExpression ref = env.topFrame().operandStack.peekRef();

                if (res != null && res instanceof String) {

                    String string = (String) res;
                    ReferenceConstant newStringRef = (ReferenceConstant) env.heap.getReference(string);
                    StringValue strExpr = env.heap.getField(Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE, string,
                            newStringRef, string);
                    symbReferences.put(varName, newStringRef);
                    symbExpressions.put(varName, strExpr);
                } else {
                    symbReferences.put(varName, ref);
                    if (res != null && isWrapper(res)) {
                        ReferenceConstant nonNullRef = (ReferenceConstant) ref;
                        Expression<?> expr = findOrCreate(res, nonNullRef);
                        symbExpressions.put(varName, expr);
                    }
                }

            }
        } catch (CodeUnderTestException e) {
            throw new RuntimeException(e);
        }
        // dispose all other arguments
        env.topFrame().operandStack.clearOperands();

    }

    private Expression<?> findOrCreate(Object concRef, ReferenceConstant symbRef) {
        if (concRef instanceof Boolean) {
            Boolean boolean0 = (Boolean) concRef;
            int concVal = boolean0 ? 1 : 0;
            return env.heap.getField(Types.JAVA_LANG_BOOLEAN, SymbolicHeap.$BOOLEAN_VALUE, boolean0, symbRef,
                    concVal);
        } else if (concRef instanceof Byte) {
            Byte byte0 = (Byte) concRef;
            byte concVal = byte0;
            return env.heap.getField(Types.JAVA_LANG_BYTE, SymbolicHeap.$BYTE_VALUE, byte0, symbRef, concVal);
        } else if (concRef instanceof Short) {
            Short short0 = (Short) concRef;
            short concVal = short0;
            return env.heap.getField(Types.JAVA_LANG_SHORT, SymbolicHeap.$SHORT_VALUE, short0, symbRef, concVal);
        } else if (concRef instanceof Character) {
            Character character0 = (Character) concRef;
            char concVal = character0;
            return env.heap.getField(Types.JAVA_LANG_CHARACTER, SymbolicHeap.$CHAR_VALUE, character0, symbRef,
                    concVal);
        } else if (concRef instanceof Integer) {
            Integer integer0 = (Integer) concRef;
            int concVal = integer0;
            return env.heap.getField(Types.JAVA_LANG_INTEGER, SymbolicHeap.$INT_VALUE, integer0, symbRef, concVal);
        } else if (concRef instanceof Long) {
            Long long0 = (Long) concRef;
            long concVal = long0;
            return env.heap.getField(Types.JAVA_LANG_LONG, SymbolicHeap.$LONG_VALUE, long0, symbRef, concVal);
        } else if (concRef instanceof Float) {
            Float float0 = (Float) concRef;
            float concVal = float0;
            return env.heap.getField(Types.JAVA_LANG_FLOAT, SymbolicHeap.$FLOAT_VALUE, float0, symbRef, concVal);
        } else if (concRef instanceof Double) {
            Double double0 = (Double) concRef;
            double concVal = double0;
            return env.heap.getField(Types.JAVA_LANG_FLOAT, SymbolicHeap.$DOUBLE_VALUE, double0, symbRef, concVal);
        } else {
            throw new EvosuiteError("Unreachable code!");
        }
    }

    private static boolean isWrapper(Object res) {
        return res instanceof Boolean || res instanceof Short || res instanceof Byte || res instanceof Integer
                || res instanceof Character || res instanceof Long || res instanceof Float || res instanceof Double;
    }

    private void after(StringPrimitiveStatement statement, Scope scope) {
        String valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        StringVariable stringVariable = buildStringVariable(varRefName, valueOf);
        symbExpressions.put(varRefName, stringVariable);

        String stringInstance;
        try {
            stringInstance = (String) varRef.getObject(scope);
            if (stringInstance != null) {
                stringInstance = new String(stringInstance);
            }
            scope.setObject(varRef, stringInstance);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant stringRef = newStringReference(stringInstance, stringVariable);
        symbReferences.put(varRefName, stringRef);
    }

    private void after(FileNamePrimitiveStatement statement, Scope scope) {

        EvoSuiteFile concEvosuiteFile = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();

        ReferenceExpression fileRef;
        if (concEvosuiteFile == null) {
            fileRef = env.heap.getReference(null);
            if (fileRef.getConcreteValue() != null) {
                throw new IllegalStateException("Expected null concrete object");
            }
        } else {
            fileRef = env.heap.getReference(concEvosuiteFile);
            if (fileRef.getConcreteValue() == null) {
                throw new IllegalStateException("Expected non-null concrete object");
            }
        }

        symbReferences.put(varRefName, fileRef);
    }

    private ReferenceConstant newStringReference(String concString, StringValue strExpr) {
        ReferenceConstant stringRef = (ReferenceConstant) env.heap.getReference(concString);
        env.heap.putField(Types.JAVA_LANG_STRING, SymbolicHeap.$STRING_VALUE, concString, stringRef, strExpr);
        return stringRef;
    }

    private final Map<String, Expression<?>> symbExpressions = new HashMap<>();
    private final Map<String, ReferenceExpression> symbReferences = new HashMap<>();
    private final Map<String, IntegerVariable> integerVariables = new HashMap<>();
    private final Map<String, RealVariable> realVariables = new HashMap<>();
    private final Map<String, StringVariable> stringVariables = new HashMap<>();

    private void after(IntPrimitiveStatement statement, Scope scope) {
        int valueOf = statement.getValue();
        VariableReference varRef = statement.getReturnValue();
        String varRefName = varRef.getName();
        IntegerVariable integerVariable = buildIntegerVariable(varRefName, valueOf, Integer.MIN_VALUE,
                Integer.MAX_VALUE);
        symbExpressions.put(varRefName, integerVariable);

        Integer integerInstance;
        try {
            integerInstance = (Integer) varRef.getObject(scope);
        } catch (CodeUnderTestException e) {
            throw new EvosuiteError(e);
        }
        ReferenceConstant integerRef = newIntegerReference(integerInstance, integerVariable);
        symbReferences.put(varRefName, integerRef);
    }

    private ReferenceConstant newIntegerReference(Integer concInteger, IntegerValue symbValue) {
        ReferenceConstant integerRef = (ReferenceConstant) env.heap.getReference(concInteger);
        env.heap.putField(Types.JAVA_LANG_INTEGER, SymbolicHeap.$INT_VALUE, concInteger, integerRef, symbValue);
        return integerRef;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        symbExpressions.clear();
        symbReferences.clear();
    }

    private IntegerVariable buildIntegerVariable(String name, long conV, long minValue, long maxValue) {

        IntegerVariable integerVariable;
        if (integerVariables.containsKey(name)) {
            integerVariable = integerVariables.get(name);
            integerVariable.setConcreteValue(conV);
            assert minValue == integerVariable.getMinValue();
            assert maxValue == integerVariable.getMaxValue();
        } else {
            integerVariable = new IntegerVariable(name, conV, minValue, maxValue);
            integerVariables.put(name, integerVariable);
        }
        return integerVariable;
    }

    private RealVariable buildRealVariable(String name, double conV, double minValue, double maxValue) {

        RealVariable realVariable;
        if (realVariables.containsKey(name)) {
            realVariable = realVariables.get(name);
            realVariable.setConcreteValue(conV);
            assert minValue == realVariable.getMinValue();
            assert maxValue == realVariable.getMaxValue();
        } else {
            realVariable = new RealVariable(name, conV, minValue, maxValue);
            realVariables.put(name, realVariable);
        }
        return realVariable;
    }

    private StringVariable buildStringVariable(String name, String concVal) {

        StringVariable stringVariable;
        if (stringVariables.containsKey(name)) {
            stringVariable = stringVariables.get(name);
            stringVariable.setConcreteValue(concVal);
        } else {
            stringVariable = new StringVariable(name, concVal);
            stringVariables.put(name, stringVariable);
        }
        return stringVariable;
    }


    /**
     * Upgrades a symbolic array from a literal to a variable in the symbolic heap.
     *
     * @param arrayRef a {@link org.evosuite.testcase.variable.ArrayReference} object.
     * @param concArray a {@link java.lang.Object} object.
     */
    private void upgradeSymbolicArrayLiteralToVariable(ArrayReference arrayRef, Object concArray) {
        if (Properties.IS_DSE_ARRAYS_SUPPORT_ENABLED) {
            ArrayVariable newSymArray = env.heap.buildNewArrayReferenceVariable(concArray, arrayRef.getName());
            env.heap.initializeReference(concArray, newSymArray);
            env.heap.putField(
                    "",
                    ARRAY_LENGTH,
                    concArray,
                    newSymArray,
                    symbExpressions.get(new ArraySymbolicLengthName(arrayRef.getName(),
                            UNIDIMENSIONAL_ARRAY_VALUE).getSymbolicName())
            );

            String varRefName = arrayRef.getName();
            symbReferences.put(varRefName, newSymArray);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testExecutionFinished(ExecutionResult r, Scope s) {
        // do nothing
    }

}
