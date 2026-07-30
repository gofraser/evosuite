package org.evosuite.graphs.interprocedural;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.synthesizer.ObjectConstructionGraphDiagnostics;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the object construction graph analysis. {@link #initializeDataflow}
 * iterates the branches of the target method and fills {@link #branchInterestedVarsMap} with the
 * state variables each branch depends on, and {@link #recommendedClasses} with the concrete
 * subtypes discovered via {@code INSTANCEOF} checks.
 *
 * <p>Ported from the {@code evosuite-plus-plus} fork with cleanups applied.
 */
public class InterproceduralGraphAnalysis {

	private static final Logger logger = LoggerFactory.getLogger(InterproceduralGraphAnalysis.class);

	/**
	 * Clear the analysis' mutable static state. Delegates to the {@link ObjectConstructionContext}
	 * holder; prefer wiring {@link ObjectConstructionContext#reset()} into
	 * {@link TestGenerationContext#resetContext()} for automatic between-run cleanup.
	 */
	public static void clear() {
		ObjectConstructionContext.reset();
	}

	public static boolean isReachableInClass(BytecodeInstruction source, BytecodeInstruction target) {

		BasicBlock sourceBlock = source.getBasicBlock();
		BasicBlock targetBlock = target.getBasicBlock();

		int distance = 0;
		try {
			ActualControlFlowGraph cfg = source.getActualCFG();
			distance = cfg.getDistance(sourceBlock, targetBlock);
		}
		catch (Exception e) {
			return false;
		}

		if (distance >= 0) {
			if (distance == 0) {
				if (source.getInstructionId() < target.getInstructionId()) {
					return true;
				}
			}
			else {
				return true;
			}
		}
		return false;
	}

	public static void initializeDataflow(IInterestedNodeFilter interestedNodeFilter) {
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();

		for (String className : BranchPool.getInstance(classLoader).knownClasses()) {
			// when limitToCUT== true, if not the class under test of a inner/anonymous
			// class, continue
			if (!isCUT(className))
				continue;
			// when limitToCUT==false, consider all classes, but excludes libraries ones
			// according the INSTRUMENT_LIBRARIES property
			if (!Properties.INSTRUMENT_LIBRARIES && !DependencyAnalysis.isTargetProject(className))
				continue;

			// Branches
			for (String methodName : BranchPool.getInstance(classLoader).knownMethods(className)) {
				if (Properties.TARGET_METHOD.equals(methodName)) {
					ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
					FBranchDefUseAnalyzer.analyze(GraphPool.getInstance(classLoader).getRawCFG(className, methodName));

					Map<Branch, Set<DepVariable>> map = analyzeIndividualMethod(cfg, interestedNodeFilter);
					ObjectConstructionContext.recommendedClasses = analyzeRecommendationClasses(cfg);
					ObjectConstructionContext.branchInterestedVarsMap.put(methodName, map);
				}
			}
		}
	}

	/**
	 * Whole-class mode: analyze the object-construction graph on demand and cache it in
	 * {@link ObjectConstructionContext#branchInterestedVarsMap}, instead of the eager
	 * {@link #initializeDataflow} loop over {@code TARGET_METHOD}. Idempotent per entry method.
	 */
	public static synchronized void analyzeBranchOnDemand(Branch branch, IInterestedNodeFilter interestedNodeFilter) {
		if (branch == null || branch.getInstruction() == null) {
			return;
		}
		String className = branch.getInstruction().getClassName();
		String methodName = branch.getInstruction().getMethodName();
		String cacheKey = methodName;
		TargetContext context = null;
		if (Properties.OBJECT_RULE_WHOLE_CLASS
				&& (Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty())) {
			context = TargetContext.forBranch(branch);
			if (context == null) {
				return;
			}
			cacheKey = context.getMethodSignature();
		}
		if (className == null || methodName == null
				|| ObjectConstructionContext.branchInterestedVarsMap.containsKey(cacheKey)) {
			return;
		}

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
		if (cfg == null) {
			return;
		}
		try {
			FBranchDefUseAnalyzer.analyze(GraphPool.getInstance(classLoader).getRawCFG(className, methodName));
			Map<Branch, Set<DepVariable>> map = context != null
					&& !context.getMethodSignature().equals(context.getBranchMethodSignature())
					? analyzeIndividualBranch(cfg, branch, interestedNodeFilter)
					: analyzeIndividualMethod(cfg, interestedNodeFilter);
			ObjectConstructionContext.branchInterestedVarsMap.put(cacheKey, map);
			ObjectConstructionContext.recommendedClasses.putAll(analyzeRecommendationClasses(cfg));
		} catch (Exception | StackOverflowError e) {
			// Best-effort, on-demand analysis: never let a failure here abort test generation.
			// Cache an empty result so this method is not re-analyzed (and re-failed) each generation.
			logger.warn("On-demand object-construction analysis failed for {}.{}: {}",
					className, methodName, e.toString());
			ObjectConstructionContext.branchInterestedVarsMap.put(cacheKey, new LinkedHashMap<>());
		}
	}

	@SuppressWarnings("rawtypes")
	public static Map<Integer, List<String>> analyzeRecommendationClasses(ActualControlFlowGraph cfg) {
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();

		Map<Integer, List<String>> recommendationClasses = new LinkedHashMap<Integer, List<String>>();
		for (BytecodeInstruction ins : BytecodeInstructionPool.getInstance(classLoader).
				getInstructionsIn(className, methodName)) {
			/**
			 * handle instanceof instruction, it is essential for test initialization with polymorphism
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
						MethodNode node = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);
						/**
						 * get all the instruction defining the value.
						 */
						for (AbstractInsnNode insNode : srcValue.insns) {
							BytecodeInstruction defIns = DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, insNode);
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
	public static Map<Branch, Set<DepVariable>> analyzeIndividualMethod(ActualControlFlowGraph cfg, IInterestedNodeFilter interestedNodeFilter) {
		Map<Branch, Set<DepVariable>> map = new LinkedHashMap<>();
		long deadlineNanos = analysisDeadlineNanos();

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();

		for (Branch b : BranchPool.getInstance(classLoader).retrieveBranchesInMethod(className, methodName)) {
			Set<DepVariable> inputRootVars = new LinkedHashSet<DepVariable>();
			if (deadlineReached(deadlineNanos)) {
				map.put(b, inputRootVars);
				ObjectConstructionGraphDiagnostics.recordAnalysis(b, inputRootVars);
				continue;
			}
			InterproceduralGraphAnalyzer graphAnalyzer = new InterproceduralGraphAnalyzer(
					b, interestedNodeFilter, deadlineNanos);
			Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
			try {
				if (!b.isInstrumented()) {
					Frame frame = b.getInstruction().getFrame();
					for (int i = 0; i < b.getInstruction().getOperandNum(); i++) {
						int index = frame.getStackSize() - i - 1;
						Value val = frame.getStack(index);

						graphAnalyzer.searchDependantVariables(val, cfg, inputRootVars, visitedIns,
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
					logger.debug("Object-construction analysis budget exhausted in {}.{}",
							className, methodName);
				} else {
					logger.warn("Skipping object-construction analysis for branch {} in {}.{}: {}",
							b, className, methodName, e.toString());
				}
				inputRootVars.clear();
			}

			map.put(b, inputRootVars);
			ObjectConstructionGraphDiagnostics.recordAnalysis(b, inputRootVars);
		}

		return map;
	}

	@SuppressWarnings("rawtypes")
	public static Map<Branch, Set<DepVariable>> analyzeIndividualBranch(ActualControlFlowGraph cfg, Branch branch, IInterestedNodeFilter interestedNodeFilter) {
		Map<Branch, Set<DepVariable>> map = new LinkedHashMap<>();

		InterproceduralGraphAnalyzer graphAnalyzer = new InterproceduralGraphAnalyzer(
				branch, interestedNodeFilter, analysisDeadlineNanos());
		Set<DepVariable> inputRootVars = new LinkedHashSet<DepVariable>();
		Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
		if (!branch.isInstrumented()) {
			Frame frame = branch.getInstruction().getFrame();
			for (int i = 0; i < branch.getInstruction().getOperandNum(); i++) {
				int index = frame.getStackSize() - i - 1;
				Value val = frame.getStack(index);

				graphAnalyzer.searchDependantVariables(val, cfg, inputRootVars, visitedIns,
						Properties.COMPUTATION_GRAPH_METHOD_CALL_DEPTH);
			}
		}

		map.put(branch, inputRootVars);
		ObjectConstructionGraphDiagnostics.recordAnalysis(branch, inputRootVars);

		return map;
	}

	private static long analysisDeadlineNanos() {
		if (!Properties.OBJECT_RULE_WHOLE_CLASS || Properties.OBJECT_RULE_ANALYSIS_TIMEOUT <= 0) {
			return Long.MAX_VALUE;
		}
		return System.nanoTime()
				+ Properties.OBJECT_RULE_ANALYSIS_TIMEOUT * 1_000_000L;
	}

	private static boolean deadlineReached(long deadlineNanos) {
		return deadlineNanos != Long.MAX_VALUE && System.nanoTime() - deadlineNanos >= 0;
	}

	private static boolean isCUT(String className) {
		if (!Properties.TARGET_CLASS.equals("") && !(className.equals(Properties.TARGET_CLASS)
				|| className.startsWith(Properties.TARGET_CLASS + "$"))) {
			return false;
		}
		return true;
	}

	public static Map<Branch, List<ConstructionPath>> checkObjectDifficultPath() {
		Map<Branch, Set<DepVariable>> interestedBranches = ObjectConstructionContext.branchInterestedVarsMap.get(Properties.TARGET_METHOD);

		Map<Branch, List<ConstructionPath>> interestedPaths = new LinkedHashMap<>();
		for (Branch branch : interestedBranches.keySet()) {
			Set<DepVariable> interestedVariables = interestedBranches.get(branch);

			List<ConstructionPath> paths = new ArrayList<ConstructionPath>();
			for (DepVariable interestVar : interestedVariables) {
				for (DepVariable root : interestVar.getRootVars().keySet()) {
					ConstructionPath path = root.findPath(interestVar);
					if (path != null) {
						if (path.hasValidRoot()
								&& !paths.contains(path)) {
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
