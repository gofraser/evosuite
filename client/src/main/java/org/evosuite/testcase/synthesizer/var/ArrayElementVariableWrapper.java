package org.evosuite.testcase.synthesizer.var;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BytePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.CharPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.FloatPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.LongPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.ShortPrimitiveStatement;
import org.evosuite.testcase.synthesizer.ConstructionReferenceUtil;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArrayElementVariableWrapper extends DepVariableWrapper {

    protected ArrayElementVariableWrapper(DepVariable var) {
        super(var);
    }

    @Override
    public VarRelevance generateOrFindStatement(
            TestCase test,
            boolean isLeaf,
            VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue,
            Operation recommendation) {

        try {
            return generateArrayElementStatement(test, isLeaf, variable, map);
        } catch (ConstructionFailedException e) {
            e.printStackTrace();
        }

        return null;
    }

    private VarRelevance generateArrayElementStatement(
            TestCase test,
            boolean isLeaf,
            VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map)
            throws ConstructionFailedException {
        int callerPosition =
                ConstructionReferenceUtil.definitionPosition(test, variable.callerObject);
        if (callerPosition < 0) {

            return null;
        }
        Statement stat = test.getStatement(callerPosition);
        if (stat instanceof NullStatement) {

            return null;
        }

        int opcodeRead = this.var.getInstruction().getASMNode().getOpcode();
        int opcodeWrite = getCorrespondingWriteOpcode(opcodeRead);
        Integer preferredIndex = preferredAccessorIndex(test, map);
        //        Statement statement = test.getStatement(variable.callerObject.getStPosition());

        /** check reused array element */
        List<VariableReference> usedArrayElementList =
                searchUsedArrayElementReference(test, variable, preferredIndex);
        if (!usedArrayElementList.isEmpty()) {
            return new VarRelevance(usedArrayElementList, usedArrayElementList);
        }

        ArrayAccessContext context = resolveArrayAccessContext(test, variable.callerObject);
        List<VariableReference> existingSetters =
                searchArrayElementWritingReference(test, variable, opcodeWrite, context);
        if (!existingSetters.isEmpty()) {
            return new VarRelevance(existingSetters, existingSetters);
        }

        // Reading a possibly-null element is not a realization of that node. Prefer an owner
        // method whose parameter is proven to flow to the exact backing array field. This applies
        // to non-leaf elements and to reference-array leaves: interprocedural analysis can expose
        // dependent element state through a separate getter path, making the ARRAY_ELE wrapper look
        // like a leaf even though the constructed object is semantically used downstream.
        if (context.hasDistinctOwner(test)) {
            VariableReference insertedElement =
                    generateArrayElementSetter(test, variable, opcodeWrite, context);
            if (insertedElement != null) {
                rewireMatchingArrayGetterUses(test, context, insertedElement, map);
                List<VariableReference> result = Collections.singletonList(insertedElement);
                return new VarRelevance(result, result);
            }
        }

        List<VariableReference> usedArrayRefs =
                isLeaf
                        ? Collections.emptyList()
                        : searchArrayElementReadingReference(test, variable, opcodeRead, context);
        if (usedArrayRefs != null && !usedArrayRefs.isEmpty()) {
            return new VarRelevance(usedArrayRefs, usedArrayRefs);
        }

        double prob = Randomness.nextDouble();
        if (prob > 0.8) {
            if (isLeaf) {

                /** generate setter */
                ElementSetter setter = searchSetterForArrayElement(opcodeWrite, variable, context);
                if (setter != null) {
                    GenericMethod gMethod =
                            new GenericMethod(setter.method, setter.method.getDeclaringClass());
                    TestFactory.getInstance()
                            .addMethodFor(
                                    test,
                                    context.ownerReference,
                                    gMethod,
                                    ConstructionReferenceUtil.definitionPosition(
                                                    test, context.ownerReference)
                                            + 1,
                                    false);
                    return null;
                }
            } else {

                /** generate getter */
                Method getter = searchGetterForArrayElement(opcodeRead, variable, context);
                if (getter != null) {
                    VariableReference newParentVarRef = null;
                    GenericMethod gMethod = new GenericMethod(getter, getter.getDeclaringClass());
                    newParentVarRef =
                            TestFactory.getInstance()
                                    .addMethodFor(
                                            test,
                                            context.ownerReference,
                                            gMethod,
                                            ConstructionReferenceUtil.definitionPosition(
                                                            test, context.ownerReference)
                                                    + 1,
                                            false);
                    //                    V newParentVarRef;
                    List<VariableReference> vars = new ArrayList<>();
                    if (newParentVarRef != null) {
                        vars.add(newParentVarRef);
                    }
                    return new VarRelevance(vars, vars);
                }

                return null;
            }
        // Have a try to set the array element directly.
        } else {
            if (variable.callerObject instanceof ArrayReference) {
                ArrayReference arrayRef = (ArrayReference) variable.callerObject;
                int length = arrayRef.getArrayLength();
                if (arrayRef.getArrayLength() <= 0) {
                    length = Randomness.nextInt(10) + 1;
                }
                int index =
                        preferredIndex != null && preferredIndex >= 0 && preferredIndex < length
                                ? preferredIndex
                                : Randomness.nextInt(length);

                ArrayIndex arrayIndex = new ArrayIndex(test, arrayRef, index);
                VariableReference varRef = createArrayElementVariable(test, arrayRef);
                if (varRef == null) return null;

                AssignmentStatement assignStat = new AssignmentStatement(test, arrayIndex, varRef);
                test.addStatement(assignStat, varRef.getStPosition() + 1);
                VariableReference ref = assignStat.getReturnValue();
                List<VariableReference> vars = new ArrayList<>();
                if (ref != null) {
                    vars.add(ref);
                }

                return new VarRelevance(vars, vars);
            }
        }

        return null;
    }

    private List<VariableReference> searchUsedArrayElementReference(
            TestCase test, VariableInTest variable, Integer preferredIndex) {
        List<VariableReference> elementList = new ArrayList<>();
        if (variable.callerObject instanceof ArrayReference && variable.nodePath.size() == 1) {
            ArrayReference arrayObject = (ArrayReference) variable.callerObject;
            for (int i = 0; i < test.size(); i++) {
                Statement statement = test.getStatement(i);
                if (statement instanceof AssignmentStatement) {
                    AssignmentStatement aStat = (AssignmentStatement) statement;
                    VariableReference var = aStat.getReturnValue();
                    if (var.isArrayIndex()) {
                        if (var instanceof ArrayIndex) {
                            ArrayIndex index = (ArrayIndex) var;
                            if (index.getArray().equals(arrayObject)
                                    && (preferredIndex == null
                                            || index.getArrayIndex() == preferredIndex)) {
                                elementList.add(aStat.getValue());
                            }
                        }
                    }
                }
            }
        }

        return elementList;
    }

    /**
     * If this element is returned by an accessor used in the target method, use the same index as
     * that accessor. This preserves identities such as {@code values[parameter - 1] ==
     * get(parameter)} instead of initializing a random slot.
     */
    private Integer preferredAccessorIndex(
            TestCase test, Map<DepVariableWrapper, VarRelevance> map) {
        if (test == null || map == null || var.getInstruction() == null) {
            return null;
        }
        for (DepVariableWrapper child : children) {
            if (child == null
                    || child.var == null
                    || !child.var.isMethodCall()
                    || child.var.getInstruction() == null
                    || !(child.var.getInstruction().getASMNode() instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode invocation = (MethodInsnNode) child.var.getInstruction().getASMNode();
            if (!var.getInstruction().getClassName().equals(invocation.owner.replace('/', '.'))
                    || !var.getInstruction()
                            .getMethodName()
                            .equals(invocation.name + invocation.desc)) {
                continue;
            }
            for (DepVariableWrapper parent : child.parents) {
                if (parent == null || parent.var == null || !parent.var.isParameter()) {
                    continue;
                }
                int operand = parent.findRelationPosition(child);
                if (operand <= 0) {
                    continue;
                }
                VarRelevance relevance = map.get(parent);
                if (relevance == null
                        || relevance.matchedVars == null
                        || relevance.matchedVars.isEmpty()) {
                    continue;
                }
                VariableReference argument = relevance.matchedVars.get(0);
                Integer value = integerValue(test, argument);
                if (value == null) {
                    continue;
                }
                BytecodeInstruction indexInstruction =
                        var.getInstruction().getSourceOfStackInstruction(0);
                Integer index = evaluateInteger(indexInstruction, operand - 1, value, 12);
                if (index != null) {
                    return index;
                }
            }
        }
        return null;
    }

    private static Integer integerValue(TestCase test, VariableReference reference) {
        int position = ConstructionReferenceUtil.definitionPosition(test, reference);
        if (position < 0 || !(test.getStatement(position) instanceof PrimitiveStatement)) {
            return null;
        }
        Object value = ((PrimitiveStatement<?>) test.getStatement(position)).getValue();
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Integer evaluateInteger(
            BytecodeInstruction instruction,
            int parameterPosition,
            int parameterValue,
            int remaining) {
        if (instruction == null || remaining <= 0) {
            return null;
        }
        if (instruction.isParameter() && instruction.getParameterPosition() == parameterPosition) {
            return parameterValue;
        }
        Integer constant = integerConstant(instruction);
        if (constant != null) {
            return constant;
        }
        int opcode = instruction.getASMNode().getOpcode();
        if (opcode == Opcodes.IADD || opcode == Opcodes.ISUB) {
            Integer left =
                    evaluateInteger(
                            instruction.getSourceOfStackInstruction(1),
                            parameterPosition,
                            parameterValue,
                            remaining - 1);
            Integer right =
                    evaluateInteger(
                            instruction.getSourceOfStackInstruction(0),
                            parameterPosition,
                            parameterValue,
                            remaining - 1);
            if (left != null && right != null) {
                return opcode == Opcodes.IADD ? left + right : left - right;
            }
        }
        return null;
    }

    private static Integer integerConstant(BytecodeInstruction instruction) {
        if (instruction == null || instruction.getASMNode() == null) {
            return null;
        }
        if (instruction.getASMNode() instanceof IntInsnNode) {
            return ((IntInsnNode) instruction.getASMNode()).operand;
        }
        if (instruction.getASMNode() instanceof LdcInsnNode) {
            Object value = ((LdcInsnNode) instruction.getASMNode()).cst;
            return value instanceof Number ? ((Number) value).intValue() : null;
        }
        if (instruction.getASMNode() instanceof InsnNode) {
            switch (instruction.getASMNode().getOpcode()) {
                case Opcodes.ICONST_M1:
                    return -1;
                case Opcodes.ICONST_0:
                    return 0;
                case Opcodes.ICONST_1:
                    return 1;
                case Opcodes.ICONST_2:
                    return 2;
                case Opcodes.ICONST_3:
                    return 3;
                case Opcodes.ICONST_4:
                    return 4;
                case Opcodes.ICONST_5:
                    return 5;
                default:
                    return null;
            }
        }
        return null;
    }

    private Method searchGetterForArrayElement(
            int opcodeRead, VariableInTest variable, ArrayAccessContext context) {
        Set<Method> targetMethods = new HashSet<>();
        Class<?> clazz = context.ownerReference.getGenericClass().getRawClass();
        for (Method method : clazz.getMethods()) {
            boolean isValid = checkValidArrayElementGetter(method, opcodeRead, variable);
            if (isValid) {
                targetMethods.add(method);
            }
        }

        return Randomness.choice(targetMethods);
    }

    class ElementSetter {
        Method method;
        Integer paramPos;

        public ElementSetter(Method method, Integer paramPos) {
            super();
            this.method = method;
            this.paramPos = paramPos;
        }
    }

    private ElementSetter searchSetterForArrayElement(
            int opcodeWrite, VariableInTest variable, ArrayAccessContext context) {
        List<ElementSetter> targetMethods =
                findSettersForArrayElement(opcodeWrite, variable, context);
        return Randomness.choice(targetMethods);
    }

    private List<ElementSetter> findSettersForArrayElement(
            int opcodeWrite, VariableInTest variable, ArrayAccessContext context) {
        List<ElementSetter> targetMethods = new ArrayList<>();
        if (context.ownerReference == null) {
            return targetMethods;
        }
        Class<?> clazz = context.ownerReference.getGenericClass().getRawClass();
        for (Method method : clazz.getMethods()) {
            Integer paramPos = checkValidArrayElementSetter(method, opcodeWrite, variable);
            if (paramPos != -1) {
                targetMethods.add(new ElementSetter(method, paramPos));
            }
        }
        targetMethods.sort(Comparator.comparing(setter -> setter.method.toGenericString()));
        return targetMethods;
    }

    /** AASTORE, BASTORE, CASTORE, DASTORE, FASTORE, IASTORE, LASTORE, SASTORE */
    private List<VariableReference> searchArrayElementWritingReference(
            TestCase test, VariableInTest variable, int opcodeWrite, ArrayAccessContext context) {

        List<VariableReference> variableList = new ArrayList<VariableReference>();

        // Check array[*] = ....;, and collect all array[*].
        if (variable.isDirentNodeAccess() && variable.callerObject instanceof ArrayReference) {
            for (int i = 0; i < test.size(); i++) {
                Statement stat = test.getStatement(i);
                if (stat instanceof AssignmentStatement) {
                    AssignmentStatement assignStat = (AssignmentStatement) stat;
                    for (VariableReference varRef : assignStat.getVariableReferences()) {
                        if (varRef.equals(variable.callerObject)) {
                            variableList.add(assignStat.getReturnValue());
                        }
                    }
                }
            }
        } else if (context.ownerReference != null) {
            for (int i = 0; i < test.size(); i++) {
                Statement stat = test.getStatement(i);
                if (stat instanceof MethodStatement) {
                    MethodStatement mStat = (MethodStatement) stat;
                    VariableReference ref = mStat.getCallee();
                    if (sameReference(test, ref, context.ownerReference)) {
                        Integer parameterPosition =
                                checkValidArrayElementSetter(
                                        mStat.getMethod().getMethod(), opcodeWrite, variable);
                        if (parameterPosition >= 0
                                && parameterPosition < mStat.getParameterReferences().size()) {
                            VariableReference param =
                                    mStat.getParameterReferences().get(parameterPosition);
                            variableList.add(param);
                        }
                    }
                }
            }
        }

        return variableList;
    }

    private VariableReference generateArrayElementSetter(
            TestCase test, VariableInTest variable, int opcodeWrite, ArrayAccessContext context)
            throws ConstructionFailedException {
        List<ElementSetter> setters = findSettersForArrayElement(opcodeWrite, variable, context);
        LinkedHashMap<Method, Integer> candidates = new LinkedHashMap<>();
        for (ElementSetter setter : setters) {
            candidates.put(setter.method, setter.paramPos);
        }
        ElementSetter selected = setters.isEmpty() ? null : setters.get(0);
        FieldInsnNode field = backingArrayField();

        if (selected == null) {
            return null;
        }

        Set<Statement> oldStatements =
                Collections.newSetFromMap(new IdentityHashMap<Statement, Boolean>());
        for (int i = 0; i < test.size(); i++) {
            oldStatements.add(test.getStatement(i));
        }
        GenericMethod method =
                new GenericMethod(selected.method, selected.method.getDeclaringClass());
        int ownerPosition =
                ConstructionReferenceUtil.definitionPosition(test, context.ownerReference);
        TestFactory.getInstance()
                .addMethodFor(test, context.ownerReference, method, ownerPosition + 1, false);

        MethodStatement generated = null;
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (!oldStatements.contains(statement) && statement instanceof MethodStatement) {
                MethodStatement candidate = (MethodStatement) statement;
                if (selected.method.equals(candidate.getMethod().getMethod())
                        && sameReference(test, candidate.getCallee(), context.ownerReference)) {
                    generated = candidate;
                    break;
                }
            }
        }
        if (generated == null
                || selected.paramPos < 0
                || selected.paramPos >= generated.getParameterReferences().size()) {

            return null;
        }

        VariableReference element = generated.getParameterReferences().get(selected.paramPos);
        int elementPosition = ConstructionReferenceUtil.definitionPosition(test, element);
        if (elementPosition >= 0
                && !(test.getStatement(elementPosition) instanceof ConstructorStatement)
                && context.arrayReference instanceof ArrayReference) {
            // The factory may reuse a prior getter result (which can be null) or a functional mock
            // (which is not a real SUT construction). Replace either with an actual construction
            // before the setter. Primitive arrays never enter this reference-construction helper.
            VariableReference concreteElement =
                    createReferenceElementVariable(
                            test,
                            (ArrayReference) context.arrayReference,
                            ConstructionReferenceUtil.definitionPosition(
                                            test, context.ownerReference)
                                    + 1);
            if (concreteElement != null) {
                generated.replace(element, concreteElement);
                element = concreteElement;
                elementPosition = ConstructionReferenceUtil.definitionPosition(test, element);
            }
        }
        if (elementPosition < 0 || test.getStatement(elementPosition) instanceof NullStatement) {

            return null;
        }
        return element;
    }

    private void rewireMatchingArrayGetterUses(
            TestCase test,
            ArrayAccessContext context,
            VariableReference element,
            Map<DepVariableWrapper, VarRelevance> map) {
        String owner = var.getInstruction().getClassName();
        String methodSignature = var.getInstruction().getMethodName();
        for (int position = 0; position < test.size(); position++) {
            Statement statement = test.getStatement(position);
            if (!(statement instanceof MethodStatement)) {
                continue;
            }
            MethodStatement getter = (MethodStatement) statement;
            Method method = getter.getMethod().getMethod();
            if (!owner.equals(method.getDeclaringClass().getName())
                    || !methodSignature.equals(method.getName() + MethodUtil.getSignature(method))
                    || !sameReference(test, getter.getCallee(), context.ownerReference)) {
                continue;
            }
            VariableReference oldResult = getter.getReturnValue();
            for (int usePosition = position + 1; usePosition < test.size(); usePosition++) {
                test.getStatement(usePosition).replace(oldResult, element);
            }
            VariableCodeGenerationUtil.replaceMapFromNode2Code(map, oldResult, element);
        }
    }

    private ArrayAccessContext resolveArrayAccessContext(
            TestCase test, VariableReference arrayReference) {
        VariableReference owner = arrayReference;
        int position = ConstructionReferenceUtil.definitionPosition(test, arrayReference);
        if (position >= 0) {
            Statement definition = test.getStatement(position);
            if (definition instanceof MethodStatement) {
                VariableReference callee = ((MethodStatement) definition).getCallee();
                if (callee != null) {
                    owner = callee;
                }
            } else if (definition instanceof FieldStatement) {
                VariableReference source = ((FieldStatement) definition).getSource();
                if (source != null) {
                    owner = source;
                }
            }
        }
        return new ArrayAccessContext(arrayReference, owner);
    }

    private static final class ArrayAccessContext {
        private final VariableReference arrayReference;
        private final VariableReference ownerReference;

        private ArrayAccessContext(
                VariableReference arrayReference, VariableReference ownerReference) {
            this.arrayReference = arrayReference;
            this.ownerReference = ownerReference;
        }

        private boolean hasDistinctOwner(TestCase test) {
            return ownerReference != null && !sameReference(test, arrayReference, ownerReference);
        }
    }

    private int getCorrespondingWriteOpcode(int opcodeRead) {
        switch (opcodeRead) {
            case Opcodes.AALOAD:
                return Opcodes.AASTORE;
            case Opcodes.BALOAD:
                return Opcodes.BASTORE;
            case Opcodes.CALOAD:
                return Opcodes.CASTORE;
            case Opcodes.DALOAD:
                return Opcodes.DASTORE;
            case Opcodes.FALOAD:
                return Opcodes.FASTORE;
            case Opcodes.IALOAD:
                return Opcodes.IASTORE;
            case Opcodes.LALOAD:
                return Opcodes.LASTORE;
            case Opcodes.SALOAD:
                return Opcodes.SASTORE;
            default:
                throw new IllegalArgumentException("Illegal Opcode");
        }
    }

    /**
     * check if current method is a valid setter for array element
     *
     * @param className
     * @param methodName
     * @param node
     * @param opcodeWrite
     * @return
     */
    private Integer checkValidArrayElementSetter(
            Method method, int opcodeWrite, VariableInTest variable) {

        String className = method.getDeclaringClass().getCanonicalName();
        String methodName = method.getName() + MethodUtil.getSignature(method);

        List<BytecodeInstruction> insList =
                BytecodeInstructionPool.getInstance(
                                TestGenerationContext.getInstance().getClassLoaderForSUT())
                        .getInstructionsIn(className, methodName);
        if (insList == null /* && RuntimeInstrumentation.checkIfCanInstrument(className) */) {
            GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .registerClass(className);
            insList =
                    BytecodeInstructionPool.getInstance(
                                    TestGenerationContext.getInstance().getClassLoaderForSUT())
                            .getInstructionsIn(className, methodName);
        }

        if (insList != null) {
            for (BytecodeInstruction ins : insList) {
                // *ASTORE
                if (ins.getASMNode().getOpcode() == opcodeWrite) {
                    BytecodeInstruction param = storedParameterForBackingArray(ins);
                    if (param != null) {
                        return param.getParameterPosition();
                    }
                }
            }
        }

        return -1;
    }

    /**
     * For every JVM array-store instruction the value is stack operand 0 and the array reference is
     * operand 2. A setter is usable only when the stored value comes directly from a method
     * parameter and the array operand comes from the exact field represented by this graph node.
     * This deliberately rejects methods such as {@code removePlayer(Player)}, whose parameter is
     * used for the index but whose stored value is {@code null}.
     */
    private BytecodeInstruction storedParameterForBackingArray(BytecodeInstruction store) {
        FieldInsnNode expectedField = backingArrayField();
        if (expectedField == null || store.getOperandNum() < 3) {
            return null;
        }
        BytecodeInstruction valueSource = store.getSourceOfStackInstruction(0);
        BytecodeInstruction arraySource = store.getSourceOfStackInstruction(2);
        if (valueSource == null
                || !valueSource.isParameter()
                || arraySource == null
                || !(arraySource.getASMNode() instanceof FieldInsnNode)) {
            return null;
        }
        FieldInsnNode actualField = (FieldInsnNode) arraySource.getASMNode();
        return sameField(expectedField, actualField) ? valueSource : null;
    }

    private FieldInsnNode backingArrayField() {
        for (DepVariableWrapper parent : parents) {
            if ((parent.var.getType() == DepVariable.INSTANCE_FIELD
                            || parent.var.getType() == DepVariable.STATIC_FIELD)
                    && parent.var.getInstruction() != null
                    && parent.var.getInstruction().getASMNode() instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) parent.var.getInstruction().getASMNode();
                if (field.desc != null && field.desc.startsWith("[")) {
                    return field;
                }
            }
        }
        return null;
    }

    private static boolean sameField(FieldInsnNode left, FieldInsnNode right) {
        return left.owner.equals(right.owner)
                && left.name.equals(right.name)
                && left.desc.equals(right.desc);
    }

    private static String fieldOwner(FieldInsnNode field) {
        return field == null ? null : field.owner.replace('/', '.');
    }

    private static String fieldName(FieldInsnNode field) {
        return field == null ? "[element]" : field.name;
    }

    private int nodeId() {
        return var.getInstruction() == null ? -1 : var.getInstruction().getInstructionId();
    }

    private static boolean sameReference(
            TestCase test, VariableReference left, VariableReference right) {
        if (left == right) {
            return left != null;
        }
        int leftPosition = ConstructionReferenceUtil.definitionPosition(test, left);
        int rightPosition = ConstructionReferenceUtil.definitionPosition(test, right);
        return leftPosition >= 0 && leftPosition == rightPosition;
    }

    /**
     * check if current method is a valid getter for array element
     *
     * @param className
     * @param methodName
     * @param node
     * @param opcodeRead
     * @return
     */
    private boolean checkValidArrayElementGetter(
            Method method, int opcodeRead, VariableInTest variable) {
        DepVariable parentVar = this.parents.get(0).var;
        if (parentVar.getType() != DepVariable.INSTANCE_FIELD
                && parentVar.getType() != DepVariable.STATIC_FIELD) {
            return false;
        }
        FieldInsnNode parentFNode = (FieldInsnNode) parentVar.getInstruction().getASMNode();
        if (!parentFNode.desc.contains("[")) {
            return false;
        }

        String parentDesc = parentFNode.desc;
        String formalDesc = parentDesc.substring(2, parentDesc.length() - 1).replace("/", ".");
        if (!method.getReturnType().getCanonicalName().equals(formalDesc)) {
            return false;
        }

        String className = method.getDeclaringClass().getCanonicalName();
        String methodName = method.getName() + MethodUtil.getSignature(method);
        ActualControlFlowGraph cfg =
                GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                        .getActualCFG(className, methodName);
        if (cfg == null) {
            return false;
        }
        for (BytecodeInstruction exit : cfg.getExitPoints()) {
            // TODO needs to check whether the instruction can correspond to the VariableInTest
            if (exit.isReturn()) {
                BytecodeInstruction returnInsn = exit.getSourceOfStackInstruction(0);
                if (returnInsn.getASMNode().getOpcode() == opcodeRead) {
                    for (int i = 0; i < returnInsn.getOperandNum(); i++) {
                        BytecodeInstruction defIns = returnInsn.getSourceOfStackInstruction(i);
                        if (defIns.isFieldUse()) {
                            FieldInsnNode fnode = ((FieldInsnNode) defIns.getASMNode());
                            if (fnode.name.equals(parentFNode.name)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /*
     * AALOAD, BALOAD, CALOAD, DALOAD, FALOAD, IALOAD, LALOAD, SALOAD
     */
    private List<VariableReference> searchArrayElementReadingReference(
            TestCase test, VariableInTest variable, int opcodeRead, ArrayAccessContext context) {
        List<VariableReference> varList = new ArrayList<VariableReference>();

        for (int i = 0; i < test.size(); i++) {
            Statement stat = test.getStatement(i);
            // check reading through method call
            if (stat instanceof MethodStatement) {
                MethodStatement mStat = (MethodStatement) stat;
                Method method = mStat.getMethod().getMethod();
                VariableReference ref = mStat.getCallee();
                if (sameReference(test, ref, context.ownerReference)) {
                    boolean isValid = checkValidArrayElementGetter(method, opcodeRead, variable);
                    if (isValid) {
                        VariableReference var = mStat.getReturnValue();
                        varList.add(var);
                    }
                }
            }
        }

        return varList;
    }

    private PrimitiveStatement generatePrimitiveStatementFor(
            TestCase testCase, ArrayReference arrayRef) {
        Class<?> arrayClass = arrayRef.getComponentClass();
        // TODO: For now, only support numeric/string primitives
        if (arrayClass.equals(boolean.class)) {
            return new BooleanPrimitiveStatement(testCase, false);
        } else if (arrayClass.equals(byte.class)) {
            return new BytePrimitiveStatement(testCase, (byte) 0);
        } else if (arrayClass.equals(double.class)) {
            return new CharPrimitiveStatement(testCase, (char) 0);
        } else if (arrayClass.equals(float.class)) {
            return new FloatPrimitiveStatement(testCase, 0.0f);
        } else if (arrayClass.equals(int.class)) {
            return new IntPrimitiveStatement(testCase, 0);
        } else if (arrayClass.equals(long.class)) {
            return new LongPrimitiveStatement(testCase, 0L);
        } else if (arrayClass.equals(short.class)) {
            return new ShortPrimitiveStatement(testCase, (short) 0);
        } else {
            return null;
        }
    }

    /**
     * Materialize a reference-array element independently of any getter already present in the
     * template. The ordinary argument generator is allowed to reuse a compatible method result; for
     * an array setter that is exactly the unsafe choice we are repairing, because the getter reads
     * the still-uninitialized array slot. Prefer the smallest public constructor so this repair is
     * deterministic and adds as little unrelated state as possible.
     */
    private VariableReference createReferenceElementVariable(
            TestCase test, ArrayReference arrayRef, int position)
            throws ConstructionFailedException {
        Class<?> componentClass = arrayRef.getComponentClass();
        if (componentClass == null
                || componentClass.isPrimitive()
                || componentClass.isInterface()
                || java.lang.reflect.Modifier.isAbstract(componentClass.getModifiers())) {
            return null;
        }
        List<Constructor<?>> constructors = new ArrayList<>();
        Collections.addAll(constructors, componentClass.getConstructors());
        constructors.sort(
                Comparator.comparingInt(
                                (Constructor<?> constructor) ->
                                        constructor.getParameterTypes().length)
                        .thenComparing(Constructor::toGenericString));
        if (constructors.isEmpty()) {
            return null;
        }

        Constructor<?> constructor = constructors.get(0);
        GenericConstructor genericConstructor =
                new GenericConstructor(constructor, constructor.getDeclaringClass());
        return TestFactory.getInstance()
                .addConstructor(test, genericConstructor, Math.max(0, position), 2);
    }

    private VariableReference createArrayElementVariable(TestCase test, ArrayReference arrayRef) {
        Class<?> clazz = arrayRef.getComponentClass();
        if (clazz.getConstructors().length < 1) {
            if (clazz.isPrimitive()) {
                try {
                    PrimitiveStatement primitiveStatement =
                            generatePrimitiveStatementFor(test, arrayRef);
                    VariableReference returnedVar =
                            TestFactory.getInstance()
                                    .addPrimitive(
                                            test, primitiveStatement, arrayRef.getStPosition() + 1);
                    return returnedVar;
                } catch (ConstructionFailedException e) {
                    e.printStackTrace();
                }
            } else {
                return null;
            }
        }

        Constructor<?> constructor = clazz.getConstructors()[0];
        GenericConstructor gConstructor =
                new GenericConstructor(constructor, constructor.getDeclaringClass());
        try {
            VariableReference returnedVar =
                    TestFactory.getInstance()
                            .addConstructor(test, gConstructor, arrayRef.getStPosition() + 1, 2);
            return returnedVar;

        } catch (ConstructionFailedException e) {
            e.printStackTrace();
        }

        return null;
    }

    //    @Override

    //    public List<VariableReference> findCorrespondingVariables(TestCase test, boolean isLeaf,
    // VariableReference callerObject, Map<DepVariableWrapper, List<VariableReference>> map) {
    //        Statement stat = test.getStatement(callerObject.getStPosition());
    //        if(stat instanceof NullStatement) {
    //            List<VariableReference> list = new ArrayList<>();
    //            list.add(stat.getReturnValue());
    //            return list;
    //        }
    //
    //        if(this.var.getType() == DepVariable.PARAMETER) {

    //            List<VariableReference> usedArrayElementList =
    // searchUsedArrayElementReference(test,
    // callerObject);
    //            if(!usedArrayElementList.isEmpty()) {
    //                return usedArrayElementList;
    //            }
    //        }
    //
    //        if (callerObject instanceof ArrayReference) {
    //            int opcodeRead = this.var.getInstruction().getASMNode().getOpcode();
    //            int opcodeWrite = getCorrespondingWriteOpcode(opcodeRead);
    //            VariableReference realParentRef = null;
    //            Statement statement = test.getStatement(callerObject.getStPosition());
    //
    //            if (statement instanceof MethodStatement) {
    //                MethodStatement mStatement = (MethodStatement) statement;
    //                realParentRef = mStatement.getCallee();
    //            }
    //

    //            List<VariableReference> usedArrayElementList =
    // searchUsedArrayElementReference(test,
    // callerObject);
    //            if(!usedArrayElementList.isEmpty()) {
    //                return usedArrayElementList;
    //            }
    //
    //            double prob = Randomness.nextDouble();
    //            if (realParentRef  != null && prob > 0.8) {
    //                /**
    //                 * check reused array element
    //                 */
    //                VariableReference usedArrayRef = isLeaf

    //                        ? searchArrayElementWritingReference(test, callerObject,
    // realParentRef,
    // opcodeWrite)
    //                        : searchArrayElementReadingReference(test, callerObject,
    // realParentRef,
    // opcodeRead);
    //                if (usedArrayRef != null) {
    //                    VariableReference generatedVariable = isLeaf ? null : usedArrayRef;
    //                    List<VariableReference> vars = new ArrayList<>();
    //                    if(generatedVariable != null) {
    //                        vars.add(generatedVariable);
    //                    }
    //                    return vars;
    //                }
    //
    //            }
    //        }
    //
    //        return new ArrayList<>();
    //    }

    @Override
    public VariableReference find(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map) {
        // TODO
        return null;
    }
}
