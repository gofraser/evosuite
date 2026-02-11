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
package org.evosuite.symbolic.vm.heap;

import org.evosuite.Properties;
import org.evosuite.symbolic.LambdaUtils;
import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.bv.IntegerValue;
import org.evosuite.symbolic.expr.fp.RealValue;
import org.evosuite.symbolic.expr.ref.ClassReferenceConstant;
import org.evosuite.symbolic.expr.ref.ClassReferenceVariable;
import org.evosuite.symbolic.expr.ref.ReferenceConstant;
import org.evosuite.symbolic.expr.ref.ReferenceExpression;
import org.evosuite.symbolic.expr.ref.array.ArrayConstant;
import org.evosuite.symbolic.expr.ref.array.ArrayVariable;
import org.evosuite.symbolic.expr.reftype.ArrayTypeConstant;
import org.evosuite.symbolic.expr.reftype.ClassTypeConstant;
import org.evosuite.symbolic.expr.reftype.LambdaSyntheticTypeConstant;
import org.evosuite.symbolic.expr.reftype.ReferenceTypeExpression;
import org.evosuite.symbolic.expr.str.StringValue;
import org.evosuite.symbolic.vm.ExpressionFactory;
import org.evosuite.symbolic.vm.heap.symbolicHeapSection.ArraysSection;
import org.evosuite.symbolic.vm.heap.symbolicHeapSection.SymbolicHeapArraySectionFactory;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Symbolic heap representation.
 *
 * @author galeotti
 */
public final class SymbolicHeap {

    /**
     * Field Value Constants.
     **/
    public static final String $INT_VALUE = "$intValue";
    public static final String $BYTE_VALUE = "$byteValue";
    public static final String $CHAR_VALUE = "$charValue";
    public static final String $LONG_VALUE = "$longValue";
    public static final String $SHORT_VALUE = "$shortValue";
    public static final String $FLOAT_VALUE = "$floatValue";
    public static final String $DOUBLE_VALUE = "$doubleValue";
    public static final String $STRING_VALUE = "$stringValue";
    public static final String $MATCHER_INPUT = "$matcherInput";
    public static final String $BOOLEAN_VALUE = "$booleanValue";
    public static final String $STRING_READER_VALUE = "$stringReaderValue";
    public static final String $BIG_INTEGER_CONTENTS = "$bigInteger_contents";
    public static final String $STRING_TOKENIZER_VALUE = "$stringTokenizerValue";
    public static final String $STRING_BUFFER_CONTENTS = "$stringBuffer_contents";
    public static final String $STRING_BUILDER_CONTENTS = "$stringBuilder_contents";

    /**
     * Reference Constants.
     */
    public static final int NULL_INSTANCE_ID = 0;

    /**
     * Reference Type Constants.
     */
    public static final int NULL_TYPE_ID = 0;
    public static final int OBJECT_TYPE_ID = 1;

    protected static final Logger logger = LoggerFactory.getLogger(SymbolicHeap.class);

    /**
     * Counter for instances.
     * <p>
     * Note: 0 is reserved for the null constant.
     * </p>
     */
    private int newInstanceCount = 1;

    /**
     * Counter for reference types found during execution.
     * <p>
     * Note: 0 is reserved for the null constant and 1 for Object.
     * </p>
     */
    private int newReferenceTypeCount = 2;

    /**
     * Array's memory model.
     */
    private final ArraysSection symbolicArrays;

    /**
     * Stores a mapping between identityHashCodes and NonNullReferences. Every
     * time the NonNullReference for a given Object (non String) is needed, this
     * mapping is used.
     */
    private final Map<Integer, ReferenceExpression> nonNullRefs = new HashMap<>();

    /**
     * Stores a mapping between Classes and ReferenceTypes. Every
     * time the ReferenceType for a given Object (non String) is needed, this
     * mapping is used.
     */
    private final Map<Type, ReferenceTypeExpression> symbolicReferenceTypes = new HashMap<>();

    /**
     * Stores a mapping between NonNullReferences and their symbolic values. The
     * Expression contains at least one symbolic variable.
     */
    private final Map<FieldKey, Map<ReferenceExpression, Expression<?>>> symbFields = new HashMap<>();

    /**
     * Mapping between for symbolic values stored in static fields. The
     * Expression contains at least one symbolic variable.
     */
    private final Map<FieldKey, Expression<?>> symbStaticFields = new HashMap<>();

