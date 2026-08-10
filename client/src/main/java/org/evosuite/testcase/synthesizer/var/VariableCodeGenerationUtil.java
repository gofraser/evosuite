package org.evosuite.testcase.synthesizer.var;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.ObjectConstructionBoundary;
import org.evosuite.runtime.instrumentation.RuntimeInstrumentation;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.AbstractStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionReferenceUtil;
import org.evosuite.testcase.synthesizer.DataDependencyUtil;
import org.evosuite.testcase.synthesizer.FieldInitializer;
import org.evosuite.testcase.synthesizer.NonPrimitiveFieldInitializer;
import org.evosuite.testcase.synthesizer.ParameterMatch;
import org.evosuite.testcase.synthesizer.PrimitiveFieldInitializer;
import org.evosuite.testcase.synthesizer.ReflectionUtil;
import org.evosuite.testcase.synthesizer.UsedReferenceSearcher;
import org.evosuite.testcase.synthesizer.ValueSettings;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class VariableCodeGenerationUtil {

    //    private static final Logger logger =
    // LoggerFactory.getLogger(VariableCodeGenerationUtil.class);

    public static Class<?> getClassForType(org.objectweb.asm.Type type) {
        if (type == org.objectweb.asm.Type.BOOLEAN_TYPE) {
            return boolean.class;
        } else if (type == org.objectweb.asm.Type.BYTE_TYPE) {
            return byte.class;
        } else if (type == org.objectweb.asm.Type.CHAR_TYPE) {
            return char.class;
        } else if (type == org.objectweb.asm.Type.SHORT_TYPE) {
            return short.class;
        } else if (type == org.objectweb.asm.Type.INT_TYPE) {
            return int.class;
        } else if (type == org.objectweb.asm.Type.LONG_TYPE) {
            return long.class;
        } else if (type == org.objectweb.asm.Type.FLOAT_TYPE) {
            return float.class;
        } else if (type == org.objectweb.asm.Type.DOUBLE_TYPE) {
            return double.class;
        } else {
            try {
                String className = type.getClassName();
                if (type.getSort() != org.objectweb.asm.Type.ARRAY) {
                    Class<?> clazz =
                            TestGenerationContext.getInstance()
                                    .getClassLoaderForSUT()
                                    .loadClass(className);
                    return clazz;
                } else {
                    StringBuffer buffer = new StringBuffer();
                    org.objectweb.asm.Type elementType = type.getElementType();
                    String arrayString = extractedArrayString(type);
                    if (elementType.getSort() <= 8) {
                        className = convertToShortName(elementType.getClassName());
                        buffer.append(arrayString);
                        buffer.append(className);
                    } else {
                        className = elementType.getClassName();
                        buffer.append(arrayString);
                        buffer.append("L");
                        buffer.append(className);
                        buffer.append(";");
                    }
                    String fullName = buffer.toString();
                    Class<?> clazzArray =
                            Class.forName(
                                    fullName,
                                    true,
                                    TestGenerationContext.getInstance().getClassLoaderForSUT());
                    return clazzArray;
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    private static String extractedArrayString(org.objectweb.asm.Type type) {
        int arrayLength = type.getDimensions();
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < arrayLength; i++) {
            buffer.append("[");
        }
        return buffer.toString();
    }

    public static String convertToShortName(String desc) {
        switch (desc) {
            case "boolean":
                return "Z";
            case "byte":
                return "B";
            case "char":
                return "C";
            case "short":
                return "S";
            case "int":
                return "I";
            case "long":
                return "J";
            case "float":
                return "F";
            case "double":
                return "D";
            default:
                return null;
        }
    }

    public static Type convertToType(String desc) {
        switch (desc) {
            case "Z":
                return Boolean.TYPE;
            case "B":
                return Byte.TYPE;
            case "C":
                return Character.TYPE;
            case "S":
                return Short.TYPE;
            case "I":
                return Integer.TYPE;
            case "J":
                return Long.TYPE;
            case "F":
                return Float.TYPE;
            case "D":
                return Double.TYPE;
            default:
                Class<?> clazz;
                String str = desc.substring(1, desc.length() - 1);
                String className = str.replace("/", ".");
                try {
                    clazz =
                            Class.forName(
                                    className,
                                    true,
                                    TestGenerationContext.getInstance().getClassLoaderForSUT());
                } catch (ClassNotFoundException e) {
                    clazz = null;
                    e.printStackTrace();
                }
                return clazz;
        }
    }

    /**
     * Generates a getter invocation for a non-leaf target node.
     *
     * @param test the test case being extended
     * @param targetObjectReference the object owning the field
     * @param map already generated variables by dependency wrapper
     * @param fieldDeclaringClass class declaring the field
     * @param field field for which the getter is generated
     * @param usedRefSearcher helper for locating existing references
     * @param branch branch being synthesized
     * @return the generated getter result, or {@code null}
     * @throws ConstructionFailedException if statement construction fails
     */
    public static VariableReference generateFieldGetterInTest(
            TestCase test,
            VariableReference targetObjectReference,
            Map<DepVariableWrapper, VarRelevance> map,
            Class<?> fieldDeclaringClass,
            Field field,
            UsedReferenceSearcher usedRefSearcher,
            Method fieldGetter)
            throws ConstructionFailedException {

        /**
         * make sure this field has been set before get, otherwise we may have a null pointer
         * exception after using the retrieved field.
         */
        boolean isPossibleToBeNull =
                checkNullOfField(test, field, fieldDeclaringClass, targetObjectReference);

        int insertionPostion = targetObjectReference.getStPosition() + 1;
        if (isPossibleToBeNull) {
            VariableReference fieldSetter =
                    usedRefSearcher.searchRelevantFieldWritingReferenceInTest(
                            test, field, targetObjectReference);
            if (fieldSetter == null) {
                try {
                    fieldSetter =
                            generateFieldSetterInTest(
                                    test,
                                    targetObjectReference,
                                    map,
                                    fieldDeclaringClass,
                                    field,
                                    false);
                    if (fieldSetter != null) {
                        Statement s = test.getStatement(fieldSetter.getStPosition());

                        /**
                         * if the field setter is a constructor, thus, the object represented by the
                         * field setter, o_new, should be the target object reference as the field
                         * is now only relevant to o_new.
                         */
                        if (s instanceof ConstructorStatement) {
                            ConstructorStatement cStat = (ConstructorStatement) s;
                            VariableReference relevantParam = null;

                            //                            if(cStat.getParameterReferences().size()
                            // == 1) {
                            //                                relevantParam =
                            // cStat.getParameterReferences().get(0);
                            //                            }

                            for (VariableReference vRef : cStat.getParameterReferences()) {
                                if (vRef.getType().equals(field.getGenericType())) {
                                    relevantParam = vRef;
                                }
                            }

                            if (relevantParam != null) {
                                return relevantParam;
                            } else {
                                List<VariableReference> pList = cStat.getParameterReferences();
                                UsedReferenceSearcher searcher = new UsedReferenceSearcher();
                                String methodName = cStat.getMethodName() + cStat.getDescriptor();
                                //                                List<VariableReference> params =

                                //
                                // searcher.searchRelevantParameterOfSetterInTest(pList,
                                // fieldDeclaringClass.getCanonicalName(), methodName, field);
                                ParameterMatch result =
                                        searcher.searchRelevantParameterOfSetterInTest(
                                                fieldDeclaringClass.getCanonicalName(),
                                                methodName,
                                                field);
                                List<VariableReference> paramRefs = new ArrayList<>();
                                for (Integer index : result.parameterPoisitions) {
                                    paramRefs.add(pList.get(index));
                                }

                                if (!paramRefs.isEmpty()) {
                                    return paramRefs.get(0);
                                }
                            }

                            targetObjectReference = fieldSetter;
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (ConstructionFailedException e) {
                    //                    printConstructionError(test, null, b);
                    e.printStackTrace();
                }
            }

            if (fieldSetter != null) {
                insertionPostion = fieldSetter.getStPosition() + 1;
                if (targetObjectReference != null
                        && targetObjectReference.getStPosition() > fieldSetter.getStPosition()) {
                    insertionPostion = targetObjectReference.getStPosition() + 1;
                }
            } else if (targetObjectReference == null) {
                EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
                insertionPostion = mStat.getPosition() - 1;
            } else {
                insertionPostion = targetObjectReference.getStPosition() + 1;
            }
        }

        if (fieldGetter != null) {
            VariableReference newParentVarRef = null;
            GenericMethod gMethod = new GenericMethod(fieldGetter, fieldGetter.getDeclaringClass());
            if (targetObjectReference == null) {
                newParentVarRef =
                        TestFactory.getInstance()
                                .addMethod(test, gMethod, insertionPostion, 2, false);
            } else {
                gMethod = gMethod.copyWithNewOwner(targetObjectReference.getGenericClass());
                newParentVarRef =
                        TestFactory.getInstance()
                                .addMethodFor(
                                        test,
                                        targetObjectReference,
                                        gMethod,
                                        insertionPostion,
                                        false);
            }
            return newParentVarRef;
        }

        return null;
    }

    /**
     * Generates a getter invocation for a non-leaf target node.
     *
     * @param test the test case being extended
     * @param targetObjectReference the object owning the field
     * @param map already generated variables by dependency wrapper
     * @param fieldDeclaringClass class declaring the field
     * @param field field for which the getter is generated
     * @param usedRefSearcher helper for locating existing references
     * @param b branch being synthesized
     * @return the generated getter result, or {@code null}
     * @throws ConstructionFailedException if statement construction fails
     */
    public static VariableReference generateFieldGetterInTest(
            TestCase test,
            VariableReference targetObjectReference,
            Map<DepVariableWrapper, VarRelevance> map,
            Class<?> fieldDeclaringClass,
            Field field,
            UsedReferenceSearcher usedRefSearcher,
            Branch b)
            throws ConstructionFailedException {

        /**
         * make sure this field has been set before get, otherwise we may have a null pointer
         * exception after using the retrieved field.
         */
        boolean isPossibleToBeNull =
                checkNullOfField(test, field, fieldDeclaringClass, targetObjectReference);

        int insertionPostion = targetObjectReference.getStPosition() + 1;
        if (isPossibleToBeNull) {
            VariableReference fieldSetter =
                    usedRefSearcher.searchRelevantFieldWritingReferenceInTest(
                            test, field, targetObjectReference);
            if (fieldSetter == null) {
                try {
                    fieldSetter =
                            generateFieldSetterInTest(
                                    test,
                                    targetObjectReference,
                                    map,
                                    fieldDeclaringClass,
                                    field,
                                    false);
                    if (fieldSetter != null) {
                        Statement s = test.getStatement(fieldSetter.getStPosition());

                        /**
                         * if the field setter is a constructor, thus, the object represented by the
                         * field setter, o_new, should be the target object reference as the field
                         * is now only relevant to o_new.
                         */
                        if (s instanceof ConstructorStatement) {
                            ConstructorStatement cStat = (ConstructorStatement) s;
                            VariableReference relevantParam = null;

                            //                            if(cStat.getParameterReferences().size()
                            // == 1) {
                            //                                relevantParam =
                            // cStat.getParameterReferences().get(0);
                            //                            }

                            for (VariableReference vRef : cStat.getParameterReferences()) {
                                if (vRef.getType().equals(field.getGenericType())) {
                                    relevantParam = vRef;
                                }
                            }

                            if (relevantParam != null) {
                                return relevantParam;
                            } else {
                                List<VariableReference> pList = cStat.getParameterReferences();
                                UsedReferenceSearcher searcher = new UsedReferenceSearcher();
                                String methodName = cStat.getMethodName() + cStat.getDescriptor();
                                //                                List<VariableReference> params =

                                //
                                // searcher.searchRelevantParameterOfSetterInTest(pList,
                                // fieldDeclaringClass.getCanonicalName(), methodName, field);
                                ParameterMatch result =
                                        searcher.searchRelevantParameterOfSetterInTest(
                                                fieldDeclaringClass.getCanonicalName(),
                                                methodName,
                                                field);
                                List<VariableReference> paramRefs = new ArrayList<>();
                                for (Integer index : result.parameterPoisitions) {
                                    paramRefs.add(pList.get(index));
                                }

                                if (!paramRefs.isEmpty()) {
                                    return paramRefs.get(0);
                                }
                            }

                            targetObjectReference = fieldSetter;
                        }
                    }
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                } catch (ConstructionFailedException e) {
                    //                    printConstructionError(test, null, b);
                    e.printStackTrace();
                }
            }

            if (fieldSetter != null) {
                insertionPostion = fieldSetter.getStPosition() + 1;
                if (targetObjectReference != null
                        && targetObjectReference.getStPosition() > fieldSetter.getStPosition()) {
                    insertionPostion = targetObjectReference.getStPosition() + 1;
                }
            } else if (targetObjectReference == null) {
                EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
                insertionPostion = mStat.getPosition() - 1;
            } else {
                insertionPostion = targetObjectReference.getStPosition() + 1;
            }
        }

        Method getter = searchForPotentialGetterInClass(fieldDeclaringClass, field);
        if (getter != null) {
            VariableReference newParentVarRef = null;
            GenericMethod gMethod = new GenericMethod(getter, getter.getDeclaringClass());
            if (targetObjectReference == null) {
                newParentVarRef =
                        TestFactory.getInstance()
                                .addMethod(test, gMethod, insertionPostion, 2, false);
            } else {
                gMethod = gMethod.copyWithNewOwner(targetObjectReference.getGenericClass());
                newParentVarRef =
                        TestFactory.getInstance()
                                .addMethodFor(
                                        test,
                                        targetObjectReference,
                                        gMethod,
                                        insertionPostion,
                                        false);
            }
            return newParentVarRef;
        }

        return null;
    }

    private static boolean checkNullOfField(
            TestCase test,
            Field field,
            Class<?> fieldDeclaringClass,
            VariableReference targetObjectReference) {

        if (field.getType().isPrimitive()) {
            return false;
        }

        TestCase test0 = test.clone();

        targetObjectReference =
                test0.getStatement(targetObjectReference.getStPosition()).getReturnValue();
        Method getter = searchForPotentialGetterInClass(fieldDeclaringClass, field);
        if (getter != null) {
            try {
                VariableReference fieldObject = null;
                GenericMethod gMethod = new GenericMethod(getter, getter.getDeclaringClass());
                int insertionPostion = targetObjectReference.getStPosition() + 1;
                gMethod = gMethod.copyWithNewOwner(targetObjectReference.getGenericClass());
                fieldObject =
                        TestFactory.getInstance()
                                .addMethodFor(
                                        test0,
                                        targetObjectReference,
                                        gMethod,
                                        insertionPostion,
                                        false);

                //                System.currentTimeMillis();
                Method m;
                try {
                    m = field.getType().getMethod("equals", Object.class);
                    insertionPostion++;
                    VariableReference varRef =
                            TestFactory.getInstance()
                                    .addMethodFor(
                                            test0,
                                            fieldObject,
                                            new GenericMethod(m, m.getDeclaringClass()),
                                            insertionPostion);

                    ExecutionResult result = TestCaseExecutor.runTest(test0);

                    Throwable e = result.getExceptionThrownAtPosition(varRef.getStPosition());
                    if (e != null && e instanceof CodeUnderTestException) {
                        CodeUnderTestException exp = (CodeUnderTestException) e;
                        if (exp.getMessage().equals("java.lang.NullPointerException")) {
                            return true;
                        }
                    }

                    return false;

                } catch (NoSuchMethodException | SecurityException e) {
                    e.printStackTrace();
                }

            } catch (ConstructionFailedException e) {
                // The stack traces aren't even of interest to us in this case
                // Skip printing the stack traces
                return true;
            }
        }

        return false;
    }

    /** generate getter in current class */
    public static Method searchForPotentialGetterInClass(
            Class<?> fieldDeclaringClass, Field field) {
        Set<Method> targetMethods = new HashSet<>();

        Set<Method> methods = new HashSet<>();
        Collections.addAll(methods, fieldDeclaringClass.getMethods());
        Collections.addAll(methods, fieldDeclaringClass.getDeclaredMethods());
        for (Method method : methods) {
            // EvoSuite emits tests in the CUT package, so package-private/protected accessors are
            // valid construction APIs. getMethods() silently omitted precisely those accessors
            // (for example CompoundByteTransition.expand()), leaving a public factory input
            // disconnected from the private final field it initializes.
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            boolean isValid = DataDependencyUtil.isFieldGetter(method, field);
            if (isValid) {
                targetMethods.add(method);
            }
        }

        return Randomness.choice(targetMethods);
    }

    public static boolean isPrimitiveClass(String fieldType) {
        boolean flag =
                fieldType.equals("int")
                        || fieldType.equals("double")
                        || fieldType.equals("float")
                        || fieldType.equals("long")
                        || fieldType.equals("short")
                        || fieldType.equals("char")
                        || fieldType.equals("byte");
        return flag;
    }

    public static boolean isCompatible(String parentType, String subType) {
        try {
            Class<?> parentClass =
                    Class.forName(
                            parentType,
                            true,
                            TestGenerationContext.getInstance().getClassLoaderForSUT());
            Class<?> subClass =
                    Class.forName(
                            subType,
                            true,
                            TestGenerationContext.getInstance().getClassLoaderForSUT());
            return parentClass.isAssignableFrom(subClass);

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * If field is not found in current declaring class, we search recursively for its superclass.
     * For simplicity, we only search for setter, getter, constructor. in current class instead of
     * the real declaring class of this field.
     */
    public static Field searchForField(Class<?> fieldDeclaringClass, String fieldName) {
        try {
            Field field = fieldDeclaringClass.getDeclaredField(fieldName);
            return field;
        } catch (NoSuchFieldException e) {
            if (fieldDeclaringClass.getSuperclass() != null) {
                return searchForField(fieldDeclaringClass.getSuperclass(), fieldName);
            }
        }
        return null;
    }

    public static void generateElements(
            Class<?> type, TestCase test, VariableReference usedFieldInTest) {

        Method addMethod = null;
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals("add") && m.getParameterCount() == 1) {
                addMethod = m;
                break;
            }
        }

        if (addMethod != null) {
            int addElementNum = Randomness.nextInt(0, 3);

            for (int i = 0; i < addElementNum; i++) {
                GenericMethod gMethod = new GenericMethod(addMethod, type);
                try {
                    int position = usedFieldInTest.getStPosition() + 1;
                    TestFactory.getInstance()
                            .addMethodFor(test, usedFieldInTest, gMethod, position, false);
                } catch (ConstructionFailedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")

    /** generate setter in current test */
    public static VariableReference generateFieldSetterInTest(
            TestCase test,
            VariableReference targetObjectReference,
            Map<DepVariableWrapper, VarRelevance> map,
            Class<?> fieldDeclaringClass,
            Field field,
            boolean allowNullValue)
            throws ClassNotFoundException, ConstructionFailedException {
        String className = fieldDeclaringClass.getName();
        List<BytecodeInstruction> insList =
                BytecodeInstructionPool.getInstance(
                                TestGenerationContext.getInstance().getClassLoaderForSUT())
                        .getInstructionsIn(className);

        if (insList == null && RuntimeInstrumentation.checkIfCanInstrument(className)) {
            GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .registerClass(className);
            insList =
                    BytecodeInstructionPool.getInstance(
                                    TestGenerationContext.getInstance().getClassLoaderForSUT())
                            .getInstructionsIn(className);
        }

        if (insList == null) {

            return null;
        }

        String targetClassName = checkTargetClassName(field, targetObjectReference);
        Executable setter =
                searchForPotentialSetterInClass(
                        field,
                        targetClassName,
                        targetObjectReference == null,
                        isFreshlyConstructedReceiver(test, targetObjectReference));
        //        System.currentTimeMillis();
        if (setter != null && !isTarget(setter)) {
            if (setter instanceof Method) {
                GenericMethod gMethod =
                        new GenericMethod((Method) setter, setter.getDeclaringClass());
                VariableReference generatedSetter = null;
                if (targetObjectReference == null) {
                    EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
                    generatedSetter =
                            TestFactory.getInstance()
                                    .addMethod(
                                            test,
                                            gMethod,
                                            mStat.getPosition() - 1,
                                            2,
                                            allowNullValue);
                } else {
                    generatedSetter =
                            generateMembershipSetterWithTargetArgument(
                                    test, targetObjectReference, field, (Method) setter);
                    if (generatedSetter == null) {
                        gMethod = gMethod.copyWithNewOwner(targetObjectReference.getGenericClass());
                        generatedSetter =
                                TestFactory.getInstance()
                                        .addMethodFor(
                                                test,
                                                targetObjectReference,
                                                gMethod,
                                                targetObjectReference.getStPosition() + 1,
                                                allowNullValue);
                    }
                }

                return generatedSetter;
            } else if (setter instanceof Constructor) {

                Statement constructorStatement =
                        findConstructorStatement(test, (Constructor) setter, targetObjectReference);
                if (constructorStatement != null) {

                    return constructorStatement.getReturnValue();
                } else {

                    /**
                     * check if the new instance can be set as a field of its parent. if yes, we
                     * change the test, otherwise, we do nothing.
                     */
                    GenericConstructor gConstructor =
                            new GenericConstructor(
                                    (Constructor) setter, setter.getDeclaringClass());
                    VariableReference returnedVar =
                            TestFactory.getInstance()
                                    .addConstructor(
                                            test,
                                            gConstructor,
                                            targetObjectReference.getStPosition(),
                                            2);

                    for (int i = 0; i < test.size(); i++) {
                        Statement stat = test.getStatement(i);
                        if (stat.references(targetObjectReference)) {
                            if (returnedVar.getStPosition() < stat.getPosition()) {
                                stat.replace(targetObjectReference, returnedVar);
                                replaceMapFromNode2Code(map, targetObjectReference, returnedVar);
                            } else if (returnedVar.getStPosition() > stat.getPosition()
                                    && stat.getPosition()
                                            != targetObjectReference.getStPosition()) {
                            }
                        }
                    }

                    return returnedVar;
                }
            }
        }

        return null;
    }

    /**
     * A collection-membership guard requires object identity: populating the field with a newly
     * generated value and invoking the target with an equivalent but different object is useless.
     * When bytecode proves {@code field.contains(targetArgument)}, call the selected one-argument
     * container mutator with that exact argument.
     */
    private static VariableReference generateMembershipSetterWithTargetArgument(
            TestCase test, VariableReference receiver, Field field, Method setter) {
        if (test == null
                || receiver == null
                || field == null
                || setter == null
                || setter.getParameterCount() != 1
                || !(Collection.class.isAssignableFrom(field.getType())
                        || Map.class.isAssignableFrom(field.getType()))) {
            return null;
        }
        int targetParameter = DataDependencyUtil.membershipTargetParameter(field);
        EntityWithParametersStatement target = test.findTargetExecutableStatement();
        if (targetParameter < 0
                || target == null
                || targetParameter >= target.getParameterReferences().size()) {
            return null;
        }
        VariableReference argument = target.getParameterReferences().get(targetParameter);
        if (argument == null
                || !setter.getParameterTypes()[0].isAssignableFrom(argument.getVariableClass())) {
            return null;
        }
        int receiverPosition = ConstructionReferenceUtil.definitionPosition(test, receiver);

        int argumentPosition = ConstructionReferenceUtil.definitionPosition(test, argument);

        if (receiverPosition < 0 || argumentPosition < 0) {
            return null;
        }
        int insertionPosition = Math.max(receiverPosition, argumentPosition) + 1;
        insertionPosition = Math.min(insertionPosition, target.getPosition());
        GenericMethod method = new GenericMethod(setter, receiver.getGenericClass());
        VariableReference result =
                test.addStatement(
                        new MethodStatement(
                                test, method, receiver, Collections.singletonList(argument)),
                        insertionPosition);
        return result;
    }

    public static VariableReference generateFieldSetterInTest(
            TestCase test,
            VariableReference targetObjectReference,
            Map<DepVariableWrapper, VarRelevance> map,
            Class<?> fieldDeclaringClass,
            Field field,
            boolean allowNullValue,
            Executable fieldSetter)
            throws ClassNotFoundException, ConstructionFailedException {
        if (fieldSetter != null && !isTarget(fieldSetter)) {
            if (fieldSetter instanceof Method) {
                GenericMethod genericFieldSetter =
                        new GenericMethod((Method) fieldSetter, fieldSetter.getDeclaringClass());
                VariableReference generatedSetter = null;
                if (targetObjectReference == null) {
                    EntityWithParametersStatement methodStatement =
                            test.findTargetExecutableStatement();
                    generatedSetter =
                            TestFactory.getInstance()
                                    .addMethod(
                                            test,
                                            genericFieldSetter,
                                            methodStatement.getPosition() - 1,
                                            2,
                                            allowNullValue);
                } else {
                    genericFieldSetter =
                            genericFieldSetter.copyWithNewOwner(
                                    targetObjectReference.getGenericClass());
                    generatedSetter =
                            TestFactory.getInstance()
                                    .addMethodFor(
                                            test,
                                            targetObjectReference,
                                            genericFieldSetter,
                                            targetObjectReference.getStPosition() + 1,
                                            allowNullValue);
                }
                return generatedSetter;
            } else if (fieldSetter instanceof Constructor) {
                Statement constructorStatement =
                        findConstructorStatement(
                                test, (Constructor) fieldSetter, targetObjectReference);
                if (constructorStatement != null) {
                    return constructorStatement.getReturnValue();
                } else {

                    /**
                     * check if the new instance can be set as a field of its parent. if yes, we
                     * change the test, otherwise, we do nothing.
                     */
                    GenericConstructor gConstructor =
                            new GenericConstructor(
                                    (Constructor) fieldSetter, fieldSetter.getDeclaringClass());
                    VariableReference returnedVar =
                            TestFactory.getInstance()
                                    .addConstructor(
                                            test,
                                            gConstructor,
                                            targetObjectReference.getStPosition(),
                                            2);

                    for (int i = 0; i < test.size(); i++) {
                        Statement statement = test.getStatement(i);
                        if (statement.references(targetObjectReference)) {
                            if (returnedVar.getStPosition() < statement.getPosition()) {
                                statement.replace(targetObjectReference, returnedVar);
                                replaceMapFromNode2Code(map, targetObjectReference, returnedVar);
                            } else if (returnedVar.getStPosition() > statement.getPosition()
                                    && statement.getPosition()
                                            != targetObjectReference.getStPosition()) {
                            }
                        }
                    }

                    return returnedVar;
                }
            }
        }

        return null;
    }

    private static Statement findConstructorStatement(
            TestCase test, Constructor setter, VariableReference targetObjectReference) {
        for (int i = 0; i < test.size(); i++) {
            Statement stat = test.getStatement(i);

            if (stat instanceof ConstructorStatement) {
                ConstructorStatement cStat = (ConstructorStatement) stat;
                if (cStat.getConstructor().getConstructor().equals(setter)) {

                    if (cStat.getReturnValue() == targetObjectReference) {
                        return stat;
                    }
                }
            }
        }

        return null;
    }

    public static void replaceMapFromNode2Code(
            Map<DepVariableWrapper, VarRelevance> map,
            VariableReference oldObject,
            VariableReference newObject) {
        for (DepVariableWrapper key : map.keySet()) {
            List<VariableReference> list = map.get(key).influentialVars;

            if (containsIdentity(list, oldObject)) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == oldObject) {
                        list.set(i, newObject);
                    }
                }
            }

            list = map.get(key).matchedVars;

            if (containsIdentity(list, oldObject)) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) == oldObject) {
                        list.set(i, newObject);
                    }
                }
            }
        }
    }

    private static boolean containsIdentity(
            List<VariableReference> references, VariableReference candidate) {
        if (references == null) {
            return false;
        }
        for (VariableReference reference : references) {
            if (reference == candidate) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTarget(Executable setter) {
        String className = setter.getDeclaringClass().getCanonicalName();

        String name = null;
        if (setter instanceof Method) {
            name = setter.getName();
        } else {
            name = "<init>";
        }

        String methodName = name + ReflectionUtil.getSignature(setter);

        return className.equals(org.evosuite.testcase.synthesizer.TargetContext.activeClassName())
                && methodName.equals(
                        org.evosuite.testcase.synthesizer.TargetContext.activeMethodSignature());
    }

    /**
     * Returns a method and one of its parameters for setting the field.
     *
     * @param field field whose setter is sought
     * @param targetClassName target class name
     * @return a suitable setter executable
     * @throws ClassNotFoundException if the target class cannot be loaded
     */
    public static Executable searchForPotentialSetterInClass(Field field, String targetClassName)
            throws ClassNotFoundException {
        return searchForPotentialSetterInClass(field, targetClassName, true, false);
    }

    /**
     * Eligibility counterpart of field synthesis for an object which already exists in the entry
     * prefix. Constructors are deliberately excluded: runtime synthesis will not replace that
     * receiver, so a constructor-only dependency is not an executable setup opportunity.
     *
     * <p>The existing candidate ranking samples EvoSuite's RNG. Eligibility discovery must remain
     * invisible to ordinary search, hence the exact state is restored after this read-only probe.
     */
    public static boolean hasUsableSetterForExistingReceiver(Field field, String targetClassName) {
        if (field == null || targetClassName == null || targetClassName.isEmpty()) {
            return false;
        }
        Randomness.State state = Randomness.snapshot();
        try {
            return searchForPotentialSetterInClass(field, targetClassName, false, false) != null;
        } catch (ClassNotFoundException | RuntimeException | LinkageError failure) {
            return false;
        } finally {
            Randomness.restore(state);
        }
    }

    private static Executable searchForPotentialSetterInClass(
            Field field,
            String targetClassName,
            boolean allowConstructorReplacement,
            boolean freshReceiver)
            throws ClassNotFoundException {
        if (isPlatformImplementationState(field, targetClassName)) {

            return null;
        }

        LinkedHashMap<Executable, List<ValueSettings>> setterMap =
                DataDependencyUtil.searchForPotentialSettersInClass(field, targetClassName);
        //        List<Executable> fieldSettingMethods = potentialSetter.setterList;

        //        List<Map<BytecodeInstruction, List<BytecodeInstruction>>> difficultyList =
        // potentialSetter.difficultyList;
        //        List<Set<Integer>> numberOfValidParams = potentialSetter.numberOfValidParams;

        //        System.currentTimeMillis();

        if (!setterMap.isEmpty()) {
            //            Executable entry = Randomness.choice(fieldSettingMethods);
            double[] scores = new double[setterMap.size()];
            Map<Executable, String> rejectionReasons = new LinkedHashMap<>();
            Set<Executable> directAssignments = new HashSet<>();
            for (Entry<Executable, List<ValueSettings>> entry : setterMap.entrySet()) {
                if (!(entry.getKey() instanceof Constructor)
                        && !isTarget(entry.getKey())
                        && DataDependencyUtil.isDirectFieldAssignmentSetting(
                                field, entry.getValue())) {
                    directAssignments.add(entry.getKey());
                }
            }
            Iterator<Entry<Executable, List<ValueSettings>>> iter = setterMap.entrySet().iterator();
            for (int i = 0; i < scores.length; i++) {
                Entry<Executable, List<ValueSettings>> entry = iter.next();
                scores[i] = estimateCoverageLikelihood(entry.getValue());
                boolean directAssignment = directAssignments.contains(entry.getKey());
                if (directAssignment) {
                    scores[i] += 100;
                }

                java.lang.reflect.Parameter[] pList = entry.getKey().getParameters();
                boolean hasParameterIndependentSetting = false;
                Set<Integer> relevantParameters = new HashSet<>();
                for (ValueSettings valueSetting : entry.getValue()) {
                    if (valueSetting.releventPrams == null
                            || valueSetting.releventPrams.isEmpty()) {
                        hasParameterIndependentSetting = true;
                    } else {
                        relevantParameters.addAll(valueSetting.releventPrams);
                    }
                }

                for (Integer index : relevantParameters) {
                    if (index == null || index < 0 || index >= pList.length) {
                        continue;
                    }
                    scores[i] += 1;
                    if (isValueParameterCompatible(field.getType(), pList[index].getType())) {
                        scores[i] += 20;
                    }
                }
                boolean typeCompatible =
                        hasCompatibleRelevantParameter(field, entry.getKey(), relevantParameters);
                boolean containerMutation =
                        DataDependencyUtil.isContainerMutationSetting(field, entry.getValue());
                boolean destructiveContainerMutation =
                        DataDependencyUtil.isDestructiveContainerMutationSetting(
                                field, entry.getValue());

                if (entry.getKey() instanceof Method && typeCompatible) {
                    scores[i] += 10;
                }
                if (containerMutation) {
                    // A Map/Collection mutator controls the field's contents through its key/value
                    // or element parameters; those parameters intentionally do not have the
                    // container's own type.
                    scores[i] += 15;
                }
                String rejectionReason =
                        containerMutation
                                ? null
                                : candidateRejectionReason(
                                        field,
                                        entry.getKey(),
                                        relevantParameters,
                                        hasParameterIndependentSetting);
                if (rejectionReason != null) {
                    /*
                     * An indirect influence is not enough to make a useful synthesis action. For
                     * example, Action.unpack(DataInputStream) reaches each int field through
                     * readByte(), but the stream is neither the field value nor a tunable value
                     * slot. Generating it can throw before the target call. Keep true fixed-effect
                     * methods (which have a parameter-independent setting) and constructors, but
                     * require parameter-driven methods to expose at least one field-compatible
                     * relevant parameter.
                     */
                    rejectionReasons.put(entry.getKey(), rejectionReason);
                }
                if (!allowConstructorReplacement && entry.getKey() instanceof Constructor) {
                    rejectionReasons.put(entry.getKey(), "would_replace_existing_receiver");
                }
                if (isTarget(entry.getKey())) {
                    rejectionReasons.put(entry.getKey(), "target_entry_call_not_setup");
                }
                if (!directAssignments.isEmpty() && !directAssignment) {
                    rejectionReasons.put(
                            entry.getKey(), "lower_priority_than_direct_field_assignment");
                }
                if (freshReceiver && destructiveContainerMutation) {
                    rejectionReasons.put(entry.getKey(), "destructive_mutation_on_fresh_receiver");
                }

                //                System.currentTimeMillis();
            }

            double[] probability = normalizeEligible(scores, setterMap, rejectionReasons);
            if (rejectionReasons.size() == setterMap.size()) {

                return null;
            }
            double p = Randomness.nextDouble();
            //            System.currentTimeMillis();
            int selected = select(p, probability);
            Executable initiallySelected = (Executable) setterMap.keySet().toArray()[selected];
            if (rejectionReasons.containsKey(initiallySelected)) {
                selected = firstEligibleIndex(setterMap, rejectionReasons);
            }

            Executable e = (Executable) setterMap.keySet().toArray()[selected];
            return e;
        }

        return null;
    }

    /**
     * A directly constructed receiver with no intervening instance call still has its initial
     * container state. This is the point at which remove/clear-style candidates are guaranteed not
     * to perform construction work for the common empty-container initialization pattern.
     */
    static boolean isFreshlyConstructedReceiver(
            TestCase test, VariableReference targetObjectReference) {
        if (test == null || targetObjectReference == null) {
            return false;
        }
        int definition = targetObjectReference.getStPosition();
        if (definition < 0
                || definition >= test.size()
                || !(test.getStatement(definition) instanceof ConstructorStatement)) {
            return false;
        }
        EntityWithParametersStatement target = test.findTargetExecutableStatement();
        int limit = target == null ? test.size() : target.getPosition();
        for (int position = definition + 1; position < limit; position++) {
            Statement statement = test.getStatement(position);
            if (statement instanceof MethodStatement) {
                VariableReference callee = ((MethodStatement) statement).getCallee();
                if (targetObjectReference.equals(callee)) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean isPlatformImplementationState(Field field, String targetClassName) {
        String fieldOwner = field == null ? null : field.getDeclaringClass().getName();
        return ObjectConstructionBoundary.isExternalImplementationClass(fieldOwner)
                || ObjectConstructionBoundary.isExternalImplementationClass(targetClassName);
    }

    static String candidateRejectionReason(
            Field field,
            Executable executable,
            Set<Integer> relevantParameters,
            boolean hasParameterIndependentSetting) {
        if (!(executable instanceof Method)) {
            return null;
        }
        if (hasCompatibleRelevantParameter(field, executable, relevantParameters)) {
            return null;
        }
        if (hasParameterIndependentSetting) {
            return null;
        }
        return "no_directly_compatible_value_parameter";
    }

    private static boolean hasCompatibleRelevantParameter(
            Field field, Executable executable, Set<Integer> relevantParameters) {
        if (field == null || executable == null || relevantParameters == null) {
            return false;
        }
        Class<?>[] parameterTypes = executable.getParameterTypes();
        for (Integer index : relevantParameters) {
            if (index != null
                    && index >= 0
                    && index < parameterTypes.length
                    && isValueParameterCompatible(field.getType(), parameterTypes[index])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValueParameterCompatible(Class<?> fieldType, Class<?> parameterType) {
        Class<?> boxedFieldType = boxPrimitive(fieldType);
        Class<?> boxedParameterType = boxPrimitive(parameterType);
        return boxedFieldType != null
                && boxedParameterType != null
                && boxedFieldType.isAssignableFrom(boxedParameterType);
    }

    private static Class<?> boxPrimitive(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == void.class) return Void.class;
        return type;
    }

    private static double[] normalizeEligible(
            double[] scores,
            LinkedHashMap<Executable, List<ValueSettings>> candidates,
            Map<Executable, String> rejectionReasons) {
        double sum = 0.0;
        int eligibleCount = 0;
        int index = 0;
        for (Executable executable : candidates.keySet()) {
            if (!rejectionReasons.containsKey(executable)) {
                sum += Math.max(0.0, scores[index]);
                eligibleCount++;
            }
            index++;
        }
        double[] cumulative = new double[scores.length];
        double running = 0.0;
        int lastEligible = -1;
        index = 0;
        for (Executable executable : candidates.keySet()) {
            if (!rejectionReasons.containsKey(executable) && eligibleCount > 0) {
                running += sum > 0.0 ? Math.max(0.0, scores[index]) / sum : 1.0 / eligibleCount;
                lastEligible = index;
            }
            cumulative[index] = running;
            index++;
        }
        if (lastEligible >= 0) {
            for (int i = lastEligible; i < cumulative.length; i++) {
                cumulative[i] = 1.0;
            }
        }
        return cumulative;
    }

    private static int firstEligibleIndex(
            LinkedHashMap<Executable, List<ValueSettings>> candidates,
            Map<Executable, String> rejectionReasons) {
        int index = 0;
        for (Executable executable : candidates.keySet()) {
            if (!rejectionReasons.containsKey(executable)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /**
     * we have three factors to estimate how difficult a setter is to influence some branch in the
     * target method, (1) call chain, (2) the control flow in the call chain, (3) the number of
     * mutable variables.
     *
     * @param map
     * @param integer
     * @return
     */
    private static double estimateCoverageLikelihood(List<ValueSettings> settings) {
        double sum = 0;
        for (ValueSettings setting : settings) {
            double callchainSize = setting.callChain.size() * 1.0d;
            sum += setting.releventPrams.size() / (callchainSize + 1);
        }

        return sum;
    }

    private static int select(double p, double[] probability) {
        for (int i = 0; i < probability.length; i++) {
            if (i == 0) {
                if (p <= probability[i]) {
                    return i;
                }
            } else {
                if (probability[i - 1] < p && p <= probability[i]) {
                    return i;
                }
            }
        }

        return 0;
    }

    private static double[] normalize(double[] scores) {
        double sum = 0;
        for (int i = 0; i < scores.length; i++) {
            sum += scores[i];
        }

        double[] prob = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            prob[i] = scores[i] / sum;
        }

        for (int i = 1; i < scores.length; i++) {
            prob[i] += prob[i - 1];
        }

        return prob;
    }

    public static String checkTargetClassName(
            Field field, VariableReference targetObjectReference) {
        if (targetObjectReference != null) {
            String typeName = targetObjectReference.getType().getTypeName();
            if (typeName.contains("<")) {
                typeName = typeName.substring(0, typeName.indexOf("<"));
                return typeName;
            } else {
                return typeName;
            }
        } else {
            return field.getDeclaringClass().getCanonicalName();
        }
    }

    public static VariableReference generatePublicFieldSetterOrGetter(
            TestCase test,
            VariableReference targetObjectReference,
            String fieldType,
            GenericField genericField,
            boolean allowNullValue,
            Branch branch)
            throws ConstructionFailedException {
        AbstractStatement stmt;
        if (java.util.Arrays.asList("Z", "B", "C", "S", "I", "J", "F", "D").contains(fieldType)) {
            stmt =
                    addStatementToSetOrGetPublicField(
                            test,
                            fieldType,
                            genericField,
                            targetObjectReference,
                            new PrimitiveFieldInitializer(
                                    DataDependencyUtil.switchCaseValueForField(
                                            branch, genericField.getField())),
                            allowNullValue);
        } else {
            stmt =
                    addStatementToSetOrGetPublicField(
                            test,
                            fieldType,
                            genericField,
                            targetObjectReference,
                            new NonPrimitiveFieldInitializer(),
                            allowNullValue);
        }

        if (stmt != null && stmt.getReturnValue() != null) {
            if (Collection.class.isAssignableFrom(genericField.getField().getType())) {
                Class<?> fType = genericField.getField().getType();
                VariableCodeGenerationUtil.generateElements(fType, test, stmt.getReturnValue());
            }
            return stmt.getReturnValue();
        }

        return null;
    }

    private static AbstractStatement addStatementToSetOrGetPublicField(
            TestCase test,
            String fieldType,
            GenericField genericField,
            VariableReference parentVarRef,
            FieldInitializer fieldInitializer,
            boolean allowNullValue)
            throws ConstructionFailedException {
        EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
        int insertionPosition =
                (parentVarRef != null) ? parentVarRef.getStPosition() + 1 : mStat.getPosition() - 1;

        if (insertionPosition >= mStat.getPosition()) {
            insertionPosition = mStat.getPosition() - 1;
        }

        FieldReference fieldVar = null;
        if (genericField.isStatic() || parentVarRef == null) {
            fieldVar = new FieldReference(test, genericField);
        } else {
            fieldVar = new FieldReference(test, genericField, parentVarRef);
        }

        VariableReference objRef =
                fieldInitializer.assignField(
                        TestFactory.getInstance(),
                        test,
                        fieldType,
                        genericField,
                        insertionPosition,
                        fieldVar,
                        allowNullValue);

        AbstractStatement stmt = new AssignmentStatement(test, fieldVar, objRef);
        test.addStatement(stmt, objRef.getStPosition() + 1);

        return stmt;
    }
}
