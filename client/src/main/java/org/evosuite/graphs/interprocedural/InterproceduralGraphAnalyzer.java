package org.evosuite.graphs.interprocedural;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeAnalyzer;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.graphs.interprocedural.interestednode.IInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.graphs.interprocedural.var.DepVariableFactory;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.ConcreteClassAnalyzer;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The backward slicer of the object construction graph analysis: one instance per branch. It walks
 * data flow (operand-stack def-use via ASM {@link SourceValue} frames and local/field def-use),
 * control dependencies, and interprocedurally into called methods (depth-bounded), collecting the
 * state-variable leaf nodes selected by the {@link IInterestedNodeFilter}. Calls whose bytecode
 * cannot be analyzed (for example interface and abstract-method calls) are retained as behavioral
 * terminal nodes so their receiver/argument construction paths are not lost. See
 *
 * <p>Ported from the {@code evosuite-plus-plus} fork with cleanups applied (debug leftovers
 * and hardcoded name/id traps removed).
 */
public class InterproceduralGraphAnalyzer {

	private static final Logger logger = LoggerFactory.getLogger(InterproceduralGraphAnalyzer.class);

	private Map<BytecodeInstruction, DepVariable> insPool = new LinkedHashMap<>();
	private IInterestedNodeFilter interestedNodeFilter;
	private final long analysisDeadlineNanos;

	public Branch branchInProcess;


	public InterproceduralGraphAnalyzer(Branch b, IInterestedNodeFilter interestedNodeFilter) {
		this(b, interestedNodeFilter, Long.MAX_VALUE);
	}

	InterproceduralGraphAnalyzer(Branch b, IInterestedNodeFilter interestedNodeFilter,
			long analysisDeadlineNanos) {
		this.branchInProcess = b;
		this.interestedNodeFilter = interestedNodeFilter;
		this.analysisDeadlineNanos = analysisDeadlineNanos;
	}

	static final class AnalysisTimeoutException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private void checkAnalysisBudget() {
		if (analysisDeadlineNanos != Long.MAX_VALUE
				&& System.nanoTime() - analysisDeadlineNanos >= 0) {
			throw new AnalysisTimeoutException();
		}
	}

	private Map<String, Set<DepVariable>> analyzeReturnValueFromMethod(BytecodeInstruction instruction, int callGraphDepth) {
		checkAnalysisBudget();

		if (callGraphDepth <= 0) {
			return null;
		}

		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();

		String invokedClassName = instruction.getCalledMethodsClass();
		if (isPlatformImplementationClass(invokedClassName)) {
			return null;
		}
		String className = resolveAnalyzableClass(invokedClassName,
				instruction.getCalledMethod());
		String methodName = instruction.getCalledMethod();

		try {
			Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
			if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
				return null;
			}
		} catch (ClassNotFoundException e1) {
			return null;
		}

