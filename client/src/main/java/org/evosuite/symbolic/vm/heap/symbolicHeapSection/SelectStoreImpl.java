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
package org.evosuite.symbolic.vm.heap.symbolicHeapSection;

import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.ref.array.ArrayConstant;
import org.evosuite.symbolic.expr.ref.array.ArrayValue;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.utils.TypeUtil;
import org.objectweb.asm.Type;

import java.util.HashMap;
import java.util.Map;

/**
 * Arrays implementation using a composition of select / store operations.
 *
 * @author Ignacio Lebrero
 */
public class SelectStoreImpl implements ArraysSection {

    /**
     * Symbolic Arrays Memory model.
     *
     * <p>TODO: Implement Strings and References</p>
     */
    private final Map<ReferenceExpression, ArrayValue.RealArrayValue> realArrays = new HashMap<>();
    private final Map<ReferenceExpression, ArrayValue.StringArrayValue> stringArrays = new HashMap<>();
    private final Map<ReferenceExpression, ArrayValue.IntegerArrayValue> integerArrays = new HashMap<>();
    private final Map<ReferenceExpression, ArrayValue.ReferenceArrayValue> referenceArrays = new HashMap<>();


    @Override
    public IntegerValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                  IntegerValue symbolicValue) {
        ArrayValue.IntegerArrayValue arrayExpression = integerArrays.get(symbolicArray);
        return ExpressionFactory.buildArraySelectExpression(arrayExpression, symbolicIndex, symbolicValue);
    }

    /**
     * TODO: Implement me!.
     *
     * @param symbolicArray the symbolic array
     * @param symbolicIndex the symbolic index
     * @param symbolicValue the symbolic value
     * @return the loaded value
     */
    @Override
    public ReferenceExpression arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                         ReferenceExpression symbolicValue) {
        // ArrayValue.ReferenceArrayValue arrayExpression = referenceArrays.get(symbolicArray);
        return null;
    }

    @Override
    public RealValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                               RealValue symbolicValue) {
        ArrayValue.RealArrayValue arrayExpression = realArrays.get(symbolicArray);
        return ExpressionFactory.buildArraySelectExpression(arrayExpression, symbolicIndex, symbolicValue);
    }

    @Override
    public StringValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                 StringValue symbolicValue) {
        ArrayValue.StringArrayValue arrayExpression = stringArrays.get(symbolicArray);
        return ExpressionFactory.buildArraySelectExpression(arrayExpression, symbolicIndex, symbolicValue);
    }

    @Override
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           IntegerValue symbolicValue) {
        ArrayValue.IntegerArrayValue symbolicArrayInstance = integerArrays.get(symbolicArray);
        ArrayValue.IntegerArrayValue newSymbolicArrayInstance = ExpressionFactory.buildArrayStoreExpression(
                symbolicArrayInstance,
                symbolicIndex,
                symbolicValue,
                concreteArray
        );

        integerArrays.put(symbolicArray, newSymbolicArrayInstance);
    }

    @Override
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           RealValue symbolicValue) {

        ArrayValue.RealArrayValue symbolicArrayInstance = realArrays.get(symbolicArray);
        ArrayValue.RealArrayValue newSymbolicArrayInstance = ExpressionFactory.buildArrayStoreExpression(
                symbolicArrayInstance,
                symbolicIndex,
                symbolicValue,
                concreteArray
        );

        realArrays.put(symbolicArray, newSymbolicArrayInstance);
    }

    /**
     * TODO: Implement me!.
     *
     * @param concreteArray the concrete array
     * @param symbolicArray the symbolic array
     * @param symbolicIndex the symbolic index
     * @param symbolicValue the symbolic value
     */
    @Override
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           ReferenceExpression symbolicValue) {
        /*
         * ArrayValue.ReferenceArrayValue symbolicArrayInstance = referenceArrays.get(symbolicArray);
         * ArrayValue.ReferenceArrayValue newSymbolicArrayInstance = ExpressionFactory.buildArrayStoreExpression(
         *     symbolicArrayInstance,
         *     symbolicIndex,
         *     symbolicValue,
         *     concreteArray
         * );
         *
         * referenceArrays.put(symbolicArray, newSymbolicArrayInstance);
         */
    }


    @Override
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           StringValue symbolicValue) {
        ArrayValue.StringArrayValue symbolicArrayInstance = stringArrays.get(symbolicArray);
        ArrayValue.StringArrayValue newSymbolicArrayInstance = ExpressionFactory.buildArrayStoreExpression(
                symbolicArrayInstance,
                symbolicIndex,
                symbolicValue,
                concreteArray
        );

        stringArrays.put(symbolicArray, newSymbolicArrayInstance);
    }

    @Override
    public ArrayVariable createVariableArray(Object concreteArray, int instanceId, String arrayName) {
        ArrayVariable arrayVariable = (ArrayVariable) ExpressionFactory.buildArrayVariableExpression(instanceId,
                arrayName, concreteArray);

        if (concreteArray != null) {
            initializeArrayReference(arrayVariable);
        }

        return arrayVariable;
    }

    @Override
   public ArrayConstant createConstantArray(Type arrayType, int instanceId) {
        ArrayConstant arrayConstant = (ArrayConstant) ExpressionFactory.buildArrayConstantExpression(arrayType,
                instanceId);

        return arrayConstant;
    }

    @Override
    public void initializeArrayReference(ReferenceExpression symbolicArray) {
        Type arrayType = symbolicArray.getObjectType().getElementType();

        if (TypeUtil.isIntegerValue(arrayType)) {
            initIntegerArray(symbolicArray);
        } else if (TypeUtil.isRealValue(arrayType)) {
            initRealArray(symbolicArray);
        } else if (TypeUtil.isStringValue(arrayType)) {
            initStringArray(symbolicArray);
        } else {
            initReferenceArray(symbolicArray);
        }
    }

    private void initReferenceArray(ReferenceExpression symbolicArray) {
        referenceArrays.put(
                symbolicArray,
                (ArrayValue.ReferenceArrayValue) symbolicArray
        );
    }

    private void initStringArray(ReferenceExpression symbolicArray) {
        stringArrays.put(
                symbolicArray,
                (ArrayValue.StringArrayValue) symbolicArray
        );
    }

    private void initRealArray(ReferenceExpression symbolicArray) {
        realArrays.put(
                symbolicArray,
                (ArrayValue.RealArrayValue) symbolicArray
        );
    }

    private void initIntegerArray(ReferenceExpression symbolicArray) {
        integerArrays.put(
                symbolicArray,
                (ArrayValue.IntegerArrayValue) symbolicArray
        );
    }
}
