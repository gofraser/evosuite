package org.evosuite.graphs.interprocedural;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeAnalyzer;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.graphs.interprocedural.interestednode.IInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.graphs.interprocedural.var.DepVariableFactory;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The backward slicer of the object construction graph analysis: one instance per branch. It walks
 * data flow (operand-stack def-use via ASM {@link SourceValue} frames and local/field def-use),
 * control dependencies, and interprocedurally into called methods (depth-bounded), collecting the
 * state-variable leaf nodes selected by the {@link IInterestedNodeFilter}. See
 *
 * <p>Ported from the {@code evosuite-plus-plus} fork with cleanups applied (debug leftovers
 * and hardcoded name/id traps removed).
 */
public class InterproceduralGraphAnalyzer {

	private static final Logger logger = LoggerFactory.getLogger(InterproceduralGraphAnalyzer.class);

	private Map<BytecodeInstruction, DepVariable> insPool = new LinkedHashMap<>();
	private IInterestedNodeFilter interestedNodeFilter;

	public Branch branchInProcess;


	public InterproceduralGraphAnalyzer(Branch b, IInterestedNodeFilter interestedNodeFilter) {
		this.branchInProcess = b;
		this.interestedNodeFilter = interestedNodeFilter;
	}

	private Map<String, Set<DepVariable>> analyzeReturnValueFromMethod(BytecodeInstruction instruction, int callGraphDepth) {

		if (callGraphDepth <= 0) {
			return new LinkedHashMap<>();
		}

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();

		String className = instruction.getCalledMethodsClass();
		String methodName = instruction.getCalledMethod();

		try {
			Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
			if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
				return new LinkedHashMap<>();
			}
		} catch (ClassNotFoundException e1) {
			return new LinkedHashMap<>();
		}

