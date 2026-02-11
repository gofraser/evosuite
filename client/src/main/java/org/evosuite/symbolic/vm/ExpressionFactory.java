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
package org.evosuite.symbolic.vm;

import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerBinaryExpression;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealBinaryExpression;
import org.evosuite.symbolic.expr.fp.RealConstant;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.NullReferenceConstant;
import org.evosuite.symbolic.expr.ref.array.ArrayConstant;
import org.evosuite.symbolic.expr.ref.array.ArraySelect;
import org.evosuite.symbolic.expr.ref.array.ArrayStore;
import org.evosuite.symbolic.expr.ref.array.ArrayValue;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.evosuite.symbolic.expr.reftype.ArrayTypeConstant;
import org.evosuite.symbolic.expr.reftype.ClassTypeConstant;
import org.evosuite.symbolic.expr.reftype.LambdaSyntheticTypeConstant;
import org.evosuite.symbolic.expr.reftype.NullTypeConstant;
import org.evosuite.symbolic.expr.str.StringConstant;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.utils.TypeUtil;
import org.objectweb.asm.Type;


/**
 * Expression factory for creating symbolic expressions.
 *
 * @author galeotti
 */
public abstract class ExpressionFactory {

    /** Primitive Constants. */
    public static final RealConstant RCONST_2 = new RealConstant(2);
    public static final RealConstant RCONST_1 = new RealConstant(1);
    public static final RealConstant RCONST_0 = new RealConstant(0);
    public static final IntegerConstant ICONST_5 = new IntegerConstant(5);
    public static final IntegerConstant ICONST_4 = new IntegerConstant(4);
    public static final IntegerConstant ICONST_3 = new IntegerConstant(3);
    public static final IntegerConstant ICONST_2 = new IntegerConstant(2);
    public static final IntegerConstant ICONST_1 = new IntegerConstant(1);
    public static final IntegerConstant ICONST_0 = new IntegerConstant(0);
    public static final IntegerConstant ICONST_M1 = new IntegerConstant(-1);

    /** Reference Constants. */
    public static final NullReferenceConstant NULL_REFERENCE = NullReferenceConstant.getInstance();

    /** Reference Type Constants. */
    public static final NullTypeConstant NULL_TYPE_REFERENCE = NullTypeConstant.getInstance();
    public static final ClassTypeConstant OBJECT_TYPE_REFERENCE = buildObjectTypeConstant();

    /**
     * Builds a new integer constant.
     *
     * @param value the concrete value
     * @return a new IntegerConstant
     */
    public static IntegerConstant buildNewIntegerConstant(int value) {
        return buildNewIntegerConstant((long) value);
    }

    /**
     * Builds a new integer constant.
     *
     * @param value the concrete value
     * @return a new IntegerConstant
     */
    public static IntegerConstant buildNewIntegerConstant(long value) {
        if (value == -1) {
            return ICONST_M1;
        } else if (value == 0) {
            return ICONST_0;
        } else if (value == 1) {
            return ICONST_1;
        } else if (value == 2) {
            return ICONST_2;
        } else if (value == 3) {
            return ICONST_3;
        } else if (value == 4) {
            return ICONST_4;
        } else if (value == 5) {
            return ICONST_5;
        }

        return new IntegerConstant(value);
    }

    /**
     * Builds a new real constant.
     *
     * @param x the concrete value
     * @return a new RealConstant
     */
    public static RealConstant buildNewRealConstant(float x) {
        return buildNewRealConstant((double) x);
    }

    /**
     * Builds a new real constant.
     *
     * @param x the concrete value
     * @return a new RealConstant
     */
    public static RealConstant buildNewRealConstant(double x) {
        if (x == 0) {
            return RCONST_0;
        } else if (x == 1) {
            return RCONST_1;
        } else if (x == 2) {
            return RCONST_2;
        }

        return new RealConstant(x);
    }

    /**
     * Builds a new string constant.
     *
     * @param string the concrete value
     * @return a new StringConstant
     */
    public static StringConstant buildNewStringConstant(String string) {
        return new StringConstant(string.intern());
    }

    /**
     * Builds an addition expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new addition expression
     */
    public static IntegerValue add(IntegerValue left, IntegerValue right,
                                   long con) {
        if (!(left instanceof IntegerConstant)
                && (right instanceof IntegerConstant)) {

            return buildAddNormalized(right, left, con);
        } else {
            return buildAddNormalized(left, right, con);
        }
    }

