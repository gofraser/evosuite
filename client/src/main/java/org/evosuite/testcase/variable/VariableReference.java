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
package org.evosuite.testcase.variable;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassImpl;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * This class represents a variable in a test case.
 *
 * <p>TODO: Store generic types in this variable - we know at creation what it is
 * (from method calls).
 *
 * @author Gordon Fraser
 */
public interface VariableReference extends Comparable<VariableReference>, Serializable {

    /**
     * The position of the statement, defining this VariableReference, in the
     * testcase.
     */
    int getStPosition();

    /**
     * Distance metric used to select variables for mutation based on how close
     * they are to the SUT.
     */
    int getDistance();

    /**
     * Set the distance metric.
     */
    void setDistance(int distance);

    /**
     * Create a copy of the current variable.
     */
    VariableReference clone();

    /**
     * Create a copy of the current variable for new test.
     */
    VariableReference clone(TestCase newTest);

    /**
     * Create a copy of the current variable for new test.
     */
    VariableReference copy(TestCase newTest, int offset);

    /**
     * Return simple class name.
     */
    String getSimpleClassName();

    /**
     * Return class name.
     */
    String getClassName();

    /**
     * getComponentName.
     */
    String getComponentName();

    /**
     * getComponentType.
     */
    Type getComponentType();

    /**
     * getGenericClass.
     */
    GenericClass<?> getGenericClass();

    /**
     * getTestCase.
     */
    TestCase getTestCase();

    /**
     * Return true if variable is an enumeration.
     */
    boolean isEnum();

    /**
     * Return true if variable is a primitive type.
     */
    boolean isPrimitive();

    /**
     * Return true if variable is void.
     */
    boolean isVoid();

    /**
     * Return true if variable is an array.
     */
    boolean isArray();

    /**
     * Return true if this is an index into an array variable.
     */
    boolean isArrayIndex();

    /**
     * Return true if this is a reference to a public field.
     */
    boolean isFieldReference();

    /**
     * Return true if variable is a string.
     */
    boolean isString();

    /**
     * Return true if type of variable is a primitive wrapper.
     */
    boolean isWrapperType();

    /**
     * Return true if we can validly access this variable.
     *
     * <p>This might not be the case for a field reference if the owner class is not accessible.
     *
     * @return true if accessible.
     */
    boolean isAccessible();

    /**
     * Return true if other type can be assigned to this variable.
     *
     * @param other Right hand side of the assignment.
     */
    boolean isAssignableFrom(Type other);

    /**
     * Return true if this variable can by assigned to a variable of other type.
     *
     * @param other Left hand side of the assignment.
     */
    boolean isAssignableTo(Type other);

    /**
     * Return true if other type can be assigned to this variable.
     *
     * @param other Right hand side of the assignment.
     */
    boolean isAssignableFrom(VariableReference other);

    /**
     * Return true if this variable can by assigned to a variable of other type.
     *
     * @param other Left hand side of the assignment.
     */
    boolean isAssignableTo(VariableReference other);

    /**
     * Return type of this variable.
     */
    Type getType();

    /**
     * Set type of this variable.
     */
    void setType(Type type);

    /**
     * Return raw class of this variable.
     */
    Class<?> getVariableClass();

    /**
     * Return raw class of this variable's component.
     */
    Class<?> getComponentClass();

    /**
     * Return the actual object represented by this variable for a given scope.
     *
     * @param scope The scope of the test case execution.
     * @throws org.evosuite.testcase.execution.CodeUnderTestException if code from the class
     *                                                                under test throws an
     *                                                                exception. (E.g. the
     *                                                                static init of a field)
     */
    Object getObject(Scope scope) throws CodeUnderTestException;

    /**
     * getOriginalCode.
     *
     * @return the code this variable reference stems from or null if it was generated.
     */
    String getOriginalCode();

    /**
     * Set the actual object represented by this variable in a given scope.
     *
     * @param scope The scope of the test case execution.
     * @param value The value to be assigned.
     * @throws org.evosuite.testcase.execution.CodeUnderTestException if code from the class
     *                                                                under test throws an
     *                                                                exception. (E.g. the
     *                                                                static init of a field)
     */
    void setObject(Scope scope, Object value) throws CodeUnderTestException;

    /**
     * Set the code fragment that defined this variable reference if imported
     * from an existing test case.
     *
     * @param code The code fragment that defined this variable reference.
     */
    void setOriginalCode(String code);

    /**
     * {@inheritDoc}
     *
     * <p>Return string representation of the variable.
     */
    @Override
    String toString();

    /**
     * Return name for source code representation.
     */
    String getName();

    /**
     * getAdditionalVariableReference.
     */
    VariableReference getAdditionalVariableReference();

    /**
     * setAdditionalVariableReference.
     */
    void setAdditionalVariableReference(VariableReference var);

    /**
     * The reference that takes this one's place in a statement when {@code var1} is replaced by
     * {@code var2}: {@code var2} if this reference is {@code var1}, a new reference if
     * {@code var1} is used inside this one (the owner of a field, the array of an element), and
     * this reference itself if it does not use {@code var1}.
     *
     * <p>This reference is never changed. Element and field references are shared by the
     * statements that use them, so rebinding one in place for one statement would silently
     * rebind, and retype, it in every other statement too. The result is computed from
     * {@code var2} and from what this reference was declared as, never from state another
     * replacement could have changed.
     *
     * @param var1 the variable to be replaced
     * @param var2 its replacement
     * @return the reference to use instead of this one, or null if this reference cannot be
     *         rebound to {@code var2} (it would have to keep using {@code var1})
     */
    default VariableReference withReplacement(VariableReference var1, VariableReference var2) {
        return equals(var1) ? var2 : this;
    }

    /**
     * loadBytecode.
     */
    void loadBytecode(GeneratorAdapter mg, Map<Integer, Integer> locals);

    /**
     * storeBytecode.
     */
    void storeBytecode(GeneratorAdapter mg, Map<Integer, Integer> locals);

    /**
     * changeClassLoader.
     */
    void changeClassLoader(ClassLoader loader);

    /**
     * getDefaultValue.
     */
    Object getDefaultValue();

    /**
     * getDefaultValueString.
     */
    String getDefaultValueString();

    @Override
    int compareTo(VariableReference other);

    /**
     * same.
     */
    boolean same(VariableReference r);
}
