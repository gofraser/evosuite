package org.evosuite.graphs.interprocedural;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BasicBlock;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.interestednode.IInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.graphs.interprocedural.var.DepVariableFactory;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.testcase.factories.ObjectRuleRuntime;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point of the object construction graph analysis. On-demand branch analysis fills the
 * run-scoped object-rule runtime with the state variables each branch depends on and the concrete
 * subtypes discovered via {@code INSTANCEOF} checks.
 *
 * <p>Ported from the {@code evosuite-plus-plus} fork with cleanups applied.
 */
public class InterproceduralGraphAnalysis {

    private static final Logger logger =
            LoggerFactory.getLogger(InterproceduralGraphAnalysis.class);

    /**
     * Physical methods ({@code className#methodName}, keyed on the branch's own containing method
     * rather than the resolved accessible entry) that already blew their analysis budget in the
     * current run. {@link #analyzeBranchOnDemand} caches per branch when a private method's graph
     * is exposed through a nearest accessible caller, so that two private methods sharing one
     * caller do not suppress each other's analysis (see the cache-key comment there). But that
     * means every branch of the *same* expensive private method independently pays the full
     * {@link Properties#OBJECT_RULE_ANALYSIS_TIMEOUT} budget with no memory of previous timeouts —
     * measured as 154 repeated timeouts analyzing {@code JVCParserTokenManager.jjMoveNfa_2} and 132
     * analyzing {@code LovinsStemmer.removeEnding} on the release corpus, enough on its own to
     * starve those classes' searches. This map lets later branches of a method already known to be
     * too expensive skip straight to the conservative "no graph" outcome.
     *
     * <p>This class only exposes static entry points, so it has no lifecycle hook of its own to
     * clear the map on a fresh run or target class. Instead it follows the same reset-epoch pattern
     * as {@code ObjectRuleEntryBuilder.synchronizeFailureCache}: the map is only ever read and
     * written after checking it against the current {@link
     * org.evosuite.testcase.factories.ObjectRuleRuntime#getResetEpoch()}, and is dropped whenever
     * that epoch has moved on.
     */
    private static final Map<String, Boolean> timedOutMethods = new LinkedHashMap<>();

    /** The reset epoch {@link #timedOutMethods} was last cleared for. */
    private static volatile long timedOutMethodsEpoch = -1L;

    /**
     * Whether the physical method identified by {@code physicalMethodKey} (see {@link
     * #timedOutMethods}) already blew its analysis budget in the current run.
     */
    private static boolean isMethodAnalysisTimedOut(String physicalMethodKey) {
        synchronizeTimedOutMethods();
        return timedOutMethods.containsKey(physicalMethodKey);
    }

    /** Records that the physical method identified by {@code physicalMethodKey} timed out. */
    private static void markMethodAnalysisTimedOut(String physicalMethodKey) {
        synchronizeTimedOutMethods();
        timedOutMethods.put(physicalMethodKey, Boolean.TRUE);
    }

    private static void synchronizeTimedOutMethods() {
        long currentEpoch =
                TestGenerationContext.getInstance().getObjectRuleRuntime().getResetEpoch();
        if (timedOutMethodsEpoch != currentEpoch) {
            timedOutMethods.clear();
            timedOutMethodsEpoch = currentEpoch;
        }
    }

    /** Clear the analysis state owned by the current run. */
    public static void clear() {

        TestGenerationContext.getInstance().getObjectRuleRuntime().reset();
    }