    /**
     * Constructor.
     */
    public SymbolicHeap() {
        this.symbolicArrays = SymbolicHeapArraySectionFactory
                .getInstance()
                .getSymbolicHeapArraySection(Properties.SELECTED_DSE_ARRAYS_MEMORY_MODEL_VERSION);
    }

    /**
     * This constructor is for references created in instrumented code (NEW,
     * ANEW, NEWARRAY, etc).
     * <p>
     * It is the only way of creating uninitialized non-null references.
     * </p>
     *
     * @param objectType the type of the object
     * @return a new ClassReferenceConstant
     */
    public ClassReferenceConstant buildNewClassReferenceConstant(Type objectType) {
        if (objectType.getClassName() == null) {
            throw new IllegalArgumentException();
        }

        final int newInstanceId = newInstanceCount++;
        return new ClassReferenceConstant(objectType, newInstanceId);
    }


    /**
     * Updates an instance field. The symbolic expression is stored iif it is
     * not a constant expression (i.e. it has at least one variable).
     *
     * @param className    the name of the class
     * @param fieldName    the name of the field
     * @param concReceiver the concrete Object receiver instance
     * @param symbReceiver a symbolic NonNullReference instance
     * @param symbValue    the Expression to be stored. Null value means the previous
     *                     symbolic expression has to be erased.
     */
    public void putField(String className, String fieldName, Object concReceiver, ReferenceExpression symbReceiver,
                         Expression<?> symbValue) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(className, fieldName);

