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

import com.googlecode.gentyref.GenericTypeReflector;
import org.apache.commons.lang3.ClassUtils;
import org.evosuite.runtime.mock.OverrideMock;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.generic.GenericClassFactory;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This class defines an reference to an array element. E.g. foo[3].
 *
 * @author Sebastian Steenbuck
 */
public class ArrayIndex extends VariableReferenceImpl {

    private static final long serialVersionUID = -4492869536935582711L;

    /**
     * Index in the array.
     */
    private List<Integer> indices;

    /**
     * If this variable is contained in an array, this is the reference to the
     * array.
     */
    protected ArrayReference array = null;

    /**
     * Constructor.
     *
     * @param testCase a {@link org.evosuite.testcase.TestCase} object.
     * @param array    a {@link ArrayReference} object.
     * @param index    a int.
     */
    public ArrayIndex(TestCase testCase, ArrayReference array, int index) {
        this(testCase, array, Collections.singletonList(index));
    }

    /**
     * Constructor for ArrayIndex.
     *
     * @param testCase a {@link org.evosuite.testcase.TestCase} object.
     * @param array    a {@link ArrayReference} object.
     * @param indices  a {@link java.util.List} object.
     */
    public ArrayIndex(TestCase testCase, ArrayReference array, List<Integer> indices) {
        super(testCase, GenericClassFactory.get(getReturnType(array, indices.size())));
        this.array = array;
        setArrayIndices(indices);
    }

    private static Type getReturnType(ArrayReference array, int indicesCnt) {
        assert indicesCnt >= 1;
        Type result = array.getComponentType();
        // Class<?> result = (Class<?>) array.getComponentType();
        for (int idx = 1; idx < indicesCnt; idx++) {
            result = GenericTypeReflector.getArrayComponentType(result);
            //result = result.getComponentType();
        }
        return result;
    }

    /**
     * Getter for the field <code>array</code>.
     *
     * @return a {@link ArrayReference} object.
     */
    public ArrayReference getArray() {
        return array;
    }

    /**
     * Setter for the field <code>array</code>.
     *
     * @param r a {@link ArrayReference} object.
     */
    public void setArray(ArrayReference r) {
        array = r;
    }

    /**
     * Return true if variable is an array index.
     *
     * @return a boolean.
     */
    public boolean isArrayIndex() {
        return true;
    }

    /**
     * getArrayIndex.
     *
     * @return a int.
     */
    public int getArrayIndex() {
        assert indices.size() == 1;
        return indices.get(0);
    }

    /**
     * setArrayIndex.
     *
     * @param index a int.
     */
    public void setArrayIndex(int index) {
        assert indices.size() == 1;
        indices.set(0, index);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStPosition() {
        assert (array != null);
        for (int i = 0; i < testCase.size(); i++) {
            if (testCase.getStatement(i).getReturnValue().equals(this)) {
                return i;
            }
        }

        // notice that this case is only reached if no AssignmentStatement was used
        // to assign to the array index (as in that case the for loop would have found something).
        // Therefore the array must have been assigned in some method and we can return the method call

        return array.getStPosition();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Return name for source code representation.
     */
    @Override
    public String getName() {
        return ArrayUtil.buildArrayIndexName(array.getName(), indices);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void loadBytecode(GeneratorAdapter mg, Map<Integer, Integer> locals) {
        if (indices.size() > 1) {
            throw new RuntimeException("Not yet implemented for multidimensional arrays!");
        }
        array.loadBytecode(mg, locals);
        mg.push(indices.get(0));
        mg.arrayLoad(org.objectweb.asm.Type.getType(type.getRawClass()));

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void storeBytecode(GeneratorAdapter mg, Map<Integer, Integer> locals) {
        if (indices.size() > 1) {
            throw new RuntimeException("Not yet implemented for multidimensional arrays!");
        }

        int localVar = mg.newLocal(org.objectweb.asm.Type.getType(getVariableClass()));
        mg.storeLocal(localVar);

        array.loadBytecode(mg, locals);

        mg.push(indices.get(0));
        mg.loadLocal(localVar);

        mg.arrayStore(org.objectweb.asm.Type.getType(type.getRawClass()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean same(VariableReference r) {
        if (r == null) {
            return false;
        }

        if (!(r instanceof ArrayIndex)) {
            return false;
        }

        ArrayIndex other = (ArrayIndex) r;
        if (this.getStPosition() != r.getStPosition()) {
            return false;
        }

        if (!this.array.same(other.getArray())) {
            return false;
        }

        if (!indices.equals(other.indices)) {
            return false;
        }

        return this.type.equals(r.getGenericClass());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Return the actual object represented by this variable for a given scope.
     */
    @Override
    public Object getObject(Scope scope) throws CodeUnderTestException {
        Object arrayObject = array.getObject(scope);
        try {
            for (int idx = 0; idx < indices.size() - 1; idx++) {
                if (arrayObject == null) {
                    throw new CodeUnderTestException(new NullPointerException());
                }
                arrayObject = Array.get(arrayObject, indices.get(idx));
            }
            if (arrayObject == null) {
                throw new CodeUnderTestException(new NullPointerException());
            }
            return Array.get(arrayObject, indices.get(indices.size() - 1));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new CodeUnderTestException(e);
        }
    }

    private Integer getIntValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return ((Number) object).intValue();
        } else if (object instanceof Character) {
            return (int) (Character) object;
        } else {
            return 0;
        }
    }

    private Short getShortValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return (short) ((Number) object).intValue();
        } else if (object instanceof Character) {
            return (short) ((Character) object).charValue();
        } else {
            return 0;
        }
    }

    private Byte getByteValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return (byte) ((Number) object).intValue();
        } else if (object instanceof Character) {
            return (byte) ((Character) object).charValue();
        } else {
            return 0;
        }
    }

    private Long getLongValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return ((Number) object).longValue();
        } else if (object instanceof Character) {
            return (long) (Character) object;
        } else {
            return 0L;
        }
    }

