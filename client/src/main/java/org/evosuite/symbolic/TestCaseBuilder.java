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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.symbolic;

import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.statements.environment.FileNamePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.*;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestCaseBuilder {

    private final DefaultTestCase testCase;
    private final Map<Integer, Throwable> exceptions = new HashMap<>();

    private int nextPosition;

    public TestCaseBuilder() {
        this.testCase = new DefaultTestCase();
        this.nextPosition = 0;
    }

    public TestCaseBuilder(DefaultTestCase testCase, int startingPosition) {
        this.testCase = testCase;
        this.nextPosition = startingPosition;
    }

    /**
     * Appends a constructor statement to the test case.
     *
     * @param constructor a {@link java.lang.reflect.Constructor} object.
     * @param parameters an array of {@link org.evosuite.testcase.variable.VariableReference} objects.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendConstructor(Constructor<?> constructor, VariableReference... parameters) {
        List<VariableReference> parameterList = Arrays.asList(parameters);
        ConstructorStatement constructorStmt = new ConstructorStatement(testCase,
                new GenericConstructor(constructor,
                        constructor.getDeclaringClass()), parameterList);

        addStatement(constructorStmt);


        return constructorStmt.getReturnValue();
    }

    /**
     * Appends an int primitive statement to the test case.
     *
     * @param intValue a int.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendIntPrimitive(int intValue) {
        IntPrimitiveStatement primitiveStmt = new IntPrimitiveStatement(testCase,
                intValue);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Returns the code representation of the test case.
     *
     * @return a {@link java.lang.String} object.
     */
    public String toCode() {
        return testCase.toCode();
    }

    /**
     * Appends a method call statement to the test case.
     *
     * @param callee     <code>null</code> for static methods
     * @param method     the method to be called
     * @param parameters the parameters for the method call
     * @return <code>void reference</code> for void methods
     */
    public VariableReference appendMethod(VariableReference callee,
                                          Method method, VariableReference... parameters) {
        List<VariableReference> parameterList = Arrays.asList(parameters);
        MethodStatement methodStmt = null;
        if (callee == null) {
            methodStmt = new MethodStatement(testCase, new GenericMethod(method,
                    method.getDeclaringClass()), callee, parameterList);
        } else {
            methodStmt = new MethodStatement(testCase, new GenericMethod(method,
                    callee.getType()), callee, parameterList);
        }
        addStatement(methodStmt);
        return methodStmt.getReturnValue();
    }

    /**
     * Returns the default test case.
     *
     * @return a {@link org.evosuite.testcase.DefaultTestCase} object.
     */
    public DefaultTestCase getDefaultTestCase() {
        return testCase;
    }

    /**
     * Appends a string primitive statement to the test case.
     *
     * @param string a {@link java.lang.String} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendStringPrimitive(String string) {
        StringPrimitiveStatement primitiveStmt = new StringPrimitiveStatement(
                testCase, string);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a boolean primitive statement to the test case.
     *
     * @param b a boolean.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendBooleanPrimitive(boolean b) {
        BooleanPrimitiveStatement primitiveStmt = new BooleanPrimitiveStatement(
                testCase, b);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a double primitive statement to the test case.
     *
     * @param d a double.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendDoublePrimitive(double d) {
        DoublePrimitiveStatement primitiveStmt = new DoublePrimitiveStatement(
                testCase, d);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends an assignment statement to the test case.
     *
     * @param receiver a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param field a {@link java.lang.reflect.Field} object.
     * @param value a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public void appendAssignment(VariableReference receiver, Field field,
                                 VariableReference value) {
        FieldReference fieldReference;

        if (receiver == null) {
            fieldReference = new FieldReference(testCase, new GenericField(field,
                    field.getDeclaringClass()));
        } else {
            fieldReference = new FieldReference(testCase, new GenericField(field,
                    receiver.getType()), receiver);
        }
        AssignmentStatement stmt = new AssignmentStatement(testCase, fieldReference,
                value);
        addStatement(stmt);
    }

    /**
     * Appends a static field statement to the test case.
     *
     * @param field a {@link java.lang.reflect.Field} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendStaticFieldStmt(Field field) {
        Class<?> declaringClass = field.getDeclaringClass();
        final GenericField genericField = new GenericField(field,
                declaringClass);
        // static field (receiver is null)
        FieldStatement stmt = new FieldStatement(testCase, genericField, null);
        addStatement(stmt);
        return stmt.getReturnValue();
    }

    /**
     * Appends a field statement to the test case.
     *
     * @param receiver a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param field a {@link java.lang.reflect.Field} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendFieldStmt(VariableReference receiver,
                                             Field field) {

        if (receiver == null) {
            throw new NullPointerException(
                    "Receiver object for a non-static field cannot be null");
        }
        FieldStatement stmt = new FieldStatement(testCase, new GenericField(field,
                receiver.getType()), receiver);
        addStatement(stmt);
        return stmt.getReturnValue();
    }

    /**
     * Appends a null statement to the test case.
     *
     * @param type a {@link java.lang.reflect.Type} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendNull(Type type) {
        NullStatement stmt = new NullStatement(testCase, type);
        addStatement(stmt);
        return stmt.getReturnValue();
    }

    /**
     * Appends an enum primitive statement to the test case.
     *
     * @param value a {@link java.lang.Enum} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendEnumPrimitive(Enum<?> value) {
        EnumPrimitiveStatement primitiveStmt = new EnumPrimitiveStatement(testCase,
                value);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends an array statement to the test case.
     *
     * @param type a {@link java.lang.reflect.Type} object.
     * @param length an array of int.
     * @return a {@link org.evosuite.testcase.variable.ArrayReference} object.
     */
    public ArrayReference appendArrayStmt(Type type, int... length) {
        ArrayStatement arrayStmt = new ArrayStatement(testCase, type, length);
        addStatement(arrayStmt);
        return (ArrayReference) arrayStmt.getReturnValue();
    }

    /**
     * Appends an array element assignment.
     * array[index] := var
     *
     * @param array the array reference
     * @param index the element index
     * @param var   the value to be assigned
     */
    public void appendAssignment(ArrayReference array, int index,
                                 VariableReference var) {

        ArrayIndex arrayIndex = new ArrayIndex(testCase, array, index);
        AssignmentStatement stmt = new AssignmentStatement(testCase, arrayIndex, var);
        addStatement(stmt);
    }

    /**
     * Appends a multi-dimensional array element assignment.
     * array[index[0]][index[1]]...[index[n]] := var
     *
     * @param array the array reference
     * @param index the list of indices
     * @param var   the value to be assigned
     */
    public void appendAssignment(ArrayReference array, List<Integer> index,
                                 VariableReference var) {

        ArrayIndex arrayIndex = new ArrayIndex(testCase, array, index);
        AssignmentStatement stmt = new AssignmentStatement(testCase, arrayIndex, var);
        addStatement(stmt);
    }

    /**
     * Appends an assignment from an array element to a variable.
     * var := array[index]
     *
     * @param var   the variable reference
     * @param array the array reference
     * @param index the element index
     */
    public void appendAssignment(VariableReference var, ArrayReference array,
                                 int index) {
        ArrayIndex arrayIndex = new ArrayIndex(testCase, array, index);
        AssignmentStatement stmt = new AssignmentStatement(testCase, var, arrayIndex);
        addStatement(stmt);
    }

    /**
     * Appends a long primitive statement to the test case.
     *
     * @param l a long.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendLongPrimitive(long l) {
        LongPrimitiveStatement primitiveStmt = new LongPrimitiveStatement(testCase, l);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a float primitive statement to the test case.
     *
     * @param f a float.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendFloatPrimitive(float f) {
        FloatPrimitiveStatement primitiveStmt = new FloatPrimitiveStatement(testCase,
                f);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a short primitive statement to the test case.
     *
     * @param s a short.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendShortPrimitive(short s) {
        ShortPrimitiveStatement primitiveStmt = new ShortPrimitiveStatement(testCase,
                s);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a byte primitive statement to the test case.
     *
     * @param b a byte.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendBytePrimitive(byte b) {
        BytePrimitiveStatement primitiveStmt = new BytePrimitiveStatement(testCase, b);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a char primitive statement to the test case.
     *
     * @param c a char.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendCharPrimitive(char c) {
        CharPrimitiveStatement primitiveStmt = new CharPrimitiveStatement(testCase, c);
        addStatement(primitiveStmt);
        return primitiveStmt.getReturnValue();
    }

    /**
     * Appends a class primitive statement to the test case.
     *
     * @param value a {@link java.lang.Class} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendClassPrimitive(Class<?> value) {
        ClassPrimitiveStatement stmt = new ClassPrimitiveStatement(testCase, value);
        addStatement(stmt);
        return stmt.getReturnValue();
    }

    /**
     * Appends a file name primitive statement to the test case.
     *
     * @param evosuiteFile a {@link org.evosuite.runtime.testdata.EvoSuiteFile} object.
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference appendFileNamePrimitive(EvoSuiteFile evosuiteFile) {
        FileNamePrimitiveStatement stmt = new FileNamePrimitiveStatement(testCase, evosuiteFile);
        addStatement(stmt);
        return stmt.getReturnValue();
    }


    /**
     * Appends an assignment from a field to another field.
     * x.f1 := y.f2
     *
     * @param receiver the receiver object of the destination field
     * @param field    the destination field
     * @param src      the receiver object of the source field
     * @param fieldSrc the source field
     */
    public void appendAssignment(VariableReference receiver, Field field,
                                 VariableReference src, Field fieldSrc) {
        FieldReference dstFieldReference;
        if (receiver == null) {
            dstFieldReference = new FieldReference(testCase, new GenericField(field,
                    field.getDeclaringClass()));
        } else {
            dstFieldReference = new FieldReference(testCase, new GenericField(field,
                    receiver.getType()), receiver);
        }

        FieldReference srcFieldReference;
        if (src == null) {
            srcFieldReference = new FieldReference(testCase, new GenericField(fieldSrc,
                    fieldSrc.getDeclaringClass()));
        } else {
            srcFieldReference = new FieldReference(testCase, new GenericField(fieldSrc,
                    src.getType()), src);
        }
        AssignmentStatement stmt = new AssignmentStatement(testCase, dstFieldReference,
                srcFieldReference);

        addStatement(stmt);
    }


    /**
     * Adds an exception to the current statement.
     *
     * @param exception a {@link java.lang.Throwable} object.
     */
    public void addException(Throwable exception) {
        int currentPos = this.testCase.size() - 1;
        if (currentPos < 0) {
            throw new IllegalStateException("Cannot add exception to empty test case");
        }

        if (exceptions.containsKey(currentPos)) {
            throw new IllegalStateException("Statement already contains an exception!");
        }

        exceptions.put(currentPos, exception);
    }

    /**
     * Inserts an statement on the current selected position.
     *
     * @param stmt the statement to be added
     */
    private void addStatement(Statement stmt) {

        if (nextPosition == 0) {
            testCase.addStatement(stmt);
        } else {
            testCase.addStatement(stmt, nextPosition);
        }

        nextPosition++;
    }

}