        // NOTE (ilebrero): We need to store elements even if they are constant due to probable usage later on of their
        //                    reference (i.e. if the reference is bounded to an object like a closure.)
        //        if (symbValue == null || !symbValue.containsSymbolicVariable()) {
        if (symbValue == null) {
            symbField.remove(symbReceiver);
        } else {
            symbField.put(symbReceiver, symbValue);
        }
    }

    /**
     * Special updating case scenario for Reference expression values.
     *
     * @param className    the name of the class
     * @param fieldName    the name of the field
     * @param concReceiver the concrete Object receiver instance
     * @param symbReceiver a symbolic NonNullReference instance
     * @param symbValue    the Expression to be stored. Null value means the previous
     *                     symbolic expression has to be erased.
     */
    public void putField(String className, String fieldName, Object concReceiver, ReferenceExpression symbReceiver,
                         ReferenceExpression symbValue) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(className, fieldName);

        // NOTE (ilebrero): We need to store elements even if they are constant due to probable usage later on of their
        //                    reference (i.e. if the reference is bounded to an object like a closure.)
        //        if (symbValue == null || !symbValue.containsSymbolicVariable()) {
        if (symbValue == null) {
            symbField.remove(symbReceiver);
        } else {
            symbField.put(symbReceiver, symbValue);
        }
    }

    private Map<ReferenceExpression, Expression<?>> getOrCreateSymbolicField(String owner, String name) {
        FieldKey k = new FieldKey(owner, name);
        Map<ReferenceExpression, Expression<?>> symbField = symbFields.get(k);
        if (symbField == null) {
            symbField = new HashMap<>();
            symbFields.put(k, symbField);
        }

        return symbField;
    }

    /**
     * Returns a stored symbolic expression for an int field or created one.
     *
     * @param owner        the owner of the field
     * @param name         the name of the field
     * @param concReceiver the concrete receiver
     * @param symbReceiver the symbolic receiver
     * @param concValue    the concrete value
     * @return the symbolic integer value
     */
    public IntegerValue getField(String owner, String name, Object concReceiver, ReferenceExpression symbReceiver,
                                 long concValue) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(owner, name);
        IntegerValue symbValue = (IntegerValue) symbField.get(symbReceiver);
        if (symbValue == null || symbValue.getConcreteValue() != concValue) {
            symbValue = ExpressionFactory.buildNewIntegerConstant(concValue);
            symbField.remove(symbReceiver);
        }

        return symbValue;
    }

    /**
     * Returns a stored symbolic expression for a real field or created one.
     *
     * @param className    the name of the class
     * @param fieldName    the name of the field
     * @param concReceiver the concrete receiver
     * @param symbReceiver the symbolic receiver
     * @param concValue    the concrete value
     * @return the symbolic real value
     */
    public RealValue getField(String className, String fieldName, Object concReceiver,
                              ReferenceExpression symbReceiver, double concValue) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(className, fieldName);
        RealValue symbValue = (RealValue) symbField.get(symbReceiver);
        if (symbValue == null || symbValue.getConcreteValue() != concValue) {
            symbValue = ExpressionFactory.buildNewRealConstant(concValue);
            symbField.remove(symbReceiver);
        }

        return symbValue;
    }

    /**
     * Returns a stored symbolic expression for a string field or created one.
     *
     * @param className    the name of the class
     * @param fieldName    the name of the field
     * @param concReceiver the concrete receiver
     * @param symbReceiver the symbolic receiver
     * @param concValue    the concrete value
     * @return the symbolic string value
     */
    public StringValue getField(String className, String fieldName, Object concReceiver,
                                ReferenceExpression symbReceiver, String concValue) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(className, fieldName);
        StringValue symbValue = (StringValue) symbField.get(symbReceiver);
        if (symbValue == null || !symbValue.getConcreteValue().equals(concValue)) {
            symbValue = ExpressionFactory.buildNewStringConstant(concValue);
            symbField.remove(symbReceiver);
        }

        return symbValue;
    }

    /**
     * No default concrete value means the return value could be false!.
     *
     * @param className    the name of the class
     * @param fieldName    the name of the field
     * @param concReceiver the concrete receiver
     * @param symbReceiver the symbolic receiver
     * @return the symbolic expression
     */
    public Expression<?> getField(String className, String fieldName, Object concReceiver,
                                  ReferenceExpression symbReceiver) {

        Map<ReferenceExpression, Expression<?>> symbField = getOrCreateSymbolicField(className, fieldName);
        Expression<?> symbValue = symbField.get(symbReceiver);
        return symbValue;
    }

    /**
     * Updates a static field.
     *
     * @param owner     the owner of the field
     * @param name      the name of the field
     * @param symbValue the symbolic value
     */
    public void putStaticField(String owner, String name, Expression<?> symbValue) {

        FieldKey k = new FieldKey(owner, name);
        if (symbValue == null || !symbValue.containsSymbolicVariable()) {
            symbStaticFields.remove(k);
        } else {
            symbStaticFields.put(k, symbValue);
        }

    }

    /**
     * Returns a stored symbolic expression for a static int field or created one.
     *
     * @param owner     the owner of the field
     * @param name      the name of the field
     * @param concValue the concrete value
     * @return the symbolic integer value
     */
    public IntegerValue getStaticField(String owner, String name, long concValue) {

        FieldKey k = new FieldKey(owner, name);
        IntegerValue symbValue = (IntegerValue) symbStaticFields.get(k);
        if (symbValue == null || symbValue.getConcreteValue() != concValue) {
            symbValue = ExpressionFactory.buildNewIntegerConstant(concValue);
            symbStaticFields.remove(k);
        }

        return symbValue;

    }

    /**
     * Returns a stored symbolic expression for a static real field or created one.
     *
     * @param owner     the owner of the field
     * @param name      the name of the field
     * @param concValue the concrete value
     * @return the symbolic real value
     */
    public RealValue getStaticField(String owner, String name, double concValue) {

        FieldKey k = new FieldKey(owner, name);
        RealValue symbValue = (RealValue) symbStaticFields.get(k);
        if (symbValue == null || symbValue.getConcreteValue() != concValue) {
            symbValue = ExpressionFactory.buildNewRealConstant(concValue);
            symbStaticFields.remove(k);
        }

        return symbValue;
    }

    /**
     * Returns a stored symbolic expression for a static string field or created one.
     *
     * @param owner     the owner of the field
     * @param name      the name of the field
     * @param concValue the concrete value
     * @return the symbolic string value
     */
    public StringValue getStaticField(String owner, String name, String concValue) {

        FieldKey k = new FieldKey(owner, name);
        StringValue symbValue = (StringValue) symbStaticFields.get(k);
        if (symbValue == null || !symbValue.getConcreteValue().equals(concValue)) {
            symbValue = ExpressionFactory.buildNewStringConstant(concValue);
            symbStaticFields.remove(k);
        }

        return symbValue;
    }

    /**
     * Returns a <code>ReferenceConstant</code> if the concrete reference is
     * null. Otherwise, it looks in the list of non-null symbolic references for
     * a symbolic reference with the concrete value. If it is found, that
     * symbolic reference is returned, otherwise a new reference constant is
     * created (and added ot the list of non-null symbolic references).
     *
     * @param concRef the concrete reference
     * @return the symbolic reference expression
     */
    public ReferenceExpression getReference(Object concRef) {
        if (concRef == null) {
            // null reference
            return ExpressionFactory.NULL_REFERENCE;
        } else {
            int identityHashCode = System.identityHashCode(concRef);
            if (nonNullRefs.containsKey(identityHashCode)) {
                // already known object
                ReferenceExpression symbRef = nonNullRefs.get(identityHashCode);
                return symbRef;
            } else {
                // unknown object
                final Type type = Type.getType(concRef.getClass());
                ReferenceConstant refConstant;
                if (concRef.getClass().isArray()) {
                    refConstant = buildNewArrayReferenceConstant(type);
                } else {
                    refConstant = buildNewClassReferenceConstant(type);
                }

                initializeReference(concRef, refConstant);
                nonNullRefs.put(identityHashCode, refConstant);
                return refConstant;
            }
        }
    }

    /**
     * Builds a new reference variable using a varName and a concrete object.
     * The concrete object can be null.
     *
     * @param concreteObject the concrete object
     * @param varName        the variable name
     * @return a new ClassReferenceVariable
     */
    public ClassReferenceVariable buildNewClassReferenceVariable(Object concreteObject, String varName) {
        final Type referenceType;
        if (concreteObject == null) {
            referenceType = Type.getType(Object.class);
        } else {
            referenceType = Type.getType(concreteObject.getClass());
        }
        final int newInstanceId = newInstanceCount++;
        final ClassReferenceVariable r = new ClassReferenceVariable(referenceType, newInstanceId,
                varName, concreteObject);
        return r;
    }

    /**
     * Initializes a reference using a concrete object.
     *
     * @param concreteReference the concrete reference
     * @param symbolicReference the symbolic reference
     */
    public void initializeReference(Object concreteReference, ReferenceExpression symbolicReference) {
        if (symbolicReference == null) {
            // Defensive: recover if a null symbolic reference is passed.
            getReference(concreteReference);
            return;
        }
        if (concreteReference != null) {
            if (!symbolicReference.isInitialized()) {
                symbolicReference.initializeReference(concreteReference);

                if (concreteReference.getClass().isArray()) {
                    symbolicArrays.initializeArrayReference(symbolicReference);
                }
            }

            // Fix: Reference variables are initialized when created, so they were never set on the heap reference map.
            int identityHashCode = symbolicReference.getConcIdentityHashCode();
            if (!nonNullRefs.containsKey(identityHashCode)) {
                nonNullRefs.put(identityHashCode, symbolicReference);
            }
        }
    }

    /* ======= Arrays Implementation ======= */

    /**
     * This constructor is for references created in array related instrumented code (NEWARRAY, ANEWARRAY,
     * MULTINEWARRAY).
     * <p>
     * It is the only way of creating uninitialized non-null arrays.
     * </p>
     *
     * @param arrayType the type of the array
     * @return a new ArrayConstant
     */
    public ArrayConstant buildNewArrayReferenceConstant(Type arrayType) {
        if (arrayType.getClassName() == null) {
            throw new IllegalArgumentException();
        }

        final int newInstanceId = newInstanceCount++;
        return symbolicArrays.createConstantArray(arrayType, newInstanceId);
    }

    /**
     * Builds a new array reference variable using an array type, and the name the concrete array can be null.
     *
     * @param concreteArray the concrete array
     * @param arrayVarName  the name of the array variable
     * @return a new ArrayVariable
     */
    public ArrayVariable buildNewArrayReferenceVariable(Object concreteArray, String arrayVarName) {
        final int newInstanceId = newInstanceCount++;
        return symbolicArrays.createVariableArray(concreteArray, newInstanceId, arrayVarName);
    }

    /* ======= Load Operations ======= */

    /**
     * Load operation for Real arrays.
     *
     * @param symbolicArray Symbolic element og the real array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     * @return a {@link RealValue} symoblic element.
     */
    public RealValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                               RealValue symbolicValue) {
        return symbolicArrays.arrayLoad(symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Load operation for String arrays.
     *
     * @param symbolicArray Symbolic element og the string array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     * @return a {@link StringValue} symoblic element.
     */
    public StringValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                 StringValue symbolicValue) {
        return symbolicArrays.arrayLoad(symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Load operation for Integer arrays.
     *
     * @param symbolicArray Symbolic element og the integer array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     * @return a {@link IntegerValue} symoblic element.
     */
    public IntegerValue arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                  IntegerValue symbolicValue) {
        return symbolicArrays.arrayLoad(symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Load operation for Reference arrays.
     *
     * @param symbolicArray Symbolic element og the reference array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     * @return a {@link ReferenceExpression} symoblic element.
     */
    public ReferenceExpression arrayLoad(ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                                          ReferenceExpression symbolicValue) {
        return symbolicArrays.arrayLoad(symbolicArray, symbolicIndex, symbolicValue);
    }

    /* ======= Store Operations ======= */

    /**
     * Store operation for Real arrays.
     *
     * @param concreteArray the concrete array
     * @param symbolicArray Symbolic element of the reference array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     */
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           RealValue symbolicValue) {
        symbolicArrays.arrayStore(concreteArray, symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Store operation for String arrays.
     *
     * @param concreteArray the concrete array
     * @param symbolicArray Symbolic element of the reference array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     */
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           StringValue symbolicValue) {
        symbolicArrays.arrayStore(concreteArray, symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Store operation for Integer arrays.
     *
     * @param concreteArray the concrete array
     * @param symbolicArray Symbolic element of the reference array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     */
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           IntegerValue symbolicValue) {
        symbolicArrays.arrayStore(concreteArray, symbolicArray, symbolicIndex, symbolicValue);
    }

    /**
     * Store operation for Reference arrays.
     *
     * @param concreteArray the concrete array
     * @param symbolicArray Symbolic element of the reference array reference
     * @param symbolicIndex Symbolic element of the accessed index
     * @param symbolicValue Symbolic element of the accessed value
     */
    public void arrayStore(Object concreteArray, ReferenceExpression symbolicArray, IntegerValue symbolicIndex,
                           ReferenceExpression symbolicValue) {
        symbolicArrays.arrayStore(concreteArray, symbolicArray, symbolicIndex, symbolicValue);
    }


    /* ======= Types Implementation ======= */

    /**
     * Special case scenario for lambda synthetic types.
     *
     * @param lambdaAnonymousClass the lambda anonymous class
     * @param ownerIsIgnored       whether the owner is ignored
     * @return the symbolic reference type expression
     */
    public ReferenceTypeExpression buildNewLambdaTypeConstant(Type lambdaAnonymousClass, boolean ownerIsIgnored) {
        if (lambdaAnonymousClass == null) {
            throw new IllegalArgumentException("Lambda Anonymous Class cannot be null.");
        }

        ReferenceTypeExpression lambdaExpression;
        lambdaExpression = symbolicReferenceTypes.get(lambdaAnonymousClass);

        if (lambdaExpression == null) {
            final int newReferenceTypeId = newReferenceTypeCount++;

            lambdaExpression = ExpressionFactory.buildLambdaSyntheticTypeConstant(lambdaAnonymousClass,
                    ownerIsIgnored, newReferenceTypeId);
            symbolicReferenceTypes.put(lambdaAnonymousClass, lambdaExpression);
        }

        return lambdaExpression;
    }

    /**
     * General classes types constant references.
     *
     * @param type the type
     * @return the symbolic reference type expression
     */
    public ReferenceTypeExpression buildNewClassTypeConstant(Type type) {
        if (type == null) {
            throw new IllegalArgumentException("Class type cannot be null.");
        }

        ReferenceTypeExpression classExpression;
        classExpression = symbolicReferenceTypes.get(type);

        if (classExpression == null) {
            final int newReferenceTypeId = newReferenceTypeCount++;

            classExpression = ExpressionFactory.buildClassTypeConstant(type, newReferenceTypeId);
            symbolicReferenceTypes.put(type, classExpression);
        }

        return classExpression;
    }


    /**
     * Retrieves the symbolic expression related to this class.
     *
     * @param classType the class type
     * @return the symbolic reference type expression
     */
    public ReferenceTypeExpression getReferenceType(Type classType) {
        if (classType == null) {
            return ExpressionFactory.NULL_TYPE_REFERENCE;
        }
        if (classType.getClass().equals(Object.class)) {
            return ExpressionFactory.OBJECT_TYPE_REFERENCE;
        }

        ReferenceTypeExpression typeExpression;
        Class<?> typeClass = classType.getClass();
        typeExpression = symbolicReferenceTypes.get(classType);

        if (typeExpression == null) {
            final int newReferenceTypeId = newReferenceTypeCount++;

            if (LambdaUtils.isLambda(typeClass)) {
                //If we haven't seen this lambda before then it's from non-instrumented sources
                typeExpression = new LambdaSyntheticTypeConstant(classType, true, newReferenceTypeId);
            } else if (typeClass.isArray()) {
                typeExpression = new ArrayTypeConstant(classType, newReferenceTypeId);
            } else {
                typeExpression = new ClassTypeConstant(classType, newReferenceTypeId);
            }

            symbolicReferenceTypes.put(classType, typeExpression);
        }

        return typeExpression;
    }
}