    private Float getFloatValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return ((Number) object).floatValue();
        } else if (object instanceof Character) {
            return (float) (Character) object;
        } else {
            return 0F;
        }
    }

    private Double getDoubleValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number) {
            return ((Number) object).doubleValue();
        } else if (object instanceof Character) {
            return (double) (Character) object;
        } else {
            return 0.0;
        }
    }

    private Character getCharValue(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Character) {
            return (Character) object;
        } else if (object instanceof Number) {
            return (char) ((Number) object).intValue();
        } else {
            return '0';
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Set the actual object represented by this variable in a given scope.
     */
    @Override
    public void setObject(Scope scope, Object value) throws CodeUnderTestException {
        Object arrayObject = array.getObject(scope);
        try {
            for (int idx = 0; idx < indices.size() - 1; idx++) {
                if (arrayObject == null) {
                    throw new CodeUnderTestException(new NullPointerException());
                }
                arrayObject = Array.get(arrayObject, indices.get(idx));
            }
            if (arrayObject == null) {
                throw new CodeUnderTestException(new NullPointerException());
            }
            if (value == null && arrayObject.getClass().getComponentType().isPrimitive()) {
                throw new CodeUnderTestException(new NullPointerException());
            }

            Class<?> componentType = arrayObject.getClass().getComponentType();
            int lastIndex = indices.get(indices.size() - 1);

            if (ClassUtils.isPrimitiveWrapper(componentType)
                    && !ClassUtils.isPrimitiveWrapper(getVariableClass())) {
                // A wider static element type (Object, Number, ...) holds the value as is: the
                // generated test stores it without conversion, so a Short into a Long[] throws.
                // Only a wrapper-typed element is rendered with a conversion cast
                // ("(Long) (long) short0"), which the branches below mirror.
                checkReferenceStore(componentType, value);
                Array.set(arrayObject, lastIndex, value);
            } else if (componentType.equals(int.class)) {
                Array.setInt(arrayObject, lastIndex, getIntValue(value));
            } else if (componentType.equals(boolean.class)) {
                Array.setBoolean(arrayObject, lastIndex, (Boolean) value);
            } else if (componentType.equals(char.class)) {
                Array.setChar(arrayObject, lastIndex, getCharValue(value));
            } else if (componentType.equals(double.class)) {
                Array.setDouble(arrayObject, lastIndex, getDoubleValue(value));
            } else if (componentType.equals(float.class)) {
                Array.setFloat(arrayObject, lastIndex, getFloatValue(value));
            } else if (componentType.equals(long.class)) {
                Array.setLong(arrayObject, lastIndex, getLongValue(value));
            } else if (componentType.equals(short.class)) {
                Array.setShort(arrayObject, lastIndex, getShortValue(value));
            } else if (componentType.equals(byte.class)) {
                Array.setByte(arrayObject, lastIndex, getByteValue(value));
            } else if (componentType.equals(Integer.class)) {
                Array.set(arrayObject, lastIndex, getIntValue(value));
            } else if (componentType.equals(Boolean.class)) {
                checkReferenceStore(componentType, value);
                Array.set(arrayObject, lastIndex, value);
            } else if (componentType.equals(Character.class)) {
                Array.set(arrayObject, lastIndex, getCharValue(value));
            } else if (componentType.equals(Double.class)) {
                Array.set(arrayObject, lastIndex, getDoubleValue(value));
            } else if (componentType.equals(Float.class)) {
                Array.set(arrayObject, lastIndex, getFloatValue(value));
            } else if (componentType.equals(Long.class)) {
                Array.set(arrayObject, lastIndex, getLongValue(value));
            } else if (componentType.equals(Short.class)) {
                Array.set(arrayObject, lastIndex, getShortValue(value));
            } else if (componentType.equals(Byte.class)) {
                Array.set(arrayObject, lastIndex, getByteValue(value));
            } else {
                checkReferenceStore(componentType, value);
                Array.set(arrayObject, lastIndex, value);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new CodeUnderTestException(e);
        }
    }

    /**
     * The static type of an array (eg Object[]) can be wider than its runtime type (eg
     * String[]); the generated test then throws an ArrayStoreException, whereas
     * {@link Array#set} would signal this as an IllegalArgumentException.
     */
    private static void checkReferenceStore(Class<?> componentType, Object value)
            throws CodeUnderTestException {
        if (value != null && !componentType.isInstance(value)) {
            if (isMockSubstitution(componentType, value)) {
                // The generated test runs with the mocking instrumentation, which makes its
                // File a MockFile, so it stores without an exception. The search executes the
                // reflective constructor and holds the real class, which cannot be stored
                // here; leave the failure to Array.set so the test is invalid, rather than
                // recording an exception the generated test would not throw.
                return;
            }
            throw new CodeUnderTestException(new ArrayStoreException(value.getClass().getName()));
        }
    }

    private static boolean isMockSubstitution(Class<?> componentType, Object value) {
        // An override mock extends the class it replaces.
        return OverrideMock.class.isAssignableFrom(componentType)
                && componentType.getSuperclass() != null
                && componentType.getSuperclass().isInstance(value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create a copy of the current variable.
     */
    @Override
    public VariableReference copy(TestCase newTestCase, int offset) {
        ArrayReference otherArray = (ArrayReference) newTestCase.getStatement(array.getStPosition()
                + offset).getReturnValue();
        // must be set as we only use this to clone whole testcases
        return new ArrayIndex(newTestCase, otherArray, indices);
    }

    @Override
    public VariableReference clone(TestCase newTestCase) {
        return new ArrayIndex(newTestCase, (ArrayReference) array.clone(newTestCase), indices);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VariableReference getAdditionalVariableReference() {
        if (array.getAdditionalVariableReference() == null) {
            return array;
        } else {
            return array.getAdditionalVariableReference();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdditionalVariableReference(VariableReference var) {
        assert (var instanceof ArrayReference);
        array = (ArrayReference) var;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The element of the replacement array at the same indices, typed by the replacement's
     * component type. Declined if the replacement is not an array, or has fewer dimensions than
     * this element indexes into. EvoSuite might try to replace the array with a field reference,
     * but for this we have FieldStatements, which would give us ArrayReferences.
     */
    @Override
    public VariableReference withReplacement(VariableReference var1, VariableReference var2) {
        if (equals(var1)) {
            return var2;
        }
        VariableReference reboundArray = array.withReplacement(var1, var2);
        if (reboundArray == array) {
            return this;
        }
        if (!(reboundArray instanceof ArrayReference)
                || getReturnType((ArrayReference) reboundArray, indices.size()) == null) {
            return null;
        }
        return new ArrayIndex(testCase, (ArrayReference) reboundArray, new ArrayList<>(indices));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((array == null) ? 0 : array.hashCode());
        // TODO: indices shouldn't normally be null
        //       but it sometimes happens for StrongMutation...
        result = prime * result + ((indices == null) ? 0 : indices.hashCode());
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
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ArrayIndex other = (ArrayIndex) obj;
        if (array == null) {
            if (other.array != null) {
                return false;
            }
        } else if (!array.equals(other.array)) {
            return false;
        }
        return indices.equals(other.indices);
    }

    /**
     * setArrayIndices.
     *
     * @param indices a {@link java.util.List} object.
     */
    public void setArrayIndices(List<Integer> indices) {
        this.indices = new ArrayList<>();
        for (Integer i : indices) {
            this.indices.add(i);
        }
    }

    /**
     * getArrayIndices.
     *
     * @return a {@link java.util.List} object.
     */
    public List<Integer> getArrayIndices() {
        return indices;
    }
}
