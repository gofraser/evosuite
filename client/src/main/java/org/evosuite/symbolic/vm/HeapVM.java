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

import org.evosuite.dse.AbstractVM;
import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.fp.RealConstant;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.instrument.ConcolicInstrumentingClassLoader;
import org.evosuite.symbolic.vm.heap.SymbolicHeap;
import org.evosuite.symbolic.vm.string.Types;
import org.evosuite.utils.TypeUtil;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

import static org.evosuite.dse.util.Assertions.notNull;

/**
 * Static area (static fields) and heap (instance fields).
 *
 * <p>FIXME: reset static state before each execution.</p>
 *
 * @author csallner@uta.edu (Christoph Csallner)
 */
public final class HeapVM extends AbstractVM {

    private static final Logger logger = LoggerFactory.getLogger(HeapVM.class);

    public static final String ARRAY_LENGTH = "length";

    private final SymbolicEnvironment env;

    private final ConcolicInstrumentingClassLoader classLoader;

    private final PathConditionCollector pc;

    /**
     * Builds a new HeapVM.
     *
     * @param env the symbolic environment
     * @param pc the path condition collector
     * @param classLoader the class loader
     */
    public HeapVM(SymbolicEnvironment env, PathConditionCollector pc,
                  ConcolicInstrumentingClassLoader classLoader) {
        this.env = env;
        this.pc = pc;
        this.classLoader = classLoader;
    }

    /* Fields */

    /**
     * Resolve (static or instance) field.
     *
     * <p>JVM Specification, Section 5.4.3.2: Field Resolution:
     * http://java.sun.com/
     * docs/books/jvms/second_edition/html/ConstantPool.doc.html#71685</p>
     *
     * <p>TODO: Resolve field once and for all, then cache it.</p>
     */
    public static Field resolveField(Class<?> claz, String name) {
        notNull(claz, name);

        Field[] fields = claz.getDeclaredFields();
        for (Field field : fields) {
            if (field.getName().equals(name)) { // owner declares the "name" field
                return field;
            }
        }

        Class<?>[] suprs = claz.getInterfaces();
        for (Class<?> supr : suprs) {
            Field res = resolveField(supr, name);
            if (res != null) { // super interface declares it
                return res;
            }
        }

        Class<?> supr = claz.getSuperclass();
        if (supr != null) { // super class declares it
            return resolveField(supr, name);
        }

        return null;
    }

