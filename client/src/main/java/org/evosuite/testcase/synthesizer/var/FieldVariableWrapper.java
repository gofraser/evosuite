package org.evosuite.testcase.synthesizer.var;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionReferenceUtil;
import org.evosuite.testcase.synthesizer.UsedReferenceSearcher;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericField;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FieldVariableWrapper extends DepVariableWrapper{

    private boolean terminalWriterGenerated;

    protected FieldVariableWrapper(DepVariable var) {
        super(var);
    }

    /**
     * set the field into the parentVarRef
     *
     * @param test
     * @param position
     * @param var
     * @param parentVarRef
     * @param isStatic
     * @return
     */
    @Override
    public VarRelevance generateOrFindStatement(
            TestCase test,
            boolean isLeaf,
            VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue,
            Operation recommendation) {
        List<VariableReference> list = new ArrayList<>();

        // TODO Darien

        VariableReference var = generateOrFind(test, isLeaf, variable, map, b, allowNullValue);
        if (var != null) {
            list.add(var);
        }

        return new VarRelevance(list, list);
    }

    public VariableReference generateOrFind(
            TestCase test,
            boolean isLeaf,
            VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue) {
        terminalWriterGenerated = false;
        FieldInsnNode fieldNode = (FieldInsnNode) this.var.getInstruction().getASMNode();
        String fieldType = fieldNode.desc;
        String fieldOwner = fieldNode.owner.replace("/", ".");
        String fieldTypeName = fieldType.replace("/", ".");
        if (fieldTypeName.startsWith("L")) {
            fieldTypeName = fieldTypeName.substring(1, fieldTypeName.length() - 1);
        } else if (fieldTypeName.startsWith("[L")) {
            fieldTypeName = fieldTypeName.substring(2, fieldTypeName.length() - 1);
        }
        String fieldName = fieldNode.name;

        // string value
        if (fieldOwner.equals("java.lang.String") && fieldName.equals("value")) return null;

        if (variable.callerObject != null) {
            int callerPosition =
                    ConstructionReferenceUtil.definitionPosition(test, variable.callerObject);
            if (callerPosition < 0) {
                return null;
            }
            Statement stat = test.getStatement(callerPosition);
            if (stat instanceof NullStatement) {

                return null;
            }

            String callerType = variable.callerObject.getClassName();
            if (!VariableCodeGenerationUtil.isPrimitiveClass(callerType)) {
                if (!VariableCodeGenerationUtil.isCompatible(fieldOwner, callerType)) {
                    return null;
                }
            }
        }

        try {
            Class<?> fieldDeclaringClass =
                    TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(fieldOwner);
            //            registerAllMethods(fieldDeclaringClass);
            Field field = VariableCodeGenerationUtil.searchForField(fieldDeclaringClass, fieldName);

            if (field == null) {
                return null;
            }

            VariableReference factoryInput =
                    findFactoryBackedFieldValue(test, variable.callerObject, field);
            if (factoryInput != null) {

                return factoryInput;
            }

            /**
             * if the field is leaf, check if there is setter in the testcase if the field is not
             * leaf, check if there is getter in the testcase if found, stop here
             */
            UsedReferenceSearcher usedRefSearcher = new UsedReferenceSearcher();
            VariableReference usedFieldInTest =
                    isLeaf
                            // TODO
                            ? usedRefSearcher.searchRelevantFieldWritingReferenceInTest(
                                    test, field, variable.callerObject)
                            // TODO
                            : usedRefSearcher.searchRelevantFieldReadingReferenceInTest(
                                    test, field, variable.callerObject);
            //            System.currentTimeMillis();
            if (usedFieldInTest != null) {

                /** generate some elements for container classes */
                if (Collection.class.isAssignableFrom(usedFieldInTest.getVariableClass())) {
                    VariableCodeGenerationUtil.generateElements(
                            field.getType(), test, usedFieldInTest);
                }

                return usedFieldInTest;
            }

            /** now we try to generate the relevant statement in the test case. */
            GenericField genericField = new GenericField(field, field.getDeclaringClass());
            int fieldModifiers = field.getModifiers();

            /**
             * deal with public field, we handle the public field getter/setter in the same way,
             * a.k.a., create a new public field instance.
             */
            if (Modifier.isPublic(fieldModifiers)) {
                if (genericField.isFinal()) {
                    // Final fields cannot be assigned, but they are often precisely the stable
                    // constants used by a branch predicate (enum constants and their public
                    // value fields are common examples).  The old code rejected them outright,
                    // which left the downstream method-call argument unresolved and caused a
                    // random replacement value to be generated.  Materialize a read of the
                    // exact field instead.
                    VariableReference source =
                            genericField.isStatic() ? null : variable.callerObject;
                    if (!genericField.isStatic() && source == null) {
                        return null;
                    }
                    EntityWithParametersStatement targetCall = test.findTargetExecutableStatement();
                    int insertionPosition =
                            targetCall == null ? test.size() : targetCall.getPosition();
                    if (source != null) {
                        insertionPosition = Math.max(insertionPosition, source.getStPosition() + 1);
                    }
                    return test.addStatement(
                            new FieldStatement(test, genericField, source), insertionPosition);
                }

                for (int i = 0; i < 3; i++) {
                    try {
                        // TODO
                        VariableReference obj =
                                VariableCodeGenerationUtil.generatePublicFieldSetterOrGetter(
                                        test,
                                        variable.callerObject,
                                        fieldType,
                                        genericField,
                                        allowNullValue,
                                        b);
                        return obj;
                    } catch (ConstructionFailedException e) {

                    }
                }
            }

            /** deal with non-public field */
            if (!isLeaf) {
                /*
                 * A non-leaf field is often followed by observational plumbing such as
                 * Map.values().iterator().hasNext(). A getter is useful when one exists, but a
                 * private container with no getter must be populated through an owning-object
                 * mutator rather than abandoned as unreadable state.
                 */
                VariableReference setterObject =
                        VariableCodeGenerationUtil.generateFieldSetterInTest(
                                test,
                                variable.callerObject,
                                map,
                                fieldDeclaringClass,
                                field,
                                allowNullValue);
                if (setterObject != null) {
                    terminalWriterGenerated = true;
                    return setterInput(test, setterObject);
                }
                /*
                 * A getter is only a fallback when no writer exists. Preferring it would satisfy
                 * graph traversal without changing the container, yielding an inert template.
                 */
                return VariableCodeGenerationUtil.generateFieldGetterInTest(
                        test,
                        variable.callerObject,
                        map,
                        fieldDeclaringClass,
                        field,
                        usedRefSearcher,
                        b);
            } else {
                // TODO
                VariableReference setterObject =
                        VariableCodeGenerationUtil.generateFieldSetterInTest(
                                test,
                                variable.callerObject,
                                map,
                                fieldDeclaringClass,
                                field,
                                allowNullValue);
                if (setterObject != null) {
                    return setterInput(test, setterObject);
                }
                return setterObject;
            }

        } catch (ClassNotFoundException | SecurityException | ConstructionFailedException e) {
            //            this.printConstructionError(test, b);
            e.printStackTrace();
            return null;
        }
    }

    /**
     * A private final collection may be the immutable view of an input to the static factory that
     * created the target receiver. In that case the factory argument is the only public,
     * semantically exact representation of the field. Reuse it, but only after constructor bytecode
     * confirms that this particular field is derived from a compatible constructor parameter; this
     * excludes similarly typed derived caches.
     */
    private static VariableReference findFactoryBackedFieldValue(
            TestCase test, VariableReference receiver, Field field) {
        if (test == null
                || receiver == null
                || field == null
                || !Modifier.isFinal(field.getModifiers())
                || !(Collection.class.isAssignableFrom(field.getType())
                        || java.util.Map.class.isAssignableFrom(field.getType()))) {
            return null;
        }
        int receiverPosition = ConstructionReferenceUtil.definitionPosition(test, receiver);
        if (receiverPosition < 0
                || !(test.getStatement(receiverPosition) instanceof MethodStatement)) {
            return null;
        }
        MethodStatement factory = (MethodStatement) test.getStatement(receiverPosition);
        if (factory.getCallee() != null
                || !Modifier.isStatic(factory.getMethod().getMethod().getModifiers())) {
            return null;
        }
        List<VariableReference> arguments = factory.getParameterReferences();
        for (int index = 0; index < arguments.size(); index++) {
            VariableReference argument = arguments.get(index);
            if (argument == null
                    || !field.getType().isAssignableFrom(argument.getVariableClass())
                    || !genericArgumentsCompatible(field.getGenericType(), argument)) {
                continue;
            }
            if (fieldDerivedFromCompatibleConstructorParameter(field)) {
                return argument;
            }
        }
        return null;
    }

    private static VariableReference setterInput(TestCase test, VariableReference setterObject) {
        int position = ConstructionReferenceUtil.definitionPosition(test, setterObject);
        if (position >= 0) {
            Statement statement = test.getStatement(position);
            if (statement instanceof MethodStatement) {
                MethodStatement method = (MethodStatement) statement;
                if (!method.getParameterReferences().isEmpty()) {
                    return method.getParameterReferences().get(0);
                }
            }
        }
        return setterObject;
    }

    @Override
    public boolean traverseChildrenAfterGeneration(boolean generated) {
        // The mutator already realizes the field predicate. Its Map/iterator/query descendants are
        // runtime observations, not additional source-level construction operations.
        return !terminalWriterGenerated && super.traverseChildrenAfterGeneration(generated);
    }

    private static boolean genericArgumentsCompatible(
            java.lang.reflect.Type fieldType, VariableReference argument) {
        List<GenericClass<?>> fieldArguments =
                GenericClassFactory.get(fieldType).getParameterClasses();
        List<GenericClass<?>> valueArguments = argument.getGenericClass().getParameterClasses();
        if (fieldArguments.isEmpty() || valueArguments.isEmpty()) {
            return true;
        }
        if (fieldArguments.size() != valueArguments.size()) {
            return false;
        }
        for (int i = 0; i < fieldArguments.size(); i++) {
            Class<?> expected = fieldArguments.get(i).getRawClass();
            Class<?> actual = valueArguments.get(i).getRawClass();
            if (expected != null && actual != null && !expected.isAssignableFrom(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fieldDerivedFromCompatibleConstructorParameter(Field field) {
        ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
        for (Constructor<?> constructor : field.getDeclaringClass().getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            int local = 1;
            for (int index = 0; index < parameterTypes.length; index++) {
                if (field.getType().isAssignableFrom(parameterTypes[index])
                        && constructorStoresParameterInField(loader, constructor, field, local)) {
                    return true;
                }
                local +=
                        parameterTypes[index] == long.class || parameterTypes[index] == double.class
                                ? 2
                                : 1;
            }
        }
        return false;
    }

    private static boolean constructorStoresParameterInField(
            ClassLoader loader, Constructor<?> constructor, Field field, int parameterLocal) {
        String method = "<init>" + org.evosuite.utils.MethodUtil.getSignature(constructor);
        List<BytecodeInstruction> instructions =
                BytecodeInstructionPool.getInstance(loader)
                        .getInstructionsIn(field.getDeclaringClass().getName(), method);
        if (instructions == null) {
            return false;
        }
        for (BytecodeInstruction instruction : instructions) {
            AbstractInsnNode node = instruction.getASMNode();
            if (!(node instanceof FieldInsnNode) || node.getOpcode() != Opcodes.PUTFIELD) {
                continue;
            }
            FieldInsnNode put = (FieldInsnNode) node;
            if (!field.getName().equals(put.name)
                    || !Type.getDescriptor(field.getType()).equals(put.desc)) {
                continue;
            }
            BytecodeInstruction value = instruction.getSourceOfStackInstruction(0);
            if (dependsOnLocal(
                    value,
                    parameterLocal,
                    Collections.newSetFromMap(new IdentityHashMap<BytecodeInstruction, Boolean>()),
                    12)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dependsOnLocal(
            BytecodeInstruction instruction,
            int local,
            Set<BytecodeInstruction> visited,
            int remaining) {
        if (instruction == null || remaining <= 0 || !visited.add(instruction)) {
            return false;
        }
        if (instruction.getASMNode() instanceof VarInsnNode
                && ((VarInsnNode) instruction.getASMNode()).var == local) {
            return true;
        }
        int operands =
                instruction.isMethodCall()
                        ? instruction.getCalledMethodsArgumentCount()
                                + (instruction.getASMNode().getOpcode() == Opcodes.INVOKESTATIC
                                        ? 0
                                        : 1)
                        : instruction.getOperandNum();
        for (int operand = 0; operand < operands; operand++) {
            if (dependsOnLocal(
                    instruction.getSourceOfStackInstruction(operand),
                    local,
                    visited,
                    remaining - 1)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public VariableReference find(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map) {
        FieldInsnNode fieldNode = (FieldInsnNode) this.var.getInstruction().getASMNode();
        String fieldType = fieldNode.desc;
        String fieldOwner = fieldNode.owner.replace("/", ".");
        String fieldTypeName = fieldType.replace("/", ".");
        if (fieldTypeName.startsWith("L")) {
            fieldTypeName = fieldTypeName.substring(1, fieldTypeName.length() - 1);
        } else if (fieldTypeName.startsWith("[L")) {
            fieldTypeName = fieldTypeName.substring(2, fieldTypeName.length() - 1);
        }
        String fieldName = fieldNode.name;

        // string value
        if (fieldOwner.equals("java.lang.String") && fieldName.equals("value")) return null;

        if (callerObject != null) {
            Statement stat = test.getStatement(callerObject.getStPosition());
            if (stat instanceof NullStatement) {
                return null;
            }

            String callerType = callerObject.getClassName();
            if (!VariableCodeGenerationUtil.isPrimitiveClass(callerType)) {
                if (!VariableCodeGenerationUtil.isCompatible(fieldOwner, callerType)) {
                    return null;
                }
            }
        }

        try {
            Class<?> fieldDeclaringClass =
                    TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(fieldOwner);
            //            registerAllMethods(fieldDeclaringClass);
            Field field = VariableCodeGenerationUtil.searchForField(fieldDeclaringClass, fieldName);

            /**
             * if the field is leaf, check if there is setter in the testcase if the field is not
             * leaf, check if there is getter in the testcase if found, stop here
             */
            UsedReferenceSearcher usedRefSearcher = new UsedReferenceSearcher();
            VariableReference usedFieldInTest =
                    isLeaf
                            ? usedRefSearcher.searchRelevantFieldWritingReferenceInTest(
                                    test, field, callerObject)
                            : usedRefSearcher.searchRelevantFieldReadingReferenceInTest(
                                    test, field, callerObject);
            if (usedFieldInTest != null) {
                return usedFieldInTest;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
