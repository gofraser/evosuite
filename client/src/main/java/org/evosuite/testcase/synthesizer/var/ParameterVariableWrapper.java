package org.evosuite.testcase.synthesizer.var;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.ConstructionReferenceUtil;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParameterVariableWrapper extends DepVariableWrapper {

    private static final Logger logger = LoggerFactory.getLogger(ParameterVariableWrapper.class);

    protected ParameterVariableWrapper(DepVariable var) {
        super(var);
    }

    @Override
    public VarRelevance generateOrFindStatement(
            TestCase test,
            boolean isLeaf,
            VariableInTest variable,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue) {
        List<VariableReference> list = new ArrayList<>();
        VariableReference var =
                generateOrFind(test, isLeaf, variable.callerObject, map, b, allowNullValue);
        if (var != null) {
            list.add(var);
        }

        return new VarRelevance(list, list);
    }

    /**
     * Parameter statements are used only in the target method invocation.
     *
     * @param test the test case being extended
     * @param isLeaf whether the dependency is a leaf
     * @param callerObject the caller object, if any
     * @param map already generated variables by dependency wrapper
     * @param b the branch being synthesized
     * @param allowNullValue whether null is an allowed result
     * @return the generated or reused variable reference
     * @throws ConstructionFailedException if statement construction fails
     * @throws ClassNotFoundException if a required class cannot be loaded
     */
    public VariableReference generateOrFind(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map,
            Branch b,
            boolean allowNullValue) {

        String castSubClass = checkCastClassForParameter();
        if (castSubClass == null) {
            int paramPosition = this.var.getParamOrder() - 1;
            List<String> recommendations =
                    org.evosuite.TestGenerationContext.getInstance()
                            .getObjectRuleRuntime()
                            .getRecommendedClasses()
                            .get(paramPosition);
            if (recommendations != null && !recommendations.isEmpty()) {
                castSubClass = Randomness.choice(recommendations);
            }
        }

        VariableReference parameter = find(test, allowNullValue, callerObject, map);
        if (parameter != null) {
            Statement s = test.getStatement(parameter.getStPosition());
            if (!(s instanceof NullStatement)
                    && (castSubClass == null || isCompatibleWithCast(parameter, castSubClass))) {
                materializeContainer(test, parameter, allowNullValue);
                return parameter;
            }
        }

        if (castSubClass != null) {
            VariableReference newParameter = null;
            try {
                newParameter = generateParameter(test, castSubClass, allowNullValue && isLeaf);
            } catch (ConstructionFailedException e) {
                logger.debug("Ignored failure during object-rule synthesis", e);
            }

            if (newParameter == null) {
                return callerObject;
            }

            EntityWithParametersStatement targetStatement = test.findTargetExecutableStatement();
            if (targetStatement != null) {
                VariableReference oldParamRef =
                        targetStatement.getParameterReferences().get(this.var.getParamOrder() - 1);
                targetStatement.replace(oldParamRef, newParameter);
            }
            materializeDependentIndexedRead(test, newParameter, allowNullValue);
            materializeContainer(test, newParameter, allowNullValue);

            return newParameter;
        } else {

            // find the existing parameters
            EntityWithParametersStatement methodStat = test.findTargetExecutableStatement();
            if (methodStat == null) {
                return null;
            }
            int paramPosition = this.var.getParamOrder();
            VariableReference paramRef = methodStat.getParameterReferences().get(paramPosition - 1);

            // make sure the parameter obj is not null
            int paramPosInTest = ConstructionReferenceUtil.definitionPosition(test, paramRef);
            if (paramPosInTest < 0) {
                return null;
            }
            Statement paramDef = test.getStatement(paramPosInTest);
            // A parameter with dependent state cannot remain null even when ordinary random
            // generation allows nulls: none of its child fields can be realized through it.
            if ((!allowNullValue || !isLeaf) && paramDef instanceof NullStatement) {
                TestFactory testFactory = TestFactory.getInstance();
                boolean isSuccess = testFactory.changeNullStatement(test, paramDef);
                if (isSuccess) {
                    paramRef = methodStat.getParameterReferences().get(paramPosition - 1);
                } else {

                    return null;
                }
            }

            materializeContainer(test, paramRef, allowNullValue);
            return paramRef;
        }
    }

    String checkCastClassForParameter() {
        DepVariable var = this.var;

        String potentialCastType = null;
        Deque<DepVariableWrapper> pending = new ArrayDeque<>();
        pending.addAll(this.children);
        Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = 96;
        while (!pending.isEmpty() && remaining-- > 0) {
            DepVariableWrapper current = pending.removeFirst();
            if (current == null || !visited.add(current) || current.var.getInstruction() == null) {
                continue;
            }
            DepVariable v = current.var;
            if (v.getInstruction().toString().contains("CHECKCAST")) {
                BytecodeInstruction ins = v.getInstruction();
                AbstractInsnNode insNode = ins.getASMNode();
                if (insNode instanceof TypeInsnNode) {
                    TypeInsnNode targetNode = (TypeInsnNode) insNode;
                    String classType = targetNode.desc;
                    potentialCastType =
                            org.objectweb.asm.Type.getObjectType(classType).getClassName();

                    ActualControlFlowGraph actualControlFlowGraph =
                            var.getInstruction().getActualCFG();
                    int paramOrder = var.getParamOrder();

                    String methodSig = actualControlFlowGraph.getMethodName();
                    String[] parameters = extractParameter(methodSig);
                    String paramType = parameters[paramOrder - 1];

                    if (VariableCodeGenerationUtil.isCompatible(paramType, potentialCastType)) {
                        return potentialCastType;
                    }
                }
            }
            pending.addAll(current.children);
        }

        return null;
    }

    private VariableReference generateParameter(
            TestCase test, String castSubClass, boolean allowNullValue)
            throws ConstructionFailedException {

        String paramType = castSubClass;
        if (paramType == null) {
            ActualControlFlowGraph actualControlFlowGraph = var.getInstruction().getActualCFG();
            int paramOrder = var.getParamOrder();

            String methodSig = actualControlFlowGraph.getMethodName();
            String[] parameters = extractParameter(methodSig);
            paramType = parameters[paramOrder - 1];
        }

        Class<?> paramClass;
        try {
            paramClass =
                    TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(paramType);
        } catch (ClassNotFoundException e) {
            return null;
        }
        paramClass = ContainerSetup.concreteContainerClass(paramClass);

        GenericClass<?> paramDeclaringClazz = GenericClassFactory.get(paramClass);
        VariableReference paramRef = null;
        Constructor<?> constructor =
                selectConstructor(paramDeclaringClazz.getRawClass().getConstructors());
        if (constructor != null) {
            GenericConstructor gc = new GenericConstructor(constructor, paramDeclaringClazz);
            EntityWithParametersStatement methodStat = test.findTargetExecutableStatement();
            if (methodStat == null) {
                return null;
            }

            if (allowNullValue) {
                if (Randomness.nextDouble() < Properties.NULL_PROBABILITY) {
                    paramRef =
                            TestFactory.getInstance()
                                    .createNull(test, paramClass, methodStat.getPosition() - 1, 0);
                }
            } else {
                paramRef =
                        TestFactory.getInstance()
                                .addConstructor(test, gc, methodStat.getPosition() - 1, 2);
            }
        }

        return paramRef;
    }

    void materializeContainer(
            TestCase test, VariableReference parameter, boolean allowNullValue) {
        Class<?> parameterClass = parameter == null ? null : parameter.getVariableClass();
        boolean mapCardinality =
                parameterClass != null
                        && java.util.Map.class.isAssignableFrom(parameterClass)
                        && hasCardinalityDependency();
        if (parameterClass == null || (!hasContainerStateDependency() && !mapCardinality)) {
            return;
        }
        try {
            Method mutator;
            if (java.util.Map.class.isAssignableFrom(parameterClass)) {
                mutator = parameterClass.getMethod("put", Object.class, Object.class);
            } else if (java.util.Collection.class.isAssignableFrom(parameterClass)) {
                mutator = parameterClass.getMethod("add", Object.class);
            } else {
                return;
            }
            int position = ContainerSetup.insertionPosition(test);
            GenericMethod method = new GenericMethod(mutator, mutator.getDeclaringClass());
            MethodStatement existingMutation = findContainerMutation(test, parameter, mutator);
            if (existingMutation == null) {
                ContainerSetup.addEntry(test, parameter, method, position);
            } else if (java.util.Map.class.isAssignableFrom(parameterClass)) {
                ContainerSetup.ensureStringMapKey(test, existingMutation.getReturnValue());
            }
        } catch (Exception e) {

            // Container setup is best effort; the caller will reject the incomplete path.
        }
    }

    private static MethodStatement findContainerMutation(
            TestCase test, VariableReference parameter, Method mutator) {
        for (int i = Math.max(0, parameter.getStPosition()); i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof MethodStatement) {
                MethodStatement method = (MethodStatement) statement;
                if (mutator.equals(method.getMethod().getMethod())
                        && method.getCallee() != null
                        && method.getCallee().equals(parameter)) {
                    return method;
                }
            }
        }
        return null;
    }

    private boolean hasContainerStateDependency() {
        Deque<DepVariableWrapper> pending = new ArrayDeque<>(children);
        Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = 96;
        while (!pending.isEmpty() && remaining-- > 0) {
            DepVariableWrapper node = pending.removeFirst();
            if (node == null || !visited.add(node) || node.var.getInstruction() == null) {
                continue;
            }
            if (node.var.getInstruction().getASMNode() instanceof MethodInsnNode) {
                String name = ((MethodInsnNode) node.var.getInstruction().getASMNode()).name;
                if ("iterator".equals(name)
                        || "entrySet".equals(name)
                        || "hasNext".equals(name)
                        || "next".equals(name)) {
                    return true;
                }
            }
            pending.addAll(node.children);
        }
        return false;
    }

    private boolean hasCardinalityDependency() {
        Deque<DepVariableWrapper> pending = new ArrayDeque<>(children);
        Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = 96;
        while (!pending.isEmpty() && remaining-- > 0) {
            DepVariableWrapper node = pending.removeFirst();
            if (node == null || !visited.add(node) || node.var.getInstruction() == null) {
                continue;
            }
            if (node.var.getInstruction().getASMNode() instanceof MethodInsnNode) {
                String name = ((MethodInsnNode) node.var.getInstruction().getASMNode()).name;
                if ("size".equals(name) || "isEmpty".equals(name)) {
                    return true;
                }
            }
            pending.addAll(node.children);
        }
        return false;
    }

    /** Initialize a cast-derived list parameter before a shared read node is synthesized. */
    private void materializeDependentIndexedRead(
            TestCase test, VariableReference parameter, boolean allowNullValue) {
        Deque<DepVariableWrapper> pending = new ArrayDeque<>();
        pending.add(this);
        Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        int remaining = 96;
        while (!pending.isEmpty() && remaining-- > 0) {
            DepVariableWrapper node = pending.removeFirst();
            if (node == null || !visited.add(node)) {
                continue;
            }
            if (node instanceof OtherVariableWrapper
                    && node.var.getInstruction() != null
                    && node.var.getInstruction().getASMNode() instanceof MethodInsnNode) {
                MethodInsnNode methodNode = (MethodInsnNode) node.var.getInstruction().getASMNode();
                if ("get".equals(methodNode.name) && methodNode.desc.startsWith("(I)")) {
                    try {
                        Class<?> owner =
                                TestGenerationContext.getInstance()
                                        .getClassLoaderForSUT()
                                        .loadClass(methodNode.owner.replace('/', '.'));
                        java.lang.reflect.Method get = owner.getMethod("get", int.class);
                        VariableReference element =
                                ((OtherVariableWrapper) node)
                                        .materializeIndexedListRead(
                                                test, get, parameter, allowNullValue);
                        if (element != null) {
                            return;
                        }
                    } catch (Exception ignored) {

                        // This is an optional setup refinement; ordinary synthesis remains
                        // available.
                    }
                }
            }
            pending.addAll(node.parents);
            pending.addAll(node.children);
        }
    }

    static Constructor<?> selectConstructor(Constructor<?>[] constructors) {
        if (constructors == null || constructors.length == 0) {
            return null;
        }
        for (Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                return constructor;
            }
        }
        return Randomness.choice(constructors);
    }

    private static boolean isCompatibleWithCast(VariableReference parameter, String castClassName) {
        try {
            Class<?> castClass =
                    TestGenerationContext.getInstance()
                            .getClassLoaderForSUT()
                            .loadClass(castClassName);
            return castClass.isAssignableFrom(parameter.getVariableClass());
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String[] extractParameter(String methodSig) {
        Type[] argumentTypes = Type.getArgumentTypes(methodSig.substring(methodSig.indexOf('(')));
        String[] args = new String[argumentTypes.length];
        for (int i = 0; i < args.length; i++) {
            args[i] = argumentTypes[i].getClassName();
        }
        return args;
    }

    @Override
    public VariableReference find(
            TestCase test,
            boolean isLeaf,
            VariableReference callerObject,
            Map<DepVariableWrapper, VarRelevance> map) {
        Statement s = test.getStatement(test.size() - 1);
        if (s instanceof MethodStatement) {

            int parameterOrder = this.var.getParamOrder();

            MethodStatement targetMethodStatement = (MethodStatement) s;

            List<VariableReference> paraList = targetMethodStatement.getParameterReferences();

            return paraList.get(parameterOrder - 1);
        }

        return null;
    }
}
