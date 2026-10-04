package org.evosuite.testcase.synthesizer.var;

import org.evosuite.TestGenerationContext;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.TestUsageChecker;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OtherVariableWrapper extends DepVariableWrapper {

    private static final Logger logger = LoggerFactory.getLogger(OtherVariableWrapper.class);
    private boolean terminalObservation;

    protected OtherVariableWrapper(DepVariable var) {
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
        List<VariableReference> list = new ArrayList<>();
        terminalObservation = false;
        VariableReference var =
                generateOrFind(test, isLeaf, variable.callerObject, map, b, allowNullValue);
        if (var != null) {
            list.add(var);
        }
        if (var == null && terminalObservation) {
            return null;
        }
        return new VarRelevance(list, list);
    }

    @Override
    public boolean traverseChildrenAfterFailedGeneration() {
        return !terminalObservation;
    }

    public VariableReference generateOrFind(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue) {
        int opcode = this.var.getInstruction().getASMNode().getOpcode();
        if (opcode == Opcodes.ALOAD || opcode == Opcodes.DUP || opcode == Opcodes.DUP2) {
            for (DepVariableWrapper parentNode : this.parents) {
                if (map.get(parentNode) != null) {
                    VariableReference generatedVariable = map.get(parentNode).matchedVars.get(0);
                    return generatedVariable;
                }
            }
        } else if (opcode == Opcodes.INVOKEVIRTUAL
                || opcode == Opcodes.INVOKESPECIAL
                || opcode == Opcodes.INVOKESTATIC
                || opcode == Opcodes.INVOKEDYNAMIC
                || opcode == Opcodes.INVOKEINTERFACE) {

            if (callerObject == null) {
                return null;
            }

            VariableReference generatedVariable =
                    generateMethodCallStatement(test, map, callerObject, allowNullValue);
            return generatedVariable;
        }

        return null;
    }

    private VariableReference generateMethodCallStatement(
            TestCase test,
            Map<DepVariableWrapper, VarRelevance> map,
            VariableReference callerObject,
            boolean allowNullValue) {
        int opcode = this.var.getInstruction().getASMNode().getOpcode();
        try {
            MethodInsnNode methodNode = ((MethodInsnNode) this.var.getInstruction().getASMNode());
            String owner = methodNode.owner;
            String fieldOwner = owner.replace("/", ".");
            String fullName = methodNode.name + methodNode.desc;
            Class<?> fieldDeclaringClass =
                    TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(fieldOwner);
            org.objectweb.asm.Type[] types =
                    org.objectweb.asm.Type.getArgumentTypes(
                            fullName.substring(fullName.indexOf("("), fullName.length()));
            Class<?>[] paramClasses = new Class<?>[types.length];
            int index = 0;
            for (org.objectweb.asm.Type type : types) {
                Class<?> paramClass = VariableCodeGenerationUtil.getClassForType(type);
                paramClasses[index++] = paramClass;
            }

            if (!fullName.contains("<init>")) {
                Method call = null;
                try {
                    call =
                            fieldDeclaringClass.getMethod(
                                    fullName.substring(0, fullName.indexOf("(")), paramClasses);
                } catch (Exception e) {
                    try {
                        Method declared =
                                fieldDeclaringClass.getDeclaredMethod(
                                        fullName.substring(0, fullName.indexOf("(")), paramClasses);
                        if (!Modifier.isPrivate(declared.getModifiers())) {
                            call = declared;
                        }
                    } catch (Exception ignored) {
                        // No source-level callable method represents this node.
                    }
                }

                if (call == null) {
                    /*
                     * A private accessor cannot be emitted as a source-level call, but its return
                     * value may already have been materialized from the interprocedural graph
                     * (notably an array element). Reuse that exact alias instead of abandoning the
                     * accessor's child fields.
                     */
                    return findMappedArrayElement(
                            fieldOwner, fullName, Type.getReturnType(methodNode.desc), map);
                }

                VariableReference calleeVarRef = null;
                Map<Integer, VariableReference> paramRefMap = new HashMap<>();
                List<VariableReference> receiverCandidates = new ArrayList<>();

                for (DepVariableWrapper par : this.parents) {
                    VarRelevance parRelevance = map.get(par);
                    if (parRelevance == null
                            || parRelevance.matchedVars == null
                            || parRelevance.matchedVars.isEmpty()) {
                        continue;
                    }

                    VariableReference parRef = parRelevance.matchedVars.get(0);
                    int position = par.findRelationPosition(this);
                    if (position > -1) {
                        paramRefMap.put(position, parRef);
                        if (position == 0 && !containsReference(receiverCandidates, parRef)) {
                            receiverCandidates.add(parRef);
                        }
                    }
                }

                if (opcode == Opcodes.INVOKESTATIC) {
                    calleeVarRef = null;
                    MethodStatement existingCall = findMethodCall(call, null, test);
                    if (existingCall != null) {
                        return existingCall.getReturnValue();
                    }
                    VariableReference mappedArrayElement = findMappedArrayElement(call, map);
                    if (mappedArrayElement != null) {
                        return mappedArrayElement;
                    }
                    GenericMethod genericMethod = new GenericMethod(call, call.getDeclaringClass());
                    int insertionPosition =
                            methodCallInsertionPosition(test, null, paramRefMap.values());
                    VariableReference varRef =
                            TestFactory.getInstance()
                                    .addMethod(
                                            test,
                                            genericMethod,
                                            insertionPosition,
                                            1,
                                            allowNullValue);
                    MethodStatement statement =
                            (MethodStatement) test.getStatement(varRef.getStPosition());
                    for (int i = 0; i < statement.getParameterReferences().size(); i++) {
                        VariableReference oldParam = statement.getParameterReferences().get(i);
                        VariableReference newParam = paramRefMap.get(i);
                        if (newParam != null) {
                            statement.replace(oldParam, newParam);
                            VariableCodeGenerationUtil.replaceMapFromNode2Code(
                                    map, oldParam, newParam);
                        }
                    }
                    return varRef;
                } else {
                    Statement stat = test.getStatement(callerObject.getStPosition());
                    if (stat instanceof NullStatement) {
                        return null;
                    }

                    calleeVarRef = paramRefMap.get(0);
                    if (calleeVarRef != null) {

                        Class<?> calleeType = calleeVarRef.getVariableClass();
                        Class<?> callObjectType = call.getDeclaringClass();

                        if (isCompatibleInstanceReceiver(callObjectType, calleeType)) {
                            VariableReference collectionElement =
                                    materializeIndexedListReads(
                                            test, call, receiverCandidates, allowNullValue);
                            if (collectionElement != null) {
                                return collectionElement;
                            }
                            VariableReference membershipElement =
                                    materializeMembershipObservation(
                                            test, call, calleeVarRef, paramRefMap);
                            if (membershipElement != null) {
                                return membershipElement;
                            }
                            VariableReference querySetup =
                                    materializeCollectionQueryObservation(test, call, calleeVarRef);
                            if (querySetup != null) {
                                return querySetup;
                            }
                            VariableReference builderSetup =
                                    materializeAddNewObservation(test, call, calleeVarRef);
                            if (builderSetup != null) {
                                return builderSetup;
                            }
                            if (isPureObservation(call)) {
                                terminalObservation = true;

                                return null;
                            }

                            MethodStatement existingCall = findMethodCall(call, calleeVarRef, test);
                            if (existingCall != null) {
                                return existingCall.getReturnValue();
                            }
                            VariableReference mappedArrayElement =
                                    findMappedArrayElement(call, map);
                            if (mappedArrayElement != null) {
                                return mappedArrayElement;
                            }

                            GenericMethod genericMethod =
                                    new GenericMethod(call, call.getDeclaringClass());
                            int insertionPosition =
                                    methodCallInsertionPosition(
                                            test, calleeVarRef, paramRefMap.values());
                            VariableReference varRef =
                                    TestFactory.getInstance()
                                            .addMethodFor(
                                                    test,
                                                    calleeVarRef,
                                                    genericMethod,
                                                    insertionPosition,
                                                    allowNullValue);
                            MethodStatement statement =
                                    (MethodStatement) test.getStatement(varRef.getStPosition());
                            for (int i = 0; i < statement.getParameterReferences().size(); i++) {
                                VariableReference oldParam =
                                        statement.getParameterReferences().get(i);
                                VariableReference newParam = paramRefMap.get(i + 1);
                                if (newParam != null) {
                                    statement.replace(oldParam, newParam);
                                    VariableCodeGenerationUtil.replaceMapFromNode2Code(
                                            map, oldParam, newParam);
                                }
                            }
                            return varRef;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Object-rule method-call synthesis failed: {}", e.toString());
        }

        return null;
    }

    static boolean isPureObservation(Method method) {
        if (method == null
                || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() == Void.TYPE) {
            return false;
        }
        String name = method.getName();
        return name.startsWith("get")
                || name.startsWith("is")
                || name.startsWith("has")
                || "LA".equals(name)
                || "LT".equals(name)
                || "size".equals(name)
                || "isEmpty".equals(name)
                || "iterator".equals(name)
                || "entrySet".equals(name)
                || "keySet".equals(name)
                || "values".equals(name)
                || "toClass".equals(name)
                || "equals".equals(name)
                || "isAssignableFrom".equals(name)
                || "compareTo".equals(name)
                || "toString".equals(name)
                || "hashCode".equals(name)
                || "schemaType".equals(name)
                || "selectPath".equals(name)
                || "execQuery".equals(name)
                || "newCursor".equals(name)
                || "xmlText".equals(name);
    }

    /**
     * Generated document/builders commonly pair {@code getX()} with {@code addNewX()}. A getter is
     * only evidence about state; invoking the paired builder is the pre-call action that makes that
     * state exist. XMLBeans document roots are the motivating case, but the convention is safe for
     * any public zero-argument pair with compatible return types.
     */
    static VariableReference materializeAddNewObservation(
            TestCase test, Method observation, VariableReference receiver) throws Exception {
        if (observation == null
                || receiver == null
                || observation.getParameterCount() != 0
                || !observation.getName().startsWith("get")
                || observation.getName().length() <= 3
                || observation.getReturnType() == Void.TYPE) {
            return null;
        }
        Method builder = findPairedAddNewMethod(observation, receiver.getVariableClass());
        if (builder == null) {
            return null;
        }
        /*
         * Canonical graph merging can still retain two bytecode occurrences of the same getX()
         * path. Replaying addNewX() for each occurrence creates two different children, so setup
         * performed through the second child is invisible when the target subsequently calls
         * getX() and receives the first. Reuse the already materialized builder result for the
         * same receiver and paired method.
         */
        MethodStatement existingBuilder = findMethodCall(builder, receiver, test);
        if (existingBuilder != null) {
            return existingBuilder.getReturnValue();
        }
        int insertionPosition =
                methodCallInsertionPosition(test, receiver, Collections.emptyList());
        VariableReference result =
                test.addStatement(
                        new MethodStatement(
                                test,
                                new GenericMethod(builder, builder.getDeclaringClass()),
                                receiver,
                                Collections.emptyList()),
                        insertionPosition);
        return result;
    }

    /** Returns the zero-argument addNewX method paired with a getX observation, if one exists. */
    public static Method findPairedAddNewMethod(Method observation, Class<?> receiverType) {
        if (observation == null
                || receiverType == null
                || observation.getParameterCount() != 0
                || !observation.getName().startsWith("get")
                || observation.getName().length() <= 3
                || observation.getReturnType() == Void.TYPE) {
            return null;
        }
        String builderName = "addNew" + observation.getName().substring(3);
        Method builder;
        try {
            builder = receiverType.getMethod(builderName);
        } catch (NoSuchMethodException missingOnConcreteType) {
            try {
                builder = observation.getDeclaringClass().getMethod(builderName);
            } catch (NoSuchMethodException missingOnInterface) {
                return null;
            }
        }
        if (Modifier.isStatic(builder.getModifiers())
                || builder.getParameterCount() != 0
                || !observation.getReturnType().isAssignableFrom(builder.getReturnType())) {
            return null;
        }
        return builder;
    }

    /**
     * Whether graph-to-code synthesis has a state-changing translation for an observation. Pure
     * queries without one are evidence about state, but cannot by themselves justify an EvoObj
     * attempt.
     */
    public static boolean canMaterializeObservation(Method observation, Class<?> receiverType) {
        if (observation == null || receiverType == null) {
            return false;
        }
        /*
         * A static call computes a value; replaying it cannot establish state on the entry
         * receiver or one of its arguments. Treating an unknown-purity static helper as a setup
         * action made graphs such as File.getName() -> FileNameMap.getContentTypeFor() eligible,
         * although synthesis correctly emitted no mutation.
         */
        if (Modifier.isStatic(observation.getModifiers())) {
            return false;
        }
        if (findPairedAddNewMethod(observation, receiverType) != null) {
            return true;
        }
        String name = observation.getName();
        if (isMembershipObservation(observation) && !String.class.isAssignableFrom(receiverType)) {
            return true;
        }
        if ((java.util.Collection.class.isAssignableFrom(receiverType)
                        || java.util.Map.class.isAssignableFrom(receiverType))
                && ("size".equals(name)
                        || "isEmpty".equals(name)
                        || "iterator".equals(name)
                        || "entrySet".equals(name)
                        || "keySet".equals(name)
                        || "values".equals(name))) {
            return true;
        }
        if (java.util.List.class.isAssignableFrom(receiverType)
                && "get".equals(name)
                && observation.getParameterCount() == 1) {
            return true;
        }
        if (isPureObservation(observation)) {
            return false;
        }
        try {
            return !CheapPurityAnalyzer.getInstance().isPure(observation);
        } catch (RuntimeException | LinkageError ignored) {
            // Unknown dependency purity is not enough to discard an otherwise callable method.
            return true;
        }
    }

    /**
     * Places a replayed observation after every graph value it consumes. Graph nodes are resolved
     * incrementally, so a field used as an argument may have been inserted after the receiver. The
     * old receiver-adjacent placement followed by parameter replacement created a forward reference
     * (for example {@code type.equals(UNKNOW)} before the {@code UNKNOW} field read). A crossover
     * cut between those statements then left the method callee or argument permanently orphaned.
     * Prefer the position immediately before the target call, while also handling callers without a
     * marked target and any unusual dependency that is already later in the test.
     */
    static int methodCallInsertionPosition(
            TestCase test, VariableReference receiver, Iterable<VariableReference> parameters) {
        int afterDependencies = 0;
        if (receiver != null) {
            afterDependencies = receiver.getStPosition() + 1;
        }
        if (parameters != null) {
            for (VariableReference parameter : parameters) {
                if (parameter != null) {
                    afterDependencies = Math.max(afterDependencies, parameter.getStPosition() + 1);
                }
            }
        }
        EntityWithParametersStatement targetCall = test.findTargetExecutableStatement();
        int beforeTarget = targetCall == null ? test.size() : targetCall.getPosition();
        return Math.min(test.size(), Math.max(afterDependencies, beforeTarget));
    }

    /**
     * A boolean membership call is an observation, not setup. Replaying {@code contains(key)}
     * before the target leaves the receiver unchanged and therefore cannot help cover the positive
     * direction. Translate it to the receiver's public mutator instead: collections use {@code
     * add}, maps use {@code put}, and header-like APIs use their {@code add}/{@code set} method.
     * The exact observed key/value is retained.
     */
    private VariableReference materializeMembershipObservation(
            TestCase test,
            Method observation,
            VariableReference receiver,
            Map<Integer, VariableReference> parameterReferences)
            throws Exception {
        if (!isMembershipObservation(observation) || receiver == null) {
            return null;
        }
        VariableReference observed = parameterReferences.get(1);
        if (observed == null) {
            observed = materializeLiteralArgument(test, 0);
        }
        if (observed == null) {

            return null;
        }
        Method mutation =
                findMembershipMutation(
                        observation, receiver.getVariableClass(), observed.getVariableClass());
        if (mutation == null) {
            return null;
        }

        EntityWithParametersStatement targetCall = test.findTargetExecutableStatement();
        int insertionPosition = targetCall == null ? test.size() : targetCall.getPosition();
        insertionPosition =
                Math.max(
                        insertionPosition,
                        Math.max(receiver.getStPosition(), observed.getStPosition()) + 1);
        GenericMethod genericMutation = new GenericMethod(mutation, mutation.getDeclaringClass());
        if ("containsKey".equals(observation.getName())
                && "put".equals(mutation.getName())
                && mutation.getParameterCount() == 2) {
            VariableReference value =
                    materializeMapValueForObservedKey(test, receiver, observed, insertionPosition);
            test.addStatement(
                    new MethodStatement(
                            test,
                            genericMutation,
                            receiver,
                            java.util.Arrays.asList(observed, value)),
                    Math.max(
                            insertionPosition,
                            Math.max(observed.getStPosition(), value.getStPosition()) + 1));
            return observed;
        }
        if (mutation.getParameterCount() == 1
                || canReuseObservationForEveryMutationArgument(
                        observation, mutation, observed.getVariableClass())) {
            List<VariableReference> arguments =
                    mutation.getParameterCount() == 1
                            ? Collections.singletonList(observed)
                            : java.util.Arrays.asList(observed, observed);
            test.addStatement(
                    new MethodStatement(test, genericMutation, receiver, arguments),
                    insertionPosition);
            return observed;
        }
        VariableReference result =
                TestFactory.getInstance()
                        .addMethodFor(test, receiver, genericMutation, insertionPosition, false);
        MethodStatement mutationStatement =
                (MethodStatement) test.getStatement(result.getStPosition());
        int observedParameter =
                "containsValue".equals(observation.getName())
                                && mutationStatement.getParameterReferences().size() > 1
                        ? 1
                        : 0;
        VariableReference generated =
                mutationStatement.getParameterReferences().get(observedParameter);
        mutationStatement.replace(generated, observed);
        return observed;
    }

    /**
     * A zero-argument query returning a collection often represents state that must first be
     * populated through the receiver's mutator. This covers state-machine APIs such as {@code
     * ByteState.getCeilings()}, where replaying the getter cannot create a ceiling but {@code
     * putTransition(byte, transition)} can.
     */
    private VariableReference materializeCollectionQueryObservation(
            TestCase test, Method observation, VariableReference receiver) throws Exception {
        if (observation == null
                || receiver == null
                || observation.getParameterCount() != 0
                || !(java.util.Collection.class.isAssignableFrom(observation.getReturnType())
                        || Iterable.class.isAssignableFrom(observation.getReturnType()))) {
            return null;
        }
        List<Method> mutations = collectionMutationCandidates(receiver.getVariableClass());
        for (Method mutation : mutations) {
            EntityWithParametersStatement target = test.findTargetExecutableStatement();
            int position = target == null ? test.size() : target.getPosition();
            List<VariableReference> arguments = new ArrayList<>();
            boolean supported = true;
            for (Class<?> parameter : mutation.getParameterTypes()) {
                VariableReference argument =
                        deterministicMutationArgument(
                                test, receiver, parameter, position + arguments.size());
                if (argument == null) {
                    supported = false;
                    break;
                }
                arguments.add(argument);
            }
            if (!supported) {
                continue;
            }
            int afterArguments = position;
            for (VariableReference argument : arguments) {
                afterArguments = Math.max(afterArguments, argument.getStPosition() + 1);
            }
            VariableReference result =
                    test.addStatement(
                            new MethodStatement(
                                    test,
                                    new GenericMethod(mutation, receiver.getGenericClass()),
                                    receiver,
                                    arguments),
                            afterArguments);
            VariableReference observed =
                    test.addStatement(
                            new MethodStatement(
                                    test,
                                    new GenericMethod(observation, receiver.getGenericClass()),
                                    receiver,
                                    Collections.emptyList()),
                            result.getStPosition() + 1);

            return observed;
        }
        return null;
    }

    /**
     * The receiver's own {@code add*}/{@code put*} methods that the generated test can call,
     * fewest parameters first. A non-public method qualifies only when its class is in the
     * package the tests are generated into.
     */
    static List<Method> collectionMutationCandidates(Class<?> receiverClass) {
        List<Method> mutations = new ArrayList<>();
        for (Method candidate : receiverClass.getDeclaredMethods()) {
            String name = candidate.getName();
            if (!TestUsageChecker.isCallableFromTestPackage(candidate)
                    || Modifier.isStatic(candidate.getModifiers())
                    || candidate.getParameterCount() < 1
                    || candidate.getParameterCount() > 2
                    || !(name.startsWith("add") || name.startsWith("put"))) {
                continue;
            }
            mutations.add(candidate);
        }
        mutations.sort(
                (left, right) -> {
                    int count =
                            Integer.compare(left.getParameterCount(), right.getParameterCount());
                    return count != 0
                            ? count
                            : left.toGenericString().compareTo(right.toGenericString());
                });
        return mutations;
    }

    private static VariableReference deterministicMutationArgument(
            TestCase test, VariableReference receiver, Class<?> parameter, int position)
            throws Exception {
        if (parameter.isPrimitive() || parameter == String.class || parameter.isEnum()) {
            PrimitiveStatement<?> primitive =
                    PrimitiveStatement.getPrimitiveStatement(
                            test, GenericClassFactory.get(parameter));
            if (parameter == byte.class) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                PrimitiveStatement raw = primitive;
                raw.setValue((byte) 1);
            }
            return test.addStatement(primitive, position);
        }
        Class<?> concrete =
                parameter.isAssignableFrom(receiver.getVariableClass())
                        ? receiver.getVariableClass()
                        : parameter;
        if (concrete.isInterface() || Modifier.isAbstract(concrete.getModifiers())) {
            return null;
        }
        try {
            Constructor<?> constructor = concrete.getDeclaredConstructor();
            if (Modifier.isPrivate(constructor.getModifiers())) {
                return null;
            }
            return test.addStatement(
                    new ConstructorStatement(
                            test,
                            new GenericConstructor(constructor, concrete),
                            Collections.emptyList()),
                    position);
        } catch (NoSuchMethodException failure) {
            return null;
        }
    }

    private VariableReference materializeMapValueForObservedKey(
            TestCase test, VariableReference receiver, VariableReference key, int position)
            throws Exception {
        Class<?> inferred = inferMapValueCast(stringValue(test, key));
        if (inferred != null
                && !inferred.isInterface()
                && !Modifier.isAbstract(inferred.getModifiers())) {
            Constructor<?> constructor = null;
            try {
                constructor = inferred.getConstructor();
            } catch (NoSuchMethodException ignored) {
                // Fall through to a typed null; it preserves the key branch without a random tree.
            }
            if (constructor != null) {
                return test.addStatement(
                        new ConstructorStatement(
                                test,
                                new GenericConstructor(constructor, inferred),
                                Collections.emptyList()),
                        position);
            }
        }
        if (inferred == null || inferred == Object.class || inferred == String.class) {
            return test.addStatement(new StringPrimitiveStatement(test, "value"), position);
        }
        return test.addStatement(new NullStatement(test, inferred), position);
    }

    private static String stringValue(TestCase test, VariableReference reference) {
        if (test == null
                || reference == null
                || reference.getStPosition() < 0
                || reference.getStPosition() >= test.size()
                || !(test.getStatement(reference.getStPosition())
                        instanceof StringPrimitiveStatement)) {
            return null;
        }
        return ((StringPrimitiveStatement) test.getStatement(reference.getStPosition())).getValue();
    }

    /**
     * Find a later {@code map.get(theSameLiteral)} whose result is cast by the target. This is the
     * value contract implied by the branch and lets containsKey setup use (for example) an
     * ArrayList for "symbols" instead of the semantically invalid String key as its value.
     */
    private Class<?> inferMapValueCast(String key) {
        if (key == null || var.getInstruction() == null) {
            return null;
        }
        BytecodeInstruction origin = var.getInstruction();
        List<BytecodeInstruction> instructions =
                BytecodeInstructionPool.getInstance(
                                TestGenerationContext.getInstance().getClassLoaderForSUT())
                        .getInstructionsIn(origin.getClassName(), origin.getMethodName());
        if (instructions == null) {
            return null;
        }
        for (int i = 0; i < instructions.size(); i++) {
            BytecodeInstruction candidate = instructions.get(i);
            if (!(candidate.getASMNode() instanceof MethodInsnNode)
                    || !"get".equals(((MethodInsnNode) candidate.getASMNode()).name)
                    || !key.equals(literalArgument(candidate, 0))) {
                continue;
            }
            for (int next = i + 1; next < Math.min(instructions.size(), i + 5); next++) {
                if (instructions.get(next).getASMNode() instanceof TypeInsnNode
                        && instructions.get(next).getASMNode().getOpcode() == Opcodes.CHECKCAST) {
                    String className =
                            ((TypeInsnNode) instructions.get(next).getASMNode())
                                    .desc.replace('/', '.');
                    try {
                        return TestGenerationContext.getInstance()
                                .getClassLoaderForSUT()
                                .loadClass(className);
                    } catch (ClassNotFoundException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static Object literalArgument(BytecodeInstruction invocation, int argumentIndex) {
        try {
            int count = invocation.getCalledMethodsArgumentCount();
            BytecodeInstruction source =
                    invocation.getSourceOfStackInstruction(count - argumentIndex - 1);
            return source != null && source.getASMNode() instanceof LdcInsnNode
                    ? ((LdcInsnNode) source.getASMNode()).cst
                    : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Constants are deliberately not graph roots, but they are often the most important part of a
     * collection observation (for example {@code parameters.containsKey("symbols")}). Recover a
     * literal directly from the invocation stack and make it an ordinary, mutable EvoSuite
     * primitive. This retains the semantic operand instead of asking TestFactory for an unrelated
     * value.
     */
    private VariableReference materializeLiteralArgument(TestCase test, int argumentIndex) {
        try {
            BytecodeInstruction invocation = var.getInstruction();
            int argumentCount = invocation.getCalledMethodsArgumentCount();
            if (argumentIndex < 0 || argumentIndex >= argumentCount) {
                return null;
            }
            Object constant = literalArgument(invocation, argumentIndex);
            if (!(constant instanceof String)) {
                return null;
            }
            EntityWithParametersStatement target = test.findTargetExecutableStatement();
            int position = target == null ? test.size() : target.getPosition();
            VariableReference literal =
                    test.addStatement(
                            new StringPrimitiveStatement(test, (String) constant), position);
            return literal;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static boolean isMembershipObservation(Method method) {
        if (method == null
                || method.getReturnType() != boolean.class
                || method.getParameterCount() != 1) {
            return false;
        }
        String name = method.getName();
        return "contains".equals(name)
                || "containsKey".equals(name)
                || "containsValue".equals(name);
    }

    static Method findMembershipMutation(
            Method observation, Class<?> receiverType, Class<?> observedType) {
        if (!isMembershipObservation(observation) || receiverType == null || observedType == null) {
            return null;
        }
        boolean valueObservation = "containsValue".equals(observation.getName());
        Method best = null;
        for (Method candidate : receiverType.getMethods()) {
            String name = candidate.getName();
            if (!("add".equals(name) || "put".equals(name) || "set".equals(name))
                    || Modifier.isStatic(candidate.getModifiers())
                    || candidate.isBridge()
                    || candidate.isSynthetic()
                    || candidate.getParameterCount() < 1
                    || candidate.getParameterCount() > 2) {
                continue;
            }
            int observedParameter = valueObservation && candidate.getParameterCount() > 1 ? 1 : 0;
            if (!candidate.getParameterTypes()[observedParameter].isAssignableFrom(observedType)) {
                continue;
            }
            if (best == null
                    || membershipMutationPriority(observation, candidate, observedType)
                            < membershipMutationPriority(observation, best, observedType)
                    || (membershipMutationPriority(observation, candidate, observedType)
                                    == membershipMutationPriority(observation, best, observedType)
                            && candidate.toGenericString().compareTo(best.toGenericString()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private static int membershipMutationPriority(
            Method observation, Method mutation, Class<?> observedType) {
        if (mutation.getParameterCount() == 1) {
            return 0;
        }
        return canReuseObservationForEveryMutationArgument(observation, mutation, observedType)
                ? 1
                : 2;
    }

    private static boolean canReuseObservationForEveryMutationArgument(
            Method observation, Method mutation, Class<?> observedType) {
        if (observation == null
                || mutation == null
                || observedType == null
                || mutation.getParameterCount() != 2
                || "containsValue".equals(observation.getName())) {
            return false;
        }
        return mutation.getParameterTypes()[0].isAssignableFrom(observedType)
                && mutation.getParameterTypes()[1].isAssignableFrom(observedType);
    }

    /** The object expression may be any concrete subtype of the method's declaring type. */
    static boolean isCompatibleInstanceReceiver(Class<?> declaringType, Class<?> receiverType) {
        return declaringType != null
                && receiverType != null
                && declaringType.isAssignableFrom(receiverType);
    }

    /**
     * A {@link List#get(int)} node describes a value observed by the target method; replaying the
     * read as setup is both redundant and unsafe when the freshly constructed receiver is empty.
     * Materialize the observation by adding a compatible element and map the graph node directly to
     * that element. The target invocation will then perform its own read on a non-empty list.
     */
    VariableReference materializeIndexedListRead(
            TestCase test, Method call, VariableReference receiver, boolean allowNullValue)
            throws Exception {
        if (!isIndexedListRead(call, receiver.getVariableClass())) {
            return null;
        }

        Class<?> elementType = inferIndexedReadElementType();
        Method insertion =
                findCompatibleListInsertionMethod(receiver.getVariableClass(), elementType);
        if (insertion == null) {
            return null;
        }

        VariableReference existingElement =
                findInsertedElement(insertion, receiver, test, elementType);
        if (existingElement != null) {
            return existingElement;
        }

        GenericMethod genericInsertion =
                new GenericMethod(insertion, insertion.getDeclaringClass());
        VariableReference insertionResult =
                TestFactory.getInstance()
                        .addMethodFor(
                                test,
                                receiver,
                                genericInsertion,
                                receiver.getStPosition() + 1,
                                allowNullValue && elementType == null);
        MethodStatement insertionStatement =
                (MethodStatement) test.getStatement(insertionResult.getStPosition());
        if (insertionStatement.getParameterReferences().isEmpty()) {
            return null;
        }
        return insertionStatement.getParameterReferences().get(0);
    }

    private VariableReference materializeIndexedListReads(
            TestCase test, Method call, List<VariableReference> receivers, boolean allowNullValue)
            throws Exception {
        VariableReference observedElement = null;
        for (VariableReference receiver : receivers) {
            if (!isCompatibleInstanceReceiver(call.getDeclaringClass(), receiver.getVariableClass())
                    || !isIndexedListRead(call, receiver.getVariableClass())) {
                continue;
            }
            VariableReference element =
                    materializeIndexedListRead(test, call, receiver, allowNullValue);
            if (observedElement == null && element != null) {
                observedElement = element;
            }
        }
        return observedElement;
    }

    static boolean isIndexedListRead(Method call, Class<?> receiverType) {
        return call != null
                && receiverType != null
                && "get".equals(call.getName())
                && call.getParameterCount() == 1
                && call.getParameterTypes()[0] == int.class
                && java.util.List.class.isAssignableFrom(receiverType)
                && java.util.List.class.isAssignableFrom(call.getDeclaringClass());
    }

    static Method findCompatibleListInsertionMethod(Class<?> receiverType, Class<?> elementType) {
        Method best = null;
        for (Method candidate : receiverType.getMethods()) {
            if (!"add".equals(candidate.getName())
                    || candidate.getParameterCount() != 1
                    || Modifier.isStatic(candidate.getModifiers())
                    || candidate.isBridge()
                    || candidate.isSynthetic()) {
                continue;
            }
            Class<?> parameterType = candidate.getParameterTypes()[0];
            if (elementType != null && !parameterType.isAssignableFrom(elementType)) {
                continue;
            }
            if (best == null || best.getParameterTypes()[0].isAssignableFrom(parameterType)) {
                best = candidate;
            }
        }
        return best;
    }

    private VariableReference findInsertedElement(
            Method insertion, VariableReference receiver, TestCase test, Class<?> elementType) {
        for (int i = Math.max(0, receiver.getStPosition()); i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (!(statement instanceof MethodStatement)) {
                continue;
            }
            MethodStatement methodStatement = (MethodStatement) statement;
            if (!insertion.equals(methodStatement.getMethod().getMethod())
                    || !sameReference(receiver, methodStatement.getCallee())
                    || methodStatement.getParameterReferences().isEmpty()) {
                continue;
            }
            VariableReference element = methodStatement.getParameterReferences().get(0);
            if (elementType == null || elementType.isAssignableFrom(element.getVariableClass())) {
                return element;
            }
        }
        return null;
    }

    private Class<?> inferIndexedReadElementType() {
        Deque<DepVariableWrapper> pending = new ArrayDeque<>(children);
        Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = 64;
        while (!pending.isEmpty() && remaining-- > 0) {
            DepVariableWrapper node = pending.removeFirst();
            if (node == null || !visited.add(node) || node.var.getInstruction() == null) {
                continue;
            }
            if (node.var.getInstruction().getASMNode() instanceof TypeInsnNode
                    && node.var.getInstruction().getASMNode().getOpcode() == Opcodes.CHECKCAST) {
                String className =
                        ((TypeInsnNode) node.var.getInstruction().getASMNode())
                                .desc.replace('/', '.');
                try {
                    return TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(className);
                } catch (ClassNotFoundException ignored) {
                    // Continue: a later cast may still provide a usable element type.
                }
            }
            pending.addAll(node.children);
        }
        return null;
    }

    private static boolean sameReference(VariableReference left, VariableReference right) {
        return left == right || (left != null && right != null && left.equals(right));
    }

    private static boolean containsReference(
            List<VariableReference> references, VariableReference candidate) {
        for (VariableReference reference : references) {
            if (sameReference(reference, candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reuses an element already inserted for the exact array-reading method represented by this
     * call. Interprocedural graph construction can expose the AALOAD and the caller-side local as
     * separate paths; without this alias, synthesis inserts an element and then immediately reads a
     * different (usually null) element through the same getter.
     */
    private VariableReference findMappedArrayElement(
            Method call, Map<DepVariableWrapper, VarRelevance> map) {
        if (call == null || call.getReturnType() == void.class || map == null) {
            return null;
        }
        String methodSignature = call.getName() + org.evosuite.utils.MethodUtil.getSignature(call);
        return findMappedArrayElement(
                call.getDeclaringClass().getName(),
                methodSignature,
                Type.getType(call.getReturnType()),
                map);
    }

    private VariableReference findMappedArrayElement(
            String declaringClass,
            String methodSignature,
            Type returnType,
            Map<DepVariableWrapper, VarRelevance> map) {
        if (declaringClass == null
                || methodSignature == null
                || returnType == null
                || returnType.getSort() == Type.VOID
                || map == null) {
            return null;
        }
        for (Map.Entry<DepVariableWrapper, VarRelevance> entry : map.entrySet()) {
            DepVariableWrapper node = entry.getKey();
            if (node == null
                    || node.var.getType() != DepVariable.ARRAY_ELEMENT
                    || node.var.getInstruction() == null
                    || !declaringClass.equals(node.var.getInstruction().getClassName())
                    || !methodSignature.equals(node.var.getInstruction().getMethodName())) {
                continue;
            }
            VarRelevance relevance = entry.getValue();
            if (relevance == null
                    || relevance.matchedVars == null
                    || relevance.matchedVars.isEmpty()) {
                continue;
            }
            VariableReference element = relevance.matchedVars.get(0);
            if (returnType.equals(element.getType())
                    || returnType.getClassName().equals(element.getClassName())) {
                return element;
            }
        }
        return null;
    }

    private static MethodStatement findMethodCall(
            Method call, VariableReference callerObject, TestCase test) {
        if (call == null || test == null) {
            return null;
        }
        int start = callerObject == null ? 0 : Math.max(0, callerObject.getStPosition());
        for (int i = start; i < test.size(); i++) {
            Statement s = test.getStatement(i);
            if (s instanceof MethodStatement) {
                MethodStatement mStatement = (MethodStatement) s;
                Method invocation = mStatement.getMethod().getMethod();
                VariableReference calleeObject = mStatement.getCallee();
                if (calleeObject != null && callerObject != null) {
                    if (sameReference(calleeObject, callerObject) && invocation.equals(call)) {
                        return mStatement;
                    }
                } else if (calleeObject == null && callerObject == null) {
                    if (invocation.equals(call)) {
                        return mStatement;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public VariableReference find(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map) {
        int opcode = this.var.getInstruction().getASMNode().getOpcode();
        if (opcode == Opcodes.ALOAD || opcode == Opcodes.DUP || opcode == Opcodes.DUP2) {
            for (DepVariableWrapper parentNode : this.parents) {
                if (map.get(parentNode) != null) {
                    VariableReference generatedVariable = map.get(parentNode).matchedVars.get(0);
                    return generatedVariable;
                }
            }
        } else if (opcode == Opcodes.INVOKEVIRTUAL
                || opcode == Opcodes.INVOKESPECIAL
                || opcode == Opcodes.INVOKESTATIC
                || opcode == Opcodes.INVOKEDYNAMIC
                || opcode == Opcodes.INVOKEINTERFACE) {
            VariableReference generatedVariable = findMethodCallStatement(test, map, callerObject);
            return generatedVariable;
        }

        return null;
    }

    private VariableReference findMethodCallStatement(
            TestCase test,
            Map<DepVariableWrapper, VarRelevance> map,
            VariableReference callerObject) {
        try {
            MethodInsnNode methodNode = ((MethodInsnNode) this.var.getInstruction().getASMNode());
            String owner = methodNode.owner;
            String fieldOwner = owner.replace("/", ".");
            String fullName = methodNode.name + methodNode.desc;
            Class<?> fieldDeclaringClass =
                    TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(fieldOwner);
            org.objectweb.asm.Type[] types =
                    org.objectweb.asm.Type.getArgumentTypes(
                            fullName.substring(fullName.indexOf("("), fullName.length()));
            Class<?>[] paramClasses = new Class<?>[types.length];
            int index = 0;
            for (org.objectweb.asm.Type type : types) {
                Class<?> paramClass = VariableCodeGenerationUtil.getClassForType(type);
                paramClasses[index++] = paramClass;
            }

            if (!fullName.contains("<init>")) {
                Method call = null;
                try {
                    call =
                            fieldDeclaringClass.getMethod(
                                    fullName.substring(0, fullName.indexOf("(")), paramClasses);
                } catch (Exception e) {
                }

                if (call == null) {
                    return null;
                }

                MethodStatement mStatement = findMethodCall(call, callerObject, test);
                if (mStatement != null) {
                    return mStatement.getReturnValue();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }
}