    private static IntegerValue buildAddNormalized(IntegerValue left,
                                                   IntegerValue right, long con) {

        // can only optimize if left is a literal
        if (!(left instanceof IntegerConstant)) {
            return new IntegerBinaryExpression(left, Operator.PLUS, right, con);
        }

        /*
         * (add 0 x) --> x
         */
        if (left.getConcreteValue() == 0) {
            return right;
        }

        /*
         * (add a b) --> result of a+b
         */
        if (right instanceof IntegerConstant) {
            long a = left.getConcreteValue();
            long b = right.getConcreteValue();
            return buildNewIntegerConstant(a + b);
        }

        /*
         * (add a (add b x)) --> (add (a+b) x)
         */
        if (right instanceof IntegerBinaryExpression
                && ((IntegerBinaryExpression) right).getOperator() == Operator.PLUS) {
            IntegerBinaryExpression add = (IntegerBinaryExpression) right;
            if (add.getLeftOperand() instanceof IntegerConstant) {
                long a = left.getConcreteValue();
                long b = add.getLeftOperand().getConcreteValue();

                IntegerConstant sum = buildNewIntegerConstant(a + b);

                return new IntegerBinaryExpression(sum, Operator.PLUS,
                        add.getRightOperand(), con);
            }
        }

        return new IntegerBinaryExpression(left, Operator.PLUS, right, con);
    }

    /**
     * Builds an addition expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new addition expression
     */
    public static RealValue add(RealValue left, RealValue right, double con) {
        if (!(left instanceof RealConstant) && (right instanceof RealConstant)) {

            return buildAddNormalized(right, left, con);
        } else {
            return buildAddNormalized(left, right, con);
        }
    }

    private static RealValue buildAddNormalized(RealValue right,
                                                RealValue left, double con) {
        // can only optimize if left is a literal
        if (!(left instanceof RealConstant)) {
            return new RealBinaryExpression(left, Operator.PLUS, right, con);
        }

        /*
         * (add 0 x) --> x
         */
        if (left.getConcreteValue() == 0) {
            return right;
        }

        /*
         * (add a b) --> result of a+b
         */
        if (right instanceof RealConstant) {
            double a = left.getConcreteValue();
            double b = right.getConcreteValue();
            return buildNewRealConstant(a + b);
        }

        /*
         * (add a (add b x)) --> (add (a+b) x)
         */
        if (right instanceof RealBinaryExpression
                && ((RealBinaryExpression) right).getOperator() == Operator.PLUS) {
            RealBinaryExpression add = (RealBinaryExpression) right;
            if (add.getLeftOperand() instanceof RealConstant) {
                double a = left.getConcreteValue();
                double b = add.getLeftOperand().getConcreteValue();

                RealConstant sum = buildNewRealConstant(a + b);

                return new RealBinaryExpression(sum, Operator.PLUS,
                        add.getRightOperand(), con);
            }
        }

        return new RealBinaryExpression(left, Operator.PLUS, right, con);

    }

    /**
     * Builds a multiplication expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new multiplication expression
     */
    public static IntegerValue mul(IntegerValue left, IntegerValue right,
                                   long con) {

        if ((!(left instanceof IntegerConstant))
                && (right instanceof IntegerConstant)) {
            return buildMulNormalized(right, left, con);
        } else {
            return buildMulNormalized(left, right, con);
        }

    }

    private static IntegerValue buildMulNormalized(IntegerValue right,
                                                   IntegerValue left, long con) {

        /*
         * (mul 0 x) --> 0
         */
        if ((left instanceof IntegerConstant) && left.getConcreteValue() == 0) {
            return buildNewIntegerConstant(0);
        }

        /*
         * (mul 1 x) --> x
         */
        if ((left instanceof IntegerConstant) && left.getConcreteValue() == 1) {
            return right;
        }

        /*
         * (mul a b) --> result of a*b
         */
        if ((left instanceof IntegerConstant)
                && (right instanceof IntegerConstant)) {
            long a = left.getConcreteValue();
            long b = right.getConcreteValue();
            return buildNewIntegerConstant(a * b);

        }

        return new IntegerBinaryExpression(left, Operator.MUL, right,
                con);
    }