		ActualControlFlowGraph calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);

		if (calledCfg == null) {
			Properties.ALWAYS_REGISTER_BRANCH = true;
			GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).
				registerClass(className);
			calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);


			if (calledCfg == null) {
				MethodNode innerNode = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);
				BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
				try {
					bytecodeAnalyzer.analyze(classLoader, className, methodName, innerNode);
				} catch (Exception e) {
					/**
					 * the cfg (e.g., jdk/library class) is out of our consideration
					 */
					Properties.ALWAYS_REGISTER_BRANCH = false;
					return new LinkedHashMap<>();
				}

				bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
			}

			Properties.ALWAYS_REGISTER_BRANCH = false;
		}

		if (calledCfg == null) {
			return new LinkedHashMap<>();
		}

		GraphPool.getInstance(classLoader).alwaysRegisterActualCFG(calledCfg);
		boolean canBeAnalyzed = FBranchDefUseAnalyzer.analyze(
				GraphPool.getInstance(classLoader).getRawCFG(className, methodName));
		if (!canBeAnalyzed) {
			return new LinkedHashMap<>();
		}

		Set<DepVariable> allDepVars = new LinkedHashSet<DepVariable>();
		Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
		for (BytecodeInstruction exit : calledCfg.getExitPoints()) {
			List<BytecodeInstruction> returnSources = exit.getSourceOfStackInstructionList(0);
			if (returnSources == null) {
				// Exit point with an empty operand stack (e.g. a void return) has no
				// source-of-stack instructions to trace the return value back from.
				continue;
			}
			for (BytecodeInstruction returnInstruction : returnSources) {
				searchDefDependentVariables(returnInstruction, calledCfg, allDepVars, visitedIns, callGraphDepth - 1);
			}
		}

		Map<String, Set<DepVariable>> map = new LinkedHashMap<String, Set<DepVariable>>();
		map.put(className, allDepVars);
		return map;
	}



	/**
	 * value is written by {@code defIns}
	 */
	public DepVariable parseVariable(BytecodeInstruction defIns) {
		DepVariable var = insPool.get(defIns);
		if (var == null) {
			var = DepVariableFactory.createVariableInstance(defIns);
			insPool.put(defIns, var);
		}

		return var;
	}

	/**
	 *
	 * This method collects all the variable depended by {@code value} and put them into {@code allDepVars}
	 * {@code depVars}
	 *
	 * This search algorithm should take a walk through data flows until we find static field, instance field, or parameter.
	 * We would like to take the following usage into account:
	 * a.m1(x1, x2).m2(x3).f
	 * in which a can be either a static field, instance field, or parameter.
	 *
	 * @param value
	 * @param cfg
	 * @param allLeafDepVars
	 * @param visitedIns
	 */
	public void searchDependantVariables(Value value, ActualControlFlowGraph cfg, Set<DepVariable> allLeafDepVars,
			Set<BytecodeInstruction> visitedIns, int callGraphDepth) {

		if (!(value instanceof SourceValue)) {
			return;
		}

		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode node = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);

		SourceValue srcValue = (SourceValue) value;
		/**
		 * get all the instruction defining the value.
		 */
		for (AbstractInsnNode insNode : srcValue.insns) {
			BytecodeInstruction defIns = DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, insNode);
			if (defIns != null) {
				searchDefDependentVariables(defIns, cfg, allLeafDepVars, visitedIns, callGraphDepth);
			}
		}
	}

	private void searchDefDependentVariables(BytecodeInstruction defIns, ActualControlFlowGraph cfg, Set<DepVariable> allLeafDepVars,
			Set<BytecodeInstruction> visitedIns, int callGraphDepth) {
		if (defIns == null) {
			// Reached with a null defining instruction (e.g. a stack source that did not resolve to
			// a BytecodeInstruction); nothing to analyze. Guard here so it never propagates into
			// parseVariable/buildInputOutputForInstruction.
			return;
		}
		if (visitedIns.contains(defIns)) {
			return;
		}
		visitedIns.add(defIns);

		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode node = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);

		DepVariable outputVar = parseVariable(defIns);
		/**
		 * the variable is computed by values on stack
		 */
		List<DepVariable>[] inputVarArray = buildInputOutputForInstruction(defIns, node,
				outputVar, cfg, allLeafDepVars, visitedIns, callGraphDepth);

		/**
		 * if defIns is a method call, we need to explore more potential variables (fields) inside the method.
		 */
		if (defIns.isMethodCall() || defIns.isConstructorInvocation()) {

			if (!shouldStop(defIns)) {
				String recommnedClass = outputVar.getRecommendedImplementation();
				recommnedClass = exploreInterproceduralInstruction(allLeafDepVars, defIns, inputVarArray, recommnedClass, callGraphDepth);
				outputVar.setRecommendedImplementation(recommnedClass);
			}

		}

		/**
		 *  handle load/get static, need to put the variable into the return list
		 */
		if (interestedNodeFilter.isInterested(outputVar)) {
			allLeafDepVars.add(outputVar);
		}

		/**
		 * the variable is computed by local variables
		 */
		if (defIns.isLocalVariableUse() || defIns.isFieldUse()) {
			// keep traversing through the local/field definitions reaching this use
			DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
			defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);

			List<BytecodeInstruction> defs = DefUseAnalyzer.getDefFromUse(defIns);
			for (BytecodeInstruction def : defs) {
				buildInputOutputForInstruction(def, node,
						outputVar, cfg, allLeafDepVars, visitedIns, callGraphDepth);
			}
		}

		/**
		 * handle control flow, note that there is no need to build data flow
		 */
		try {
			for (ControlDependency control : defIns.getControlDependencies()) {
				BytecodeInstruction controlIns = control.getBranch().getInstruction();
				int operandNum = controlIns.getOperandNum();
				for (int i = 0; i < operandNum; i++) {
					Frame frame = controlIns.getFrame();
					int index = frame.getStackSize() - operandNum + i;
					Value val = frame.getStack(index);
					searchDependantVariables(val, cfg, allLeafDepVars, visitedIns, callGraphDepth);
				}
			}
		}
		catch (Exception e) {
			logger.debug("error when analyzing control dependencies of " + defIns, e);
		}
	}

	/**
	 * some method could stop, e.g., the call in evosuite
	 * @param defIns
	 * @return
	 */
	private boolean shouldStop(BytecodeInstruction defIns) {

		String className = defIns.getCalledMethodsClass();
		if (className.contains("evosuite")) {
			return true;
		}

		return false;
	}

	/**
	 * precondition: defIns is a method call
	 *
	 * @param allLeafDepVars
	 * @param defIns
	 * @param inputVarArray
	 */
	private String exploreInterproceduralInstruction(Set<DepVariable> allLeafDepVars, BytecodeInstruction defIns,
			List<DepVariable>[] inputVarArray, String recommendedClass, int callGraphDepth) {

		Map<String, Set<DepVariable>> relatedVariableMap = analyzeReturnValueFromMethod(defIns, callGraphDepth);
		if (relatedVariableMap.isEmpty()) {
			return null;
		}

		String className = relatedVariableMap.keySet().iterator().next();
		Set<DepVariable> relatedVariables = relatedVariableMap.get(className);

		for (DepVariable var : relatedVariables) {
			for (DepVariable rootVar : var.getRootVars().keySet()) {
				if (rootVar.getType() == DepVariable.PARAMETER) {
					if (!(rootVar.getInstruction().getClassName().
							equals(defIns.getCalledCFG().getClassName()) &&
							rootVar.getInstruction().getMethodName().equals(defIns.getCalledCFG().getMethodName()))) {
						continue;
					}
				}

				if (rootVar.getType() == DepVariable.THIS) {
					if (!(rootVar.getInstruction().getClassName().
							equals(defIns.getCalledCFG().getClassName()))) {
						continue;
					}
				}


				if (var.getType() == DepVariable.STATIC_FIELD) {
					allLeafDepVars.add(var);
				}
				else {
					ArrayList<ConstructionPath> paths = var.getRootVars().get(rootVar);
					ConstructionPath path = paths.get(0);

					if (path != null) {
						if (path.getPath().size() < 2) {
							continue;
						}

						DepVariable secondVar = path.getPath().get(path.size() - 2);
						if (rootVar.getType() == DepVariable.PARAMETER) {
							int index = -1;
							if (rootVar.getInstruction().getCalledCFG() == null) continue;
							if (rootVar.getInstruction().getCalledCFG().isStaticMethod()) {
								index = rootVar.getParamOrder() - 1;
							}
							else {
								index = rootVar.getParamOrder();
							}

							List<DepVariable> params = inputVarArray[index];
							if (params == null) continue;

							for (DepVariable param : params) {
								param.buildRelation(secondVar, path.getPosition().get(path.size() - 2));
							}
						}
						else if (rootVar.getType() == DepVariable.THIS) {
							List<DepVariable> objectVars = inputVarArray[0];
							if (objectVars == null) continue;
							for (DepVariable objectVar : objectVars) {
								objectVar.buildRelation(secondVar, path.getPosition().get(path.size() - 2));
							}
						}
					}
				}
			}

			allLeafDepVars.add(var);
		}

		return className;
	}

	@SuppressWarnings({ "unchecked" })
	private List<DepVariable>[] buildInputOutputForInstruction(BytecodeInstruction defInstruction, MethodNode node,
			DepVariable outputVar, ActualControlFlowGraph cfg, Set<DepVariable> allDepVars, Set<BytecodeInstruction> visitedIns, int callGraphDepth) {
		List<DepVariable>[] intputVarArray = new ArrayList[DepVariable.OPERAND_NUM_LIMIT];
		if (defInstruction == null) {
			// A null defining instruction (e.g. a def/use lookup or ASM conversion that did not
			// resolve to a BytecodeInstruction) has no operands; return the empty input array
			// rather than dereferencing it. Callers already tolerate null operand slots.
			return intputVarArray;
		}
		int operandNum = defInstruction.getOperandNum();
		for (int i = 0; i < operandNum; i++) {
			List<DepVariable> inputVars = new ArrayList<DepVariable>();

			Frame frame = defInstruction.getFrame();
			int index = frame.getStackSize() - operandNum + i;
			Value val = frame.getStack(index);

			SourceValue inputVal = (SourceValue) val;
			for (AbstractInsnNode newDefInsNode : inputVal.insns) {
				BytecodeInstruction newDefIns = DefUseAnalyzer.convert2BytecodeInstruction(cfg, node, newDefInsNode);
				if (newDefIns == null) {
					// A stack-source instruction that does not map back to a BytecodeInstruction in
					// this CFG (e.g. defined outside the analyzed method). Skip it, as
					// searchDependantVariables does, rather than passing null into the factory.
					continue;
				}
				DepVariable inputVar = parseVariable(newDefIns);
				inputVar.buildRelation(outputVar, i);

				searchDependantVariables(val, cfg, allDepVars, visitedIns, callGraphDepth);

				inputVars.add(inputVar);
			}

			intputVarArray[i] = inputVars;
		}

		return intputVarArray;
	}

}
