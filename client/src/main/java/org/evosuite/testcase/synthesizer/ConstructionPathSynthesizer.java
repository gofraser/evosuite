package org.evosuite.testcase.synthesizer;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.dataflow.Definition;
import org.evosuite.coverage.dataflow.Use;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.ConstructionPath;
import org.evosuite.graphs.interprocedural.DefUseAnalyzer;
import org.evosuite.graphs.interprocedural.ObjectConstructionBoundary;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericConstructor;
import org.objectweb.asm.tree.MethodInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class ConstructionPathSynthesizer {

    private static final Logger logger = LoggerFactory.getLogger(ConstructionPathSynthesizer.class);

    public ConstructionPathSynthesizer() {
        super();
    }

    /**
     * @param b
     * @param isBranchInTargetMethod
     * @return
     */
    public PartialGraph constructPartialComputationGraph(Branch b) {
        Map<String, Map<Branch, Set<DepVariable>>> analyzedBranches =
                TestGenerationContext.getInstance()
                        .getObjectRuleRuntime()
                        .getBranchInterestedVarsMap();
        Map<Branch, Set<DepVariable>> activeMethodBranches =
                analyzedBranches.get(TargetContext.activeMethodSignature());
        Set<DepVariable> variables =
                activeMethodBranches == null ? null : activeMethodBranches.get(b);
        if (variables == null) {
            for (Map<Branch, Set<DepVariable>> branches : analyzedBranches.values()) {
                variables = branches.get(b);
                if (variables != null) {
                    break;
                }
            }
        }
        return constructPartialComputationGraph(b, variables);
    }

    /**
     * Build the canonical, valid-root-filtered graph directly from one branch's analyzed leaves.
     * This overload accepts an already selected branch slice; normal synthesis continues to use the
     * runtime-backed overload above.
     */
    public PartialGraph constructPartialComputationGraph(Branch b, Set<DepVariable> variables) {
        PartialGraph graph = new PartialGraph();
        graph.setBranch(b);

        if (variables == null) {
            return graph;
        }

        for (DepVariable source : variables) {
            Map<DepVariable, ArrayList<ConstructionPath>> rootInfo = source.getRootVars();

            for (Map.Entry<DepVariable, ArrayList<ConstructionPath>> rootEntry
                    : rootInfo.entrySet()) {
                DepVariable root = rootEntry.getKey();
                if (root.isStaticField()
                        && ObjectConstructionBoundary.isExternalImplementationClass(
                                root.getInstruction().getClassName())) {
                    // VM caches and reflection singletons are fixed library implementation state.
                    // They cannot be constructed by a test and must not become graph roots.
                    continue;
                }

                /** this=> class; parameter => method; static field=> whatever */
                if ((root.referenceToThis()
                                && root.getInstruction()
                                        .getClassName()
                                        .equals(TargetContext.activeClassName()))
                        || (root.isParameter()
                                && root.getInstruction()
                                        .getClassName()
                                        .equals(TargetContext.activeClassName())
                                && root.getInstruction()
                                        .getMethodName()
                                        .equals(TargetContext.activeMethodSignature()))
                        || root.isStaticField()
                        || root.isLoadArrayElement()) {

                    List<ConstructionPath> paths = rootEntry.getValue();
                    ConstructionPath selectedPath = selectCoherentPath(paths);
                    if (selectedPath != null) {
                        ConstructionPath path = selectedPath;
                        for (int i = 0; i < path.size() - 1; i++) {
                            DepVariableWrapper child = graph.fetchAndMerge(path.get(i));
                            DepVariableWrapper parent = graph.fetchAndMerge(path.get(i + 1));

                            child.addParent(parent);
                            parent.addChild(child);
                        }

                        if (path.size() == 1) {
                            graph.fetchAndMerge(path.get(0));
                        }
                    }
                }
            }
        }

        connectReturnedArrayElements(graph);

        return graph;
    }

    /**
     * Interprocedural slicing maps a callee's receiver and parameters back to the caller, but an
     * accessor which returns an array element still appears as two paths: one ending at the element
     * and one starting at the call result. Join those paths so the call result's fields are
     * synthesized on the exact element selected by the accessor.
     *
     * <p>For example, {@code get(i).state} must become {@code array -> array[i] -> get(i) ->
     * state}. Without this alias edge EvoObj initializes an arbitrary element and then tries to
     * configure {@code state} on the target receiver.
     */
    private static void connectReturnedArrayElements(PartialGraph graph) {
        List<DepVariableWrapper> nodes = graph.getNodes();
        for (DepVariableWrapper call : nodes) {
            if (call == null
                    || call.var == null
                    || !call.var.isMethodCall()
                    || call.var.getInstruction() == null
                    || !(call.var.getInstruction().getASMNode() instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode invocation = (MethodInsnNode) call.var.getInstruction().getASMNode();
            String owner = invocation.owner.replace('/', '.');
            String method = invocation.name + invocation.desc;
            for (DepVariableWrapper element : nodes) {
                if (element == null
                        || element.var == null
                        || !element.var.isLoadArrayElement()
                        || element.var.getInstruction() == null
                        || !owner.equals(element.var.getInstruction().getClassName())
                        || !method.equals(element.var.getInstruction().getMethodName())) {
                    continue;
                }
                /*
                 * This is an alias/dependency edge rather than a JVM operand edge. Keeping it in
                 * the partial graph is enough to order synthesis and let the call wrapper reuse
                 * the mapped array element; the original operand relations still identify the
                 * real receiver and arguments.
                 */
                call.addParent(element);
                element.addChild(call);
            }
        }
    }

    /**
     * A DepVariable may expose several equivalent root-to-value paths after interprocedural
     * analysis. Merging every path creates a union of mutually exclusive callee alternatives and
     * asks graph-to-code synthesis to realize all of them at once. Select one deterministic,
     * constructable-looking path per source/root pair. Independent sources and roots are still all
     * retained, so this removes OR-alternatives without dropping AND-dependencies.
     */
    static ConstructionPath selectCoherentPath(List<ConstructionPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return Collections.min(
                paths,
                Comparator.comparingInt(ConstructionPathSynthesizer::pathPenalty)
                        .thenComparingInt(ConstructionPath::size)
                        .thenComparing(ConstructionPath::toString));
    }

    private static int pathPenalty(ConstructionPath path) {
        int penalty = 0;
        String previousMethod = null;
        for (DepVariable variable : path.getPath()) {
            if (variable == null || variable.getInstruction() == null) {
                penalty += 100;
                continue;
            }
            if (variable.getType() == DepVariable.OTHER) {
                penalty += 3;
            }
            String method = variable.getInstruction().getMethodName();
            if (previousMethod != null && method != null && !method.equals(previousMethod)) {
                penalty += 2;
            }
            if (ObjectConstructionBoundary.isExternalImplementationClass(
                    variable.getInstruction().getClassName())) {
                penalty += 8;
            }
            previousMethod = method;
        }
        return penalty;
    }

    private PartialGraph partialGraph;
    private Map<DepVariableWrapper, VarRelevance> graph2CodeMap;
    private boolean unresolvedRequiredRoot;

    public void buildNodeStatementCorrespondence(TestCase test, Branch b, boolean allowNullValue)
            throws ConstructionFailedException, ClassNotFoundException {

        buildNodeStatementCorrespondence(
                test, b, allowNullValue, constructPartialComputationGraph(b),
                Integer.MAX_VALUE);
    }

    /** Uses the graph prepared during branch classification. */
    public void buildNodeStatementCorrespondence(
            TestCase test, Branch b, boolean allowNullValue, PartialGraph partialGraph)
            throws ConstructionFailedException, ClassNotFoundException {
        buildNodeStatementCorrespondence(
                test, b, allowNullValue, partialGraph, Integer.MAX_VALUE);
    }

    /**
     * Builds the correspondence without allowing object-rule expansion to cross the factory's
     * exclusive statement bound. A graph node can add several statements, so the check must be
     * made before the next expansion and the expansion must be rolled back if it still exceeds the
     * remaining budget.
     */
    public void buildNodeStatementCorrespondence(
            TestCase test, Branch b, boolean allowNullValue, PartialGraph partialGraph,
            int maxStatements)
            throws ConstructionFailedException, ClassNotFoundException {

        if (partialGraph == null) {
            partialGraph = constructPartialComputationGraph(b);
        }

        final int initialStatementCount = test.size();
        if (test.size() >= maxStatements) {
            return;
        }

        List<DepVariableWrapper> topLayer = partialGraph.getTopLayer();

        /**
         * track what variable reference can be reused. Note that, one node can corresponding to
         * multiple statements. It is because a static field/method can be called twice dynamically.
         * Therefore, we need to construct multiple fields/methods for different object of the same
         * type.
         */
        Map<DepVariableWrapper, VarRelevance> map = new HashMap<>();
        boolean semanticInputHandled = SemanticStringInputSynthesizer.apply(test, partialGraph, b);
        if (test.size() >= maxStatements) {
            while (test.size() > initialStatementCount) {
                test.remove(test.size() - 1);
            }
            return;
        }
        bindTargetParameterRoots(test, topLayer, map);

        /**
         * use BFS on partial graph to generate test code, but we need to check the dependency,
         * making sure that we have generated the code of its dependency.
         */
        Queue<DepVariableWrapper> queue =
                semanticInputHandled ? new ArrayDeque<>() : new ArrayDeque<>(topLayer);

        /** when constructing some instruction, its dependency may not be ready. */
        Map<DepVariableWrapper, Integer> methodCounter = new HashMap<>();
        int c = 1;
        while (!queue.isEmpty()) {
            if (test.size() >= maxStatements) {
                break;
            }
            DepVariableWrapper node = queue.remove();

            c++;

            /** for each method callsite, we only generate once. */
            if (map.containsKey(node) && node.var.isMethodCall()) {
                continue;
            }

            boolean isValid = checkDependency(node, map);
            if (isValid) {
                VariableInTest testVariable = getCallerObject(map, node);
                int statementCountBeforeNode = test.size();
                Map<DepVariableWrapper, VarRelevance> mapBeforeNode = new HashMap<>(map);
                boolean processedBeforeNode = node.processed;
                boolean derived =
                        deriveCodeForTest(map, test, testVariable, b, allowNullValue, null);

                if (test.size() >= maxStatements) {
                    while (test.size() > statementCountBeforeNode) {
                        test.remove(test.size() - 1);
                    }
                    map.clear();
                    map.putAll(mapBeforeNode);
                    node.processed = processedBeforeNode;
                    break;
                }

                node.processed = true;

                /** the order of children size matters */
                Collections.sort(
                        node.children,
                        new Comparator<DepVariableWrapper>() {
                            @Override
                            public int compare(DepVariableWrapper o1, DepVariableWrapper o2) {
                                if (o1.var.isMethodCall() && !o2.var.isMethodCall()) {
                                    return -1;
                                } else if (!o1.var.isMethodCall() && o2.var.isMethodCall()) {
                                    return 1;
                                }

                                return o1.children.size() - o2.children.size();
                            }
                        });

                if (node.traverseChildrenAfterGeneration(derived)) {
                    for (DepVariableWrapper child : node.children) {
                        queue.add(child);
                    }
                }

            } else {
                Integer count = methodCounter.get(node);
                if (count == null) count = 0;

                if (count < 5) {
                    queue.add(node);
                    methodCounter.put(node, ++count);
                }
            }

            if (test.size() > Properties.CHROMOSOME_LENGTH) {
                break;
            }
        }

        this.setPartialGraph(partialGraph);
        this.setGraph2CodeMap(map);
        validateRequiredRoots(test, partialGraph);

        markObjectRuleSlots(test, map);
    }

    /**
     * Parameter roots denote arguments of the selected target invocation, so their correspondence
     * is known without performing any synthesis. Seed that identity before walking the graph. This
     * both gives child nodes the correct receiver and avoids losing the mapping when a wrapper
     * cannot rediscover a target statement that is no longer the last statement in the test.
     */
    private void bindTargetParameterRoots(
            TestCase test,
            List<DepVariableWrapper> roots,
            Map<DepVariableWrapper, VarRelevance> map) {
        EntityWithParametersStatement targetCall =
                test == null ? null : test.findTargetExecutableStatement();
        if (targetCall == null) {
            return;
        }
        for (DepVariableWrapper root : roots) {
            if (root == null || !root.var.isParameter()) {
                continue;
            }
            int parameterIndex = root.var.getParamOrder() - 1;
            if (parameterIndex < 0
                    || parameterIndex >= targetCall.getParameterReferences().size()) {
                continue;
            }
            VariableReference argument = targetCall.getParameterReferences().get(parameterIndex);
            List<VariableReference> references = new ArrayList<>();
            references.add(argument);
            map.put(root, new VarRelevance(references, references));
        }
    }

    /**
     * EvoObj: flag the primitive statements that synthesis produced as tunable "slots" (the matched
     * and influential variables of each graph node). The flag rides on the statement, so it
     * survives clone/crossover/insert/delete, and lets the main search bias mutation toward them.
     */
    private void markObjectRuleSlots(TestCase test, Map<DepVariableWrapper, VarRelevance> map) {
        Set<VariableReference> slots =
                Collections.newSetFromMap(new IdentityHashMap<VariableReference, Boolean>());
        for (VarRelevance relevance : map.values()) {
            if (relevance == null) {
                continue;
            }
            if (relevance.influentialVars != null) {
                slots.addAll(relevance.influentialVars);
            }
            if (relevance.matchedVars != null) {
                slots.addAll(relevance.matchedVars);
            }
        }
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if (statement instanceof PrimitiveStatement
                    && containsIdentity(slots, statement.getReturnValue())) {
                ((PrimitiveStatement<?>) statement).setObjectRuleSlot(true);
            }
        }
    }

    /** A root with child state must be the exact, non-null object passed to the target call. */
    private void validateRequiredRoots(TestCase test, PartialGraph graph) {
        unresolvedRequiredRoot = false;
        EntityWithParametersStatement targetCall =
                test == null ? null : test.findTargetExecutableStatement();
        for (DepVariableWrapper root : graph.getTopLayer()) {
            if (!root.var.isParameter() || root.children.isEmpty()) {
                continue;
            }
            int parameterIndex = root.var.getParamOrder() - 1;
            VariableReference mapped =
                    targetCall == null
                                    || parameterIndex < 0
                                    || parameterIndex >= targetCall.getParameterReferences().size()
                            ? null
                            : targetCall.getParameterReferences().get(parameterIndex);
            int nodeId =
                    root.var.getInstruction() == null
                            ? -1
                            : root.var.getInstruction().getInstructionId();
            int mappedPosition = ConstructionReferenceUtil.definitionPosition(test, mapped);
            if (mappedPosition < 0 || test.getStatement(mappedPosition) instanceof NullStatement) {
                unresolvedRequiredRoot = true;
                continue;
            }
            if (targetCall == null
                    || parameterIndex < 0
                    || parameterIndex >= targetCall.getParameterReferences().size()
                    || !sameReference(
                            test,
                            mapped,
                            targetCall.getParameterReferences().get(parameterIndex))) {
                unresolvedRequiredRoot = true;
            }
        }
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

    private static boolean containsIdentity(
            Iterable<VariableReference> references, VariableReference candidate) {
        for (VariableReference reference : references) {
            if (reference == candidate) {
                return true;
            }
        }
        return false;
    }

    public boolean hasUnresolvedRequiredRoot() {
        return unresolvedRequiredRoot;
    }

    protected boolean checkDependency(
            DepVariableWrapper node, Map<DepVariableWrapper, VarRelevance> map) {

        /** ensure every parent of current node is visited in the map */
        for (DepVariableWrapper parent : node.parents) {
            if (map.get(parent) == null
                    || map.get(parent).matchedVars == null
                    || map.get(parent).matchedVars.isEmpty()) {

                if (!parent.processed) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Some node cannot correspond to any variable in test source code, but their children can. For
     * example, arraylist.elementData[i] has a path like: arraylist -> elementData => []. the
     * elementData in arraylist cannot be accessed, but we still need to find the correspondence
     * between arraylist.elementData[i] and some variables in the source code.
     *
     * <p>Therefore, here, we need to find (1) the caller object and (2) additional nodes a call
     * should cover
     */
    protected VariableInTest getCallerObject(
            Map<DepVariableWrapper, VarRelevance> map, DepVariableWrapper node) {
        List<DepVariableWrapper> callerNodes = node.getCallerNode();

        /** for root nodes */
        if (callerNodes == null) {
            callerNodes = new ArrayList<>();
            callerNodes.add(node);
        }

        VariableReference callerObj = null;
        List<DepVariableWrapper> nodePath = new ArrayList<>();
        nodePath.add(node);

        DepVariableWrapper callerNode = callerNodes.get(0);
        VarRelevance variableRel = map.get(callerNode);

        while (variableRel == null
                || variableRel.matchedVars == null
                || variableRel.matchedVars.isEmpty()) {
            DepVariableWrapper parentNode = callerNode.getFirstParent();
            if (parentNode != null) {
                if (!nodePath.contains(parentNode)) {
                    callerNode = parentNode;
                    nodePath.add(callerNode);
                    variableRel = map.get(callerNode);
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (variableRel == null
                || variableRel.matchedVars == null
                || variableRel.matchedVars.isEmpty()) {
            return new VariableInTest(callerObj, nodePath);
        }

        //        for(int i=0; i<callerObjects.size(); i++){
        //            VariableReference callerObject = callerObjects.get(i);
        //            GenericClass t = callerObject.getGenericClass();
        //            if(t.isPrimitive()) {
        //                continue;
        //            }
        //
        //            callerObj = callerObject;
        //        }

        Collections.reverse(nodePath);
        return new VariableInTest(variableRel.matchedVars.get(0), nodePath);
    }

    //    private void buildNodeStatementRelation(TestCase test, Map<DepVariableWrapper,
    // List<VariableReference>> map,
    //            DepVariableWrapper node, Branch b, boolean allowNullValue) throws
    // ClassNotFoundException, ConstructionFailedException {
    //
    //        VariableReference callerObject = getCallerObject(map, node);
    //        deriveCodeForTest(map, test, callerObject, node, b, allowNullValue);
    //
    //    }

    protected boolean deriveCodeForTest(
            Map<DepVariableWrapper, VarRelevance> map,
            TestCase test,
            VariableInTest testVariable,
            Branch b,
            boolean allowNullValue,
            Operation recommendation)
            throws ClassNotFoundException, ConstructionFailedException {
        DepVariableWrapper node = testVariable.getNode();
        boolean isLeaf = node.children.isEmpty();

        VarRelevance relevance = null;
        try {
            relevance =
                    node.generateOrFindStatement(
                            test, isLeaf, testVariable, map, b, allowNullValue, recommendation);
        } catch (Exception e) {
            logger.debug("Object-rule statement derivation failed for {}", node.getShortName(), e);
        }
        //        List<VariableReference> generatedVariables = node.generateOrFindStatement(test,
        // isLeaf, testVariable, map, b, allowNullValue);
        if (relevance != null) {
            VarRelevance rel = map.get(node);
            if (rel == null) {
                map.put(node, relevance);
            } else {
                rel.merge(relevance);
                map.put(node, rel);
            }
        } else {
            return false;
        }

        return true;
    }

    //    private boolean isLoadedClass(Class<?> fieldDeclaringClass) {

    //        InstrumentingClassLoader classLoader =
    // TestGenerationContext.getInstance().getClassLoaderForSUT();
    //        String className = fieldDeclaringClass.getName();

    //        Map<String, RawControlFlowGraph> graphMap =
    // GraphPool.getInstance(classLoader).getRawCFGs(className);
    //        if(graphMap != null && !graphMap.isEmpty()) {
    //            return true;
    //        }
    //
    //        return false;
    //    }

    @SuppressWarnings("rawtypes")
    protected boolean hasNotInvoked(TestCase test, Executable setter) {
        for (int i = 0; i < test.size(); i++) {
            Statement s = test.getStatement(i);
            if (s instanceof MethodStatement && setter instanceof Method) {
                Method m = ((MethodStatement) s).getMethod().getMethod();
                if (m.equals(setter)) {
                    return false;
                }
            }

            if (s instanceof ConstructorStatement && setter instanceof Constructor) {
                Constructor m = ((ConstructorStatement) s).getConstructor().getConstructor();
                if (m.equals(setter)) {
                    return false;
                }
            }
        }

        return true;
    }

    //    private VariableReference retrieveParamReference4Field(TestCase test, Parameter parameter,
    // Executable executable,
    //            Statement statement) {
    //
    //        if (!(statement instanceof EntityWithParametersStatement)) {
    //            return null;
    //        }
    //

    //        EntityWithParametersStatement newStatement = (EntityWithParametersStatement)
    // statement;
    //        for (int i = 0; i < executable.getParameters().length; i++) {
    //            Parameter setterParam = executable.getParameters()[i];
    //            if (parameter.equals(setterParam)) {
    //                List<VariableReference> paramRefs = newStatement.getParameterReferences();
    //                return paramRefs.get(i);
    //            }
    //        }
    //
    //
    //        return null;
    //    }

    public static BytecodeInstruction traceBackToMethodCall(BytecodeInstruction ins) {

        if (ins.isUse()) {
            FBranchDefUseAnalyzer.analyze(ins.getRawCFG());

            Use use = DefUsePool.getUseByInstruction(ins);
            // Upstream DefUsePool has no getDefinitions(Use); reconstructed in DefUseAnalyzer (M2).
            List<Definition> defs = DefUseAnalyzer.getDefinitions(use);

            if (!defs.isEmpty()) {
                Definition def = defs.get(0);
                if (def.getFrame().getStackSize() > 0) {
                    BytecodeInstruction call = def.getSourceOfStackInstruction(0);

                    if (call != null && call.isMethodCall()) {
                        return call;
                    }
                }
            }
        }

        return null;
    }

    //            //keep traverse
    //            DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
    //            defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);
    //            Use use = DefUseFactory.makeUse(defIns);
    //            // Ignore method parameter
    //            List<Definition> defs = DefUsePool.getDefinitions(use);
    //            for (Definition def : CollectionUtil.nullToEmpty(defs)) {
    //                if (def != null) {

    //                    BytecodeInstruction defInstruction =
    // DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, def.getASMNode());
    //                    buildInputOutputForInstruction(defInstruction, node,
    //                            outputVar, cfg, allLeafDepVars, visitedIns);
    //                }
    //            }

    //    @SuppressWarnings("rawtypes")

    //    private Parameter searchForQualifiedParameter(Executable executable, Field field,
    // Parameter[] parameters,
    //            String opcode) {
    //        List<Parameter> qualifiedParams = new ArrayList<>();
    //        String fullName = null;
    //        for (Parameter param : parameters) {

    //            if (param.getType().getCanonicalName().equals(field.getType().getCanonicalName()))
    // {
    //                qualifiedParams.add(param);
    //            }
    //        }
    //
    //        if (!qualifiedParams.isEmpty()) {
    //            if (executable instanceof Method) {
    //                Method method = ((Method) executable);

    //                fullName = new GenericMethod(method,
    // method.getDeclaringClass()).getNameWithDescriptor();
    //            } else if (executable instanceof Constructor) {
    //                Constructor constructor = ((Constructor) executable);

    //                fullName = new GenericConstructor(constructor,
    // constructor.getDeclaringClass()).getNameWithDescriptor();
    //            }
    //
    //            List<BytecodeInstruction> insList = BytecodeInstructionPool
    //                    .getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())

    //

    //
    // .getAllInstructionsAtMethod(executable.getDeclaringClass().getCanonicalName(), fullName);
    //            for (BytecodeInstruction ins : insList) {
    //                if (ins.getASMNodeString().contains(opcode)) {
    //                    BytecodeInstruction defIns = ins.getSourceOfStackInstruction(0);
    //                    int count = 0;
    //                    if (defIns.isParameter()) {
    //                        for (int i = 0; i < defIns.getLocalVariableSlot(); i++) {
    //                            String typeName = executable.getParameterTypes()[i].getTypeName();

    //                            if
    // (typeName.equals(qualifiedParams.get(0).getType().getCanonicalName())) {
    //                                count++;
    //                            }
    //                        }
    //                        if (count >= 1) {
    //                            return qualifiedParams.get(count - 1);
    //                        }
    //                    }
    //                }
    //            }
    //        }
    //        return null;
    //    }

    //    private boolean isPrimitiveType(String fieldType) {
    //        boolean flag = !fieldType.contains("L") && !fieldType.contains(";");
    //        return flag;
    //    }

    //    @SuppressWarnings("rawtypes")

    //    private boolean isCalledConstructor(TestCase test, VariableReference parentVarRef,
    // Constructor constructor) {
    //
    //        if (parentVarRef == null) {
    //            return false;
    //        }
    //
    //        Statement stat = test.getStatement(parentVarRef.getStPosition());
    //        if (stat instanceof ConstructorStatement) {
    //            ConstructorStatement s = (ConstructorStatement) stat;
    //            Constructor calledConstructor = s.getConstructor().getConstructor();
    //
    //            return calledConstructor.equals(constructor);
    //        }
    //        return false;
    //    }

    @SuppressWarnings("rawtypes")
    protected void registerAllMethods(Class<?> fieldDeclaringClass) {
        try {
            for (Method method : fieldDeclaringClass.getDeclaredMethods()) {
                String methodName = method.getName() + MethodUtil.getSignature(method);
                MethodUtil.registerMethod(fieldDeclaringClass, methodName);
            }

            for (Constructor constructor : fieldDeclaringClass.getDeclaredConstructors()) {
                String constructorName = "<init>" + MethodUtil.getSignature(constructor);
                MethodUtil.registerMethod(fieldDeclaringClass, constructorName);
            }
        } catch (Exception e) {
            logger.debug("Unable to register methods for {}", fieldDeclaringClass, e);
        }
    }

    //    private Set<Integer> searchRelevantParameterOfSetterInMethod(String className, String
    // methodName, Field field) {
    //        /**
    //         * get all the field setter bytecode instructions in the method.
    //         * the field setter can be taken from callee method of @code{methodName}.
    //         */
    //        List<BytecodeInstruction> cascadingCallRelations = new LinkedList<>();

    //        Map<BytecodeInstruction, List<BytecodeInstruction>> setterMap = new
    // HashMap<BytecodeInstruction, List<BytecodeInstruction>>();
    //        Map<BytecodeInstruction, List<BytecodeInstruction>> fieldSetterMap =
    // DataDependencyUtil.analyzeFieldSetter(className, methodName,
    //                field, 5, cascadingCallRelations, setterMap);
    //        Set<Integer> validParams = new HashSet<>();
    //        if (fieldSetterMap.isEmpty()) {
    //            return validParams;
    //        }
    //

    //        for (Entry<BytecodeInstruction, List<BytecodeInstruction>> entry :
    // fieldSetterMap.entrySet()) {
    //            BytecodeInstruction setterIns = entry.getKey();
    //            List<BytecodeInstruction> callList = entry.getValue();

    //            Set<Integer> validParamPos =
    // DataDependencyUtil.checkValidParameterPositions(setterIns, className, methodName, callList);
    //            if (!validParamPos.isEmpty()) {
    //                validParams.addAll(validParamPos);
    //            }
    //        }
    //        return validParams;
    //    }

    //    private Map.Entry<Constructor, Parameter> searchForPotentialConstructor(Field field,
    // String
    // fieldOwner,
    //            Class<?> fieldDeclaringClass, List<BytecodeInstruction> insList)
    //            throws NoSuchMethodException, ClassNotFoundException {
    //
    //        String opcode = Modifier.isStatic(field.getModifiers()) ? "PUTSTATIC" : "PUTFIELD";
    //
    //        Map<Constructor, Parameter> targetConstructors = new HashMap<>();
    //        for (BytecodeInstruction ins : insList) {
    //            if (ins.getASMNodeString().contains(opcode)) {
    //                FieldInsnNode insnNode = ((FieldInsnNode) ins.getASMNode());
    //                String tmpName = insnNode.name;
    //                String tmpOwner = insnNode.owner;
    //                if (tmpName.equals(field.getName()) && tmpOwner.equals(fieldOwner)) {
    //                    String methodName = ins.getMethodName();
    //                    org.objectweb.asm.Type[] types = org.objectweb.asm.Type

    //                            .getArgumentTypes(methodName.substring(methodName.indexOf("("),
    // methodName.length()));
    //                    Class<?>[] paramClasses = new Class<?>[types.length];
    //                    int index = 0;
    //                    for (org.objectweb.asm.Type type : types) {
    //                        Class<?> paramClass = getClassForType(type);
    //                        paramClasses[index++] = paramClass;
    //                    }
    //
    //                    if (methodName.contains("<init>")) {

    //                        Constructor targetConstructor =
    // fieldDeclaringClass.getDeclaredConstructor(paramClasses);
    //                        Set<Integer> validParamPositions =
    // searchRelevantParameterOfSetterInMethod(fieldDeclaringClass.getCanonicalName(), methodName,
    // field);
    //                        if (!validParamPositions.isEmpty()) {
    //                            Integer validParamPos = Randomness.choice(validParamPositions);

    //                            Parameter param =
    // targetConstructor.getParameters()[validParamPos];
    //                            targetConstructors.put(targetConstructor, param);
    //                        }
    //                    }
    //                }
    //            }
    //        }
    //

    //        Map.Entry<Constructor, Parameter> entry =
    // Randomness.choice(targetConstructors.entrySet());
    //        return entry;
    //    }

    public static VariableReference addConstructorForClass(
            TestFactory testFactory,
            TestCase test,
            int position,
            String desc,
            boolean allowNullValue)
            throws ConstructionFailedException {
        try {
            String fieldType;
            if (desc.contains("/")) {
                fieldType =
                        desc.replace("/", ".").substring(desc.indexOf("L") + 1, desc.length() - 1);
            } else {
                fieldType = desc;
            }
            Class<?> fieldClass =
                    TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(fieldType);

            if (fieldClass.isInterface() || Modifier.isAbstract(fieldClass.getModifiers())) {
                Set<String> subclasses =
                        DependencyAnalysis.getInheritanceTree()
                                .getSubclasses(fieldClass.getCanonicalName());
                String subclass = Randomness.choice(subclasses);
                fieldClass =
                        TestGenerationContext.getInstance()
                                .getClassLoaderForSUT()
                                .loadClass(subclass);
            }

            double v = Randomness.nextDouble();
            if (v < Properties.NULL_PROBABILITY) {
                VariableReference variableReference =
                        testFactory.createNull(test, fieldClass, position, 0);
                return variableReference;
            }

            Constructor<?> constructor = Randomness.choice(fieldClass.getConstructors());
            if (constructor != null) {
                GenericConstructor genericConstructor =
                        new GenericConstructor(constructor, fieldClass);
                VariableReference variableReference =
                        testFactory.addConstructor(test, genericConstructor, position + 1, 2);
                return variableReference;
            }
            return null;
        } catch (ClassNotFoundException e) {
            logger.debug("Unable to construct class from descriptor {}", desc, e);
        }
        return null;
    }

    public PartialGraph getPartialGraph() {
        return partialGraph;
    }

    public void setPartialGraph(PartialGraph partialGraph) {
        this.partialGraph = partialGraph;
    }

    public Map<DepVariableWrapper, VarRelevance> getGraph2CodeMap() {
        return graph2CodeMap == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(graph2CodeMap);
    }

    public void setGraph2CodeMap(Map<DepVariableWrapper, VarRelevance> graph2CodeMap) {
        this.graph2CodeMap = graph2CodeMap == null
                ? null
                : new LinkedHashMap<>(graph2CodeMap);
    }
}