    /**
     * GetStatic mypackage/MyClass fieldName FieldType.
     *
     * @param owner     name of a class or interface.
     * @param fieldName name of the field to be read. The owner class or interface
     *                  itself my have declared this field. If owner is a class, then
     *                  this field may also be declared by a - super-class of the
     *                  owner class, or by a - interface implemented by (a super-class
     *                  of) the owner class.
     *
     *                  <p>http://java.sun.com/docs/books/jvms/second_edition/html/
     *                  Instructions2.doc5.html#getstatic</p>
     */
    @Override
    public void GETSTATIC(String owner, String fieldName, String desc) {

        /*
         * Prepare Class
         */
        Class<?> claz = env.ensurePrepared(owner); // type name given in
        // bytecode

        Field concreteField = resolveField(claz, fieldName); // field may be
        // declared by
        // interface
        Class<?> declaringClass = concreteField.getDeclaringClass();

        if (declaringClass.isInterface()) {
            /*
             * Unlikely that we ever get here. Java compiler probably computes
             * value of this (final) field and replaces any
             * "getstatic MyInterface myField" by "sipush fieldValue" or such.
             * Even if we get here, there should be no need to prepare this
             * field, as there has to be an explicit initialization, hence a
             * <clinit>().
             */
            logger.debug("Do we have to prepare the static fields of an interface?");
            env.ensurePrepared(declaringClass);
        }

        boolean isAccessible = concreteField.isAccessible();
        if (!isAccessible) {
            concreteField.setAccessible(true);
        }

        /*
         * First, Get symbolic expression. If no symbolic expression exists, use
         * concrete value. Then, update operand stack according to type
         */
        Type type = Type.getType(desc);

        try {

            if (type.equals(Type.INT_TYPE)) {

                int value = concreteField.getInt(null);
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.CHAR_TYPE)) {

                char value = concreteField.getChar(null);
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.SHORT_TYPE)) {

                short value = concreteField.getShort(null);
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.BOOLEAN_TYPE)) {

                boolean booleanValue = concreteField.getBoolean(null);
                int value = booleanValue ? 1 : 0;
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.BYTE_TYPE)) {

                byte value = concreteField.getByte(null);
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.LONG_TYPE)) {

                long value = concreteField.getLong(null);
                IntegerValue intExpr = env.heap.getStaticField(
                        owner, fieldName, value);
                env.topFrame().operandStack.pushBv64(intExpr);

            } else if (type.equals(Type.FLOAT_TYPE)) {

                float value = concreteField.getFloat(null);
                RealValue fp32 = env.heap.getStaticField(owner,
                        fieldName, value);
                env.topFrame().operandStack.pushFp32(fp32);

            } else if (type.equals(Type.DOUBLE_TYPE)) {

                double value = concreteField.getDouble(null);
                RealValue fp64 = env.heap.getStaticField(owner,
                        fieldName, value);
                env.topFrame().operandStack.pushFp64(fp64);

            } else {

                Object value = concreteField.get(null);
                ReferenceExpression ref = env.heap.getReference(value);
                env.topFrame().operandStack.pushRef(ref);
            }

            if (!isAccessible) {
                concreteField.setAccessible(false);
            }

        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc11.html#putstatic
     */
    @Override
    public void PUTSTATIC(String owner, String name, String desc) {

        /*
         * Prepare classes
         */
        Class<?> claz = env.ensurePrepared(owner); // type name given in
        // bytecode
        Field field = resolveField(claz, name);
        /* See GetStatic */
        Class<?> declaringClass = field.getDeclaringClass();
        if (declaringClass.isInterface()) {
            logger.debug("Do we have to prepare the static fields of an interface?");
            env.ensurePrepared(declaringClass);
        }

        /*
         * Update symbolic state (if needed)
         */
        Operand valueOperand = env.topFrame().operandStack.popOperand();
        Expression<?> symbValue = OperandUtils.retrieveOperandExpression(valueOperand);

        // NonNullReference are not stored in the symbolic heap fields
        if (symbValue instanceof ReferenceOperand) {
            return;
        }

        env.heap.putStaticField(owner, name, symbValue);
    }

    /**
     * Allocate space on the heap and push a reference ref to it onto the stack.
     *
     * <p>For each instance field declared by class className, we add a tuple (ref,
     * default value) to the field's map.</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2. doc10.html#new</p>
     */
    @Override
    public void NEW(String className) {
        /*
         * Since this callback is invoked before the actual object creation, we
         * do nothing.
         *
         * We do not need to discard any elements from the operand stack since
         * it is given empty.
         *
         * PRE-Stack: empty
         *
         * POST-Stack: objectref (delayed)
         */
        Class<?> clazz = classLoader.getClassForName(className);
        Type objectType = Type.getType(clazz);
        ReferenceConstant newObject = this.env.heap.buildNewClassReferenceConstant(objectType);
        this.env.heap.buildNewClassTypeConstant(objectType);
        env.topFrame().operandStack.pushRef(newObject);
    }

    /**
     * Retrieve the value of an instance field.
     *
     * <p>Before actually retrieving the value, the JVM will check if the instance
     * is null. If the receiver instance is null, the JVM will throw a null
     * pointer exception.</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.
     * doc5.html#getfield</p>
     */
    @Override
    public void GETFIELD(Object concReceiver, String className,
                         String fieldName, String desc) {
        // consume symbolic operand
        ReferenceExpression receiverRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concReceiver, receiverRef);

        Field field = resolveField(classLoader.getClassForName(className),
                fieldName);
        env.ensurePrepared(field.getDeclaringClass());

        boolean isAccessible = field.isAccessible();
        if (!isAccessible) {
            field.setAccessible(true);
        }

        /*
         * Schedule reference field type to be asserted -- before null check, as
         * null check will create a new node in path constraint
         */
        /* null-check */
        if (nullReferenceViolation(receiverRef, concReceiver)) {
            return;
        }

        ReferenceExpression symbReceiver = receiverRef;

        Type type = Type.getType(desc);

        try {

            if (type.equals(Type.INT_TYPE)) {

                int value = field.getInt(concReceiver);
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.LONG_TYPE)) {

                long value = field.getLong(concReceiver);
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv64(intExpr);

            } else if (type.equals(Type.FLOAT_TYPE)) {

                float value = field.getFloat(concReceiver);
                RealValue fp32 = env.heap
                        .getField(className, fieldName, concReceiver,
                                symbReceiver, value);
                env.topFrame().operandStack.pushFp32(fp32);

            } else if (type.equals(Type.DOUBLE_TYPE)) {

                double value = field.getDouble(concReceiver);
                RealValue fp64 = env.heap.getField(className,
                        fieldName, concReceiver, symbReceiver, value);
                env.topFrame().operandStack.pushFp64(fp64);

            } else if (type.equals(Type.CHAR_TYPE)) {

                char value = field.getChar(concReceiver);
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.SHORT_TYPE)) {

                short value = field.getShort(concReceiver);
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.BOOLEAN_TYPE)) {

                boolean booleanValue = field.getBoolean(concReceiver);
                int value = booleanValue ? 1 : 0;
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else if (type.equals(Type.BYTE_TYPE)) {

                byte value = field.getByte(concReceiver);
                IntegerValue intExpr = env.heap.getField(
                        className, fieldName, concReceiver, symbReceiver,
                        value);
                env.topFrame().operandStack.pushBv32(intExpr);

            } else {

                Object value = field.get(concReceiver);
                ReferenceExpression ref = env.heap.getReference(value);
                env.topFrame().operandStack.pushRef(ref);
            }

            if (!isAccessible) {
                field.setAccessible(false);
            }

        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Store a value in an instance field.
     *
     * <p>Before actually retrieving the value, the JVM will check if the instance
     * is null. If the receiver instance is null, the JVM will throw a null
     * pointer exception.</p>
     */
    @Override
    public void PUTFIELD(Object concReceiver, String className,
                         String fieldName, String desc) {
        /*
         * Pop symbolic heap
         */
        Operand valueOperand = env.topFrame().operandStack.popOperand();
        ReferenceExpression receiverRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concReceiver, receiverRef);

        /*
         * Prepare classes
         */
        Field field = resolveField(classLoader.getClassForName(className),
                fieldName);
        env.ensurePrepared(field.getDeclaringClass());

        /* null-check */
        if (nullReferenceViolation(receiverRef, concReceiver)) {
            return;
        }

        ReferenceExpression symbReceiver = receiverRef;

        /*
         * Compute new symbolic state
         */
        Expression<?> symbValue = null;
        if (valueOperand instanceof IntegerOperand) {
            IntegerOperand intOp = (IntegerOperand) valueOperand;
            symbValue = intOp.getIntegerExpression();
        } else if (valueOperand instanceof RealOperand) {
            RealOperand realOp = (RealOperand) valueOperand;
            symbValue = realOp.getRealExpression();
        } else if (valueOperand instanceof ReferenceOperand) {

            // NonNullReference are not stored in the symbolic heap fields
            return;

        }
        env.heap.putField(className, fieldName, concReceiver, symbReceiver,
                symbValue);
    }

    /* Arrays */

    /**
     * Create a (one-dimensional) array of primitive component type, e.g., new
     * int[3].
     *
     * <p>Allocate space on the heap and push a reference ref to it onto the stack.</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2. doc10.html#newarray</p>
     */
    @Override
    public void NEWARRAY(int concArrayLength, Class<?> componentType, String className, String methodName) {
        /*
         * Since this callback is invoked before the actual array creation, we
         * can only add negative index constraints.
         * newarray
         * PRE: int (length)
         *
         * POST: arrayref (delayed)
         */
        // discard symbolic arguments
        IntegerValue symbArrayLength = env.topFrame().operandStack.popBv32();

        /* negative index */
        if (negativeArrayLengthViolation(concArrayLength, symbArrayLength, className, methodName)) {
            return;
        }

        // create array class
        int[] lenghts = new int[]{0};
        Class<?> arrayClass = Array.newInstance(componentType, lenghts)
                .getClass();

        Type arrayType = Type.getType(arrayClass);

        ReferenceConstant symbArrayRef = env.heap.buildNewArrayReferenceConstant(arrayType);

        env.heap.putField("", ARRAY_LENGTH, null, symbArrayRef,
                symbArrayLength);

        env.topFrame().operandStack.pushRef(symbArrayRef);
    }

    /**
     * http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc
     * .html#anewarray
     */
    @Override
    public void ANEWARRAY(int concArrayLength, String componentTypeName, String className, String methodName) {
        /*
         * Since this callback is invoked before the actual array creation, we
         * can only add negative index constraints.
         *
         * PRE: int (length)
         *
         * POST: arrayref (delayed)
         */

        // discard symbolic arguments
        IntegerValue symbArrayLength = env.topFrame().operandStack.popBv32();

        /* negative index */
        if (negativeArrayLengthViolation(concArrayLength, symbArrayLength, className, methodName)) {
            return;
        }

        // create array class
        Type componentType = Type.getObjectType(componentTypeName.replace('/', '.'));
        Class<?> componentClass = classLoader.getClassForType(componentType);
        int[] lenghts = new int[]{0};
        Class<?> arrayClass = Array.newInstance(componentClass, lenghts)
                .getClass();

        Type arrayType = Type.getType(arrayClass);
        ReferenceConstant symbArrayRef = env.heap.buildNewArrayReferenceConstant(arrayType);

        env.heap.putField("", ARRAY_LENGTH, null, symbArrayRef,
                symbArrayLength);

        env.topFrame().operandStack.pushRef(symbArrayRef);
    }

    /**
     * MULTIANEWARRAY.
     *
     * <pre>
     * boolean[] b1 = new boolean[1]; // NEWARRAY T_BOOLEAN
     * Boolean[] B1 = new Boolean[1]; // ANEWARRAY java/lang/Boolean
     * boolean[][] b2 = new boolean[1][2]; // MULTIANEWARRAY [[Z 2
     * Boolean[][] B2 = new Boolean[1][2]; // MULTIANEWARRAY [[Ljava/lang/Boolean; 2
     * </pre>
     */
    @Override
    public void MULTIANEWARRAY(String arrayTypeDesc, int nrDimensions, String className, String methodName) {
        /*
         * Since this callback is invoked before the actual array creation, we
         * can only add negative index constraints.
         *
         * PRE: int (dimensions) | ... | int (size2) | int (size1)
         *
         * POST: arrayref (delayed)
         */

        // push negartive length constraints
        for (int i = 0; i < nrDimensions; i++) {
            IntegerValue symbLength = env.topFrame().operandStack.popBv32();
            int concLength = symbLength.getConcreteValue()
                    .intValue();
            if (negativeArrayLengthViolation(concLength, symbLength, className, methodName)) {
                return;
            }
        }

        Type multiArrayType = Type.getType(arrayTypeDesc);
        // push delayed object
        // @FIXME
        ReferenceConstant newMultiArray = env.heap.buildNewArrayReferenceConstant(multiArrayType);
        env.topFrame().operandStack.pushRef(newMultiArray);
    }

    @Override
    public void ARRAYLENGTH(Object concArray) {
        /* get symbolic arguments */
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        int concArrayLength = Array.getLength(concArray);
        ReferenceExpression symbArrayRef = arrayRef;

        IntegerValue symbArrayLength = env.heap.getField("",
                ARRAY_LENGTH, concArray, symbArrayRef, concArrayLength);
        env.topFrame().operandStack.pushBv32(symbArrayLength);
    }

    /**
     * Load an int value from an array and push it on the stack.
     *
     * <p>..., arrayref, index ==> ..., value</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2. doc6.html#iaload</p>
     */
    @Override
    public void IALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        int bv32 = Array.getInt(concArray, concIndex);
        IntegerValue c = env.heap.arrayLoad(symbArrayReference, symbIndex, new IntegerConstant(bv32));
        env.topFrame().operandStack.pushBv32(c);
    }

    @Override
    public void LALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        long bv64 = Array.getLong(concArray, concIndex);
        IntegerValue c = env.heap.arrayLoad(symbArrayReference, symbIndex,
                new IntegerConstant(bv64));
        env.topFrame().operandStack.pushBv64(c);

    }

    @Override
    public void FALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        float fp32 = Array.getFloat(concArray, concIndex);
        RealValue c = env.heap
                .arrayLoad(symbArrayReference, symbIndex, new RealConstant(fp32));
        env.topFrame().operandStack.pushFp32(c);

    }

    /**
     * Load double from array.
     *
     * <p>..., arrayref, index ==> ..., value</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2. doc3.html#daload</p>
     *
     * @param concArray the concrete array
     * @param concIndex the concrete index
     * @param className the class name
     * @param methodName the method name
     */
    @Override
    public void DALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }
        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        double fp64 = Array.getDouble(concArray, concIndex);
        RealValue c = env.heap
                .arrayLoad(symbArrayReference, symbIndex, new RealConstant(fp64));
        env.topFrame().operandStack.pushFp64(c);
    }

    @Override
    public void AALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        Object value = Array.get(concArray, concIndex);

        ReferenceExpression c = env.heap.arrayLoad(symbArrayReference,
                symbIndex, env.heap.getReference(value));
        env.topFrame().operandStack.pushRef(c);
    }

    private boolean indexTooBigViolation(int concIndex,
                                         IntegerValue symbIndex, int concArrayLength,
                                         IntegerValue symbArrayLength, String className, String methodName) {

        IntegerConstraint indexTooBigConstraint;
        if (concIndex >= concArrayLength) {
            indexTooBigConstraint = ConstraintFactory.gte(symbIndex,
                    symbArrayLength);
            if (indexTooBigConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || indexTooBigConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                this.pc.appendArrayAccessCondition(indexTooBigConstraint, className, methodName, true);
            }
            return true;
        } else {
            indexTooBigConstraint = ConstraintFactory.lt(symbIndex,
                    symbArrayLength);
            if (indexTooBigConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || indexTooBigConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                this.pc.appendArrayAccessCondition(indexTooBigConstraint, className, methodName, false);
            }
            return false;
        }
    }

    private boolean nullReferenceViolation(ReferenceExpression symbRef, Object concRef) {
        // TODO: Add constraint to path condition
        return concRef == null;
    }

    private boolean negativeIndexViolation(int concIndex,
                                           IntegerValue symbIndex, String className, String methodName) {
        IntegerConstraint negativeIndexConstraint;
        if (concIndex < 0) {
            negativeIndexConstraint = ConstraintFactory.lt(symbIndex,
                    ExpressionFactory.ICONST_0);
            if (negativeIndexConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || negativeIndexConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                pc.appendArrayAccessCondition(negativeIndexConstraint, className, methodName, true);
            }
            return true;
        } else {
            negativeIndexConstraint = ConstraintFactory.gte(symbIndex,
                    ExpressionFactory.ICONST_0);
            if (negativeIndexConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || negativeIndexConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                pc.appendArrayAccessCondition(negativeIndexConstraint, className, methodName, false);
            }
            return false;
        }
    }

    private boolean negativeArrayLengthViolation(int concArrayLength,
                                                 IntegerValue arrayLengthIndex, String className, String methodName) {
        IntegerConstraint negativeArrayLengthConstraint;
        if (concArrayLength < 0) {
            negativeArrayLengthConstraint = ConstraintFactory.lt(
                    arrayLengthIndex, ExpressionFactory.ICONST_0);
            if (negativeArrayLengthConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || negativeArrayLengthConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                pc.appendArrayAccessCondition(negativeArrayLengthConstraint, className, methodName, true);
            }
            return true;
        } else {
            negativeArrayLengthConstraint = ConstraintFactory.gte(
                    arrayLengthIndex, ExpressionFactory.ICONST_0);
            if (negativeArrayLengthConstraint.getLeftOperand()
                    .containsSymbolicVariable()
                    || negativeArrayLengthConstraint.getRightOperand()
                    .containsSymbolicVariable()) {
                pc.appendArrayAccessCondition(negativeArrayLengthConstraint, className, methodName, false);
            }
            return false;
        }
    }

    /**
     * Retrieve byte/boolean from array.
     */
    @Override
    public void BALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        Object object = Array.get(concArray, concIndex);
        int intValue;
        if (object instanceof Boolean) {
            boolean booleanValue = (Boolean) object;
            intValue = booleanValue ? 1 : 0;
        } else {
            assert object instanceof Byte;
            intValue = ((Byte) object).shortValue();
        }

        IntegerValue c = env.heap.arrayLoad(symbArrayReference, symbIndex,
                new IntegerConstant(intValue));

        env.topFrame().operandStack.pushBv32(c);

    }

    @Override
    public void CALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        char bv32 = Array.getChar(concArray, concIndex);
        IntegerValue c = env.heap.arrayLoad(symbArrayReference, symbIndex,
                new IntegerConstant(bv32));
        env.topFrame().operandStack.pushBv32(c);

    }

    @Override
    public void SALOAD(Object concArray, int concIndex, String className, String methodName) {
        // pop symbolic arguments
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        short concValue = Array.getShort(concArray, concIndex);
        IntegerValue e = env.heap.arrayLoad(symbArrayReference, symbIndex,
                new IntegerConstant(concValue));
        env.topFrame().operandStack.pushBv32(e);

    }

    /**
     * Store the top operand stack value into an array.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#iastore</p>
     */
    @Override
    public void IASTORE(Object concArray, int concIndex, String className, String methodName) {
        // pop arguments
        IntegerValue symbValue = env.topFrame().operandStack.popBv32();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    @Override
    public void LASTORE(Object concArray, int concIndex, String className, String methodName) {
        // get symbolic arguments
        IntegerValue symbValue = env.topFrame().operandStack.popBv64();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    @Override
    public void FASTORE(Object concArray, int concIndex, String className, String methodName) {
        // get symbolic arguments
        RealValue symbValue = env.topFrame().operandStack.popFp32();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    @Override
    public void DASTORE(Object concArray, int concIndex, String className, String methodName) {
        // get symbolic arguments
        RealValue symbValue = env.topFrame().operandStack.popFp64();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    /**
     * Store into reference array.
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc.html#aastore</p>
     */
    @Override
    public void AASTORE(Object concArray, int concIndex, Object concValue, String className, String methodName) {
        // pop arguments
        ReferenceExpression symbValue = env.topFrame().operandStack.popRef();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check references initialization */
        env.heap.initializeReference(concArray, arrayRef);
        env.heap.initializeReference(concValue, symbValue);

        /* array null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        // TODO: check cases for value
        //       When not typing???

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        Type arrayType = Type.getObjectType(concArray.getClass().getName());
        if (TypeUtil.isStringValue(arrayType.getElementType())) {
            StringValue stringValue = env.heap.getField(
                    Types.JAVA_LANG_STRING,
                    SymbolicHeap.$STRING_VALUE,
                    concValue,
                    env.heap.getReference(concValue),
                    (String) concValue
            );
            env.heap.arrayStore(concArray, symbArrayReference, symbIndex, stringValue);
        } else {
            //TODO: implement general objects
        }

        // NonNullReference are not stored in the symbolic heap fields
    }

    @Override
    public void BASTORE(Object concArray, int concIndex, String className, String methodName) {
        // pop arguments
        IntegerValue symbValue = env.topFrame().operandStack.popBv32();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    @Override
    public void CASTORE(Object concArray, int concIndex, String className, String methodName) {
        // pop arguments
        IntegerValue symbValue = env.topFrame().operandStack.popBv32();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    @Override
    public void SASTORE(Object concArray, int concIndex, String className, String methodName) {
        // get symbolic arguments
        IntegerValue symbValue = env.topFrame().operandStack.popBv32();
        IntegerValue symbIndex = env.topFrame().operandStack.popBv32();
        ReferenceExpression arrayRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concArray, arrayRef);

        /* null-check */
        if (nullReferenceViolation(arrayRef, concArray)) {
            return;
        }

        /* negative index */
        if (negativeIndexViolation(concIndex, symbIndex, className, methodName)) {
            return;
        }

        /* out of bound index */
        ReferenceExpression symbArrayReference = arrayRef;
        int concArrayLength = Array.getLength(concArray);
        IntegerValue symbArrayLength = env.heap.getField("", ARRAY_LENGTH,
                concArray, symbArrayReference, concArrayLength);

        if (indexTooBigViolation(concIndex, symbIndex, concArrayLength,
                symbArrayLength, className, methodName)) {
            return;
        }

        env.heap.arrayStore(concArray, symbArrayReference, symbIndex, symbValue);
    }

    /**
     * Explicit type cast.
     *
     * <pre>
     * RefTypeX x = (RefTypeX) ref;
     * </pre>
     *
     * <p>null is treated as (can be cast to) any reference type. This is
     * consistent with the null type being a subtype of every reference type.
     * Note the different treatment in {@link #INSTANCEOF}.</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc2.html#checkcast</p>
     */
    @Override
    public void CHECKCAST(Object concRef, String typeName) {
        ReferenceExpression symbRef = env.topFrame().operandStack.peekRef();
        env.heap.initializeReference(concRef, symbRef);
    }

    /**
     * Dynamic type check.
     *
     * <pre>
     * (variable instanceof TypeName)
     * </pre>
     *
     * <p>null is not treated as (is not an instance of) any reference type. This
     * requires non-standard treatment of null. Note the different treatment in
     * {@link #CHECKCAST}.</p>
     *
     * <p>If the jvm has not loaded the class/interface named TypeName before, then
     * we load it. TODO: Is this a problem?</p>
     *
     * <p>http://java.sun.com/docs/books/jvms/second_edition/html/Instructions2.doc6.html#instanceof</p>
     */
    @Override
    public void INSTANCEOF(Object concRef, String typeName) {
        /* pop symbolic arguments */
        ReferenceExpression symbRef = env.topFrame().operandStack.popRef();

        /* check reference initialization */
        env.heap.initializeReference(concRef, symbRef);
        Type type = Type.getObjectType(typeName);

        Class<?> myClazz = classLoader.getClassForType(type);
        boolean instanceOf = myClazz.isInstance(concRef);

        IntegerConstant ret;
        if (instanceOf) {
            ret = ExpressionFactory.ICONST_1;
        } else {
            ret = ExpressionFactory.ICONST_0;
        }

        /* push symbolic arguments */
        env.topFrame().operandStack.pushBv32(ret);
    }
}