		ActualControlFlowGraph calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);

		if (calledCfg == null) {
			GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).
				registerClass(className);
			calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);


			if (calledCfg == null) {
				MethodNode innerNode = DefUseAnalyzer.getMethodNode(classLoader, className, methodName);
				if (innerNode == null) {
					logger.debug("Unable to analyze {}#{}: method bytecode is unavailable",
							className, methodName);
					return null;
				}
				BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
				try {
					bytecodeAnalyzer.analyze(classLoader, className, methodName, innerNode);
				} catch (AnalyzerException e) {
					logger.debug("Unable to analyze CFG for {}#{}", className, methodName, e);
					return null;
				}

				bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
				calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
			}
		}

		if (calledCfg == null) {
			return null;
		}

		GraphPool.getInstance(classLoader).alwaysRegisterActualCFG(calledCfg);
		boolean canBeAnalyzed = FBranchDefUseAnalyzer.analyze(
				GraphPool.getInstance(classLoader).getRawCFG(className, methodName));
		if (!canBeAnalyzed) {
			return null;
		}

		Set<DepVariable> allDepVars = new LinkedHashSet<DepVariable>();
		Set<BytecodeInstruction> visitedIns = new LinkedHashSet<BytecodeInstruction>();
		RawControlFlowGraph calledRawCfg = GraphPool.getInstance(classLoader)
				.getRawCFG(className, methodName);
		for (BytecodeInstruction exit : calledCfg.getExitPoints()) {
			// Slice from the return instruction itself, not only from the values on its operand
			// stack. Boolean getters are commonly compiled as a conditional producing ICONST_0/1;
			// starting at those constants alone can omit the controlling field (for example
			// Player.isDead() -> Player.alive). The return node retains both the operand flow and
			// the CFG control dependencies needed to recover that state.
			searchDefDependentVariables(exit, calledCfg, allDepVars, visitedIns, callGraphDepth - 1);
			// Methods outside the instrumented CUT do not always have complete CDG annotations on
			// their return constants. Conservatively slice branch operands on paths leading to this
			// return as well. This recovers the state behind javac's boolean materialization pattern
			// (GETFIELD; IFxx; ICONST_0/1; IRETURN) while remaining bounded to the callee and depth.
			if (calledRawCfg != null) {
				for (BytecodeInstruction predecessor : calledRawCfg.getPreviousInstructionsInMethod(exit)) {
					if (!predecessor.isActualBranch() || predecessor.getFrame() == null) {
						continue;
					}
					int operandCount = predecessor.getOperandNum();
					for (int operand = 0; operand < operandCount; operand++) {
						int stackIndex = predecessor.getFrame().getStackSize() - operand - 1;
						if (stackIndex >= 0) {
							searchDependantVariables(predecessor.getFrame().getStack(stackIndex), calledCfg,
									allDepVars, visitedIns, callGraphDepth - 1);
						}
					}
				}
			}
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

	/** Resolve project-owned interface dispatches without descending into platform internals. */
	private String resolveAnalyzableClass(String invokedClassName, String methodSignature) {
		String declaringClass = resolveDeclaringClass(invokedClassName, methodSignature);
		try {
			ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
			Class<?> invokedClass = loader.loadClass(declaringClass);
			if (!invokedClass.isInterface() && !Modifier.isAbstract(invokedClass.getModifiers())) {
				return declaringClass;
			}
			if (Type.getArgumentTypes(methodSignature).length != 0) {
				// Argument-taking collaborator operations (for example a bean-description
				// factory lookup) are behavioral boundaries. Following their implementation
				// tends to model caches and reflection infrastructure instead of caller state.
				return declaringClass;
			}
			List<Class<?>> implementations = new ArrayList<>(ConcreteClassAnalyzer.getInstance()
					.getConcreteClasses(invokedClass, DependencyAnalysis.getInheritanceTree()));
			implementations.sort((left, right) -> left.getName().compareTo(right.getName()));
			for (Class<?> implementation : implementations) {
				if (implementation == null || implementation.isInterface()
						|| Modifier.isAbstract(implementation.getModifiers())
						|| isPlatformImplementationClass(implementation.getName())) {
					continue;
				}
				String candidate = resolveDeclaringClass(implementation.getName(), methodSignature);
				if (!candidate.equals(invokedClassName)
						&& !isPlatformImplementationClass(candidate)) {
					return candidate;
				}
			}
		} catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
			// Best effort: the unresolved call remains a behavioral terminal dependency.
		}
		return declaringClass;
	}

	/** Resolve invokevirtual owners such as Player.isBoss() to the class that actually declares
	 * the bytecode (Party). CFGs and method nodes are keyed by that declaring class. */
	private String resolveDeclaringClass(String invokedClassName, String methodSignature) {
		try {
			Class<?> current = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(invokedClassName);
			while (current != null) {
				for (Method method : current.getDeclaredMethods()) {
					if ((method.getName() + Type.getMethodDescriptor(method)).equals(methodSignature)) {
						return method.getDeclaringClass().getName();
					}
				}
				current = current.getSuperclass();
			}
		} catch (SecurityException | LinkageError | ClassNotFoundException ignored) {
			// Fall back to the bytecode owner; interprocedural analysis remains best-effort.
		}
		return invokedClassName;
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
		checkAnalysisBudget();

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
		checkAnalysisBudget();
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
				if (recommnedClass == null && defIns.isMethodCall()
						&& !defIns.isConstructorInvocation()) {
					// The branch depends on this call result, but there is no concrete bytecode to
					// slice (typically INVOKEINTERFACE or an abstract/library method). Preserve the
					// call as a terminal dependency. Its operand relations retain the receiver and
					// arguments, allowing synthesis and functional mocks to reproduce the behavior.
					allLeafDepVars.add(outputVar);
				}
				if (isAbstractDispatch(defIns)) {
					// Keep the observable interface invocation as well as the state discovered in
					// its concrete project implementation. Synthesis needs both pieces.
					allLeafDepVars.add(outputVar);
				}
			} else if (defIns.isMethodCall() && !defIns.isConstructorInvocation()
					&& shouldRetainBehavioralTerminal(defIns)) {
				// Keep the observable library call and its input relations, but never turn the
				// implementation fields behind it into construction requirements.
				allLeafDepVars.add(outputVar);
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
			if (defIns.isLocalVariableUse() && defIns.getASMNode() instanceof VarInsnNode) {
				/*
				 * SourceInterpreter's frame is the authoritative reaching-definition set for a
				 * local load. In particular, javac reuses one synthetic local slot for consecutive
				 * enhanced-for iterators; consulting every earlier store with that slot joins
				 * unrelated loops and produces cumulative object graphs.
				 */
				List<BytecodeInstruction> precedingDefinitions =
						precedingLocalDefinitions(defIns);
				boolean syntheticReusedSlot = precedingDefinitions.size() > 1
						&& !hasScopedLocalVariable(defIns, cfg);
				boolean followedFrameDefinition = false;
				if (!syntheticReusedSlot) {
					followedFrameDefinition = followFrameLocalDefinitions(
							defIns, cfg, allLeafDepVars, visitedIns, callGraphDepth);
				}
				if (syntheticReusedSlot) {
					/*
					 * SourceInterpreter can retain the previous iterator store through a loop
					 * back-edge. Hidden locals have no LocalVariableTable scope with which to
					 * separate these live ranges, so use the lexically closest store.
					 */
					searchDefDependentVariables(
							precedingDefinitions.get(precedingDefinitions.size() - 1), cfg,
							allLeafDepVars, visitedIns, callGraphDepth);
				} else if (precedingDefinitions.size() <= 1) {
					/*
					 * Preserve the ordinary def-use walk for source-level locals whose slot has
					 * not been reused. This is needed to retain distinct interprocedural paths.
					 * When several earlier stores share the slot, however, def-use analysis may
					 * join unrelated compiler-generated live ranges, so the frame source above is
					 * authoritative.
					 */
					DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
					defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);
					List<BytecodeInstruction> definitions = DefUseAnalyzer.getDefFromUse(defIns);
					for (BytecodeInstruction definition : definitions) {
						buildInputOutputForInstruction(definition, node, outputVar, cfg,
								allLeafDepVars, visitedIns, callGraphDepth);
					}
					if (!followedFrameDefinition && precedingDefinitions.size() == 1) {
						searchDefDependentVariables(precedingDefinitions.get(0), cfg,
								allLeafDepVars, visitedIns, callGraphDepth);
					}
				} else if (!followedFrameDefinition) {
					// A missing/imprecise frame still gets the closest reaching store only.
					searchDefDependentVariables(
							precedingDefinitions.get(precedingDefinitions.size() - 1), cfg,
							allLeafDepVars, visitedIns, callGraphDepth);
				}
			} else {
				// Field uses are not subject to local-slot killing; retain the ordinary def-use walk.
				DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
				defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);

				List<BytecodeInstruction> defs = DefUseAnalyzer.getDefFromUse(defIns);
				for (BytecodeInstruction def : defs) {
					buildInputOutputForInstruction(def, node,
							outputVar, cfg, allLeafDepVars, visitedIns, callGraphDepth);
				}
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
		catch (RuntimeException e) {
			logger.debug("error when analyzing control dependencies of " + defIns, e);
		}
	}

	private boolean followFrameLocalDefinitions(BytecodeInstruction use,
			ActualControlFlowGraph cfg, Set<DepVariable> allLeafDepVars,
			Set<BytecodeInstruction> visitedIns, int callGraphDepth) {
		if (use.getFrame() == null || !(use.getASMNode() instanceof VarInsnNode)) {
			return false;
		}
		int slot = ((VarInsnNode) use.getASMNode()).var;
		if (slot < 0 || slot >= use.getFrame().getLocals()) {
			return false;
		}
		Value value = use.getFrame().getLocal(slot);
		if (!(value instanceof SourceValue)) {
			return false;
		}
		InstrumentingClassLoader classLoader =
				TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode method = DefUseAnalyzer.getMethodNode(
				classLoader, cfg.getClassName(), cfg.getMethodName());
		LocalVariableNode activeScope = activeLocalVariable(use, method, slot);
		int scopeLowerBound = activeScope == null
				? -1 : precedingScopeEnd(method, activeScope, slot);
		int scopeUpperBound = activeScope == null
				? Integer.MAX_VALUE : method.instructions.indexOf(activeScope.end);
		boolean followed = false;
		for (AbstractInsnNode source : ((SourceValue) value).insns) {
			BytecodeInstruction definition =
					DefUseAnalyzer.convert2BytecodeInstruction(cfg, method, source);
			if (definition == null || definition == use) {
				continue;
			}
			int definitionIndex = method.instructions.indexOf(definition.getASMNode());
			if (definitionIndex < scopeLowerBound || definitionIndex >= scopeUpperBound) {
				continue;
			}
			followed = true;
			searchDefDependentVariables(definition, cfg, allLeafDepVars, visitedIns,
					callGraphDepth);
		}
		return followed;
	}

	private boolean hasScopedLocalVariable(BytecodeInstruction use,
			ActualControlFlowGraph cfg) {
		if (!(use.getASMNode() instanceof VarInsnNode)) {
			return false;
		}
		InstrumentingClassLoader classLoader =
				TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode method = DefUseAnalyzer.getMethodNode(
				classLoader, cfg.getClassName(), cfg.getMethodName());
		int slot = ((VarInsnNode) use.getASMNode()).var;
		return activeLocalVariable(use, method, slot) != null;
	}

	private LocalVariableNode activeLocalVariable(BytecodeInstruction use,
			MethodNode method, int slot) {
		if (method == null || method.localVariables == null) {
			return null;
		}
		int useIndex = method.instructions.indexOf(use.getASMNode());
		for (LocalVariableNode local : method.localVariables) {
			if (local.index != slot) {
				continue;
			}
			int startIndex = method.instructions.indexOf(local.start);
			int endIndex = method.instructions.indexOf(local.end);
			if (startIndex <= useIndex && useIndex < endIndex) {
				return local;
			}
		}
		return null;
	}

	private int precedingScopeEnd(MethodNode method, LocalVariableNode activeScope,
			int slot) {
		int activeStart = method.instructions.indexOf(activeScope.start);
		int lowerBound = -1;
		for (LocalVariableNode local : method.localVariables) {
			if (local == activeScope || local.index != slot) {
				continue;
			}
			int endIndex = method.instructions.indexOf(local.end);
			if (endIndex <= activeStart && endIndex > lowerBound) {
				lowerBound = endIndex;
			}
		}
		return lowerBound;
	}

	private List<BytecodeInstruction> precedingLocalDefinitions(BytecodeInstruction use) {
		List<BytecodeInstruction> definitions = new ArrayList<>();
		if (!(use.getASMNode() instanceof VarInsnNode)) {
			return definitions;
		}
		InstrumentingClassLoader classLoader =
				TestGenerationContext.getInstance().getClassLoaderForSUT();
		RawControlFlowGraph rawCfg = GraphPool.getInstance(classLoader)
				.getRawCFG(use.getClassName(), use.getMethodName());
		if (rawCfg == null) {
			return definitions;
		}
		int slot = ((VarInsnNode) use.getASMNode()).var;
		for (BytecodeInstruction candidate : rawCfg.vertexSet()) {
			if (candidate == use || !candidate.isLocalVariableDefinition()
					|| !(candidate.getASMNode() instanceof VarInsnNode)
					|| ((VarInsnNode) candidate.getASMNode()).var != slot
					|| candidate.getInstructionId() >= use.getInstructionId()) {
				continue;
			}
			definitions.add(candidate);
		}
		definitions.sort((left, right) ->
				Integer.compare(left.getInstructionId(), right.getInstructionId()));
		return definitions;
	}

	private boolean shouldRetainBehavioralTerminal(BytecodeInstruction instruction) {
		String owner = instruction.getCalledMethodsClass();
		String method = instruction.getCalledMethod();
		if (owner == null || method == null) {
			return false;
		}
		if (isDirectScalarValueType(owner)) {
			// Calls on values which EvoSuite can create directly are deterministic operations on
			// those values, not independently configurable collaborator behavior. Retaining calls
			// such as String.equals() as opaque leaves adds an OTHER node even though the receiver
			// field or parameter already captures all state that synthesis can influence.
			return false;
		}
		if (owner.startsWith("org.evosuite.instrumentation.testability.")) {
			// Testability transformations replace scalar operations with EvoSuite helper calls
			// (for example String.equals -> StringHelper.StringEquals). These are bytecode
			// plumbing, not observable collaborator operations.
			return false;
		}
		if (owner.startsWith("java.lang.reflect.") || owner.startsWith("jdk.")) {
			return false;
		}
		if (owner.equals("java.lang.Class")) {
			return !(method.startsWith("getDeclared") || method.startsWith("getConstructor")
					|| method.startsWith("newInstance"));
		}
		return !owner.equals("java.lang.reflect.Array") || !method.startsWith("newInstance");
	}

	private boolean isDirectScalarValueType(String className) {
		if (className.equals("java.lang.String")
				|| className.equals("java.lang.Boolean")
				|| className.equals("java.lang.Byte")
				|| className.equals("java.lang.Character")
				|| className.equals("java.lang.Short")
				|| className.equals("java.lang.Integer")
				|| className.equals("java.lang.Long")
				|| className.equals("java.lang.Float")
				|| className.equals("java.lang.Double")
				|| className.equals("java.lang.Void")
				|| className.equals("java.lang.Enum")) {
			return true;
		}
		try {
			Class<?> type = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(className);
			return type.isEnum();
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}

	/**
	 * some method could stop, e.g., the call in evosuite
	 * @param defIns
	 * @return
	 */
	private boolean shouldStop(BytecodeInstruction defIns) {

		String className = defIns.getCalledMethodsClass();
		if (branchInProcess != null && branchInProcess.getInstruction() != null
				&& className.equals(branchInProcess.getInstruction().getClassName())
				&& defIns.getCalledMethod().equals(
						branchInProcess.getInstruction().getMethodName())) {
			// A recursive return to the branch-owning method closes the state cycle. Re-entering
			// it only duplicates the same slice at every call-graph depth.
			return true;
		}
		if (className.contains("evosuite")
				|| ObjectConstructionBoundary.isExternalImplementationClass(className)) {
			return true;
		}

		return false;
	}

	static boolean isPlatformImplementationClass(String className) {
		return className != null && (className.startsWith("java.")
				|| className.startsWith("javax.")
				|| className.startsWith("jdk.")
				|| className.startsWith("sun.")
				|| className.startsWith("com.sun."));
	}

	private boolean isAbstractDispatch(BytecodeInstruction instruction) {
		try {
			Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(instruction.getCalledMethodsClass());
			return owner.isInterface() || Modifier.isAbstract(owner.getModifiers());
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
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
		checkAnalysisBudget();

		Map<String, Set<DepVariable>> relatedVariableMap = analyzeReturnValueFromMethod(defIns, callGraphDepth);
		if (relatedVariableMap == null) {
			return null;
		}

		String className = relatedVariableMap.keySet().iterator().next();
		Set<DepVariable> relatedVariables = relatedVariableMap.get(className);

		for (DepVariable var : relatedVariables) {
			for (DepVariable rootVar : var.getRootVars().keySet()) {
				if (rootVar.getType() == DepVariable.PARAMETER) {
					if (!(rootVar.getInstruction().getClassName().
							equals(className) &&
							rootVar.getInstruction().getMethodName().equals(defIns.getCalledMethod()))) {
						continue;
					}
				}

				if (rootVar.getType() == DepVariable.THIS) {
					if (!(rootVar.getInstruction().getClassName().
							equals(className))) {
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
							ActualControlFlowGraph declaringCfg = GraphPool.getInstance(
									TestGenerationContext.getInstance().getClassLoaderForSUT())
									.getActualCFG(className, defIns.getCalledMethod());
							if (declaringCfg == null) continue;
							if (declaringCfg.isStaticMethod()) {
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
		checkAnalysisBudget();
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