    public static boolean isReachableInClass(
            BytecodeInstruction source, BytecodeInstruction target) {

        BasicBlock sourceBlock = source.getBasicBlock();
        BasicBlock targetBlock = target.getBasicBlock();

        int distance = 0;
        try {
            ActualControlFlowGraph cfg = source.getActualCFG();
            distance = cfg.getDistance(sourceBlock, targetBlock);
        } catch (Exception e) {
            return false;
        }

        if (distance >= 0) {
            if (distance == 0) {
                if (source.getInstructionId() < target.getInstructionId()) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    /** Analyze the explicitly selected target method once during setup. */
    public static synchronized void analyzeConfiguredTargetMethod(
            IInterestedNodeFilter interestedNodeFilter) {

        if (Properties.TARGET_METHOD == null
                || Properties.TARGET_METHOD.isEmpty()
                || Properties.TARGET_CLASS == null
                || Properties.TARGET_CLASS.isEmpty()) {
            return;
        }
        InstrumentingClassLoader classLoader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();

        ActualControlFlowGraph cfg =
                GraphPool.getInstance(classLoader)
                        .getActualCFG(Properties.TARGET_CLASS, Properties.TARGET_METHOD);
        if (cfg == null) {
            return;
        }
        FBranchDefUseAnalyzer.analyze(
                GraphPool.getInstance(classLoader)
                        .getRawCFG(Properties.TARGET_CLASS, Properties.TARGET_METHOD));
        Map<Branch, Set<DepVariable>> map = analyzeIndividualMethod(cfg, interestedNodeFilter);
        ObjectRuleRuntime runtime = TestGenerationContext.getInstance().getObjectRuleRuntime();
        runtime.setRecommendedClasses(analyzeRecommendationClasses(cfg));
        runtime.putBranchInterestedVars(Properties.TARGET_METHOD, map);
    }

    /**
     * Analyze the object-construction graph on demand and cache it in the current run's graph
     * cache. Directly callable entries are cached per entry method; inaccessible branches are
     * analyzed one at a time and merged into the map for their accessible caller.
     */
    public static synchronized void analyzeBranchOnDemand(
            Branch branch, IInterestedNodeFilter interestedNodeFilter) {
        if (branch == null || branch.getInstruction() == null) {
            return;
        }
        String className = branch.getInstruction().getClassName();
        String methodName = branch.getInstruction().getMethodName();
        String cacheKey = methodName;
        TargetContext context = null;

        context = TargetContext.forBranch(branch);
        if (context == null) {
            return;
        }
        cacheKey = context.getMethodSignature();
        if (!context.getMethodSignature().equals(context.getBranchMethodSignature())) {
            // A private branch is analyzed in isolation, even though its graph is exposed through
            // the nearest accessible caller. Keep one cache entry per branch so two private
            // methods sharing that caller do not suppress one another's analysis.
            cacheKey = branchCacheKey(context, branch);
        }

        ObjectRuleRuntime runtime = TestGenerationContext.getInstance().getObjectRuleRuntime();
        boolean branchSpecificEntry =
                !context.getMethodSignature().equals(context.getBranchMethodSignature());
        Map<Branch, Set<DepVariable>> cached =
                runtime.getBranchInterestedVarsMap().get(cacheKey);
        boolean alreadyAnalyzed =
                branchSpecificEntry
                        ? cached != null && cached.containsKey(branch)
                        : cached != null;
        if (className == null || methodName == null || alreadyAnalyzed) {
            return;
        }

        String physicalMethodKey = className + "#" + methodName;
        if (branchSpecificEntry && isMethodAnalysisTimedOut(physicalMethodKey)) {
            // A previous branch of this same physical method already exhausted the analysis
            // budget; inherit that outcome instead of re-running the same expensive search.
            Map<Branch, Set<DepVariable>> failed = cached == null ? new LinkedHashMap<>() : cached;
            failed.put(branch, new LinkedHashSet<>());
            runtime.putBranchInterestedVars(cacheKey, failed);
            return;
        }

        InstrumentingClassLoader classLoader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();
        ActualControlFlowGraph cfg =
                GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
        if (cfg == null) {
            return;
        }
        try {
            FBranchDefUseAnalyzer.analyze(
                    GraphPool.getInstance(classLoader).getRawCFG(className, methodName));
            Map<Branch, Set<DepVariable>> map =
                    branchSpecificEntry
                            ? analyzeIndividualBranch(cfg, branch, interestedNodeFilter)
                            : analyzeIndividualMethod(cfg, interestedNodeFilter);

            if (branchSpecificEntry && cached != null) {
                cached.putAll(map);
            } else {
                runtime.putBranchInterestedVars(cacheKey, map);
            }

            runtime.mergeRecommendedClasses(analyzeRecommendationClasses(cfg));
        } catch (Exception | StackOverflowError e) {
            // Best-effort, on-demand analysis: never let a failure here abort test generation.

            // Cache an empty result so this method is not re-analyzed (and re-failed) each
            // generation.
            logger.warn(
                    "On-demand object-construction analysis failed for {}.{}: {}",
                    className,
                    methodName,
                    e.toString());

            if (branchSpecificEntry) {
                if (e instanceof InterproceduralGraphAnalyzer.AnalysisTimeoutException) {
                    markMethodAnalysisTimedOut(physicalMethodKey);
                }
                Map<Branch, Set<DepVariable>> failed =
                        cached == null ? new LinkedHashMap<>() : cached;
                failed.put(branch, new LinkedHashSet<>());
                runtime.putBranchInterestedVars(cacheKey, failed);
            } else {
                runtime.putBranchInterestedVars(cacheKey, new LinkedHashMap<>());
            }
        }
    }

    private static String branchCacheKey(TargetContext context, Branch branch) {
        BytecodeInstruction instruction = branch.getInstruction();
        String caseValue = branch.isSwitchCaseBranch()
                ? String.valueOf(branch.getTargetCaseValue())
                : "";
        return context.getMethodSignature()
                + "@branch["
                + instruction.getClassName()
                + ":"
                + instruction.getMethodName()
                + ":"
                + instruction.getInstructionId()
                + ":"
                + branch.getActualBranchId()
                + ":"
                + caseValue
                + "]";
    }

    @SuppressWarnings("rawtypes")
    public static Map<Integer, List<String>> analyzeRecommendationClasses(
            ActualControlFlowGraph cfg) {
        InstrumentingClassLoader classLoader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();

        Map<Integer, List<String>> recommendationClasses =
                new LinkedHashMap<Integer, List<String>>();
        for (BytecodeInstruction ins :
                BytecodeInstructionPool.getInstance(classLoader)
                        .getInstructionsIn(className, methodName)) {

            /**
             * handle instanceof instruction, it is essential for test initialization with
             * polymorphism
             */
            if (ins.checkInstanceOf()) {
                String checkingClassName = ins.getInstanceOfCheckingType();
                if (checkingClassName != null) {
                    int operandNum = ins.getOperandNum();
                    for (int i = 0; i < operandNum; i++) {
                        Frame frame = ins.getFrame();
                        int index = frame.getStackSize() - operandNum + i;
                        Value val = frame.getStack(index);
                        if (!(val instanceof SourceValue)) {
                            continue;
                        }

                        SourceValue srcValue = (SourceValue) val;
                        MethodNode node =
                                DefUseAnalyzer.getMethodNode(classLoader, className, methodName);

                        /** get all the instruction defining the value. */
                        for (AbstractInsnNode insNode : srcValue.insns) {
                            BytecodeInstruction defIns =
                                    DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, insNode);
                            if (defIns != null) {
                                DepVariable var = DepVariableFactory.createVariableInstance(defIns);
                                if (var.isParameter()) {
                                    int position = var.getInstruction().getParameterPosition();
                                    List<String> classes = recommendationClasses.get(position);
                                    if (classes == null) {
                                        classes = new ArrayList<String>();
                                    }
                                    if (!classes.contains(checkingClassName)) {
                                        classes.add(checkingClassName);
                                        recommendationClasses.put(position, classes);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return recommendationClasses;
    }

    @SuppressWarnings("rawtypes")
    public static Map<Branch, Set<DepVariable>> analyzeIndividualMethod(
            ActualControlFlowGraph cfg, IInterestedNodeFilter interestedNodeFilter) {
        Map<Branch, Set<DepVariable>> map = new LinkedHashMap<>();
        long deadlineNanos = analysisDeadlineNanos();

        InstrumentingClassLoader classLoader =
                TestGenerationContext.getInstance().getClassLoaderForSUT();
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();

        for (Branch b :
                BranchPool.getInstance(classLoader)
                        .retrieveBranchesInMethod(className, methodName)) {
            Set<DepVariable> inputRootVars = new LinkedHashSet<DepVariable>();
            if (deadlineReached(deadlineNanos)) {
                map.put(b, inputRootVars);
                continue;
            }
            InterproceduralGraphAnalyzer graphAnalyzer =
                    new InterproceduralGraphAnalyzer(b, interestedNodeFilter, deadlineNanos);
            Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
            try {
                if (!b.isInstrumented()) {
                    Frame frame = b.getInstruction().getFrame();
                    for (int i = 0; i < b.getInstruction().getOperandNum(); i++) {
                        int index = frame.getStackSize() - i - 1;
                        Value val = frame.getStack(index);

                        graphAnalyzer.searchDependantVariables(
                                val,
                                cfg,
                                inputRootVars,
                                visitedIns,
                                Properties.COMPUTATION_GRAPH_METHOD_CALL_DEPTH);
                    }
                }
            } catch (Exception | StackOverflowError e) {
                // The object-construction graph is a best-effort search aid. If analyzing one
                // branch fails (an instruction the interprocedural traversal cannot resolve, or
                // runaway recursion on a complex method), skip that branch's graph rather than
                // aborting the whole method's analysis — and with it the entire test generation.
                // A branch with no graph simply falls back to plain random generation.
                if (e instanceof InterproceduralGraphAnalyzer.AnalysisTimeoutException) {
                    logger.debug(
                            "Object-construction analysis budget exhausted in {}.{}",
                            className,
                            methodName);
                } else {
                    logger.warn(
                            "Skipping object-construction analysis for branch {} in {}.{}: {}",
                            b,
                            className,
                            methodName,
                            e.toString());
                }
                inputRootVars.clear();
            }

            map.put(b, inputRootVars);
        }

        return map;
    }

    @SuppressWarnings("rawtypes")
    public static Map<Branch, Set<DepVariable>> analyzeIndividualBranch(
            ActualControlFlowGraph cfg, Branch branch, IInterestedNodeFilter interestedNodeFilter) {
        Map<Branch, Set<DepVariable>> map = new LinkedHashMap<>();

        InterproceduralGraphAnalyzer graphAnalyzer =
                new InterproceduralGraphAnalyzer(
                        branch, interestedNodeFilter, analysisDeadlineNanos());
        Set<DepVariable> inputRootVars = new LinkedHashSet<DepVariable>();
        Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
        if (!branch.isInstrumented()) {
            Frame frame = branch.getInstruction().getFrame();
            for (int i = 0; i < branch.getInstruction().getOperandNum(); i++) {
                int index = frame.getStackSize() - i - 1;
                Value val = frame.getStack(index);

                graphAnalyzer.searchDependantVariables(
                        val,
                        cfg,
                        inputRootVars,
                        visitedIns,
                        Properties.COMPUTATION_GRAPH_METHOD_CALL_DEPTH);
            }
        }

        map.put(branch, inputRootVars);

        return map;
    }

    private static long analysisDeadlineNanos() {
        if (Properties.OBJECT_RULE_ANALYSIS_TIMEOUT <= 0) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() + Properties.OBJECT_RULE_ANALYSIS_TIMEOUT * 1_000_000L;
    }

    private static boolean deadlineReached(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
    }

    private static boolean isCUT(String className) {
        if (!Properties.TARGET_CLASS.equals("")
                && !(className.equals(Properties.TARGET_CLASS)
                        || className.startsWith(Properties.TARGET_CLASS + "$"))) {
            return false;
        }
        return true;
    }

    public static Map<Branch, List<ConstructionPath>> checkObjectDifficultPath() {
        Map<Branch, Set<DepVariable>> interestedBranches =
                TestGenerationContext.getInstance()
                        .getObjectRuleRuntime()
                        .getBranchInterestedVarsMap()
                        .get(Properties.TARGET_METHOD);

        Map<Branch, List<ConstructionPath>> interestedPaths = new LinkedHashMap<>();
        for (Map.Entry<Branch, Set<DepVariable>> entry : interestedBranches.entrySet()) {
            Branch branch = entry.getKey();
            Set<DepVariable> interestedVariables = entry.getValue();

            List<ConstructionPath> paths = new ArrayList<ConstructionPath>();
            for (DepVariable interestVar : interestedVariables) {
                for (DepVariable root : interestVar.getRootVars().keySet()) {
                    ConstructionPath path = root.findPath(interestVar);
                    if (path != null) {
                        if (path.hasValidRoot() && !paths.contains(path)) {
                            paths.add(path);
                        }
                    }
                }
            }

            if (!paths.isEmpty()) {
                interestedPaths.put(branch, paths);
            }
        }

        return interestedPaths;
    }
}