    /**
     * Builds a multiplication expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new multiplication expression
     */
    public static RealValue mul(RealValue left, RealValue right, double con) {

        if ((!(left instanceof RealConstant))
                && (right instanceof RealConstant)) {
            return buildMulNormalized(right, left, con);
        } else {
            return buildMulNormalized(left, right, con);
        }

    }

    private static RealValue buildMulNormalized(RealValue right,
                                                RealValue left, double con) {

        /*
         * (mul 0 x) --> 0
         */
        if ((left instanceof RealConstant) && left.getConcreteValue() == 0.0) {
            return buildNewRealConstant(0.0);
        }

        /*
         * (mul 1 x) --> x
         */
        if ((left instanceof RealConstant) && left.getConcreteValue() == 1.0) {
            return right;
        }

        /*
         * (mul a b) --> result of a*b
         */
        if ((left instanceof RealConstant) && (right instanceof RealConstant)) {
            double a = left.getConcreteValue();
            double b = right.getConcreteValue();
            return buildNewRealConstant(a * b);

        }

        return new RealBinaryExpression(left, Operator.MUL, right, con);
    }

    /**
     * Builds a division expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new division expression
     */
    public static RealValue div(RealValue left, RealValue right, double con) {

        /*
         * (div 0 x) --> 0
         */
        if (left instanceof RealConstant && left.getConcreteValue() == 0) {
            return buildNewRealConstant(0);
        }

        return new RealBinaryExpression(left, Operator.DIV, right, con);
    }

    /**
     * Builds a division expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new division expression
     */
    public static IntegerValue div(IntegerValue left, IntegerValue right,
                                   long con) {

        /*
         * (div 0 x) --> 0
         */
        if (left instanceof IntegerConstant && left.getConcreteValue() == 0) {
            return buildNewIntegerConstant(0);
        }

        return new IntegerBinaryExpression(left, Operator.DIV, right, con);
    }

    /**
     * Builds a remainder expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new remainder expression
     */
    public static RealValue rem(RealValue left, RealValue right, double con) {

        /*
         * (rem 0 x) --> 0
         */
        if (left instanceof RealConstant && left.getConcreteValue() == 0) {
            return buildNewRealConstant(0);
        }

        return new RealBinaryExpression(left, Operator.REM, right, con);
    }

    /**
     * Builds a remainder expression.
     *
     * @param left the left operand
     * @param right the right operand
     * @param con the concrete result
     * @return a new remainder expression
     */
    public static IntegerValue rem(IntegerValue left, IntegerValue right,
                                   long con) {

        /*
         * (rem 0 x) --> 0
         */
        if (left instanceof IntegerConstant && left.getConcreteValue() == 0) {
            return buildNewIntegerConstant(0);
        }

        return new IntegerBinaryExpression(left, Operator.REM, right, con);
    }

    /* *************************** Arrays. *************************** */

    /**
     * Builds an integer array constant expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @return a new IntegerArrayConstant
     */
    public static ArrayValue.IntegerArrayValue buildIntegerArrayConstantExpression(Type objectType, int instanceId) {
        return new ArrayConstant.IntegerArrayConstant(objectType, instanceId);
    }

    /**
     * Builds a real array constant expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @return a new RealArrayConstant
     */
    public static ArrayValue.RealArrayValue buildRealArrayConstantExpression(Type objectType, int instanceId) {
        return new ArrayConstant.RealArrayConstant(objectType, instanceId);
    }

    /**
     * Builds a string array constant expression.
     *
     * @param instanceId the instance id
     * @return a new StringArrayConstant
     */
    public static ArrayValue.StringArrayValue buildStringArrayConstantExpression(int instanceId) {
        return new ArrayConstant.StringArrayConstant(instanceId);
    }

    /**
     * Builds a reference array constant expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @return a new ReferenceArrayConstant
     */
    public static ArrayValue.ReferenceArrayValue buildReferenceArrayConstantExpression(Type objectType,
                                                                                      int instanceId) {
        return new ArrayConstant.ReferenceArrayConstant(objectType, instanceId);
    }

    /**
     * Builds an integer array variable expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @param arrayName the array name
     * @param concreteArray the concrete array
     * @return a new IntegerArrayVariable
     */
    public static ArrayValue.IntegerArrayValue buildIntegerArrayVariableExpression(Type objectType,
                                                                                  int instanceId,
                                                                                  String arrayName,
                                                                                  Object concreteArray) {
        return new ArrayVariable.IntegerArrayVariable(objectType, instanceId, arrayName, concreteArray);
    }

