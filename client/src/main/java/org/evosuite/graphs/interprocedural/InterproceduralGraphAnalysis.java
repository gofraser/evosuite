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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Entry point of the object construction graph analysis (plan §3.1). {@link #initializeDataflow}
 * iterates the branches of the target method and fills {@link #branchInterestedVarsMap} with the
 * state variables each branch depends on, and {@link #recommendedClasses} with the concrete
 * subtypes discovered via {@code INSTANCEOF} checks.
 *
 * <p>Ported from the {@code evosuite-plus-plus} fork with the §7/§E7 cleanups applied.
 */
public class InterproceduralGraphAnalysis {

	/**
	 * Clear the analysis' mutable static state. Delegates to the {@link ObjectConstructionContext}
	 * holder (plan §E7); prefer wiring {@link ObjectConstructionContext#reset()} into
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
	 * Whole-class mode (plan E1): analyze the object-construction graph of the branch's own
	 * containing method on demand and cache it in {@link ObjectConstructionContext#branchInterestedVarsMap},
	 * instead of the eager {@link #initializeDataflow} loop over {@code TARGET_METHOD}. Idempotent
	 * per method.
	 */
	public static synchronized void analyzeBranchOnDemand(Branch branch, IInterestedNodeFilter interestedNodeFilter) {
		if (branch == null || branch.getInstruction() == null) {
			return;
		}
		String className = branch.getInstruction().getClassName();
		String methodName = branch.getInstruction().getMethodName();
		if (className == null || methodName == null
				|| ObjectConstructionContext.branchInterestedVarsMap.containsKey(methodName)) {
			return;
		}

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
		if (cfg == null) {
			return;
		}
		FBranchDefUseAnalyzer.analyze(GraphPool.getInstance(classLoader).getRawCFG(className, methodName));
		Map<Branch, Set<DepVariable>> map = analyzeIndividualMethod(cfg, interestedNodeFilter);
		ObjectConstructionContext.branchInterestedVarsMap.put(methodName, map);
		ObjectConstructionContext.recommendedClasses.putAll(analyzeRecommendationClasses(cfg));
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

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();

		for (Branch b : BranchPool.getInstance(classLoader).retrieveBranchesInMethod(className, methodName)) {
			InterproceduralGraphAnalyzer graphAnalyzer = new InterproceduralGraphAnalyzer(b, interestedNodeFilter);
			Set<DepVariable> inputRootVars = new LinkedHashSet<DepVariable>();
			Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
			if (!b.isInstrumented()) {
				Frame frame = b.getInstruction().getFrame();
				for (int i = 0; i < b.getInstruction().getOperandNum(); i++) {
					int index = frame.getStackSize() - i - 1;
					Value val = frame.getStack(index);

					graphAnalyzer.searchDependantVariables(val, cfg, inputRootVars, visitedIns,
							Properties.COMPUTATION_GRAPH_METHOD_CALL_DEPTH);
				}
			}

			map.put(b, inputRootVars);
		}

		return map;
	}

	@SuppressWarnings("rawtypes")
	public static Map<Branch, Set<DepVariable>> analyzeIndividualBranch(ActualControlFlowGraph cfg, Branch branch, IInterestedNodeFilter interestedNodeFilter) {
		Map<Branch, Set<DepVariable>> map = new LinkedHashMap<>();

		InterproceduralGraphAnalyzer graphAnalyzer = new InterproceduralGraphAnalyzer(branch, interestedNodeFilter);
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

		return map;
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