    /**
     * Builds an array variable expression.
     *
     * @param instanceId the instance id
     * @param arrayName the array name
     * @param concreteArray the concrete array
     * @return a new ArrayValue
     */
    public static ArrayValue buildArrayVariableExpression(int instanceId, String arrayName, Object concreteArray) {
        Type arrayType = Type.getType(concreteArray.getClass());
        Type elementType = arrayType.getElementType();

        if (TypeUtil.isIntegerValue(elementType)) {
            return buildIntegerArrayVariableExpression(arrayType, instanceId, arrayName, concreteArray);
        }

        if (TypeUtil.isRealValue(elementType)) {
            return buildRealArrayVariableExpression(arrayType, instanceId, arrayName, concreteArray);
        }

        if (TypeUtil.isStringValue(elementType)) {
            return buildStringArrayVariableExpression(arrayType, instanceId, arrayName, concreteArray);
        }

        if (TypeUtil.isReferenceValue(elementType)) {
            return buildReferenceArrayVariableExpression(arrayType, instanceId, arrayName, concreteArray);
        }

        throw new IllegalArgumentException("Array type not yet implemented: " + arrayType.toString());
    }

    /**
     * Builds an array constant expression.
     *
     * @param arrayType the array type
     * @param instanceId the instance id
     * @return a new ArrayValue
     */
    public static ArrayValue buildArrayConstantExpression(Type arrayType, int instanceId) {
        Type elementType = arrayType.getElementType();

        if (TypeUtil.isIntegerValue(elementType)) {
            return buildIntegerArrayConstantExpression(arrayType, instanceId);
        }

        if (TypeUtil.isRealValue(elementType)) {
            return buildRealArrayConstantExpression(arrayType, instanceId);
        }

        if (TypeUtil.isStringValue(elementType)) {
            return buildStringArrayConstantExpression(instanceId);
        }

        if (TypeUtil.isReferenceValue(elementType)) {
            return buildReferenceArrayConstantExpression(arrayType, instanceId);
        }

        throw new IllegalArgumentException("Array type not yet implemented: " + arrayType.toString());
    }

    /**
     * Builds a real array variable expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @param arrayName the array name
     * @param concreteArray the concrete array
     * @return a new RealArrayVariable
     */
    public static ArrayValue.RealArrayValue buildRealArrayVariableExpression(Type objectType,
                                                                             int instanceId,
                                                                             String arrayName,
                                                                             Object concreteArray) {
        return new ArrayVariable.RealArrayVariable(objectType, instanceId, arrayName, concreteArray);
    }

    /**
     * Builds a string array variable expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @param arrayName the array name
     * @param concreteArray the concrete array
     * @return a new StringArrayVariable
     */
    public static ArrayValue.StringArrayValue buildStringArrayVariableExpression(Type objectType,
                                                                                 int instanceId,
                                                                                 String arrayName,
                                                                                 Object concreteArray) {
        return new ArrayVariable.StringArrayVariable(instanceId, arrayName, concreteArray);
    }

    /**
     * Builds a reference array variable expression.
     *
     * @param objectType the array type
     * @param instanceId the instance id
     * @param arrayName the array name
     * @param concreteArray the concrete array
     * @return a new ReferenceArrayVariable
     */
    public static ArrayValue.ReferenceArrayValue buildReferenceArrayVariableExpression(Type objectType,
                                                                                      int instanceId,
                                                                                      String arrayName,
                                                                                      Object concreteArray) {
        return new ArrayVariable.ReferenceArrayVariable(objectType, instanceId, arrayName, concreteArray);
    }

    /**
     * Builds an array select expression.
     *
     * @param arrayExpression the array expression
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @return a new IntegerArraySelect
     */
    public static IntegerValue buildArraySelectExpression(ArrayValue.IntegerArrayValue arrayExpression,
                                                          IntegerValue symbIndex,
                                                          IntegerValue symbValue) {
        return new ArraySelect.IntegerArraySelect(arrayExpression, symbIndex, symbValue);
    }

    /**
     * Builds an array select expression.
     *
     * @param arrayExpression the array expression
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @return a new RealArraySelect
     */
    public static RealValue buildArraySelectExpression(ArrayValue.RealArrayValue arrayExpression,
                                                       IntegerValue symbIndex,
                                                       RealValue symbValue) {
        return new ArraySelect.RealArraySelect(arrayExpression, symbIndex, symbValue);
    }

    /**
     * Builds an array select expression.
     *
     * @param arrayExpression the array expression
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @return a new StringArraySelect
     */
    public static StringValue buildArraySelectExpression(ArrayValue.StringArrayValue arrayExpression,
                                                         IntegerValue symbIndex,
                                                         StringValue symbValue) {
        return new ArraySelect.StringArraySelect(arrayExpression, symbIndex, symbValue);
    }

    /**
     * Builds an array store expression.
     *
     * @param symbolicArrayInstance the symbolic array instance
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @param concreteResultingArray the concrete resulting array
     * @return a new IntegerArrayStore
     */
    public static ArrayValue.IntegerArrayValue buildArrayStoreExpression(
            ArrayValue.IntegerArrayValue symbolicArrayInstance,
            IntegerValue symbIndex,
            IntegerValue symbValue,
            Object concreteResultingArray) {
        return new ArrayStore.IntegerArrayStore(symbolicArrayInstance, symbIndex, symbValue, concreteResultingArray);
    }

    /**
     * Builds an array store expression.
     *
     * @param symbolicArrayInstance the symbolic array instance
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @param concreteResultingArray the concrete resulting array
     * @return a new RealArrayStore
     */
    public static ArrayValue.RealArrayValue buildArrayStoreExpression(
            ArrayValue.RealArrayValue symbolicArrayInstance,
            IntegerValue symbIndex,
            RealValue symbValue,
            Object concreteResultingArray) {
        return new ArrayStore.RealArrayStore(symbolicArrayInstance, symbIndex, symbValue, concreteResultingArray);
    }

    /**
     * Builds an array store expression.
     *
     * @param symbolicArrayInstance the symbolic array instance
     * @param symbIndex the symbolic index
     * @param symbValue the symbolic value
     * @param concreteResultingArray the concrete resulting array
     * @return a new StringArrayStore
     */
    public static ArrayValue.StringArrayValue buildArrayStoreExpression(
            ArrayValue.StringArrayValue symbolicArrayInstance,
            IntegerValue symbIndex,
            StringValue symbValue,
            Object concreteResultingArray) {
        return new ArrayStore.StringArrayStore(symbolicArrayInstance, symbIndex, symbValue, concreteResultingArray);
    }

    /* *************************** Reference Types. *************************** */

    /**
     * Builds a new synthetic lambda reference type.
     *
     * @param lambdaAnonymousClass the lambda anonymous class
     * @param ownerIsIgnored whether the owner is ignored
     * @param newReferenceTypeId the new reference type id
     * @return a new LambdaSyntheticTypeConstant
     */
    public static LambdaSyntheticTypeConstant buildLambdaSyntheticTypeConstant(Type lambdaAnonymousClass,
                                                                               boolean ownerIsIgnored,
                                                                               int newReferenceTypeId) {
        return new LambdaSyntheticTypeConstant(lambdaAnonymousClass, ownerIsIgnored, newReferenceTypeId);
    }

    /**
     * Builds a new Class reference type.
     *
     * @param classType the class type
     * @param newReferenceTypeId the new reference type id
     * @return a new ClassTypeConstant
     */
    public static ClassTypeConstant buildClassTypeConstant(Type classType, int newReferenceTypeId) {
        return new ClassTypeConstant(classType, newReferenceTypeId);
    }

    /**
     * Builds a new Array reference type.
     *
     * @param classType the class type
     * @param newReferenceTypeId the new reference type id
     * @return a new ArrayTypeConstant
     */
    public static ArrayTypeConstant buildArrayTypeConstant(Type classType, int newReferenceTypeId) {
        return new ArrayTypeConstant(classType, newReferenceTypeId);
    }

    /**
     * Builds a new Object reference type.
     *
     * @return a new ClassTypeConstant representing java.lang.Object
     */
    private static ClassTypeConstant buildObjectTypeConstant() {
        return new ClassTypeConstant(Type.getType(Object.class), SymbolicHeap.OBJECT_TYPE_ID);
    }
}
