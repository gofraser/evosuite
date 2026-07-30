package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.MethodUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;

public class DataDependencyUtil {

	private static final Map<SetterCacheKey, LinkedHashMap<Executable, List<ValueSettings>>>
			SETTER_ANALYSIS_CACHE = new LinkedHashMap<>();
	private static final ThreadLocal<Long> SETTER_ANALYSIS_DEADLINE_NANOS = new ThreadLocal<>();
	private static final ThreadLocal<Boolean> SETTER_ANALYSIS_TIMED_OUT = new ThreadLocal<>();
	private static final double MAX_SETTER_ANALYSIS_BUDGET_FRACTION = 0.05;

	private static final class SetterCacheKey {
		private final Field field;
		private final String targetClassName;
		private final int depth;

		private SetterCacheKey(Field field, String targetClassName, int depth) {
			this.field = field;
			this.targetClassName = targetClassName;
			this.depth = depth;
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof SetterCacheKey)) {
				return false;
			}
			SetterCacheKey key = (SetterCacheKey) other;
			return depth == key.depth && field.equals(key.field)
					&& targetClassName.equals(key.targetClassName);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(field, targetClassName, depth);
		}
	}

	public static synchronized void resetSetterAnalysisCache() {
		SETTER_ANALYSIS_CACHE.clear();
	}

	/**
	 * Return the concrete case value when the selected switch goal depends on this exact field.
	 * The interested-variable analysis has already performed the interprocedural slice, so using
	 * it here also handles fields read after a private accessor (for example, an array element
	 * returned by {@code getXsqlvar(index)}).
	 */
	public static Integer switchCaseValueForField(Branch branch, Field field) {
		if (branch == null || field == null || !branch.isSwitchCaseBranch()
				|| !branch.isActualCase()) {
			return null;
		}
		for (Map<Branch, Set<DepVariable>> branches
				: ObjectConstructionContext.branchInterestedVarsMap.values()) {
			Set<DepVariable> variables = branches.get(branch);
			if (variables == null) {
				continue;
			}
			for (DepVariable variable : variables) {
				if (variable == null || variable.getType() != DepVariable.INSTANCE_FIELD
						|| variable.getInstruction() == null
						|| !(variable.getInstruction().getASMNode() instanceof FieldInsnNode)) {
					continue;
				}
				FieldInsnNode node =
						(FieldInsnNode) variable.getInstruction().getASMNode();
				if (node.owner.equals(field.getDeclaringClass().getName().replace('.', '/'))
						&& node.name.equals(field.getName())
						&& node.desc.equals(org.objectweb.asm.Type.getDescriptor(field.getType()))) {
					return branch.getTargetCaseValue();
				}
			}
		}
		return null;
	}

	/**
	 * Return the target-method parameter whose identity is queried against the supplied container
	 * field, or {@code -1} when no direct membership precondition exists.
	 *
	 * <p>This recovers the semantically important relation in
	 * {@code field.contains(argument)} even when the ordinary interested-variable slice retains
	 * the field and argument as independent roots.</p>
	 */
	public static int membershipTargetParameter(Field field) {
		if (field == null || TargetContext.activeClassName().isEmpty()
				|| TargetContext.activeMethodSignature().isEmpty()) {
			return -1;
		}
		ClassLoader loader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		List<BytecodeInstruction> instructions = BytecodeInstructionPool.getInstance(loader)
				.getInstructionsIn(TargetContext.activeClassName(),
						TargetContext.activeMethodSignature());
		if (instructions == null) {
			GraphPool.getInstance(loader).registerClass(TargetContext.activeClassName());
			instructions = BytecodeInstructionPool.getInstance(loader)
					.getInstructionsIn(TargetContext.activeClassName(),
							TargetContext.activeMethodSignature());
		}
		if (instructions == null) {
			return -1;
		}
		for (BytecodeInstruction instruction : instructions) {
			if (instruction == null || !instruction.isMethodCall()
					|| !(instruction.getASMNode() instanceof MethodInsnNode)) {
				continue;
			}
			MethodInsnNode call = (MethodInsnNode) instruction.getASMNode();
			boolean ordinaryMembership = ("contains".equals(call.name)
					|| "containsKey".equals(call.name)
					|| "containsValue".equals(call.name))
					&& instruction.getCalledMethodsArgumentCount() == 1;
			boolean transformedMembership =
					"org/evosuite/instrumentation/testability/ContainerHelper"
							.equals(call.owner)
					&& ("collectionContains".equals(call.name)
						|| "mapContainsKey".equals(call.name)
						|| "mapContainsValue".equals(call.name))
					&& instruction.getCalledMethodsArgumentCount() == 2;
			if (!ordinaryMembership && !transformedMembership) {
				continue;
			}
			BytecodeInstruction receiver = transformedMembership
					? instruction.getSourceOfStackInstruction(1)
					: invocationReceiver(instruction);
			if (!receiverReferencesField(receiver, field,
					new HashSet<BytecodeInstruction>())) {
				continue;
			}
			BytecodeInstruction argument = instruction.getSourceOfStackInstruction(0);
			if (argument != null && argument.isParameter()) {
				return argument.getParameterPosition();
			}
		}
		return -1;
	}

	static synchronized int setterAnalysisCacheSize() {
		return SETTER_ANALYSIS_CACHE.size();
	}
	/**
	 * Output is the valid parameter position of most top method call
	 * 
	 * @param setterInstruction
	 * @param className
	 * @param methodName
	 * @param callList
	 * @return
	 */
	public static Set<Integer> checkValidParameterPositions(BytecodeInstruction setterInstruction, String className,
			String methodName, List<BytecodeInstruction> callList) {
		Collections.reverse(callList);

		// only check for current method
		if (callList.isEmpty()) {
			Set<BytecodeInstruction> paramInstructions = checkCurrentParamInstructions(setterInstruction,
					new HashSet<>());
			Set<Integer> paramPositions = checkSetterParamPositions(paramInstructions);
			return paramPositions;
		}

		Set<BytecodeInstruction> paramInstructions = analyzingCascadingCall(
				new HashSet<>(Arrays.asList(setterInstruction)), callList, 0, new HashSet<>());
		Set<Integer> paramPositions = checkSetterParamPositions(paramInstructions);
		return paramPositions;
	}
	
	
	private static Set<BytecodeInstruction> analyzingCascadingCall(Set<BytecodeInstruction> insns,
			List<BytecodeInstruction> callList, int index, Set<BytecodeInstruction> topParamInsns) {
		BytecodeInstruction call = callList.get(index);
		for (BytecodeInstruction ins : insns) {
			Set<BytecodeInstruction> paramInsns = checkCurrentParamInstructions(ins, new HashSet<>());
			if (paramInsns.isEmpty()) {
				return paramInsns;
			}
			boolean isValidParameter = checkParamValidation(call, paramInsns, ins);
			if (!isValidParameter) {
				return new HashSet<>();
			}
			Set<BytecodeInstruction> newParamInsns = searchForNewParameterInstruction(call, paramInsns);
			if (index == callList.size() - 1) {
				topParamInsns.addAll(newParamInsns);
			} else {
				analyzingCascadingCall(newParamInsns, callList, index + 1, topParamInsns);
			}
		}
		return topParamInsns;
	}
	
	/**
	 * search for new load instruction when analyzing next layer of method
	 * invocation
	 * 
	 * @param call
	 * @param paramPos
	 * @return
	 */
	private static Set<BytecodeInstruction> searchForNewParameterInstruction(BytecodeInstruction call,
			Set<BytecodeInstruction> paramInsns) {
		Set<BytecodeInstruction> newParamInsns = new HashSet<>();
		Set<Integer> paramPosSet = checkSetterParamPositions(paramInsns);
		Set<BytecodeInstruction> newParams = new HashSet<>();
		for (Integer pos : paramPosSet) {
			if (call.getASMNode().getOpcode() == Opcodes.INVOKESTATIC) {
				BytecodeInstruction defIns = call.getSourceOfStackInstruction(call.getOperandNum() - pos - 1);
				newParams = checkCurrentParamInstructions(defIns, new HashSet<>());
			} else {
				BytecodeInstruction defIns = call.getSourceOfStackInstruction(call.getOperandNum() - pos - 2);
				newParams = checkCurrentParamInstructions(defIns, new HashSet<>());
			}
			if (newParams != null) {
				newParamInsns.addAll(newParams);
			}
		}
		return newParamInsns;
	}

	/**
	 * return localVariableUse instruction for parameter
	 * 
	 * @param descIns
	 * @param paramInstructionSet
	 * @return
	 */
	private static Set<BytecodeInstruction> checkCurrentParamInstructions(BytecodeInstruction descIns,
			Set<BytecodeInstruction> paramInstructionSet) {
		if (descIns.isLocalVariableUse()) {
			if (descIns.isParameter()) {
				// position here similar to list index (i.e., starts from 0)
//				int pos = checkSetterParamPos(defIns);
//				paramInstructionSet.add(new Integer(pos));
				paramInstructionSet.add(descIns);
			}
		}
		for (int i = 0; i < descIns.getOperandNum(); i++) {
			BytecodeInstruction defIns = descIns.getSourceOfStackInstruction(i);
			if (defIns == null) {
				return paramInstructionSet;
			}
			if (defIns.isLocalVariableUse()) {
				if (defIns.isParameter()) {
					// position here similar to list index (i.e., starts from 0)
//					int pos = checkSetterParamPos(defIns);
//					paramInstructionSet.add(new Integer(pos));
					paramInstructionSet.add(defIns);
				}
			}
			checkCurrentParamInstructions(defIns, paramInstructionSet);
		}
		return paramInstructionSet;
	}
	
	private static boolean checkParamValidation(BytecodeInstruction call, Set<BytecodeInstruction> paramInsns,
			BytecodeInstruction ins) {
		if (call.getCalledMethodsArgumentCount() == 0) {
			return false;
		}
		if (!call.getCalledMethod().equals(ins.getMethodName())){
			return false;
		}
		
		String class1 = call.getCalledMethodsClass();
		String class2 = ins.getClassName();
		
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		Class<?> c1 = null;
		Class<?> c2 = null;
		
		try {
			 c1 = classLoader.loadClass(class1);
			 c2 = classLoader.loadClass(class2);
		} catch (ClassNotFoundException e) {
//			e.printStackTrace();
		}
		
		if(c1 != null && c2 != null) {
			if(!c2.isAssignableFrom(c1)) {
				return false;
			}
		}
		else {
			if(!class1.equals(class2)) {
				return false;
			}
		}
		

		Set<Integer> paramPositions = checkSetterParamPositions(paramInsns);
		for (Integer pos : paramPositions) {
			if (pos > call.getCalledMethodsArgumentCount() - 1) {
				return false;
			}
		}
		return true;
	}
	
	private static Set<Integer> checkSetterParamPositions(Set<BytecodeInstruction> paramInsns) {
		Set<Integer> paramPositions = new HashSet<>();
		for (BytecodeInstruction paramInstruction : paramInsns) {
			int parameterPosition = paramInstruction.getParameterPosition();
			if(parameterPosition != -1){
				paramPositions.add(parameterPosition);				
			}
		}
		return paramPositions;
	}
	
	
	/**
	 * check whether a method can return the field.
	 * @param method
	 * @param field
	 * @return
	 */
	public static boolean isFieldGetter(Method method, Field field) {
		int opcode = Modifier.isStatic(field.getModifiers()) ? Opcodes.GETSTATIC : Opcodes.GETFIELD;

		if (!method.getReturnType().getCanonicalName().equals(field.getType().getCanonicalName())) {
			return false;
		}

		ActualControlFlowGraph cfg = GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
				.getActualCFG(method.getDeclaringClass().getCanonicalName(),
						method.getName() + MethodUtil.getSignature(method));
		if (cfg == null) {
			return false;
		}
		
		return checkValidGetter(field, opcode, cfg);
	}
	
	private static boolean checkValidGetter(Field field, int opcode, ActualControlFlowGraph cfg) {
		for (BytecodeInstruction exit : cfg.getExitPoints()) {
			if (exit.isReturn()) {
				BytecodeInstruction insn = exit.getSourceOfStackInstruction(0);
				if (insn.getASMNode().getOpcode() == opcode) {
					String fieldName = getFieldName(insn);
					if (fieldName.equals(field.getName())) {
						return true;
					}
				} else if (insn.isMethodCall()) {
					// Upstream has no BytecodeInstruction.getCalledActualCFG(); build/lookup the callee's
					// ActualControlFlowGraph through MethodUtil (which is idempotent).
					ActualControlFlowGraph calledCfg =
							MethodUtil.registerMethod(insn.getCalledMethodsClass(), insn.getCalledMethod());
					if (calledCfg != null) {
						return checkValidGetter(field, opcode, calledCfg);
					}
				}
			}
		}
		
		return false;
	}
	
	public static String getFieldName(BytecodeInstruction ins) {
		if (ins.getASMNode().getType() == AbstractInsnNode.FIELD_INSN) {
			FieldInsnNode fNode = (FieldInsnNode) ins.getASMNode();
			return fNode.name;
		}

		return null;
	}
	
//	public static List<ValueSettings> findSetterInfo(Field field, Class<?> targetClass, 
//			Executable m, String signature) {
//		/**
//		 * initialization
//		 */
//		
//		/**
//		 * get the field setting map
//		 */
//		List<ValueSettings> fieldValueSettings = new ArrayList<>();
//		DataDependencyUtil.analyzeFieldSetter(targetClass.getCanonicalName(), signature,
//				field, Properties.FIELD_SETTER_SEARCH_DEPTH, new ArrayList<>(), fieldValueSettings);
//		
////		System.currentTimeMillis();
////		
////		Set<Integer> releventPrams = new HashSet<>();
////		for (Entry<BytecodeInstruction, List<BytecodeInstruction>> entry : fieldSetterMap.entrySet()) {
////			BytecodeInstruction setterIns = entry.getKey();
////			List<BytecodeInstruction> callList = entry.getValue();
////			Set<Integer> validParamPos = DataDependencyUtil.checkValidParameterPositions(setterIns, 
////					targetClass.getCanonicalName(), signature, callList);
////			releventPrams.addAll(validParamPos);
////		}
////		
////		if(!fieldValueSettings.isEmpty()){
////			fieldSettingMethods.add(m);
////			difficultyList.add(fieldSetterMap);
////			validParams.add(releventPrams);
////		}
//	}
	
	@SuppressWarnings("rawtypes")
	public static LinkedHashMap<Executable, List<ValueSettings>> searchForPotentialSettersInClass(Field field, String targetClassName) throws ClassNotFoundException {
		SetterCacheKey cacheKey =
				new SetterCacheKey(field, targetClassName, Properties.FIELD_SETTER_SEARCH_DEPTH);
		synchronized (DataDependencyUtil.class) {
			LinkedHashMap<Executable, List<ValueSettings>> cached =
					SETTER_ANALYSIS_CACHE.get(cacheKey);
			if (cached != null) {
				return cached;
			}
		}
		LinkedHashMap<Executable, List<ValueSettings>> map = new LinkedHashMap<>();
		Class<?> targetClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
				.loadClass(targetClassName);
		long timeoutMillis = effectiveSetterAnalysisTimeoutMillis();
		if (timeoutMillis > 0) {
			SETTER_ANALYSIS_DEADLINE_NANOS.set(
					System.nanoTime() + timeoutMillis * 1_000_000L);
		}
		SETTER_ANALYSIS_TIMED_OUT.set(false);

		try {
			/*
			 * EvoSuite emits tests in the CUT package. Package-private/protected declared methods
			 * are therefore legitimate construction APIs (NameState's addTransition/addSubRule
			 * are a representative case), but Class#getMethods() silently omits them.
			 */
			Set<Method> methodSet = new LinkedHashSet<>();
			Collections.addAll(methodSet, targetClass.getMethods());
			for (Class<?> current = targetClass; current != null;
					current = current.getSuperclass()) {
				Collections.addAll(methodSet, current.getDeclaredMethods());
			}
			List<Method> methods = new ArrayList<>(methodSet);
			methods.sort((left, right) ->
					left.toGenericString().compareTo(right.toGenericString()));

			/*
			 * First perform a shallow pass. A real direct initializer is both more useful and much
			 * cheaper to discover than recursively expanding every call in generated parsers.
			 * Returning it immediately avoids spending seconds rediscovering transitive parser
			 * methods which cannot run until that initializer has already executed.
			 */
			for (Method method : methods) {
				if (!isSetterCandidate(method) || setterAnalysisTimedOut()) {
					continue;
				}
				String signature = method.getName() + ReflectionUtil.getSignature(method);
				List<ValueSettings> settings = new ArrayList<>();
				DataDependencyUtil.analyzeFieldSetter(targetClass.getCanonicalName(), signature,
						field, 0, new ArrayList<>(), settings);
				if (isDirectFieldAssignmentSetting(field, settings)) {
					map.put(method, settings);
				}
			}
			if (map.isEmpty() && !setterAnalysisTimedOut()) {
				for (Method method : methods) {
					if (!isSetterCandidate(method) || setterAnalysisTimedOut()) {
						continue;
					}
					String signature =
							method.getName() + ReflectionUtil.getSignature(method);
					List<ValueSettings> settings = new ArrayList<>();
					DataDependencyUtil.analyzeFieldSetter(targetClass.getCanonicalName(),
							signature, field, Properties.FIELD_SETTER_SEARCH_DEPTH,
							new ArrayList<>(), settings);
					if (!settings.isEmpty()) {
						map.put(method, settings);
					}
				}

				Set<Constructor<?>> constructorSet = new LinkedHashSet<>();
				Collections.addAll(constructorSet, targetClass.getConstructors());
				Collections.addAll(constructorSet, targetClass.getDeclaredConstructors());
				List<Constructor<?>> constructors = new ArrayList<>(constructorSet);
				constructors.sort((left, right) ->
						left.toGenericString().compareTo(right.toGenericString()));
				for (Constructor constructor : constructors) {
					if (setterAnalysisTimedOut()) {
						break;
					}
					if (Modifier.isPrivate(constructor.getModifiers())
							|| constructor.isSynthetic()) {
						continue;
					}
					String signature =
							"<init>" + ReflectionUtil.getSignature(constructor);
					List<ValueSettings> settings = new ArrayList<>();
					DataDependencyUtil.analyzeFieldSetter(targetClass.getCanonicalName(),
							signature, field, Properties.FIELD_SETTER_SEARCH_DEPTH,
							new ArrayList<>(), settings);
					if (!settings.isEmpty()) {
						map.put(constructor, settings);
					}
				}
			}
			if (setterAnalysisTimedOut()) {
				// An incomplete candidate set is order-dependent and unsafe to synthesize from.
				map.clear();
			}
		} finally {
			SETTER_ANALYSIS_DEADLINE_NANOS.remove();
			SETTER_ANALYSIS_TIMED_OUT.remove();
		}

		synchronized (DataDependencyUtil.class) {
			SETTER_ANALYSIS_CACHE.put(cacheKey, map);
		}
		return map;
	}

	private static boolean isSetterCandidate(Method method) {
		return !Modifier.isPrivate(method.getModifiers())
				&& !Modifier.isAbstract(method.getModifiers())
				&& !method.isBridge() && !method.isSynthetic();
	}

	private static long effectiveSetterAnalysisTimeoutMillis() {
		long configured = Properties.OBJECT_RULE_ANALYSIS_TIMEOUT;
		if (configured <= 0) {
			return 0;
		}
		if (Properties.STOPPING_CONDITION == Properties.StoppingCondition.MAXTIME
				&& Properties.SEARCH_BUDGET > 0) {
			long budgetShare = Math.max(1L,
					(long) (Properties.SEARCH_BUDGET * 1000L
							* MAX_SETTER_ANALYSIS_BUDGET_FRACTION));
			return Math.min(configured, budgetShare);
		}
		return configured;
	}

	private static boolean setterAnalysisTimedOut() {
		if (Boolean.TRUE.equals(SETTER_ANALYSIS_TIMED_OUT.get())) {
			return true;
		}
		Long deadline = SETTER_ANALYSIS_DEADLINE_NANOS.get();
		if (deadline != null && System.nanoTime() >= deadline) {
			SETTER_ANALYSIS_TIMED_OUT.set(true);
			return true;
		}
		return false;
	}

	/** Whether a candidate directly assigns the field rather than reaching it through a call. */
	public static boolean isDirectFieldAssignmentSetting(
			Field field, List<ValueSettings> settings) {
		if (field == null || settings == null) {
			return false;
		}
		for (ValueSettings setting : settings) {
			if (setting == null || setting.valueSettingInstruction == null
					|| !setting.callChain.isEmpty()
					|| !(setting.valueSettingInstruction.getASMNode() instanceof FieldInsnNode)) {
				continue;
			}
			FieldInsnNode assignment =
					(FieldInsnNode) setting.valueSettingInstruction.getASMNode();
			int opcode = assignment.getOpcode();
			if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)
					&& assignment.name.equals(field.getName())
					&& assignment.owner.replace('/', '.')
							.equals(field.getDeclaringClass().getName())
					&& !assignsLiteralNull(assignment)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Cleanup methods frequently write {@code null} directly after performing their real work.
	 * Such a write is not an initializer and must not outrank a method that installs usable state.
	 */
	private static boolean assignsLiteralNull(FieldInsnNode assignment) {
		AbstractInsnNode previous = assignment.getPrevious();
		while (previous != null && previous.getOpcode() < 0) {
			previous = previous.getPrevious();
		}
		return previous != null && previous.getOpcode() == Opcodes.ACONST_NULL;
	}
	
	public static void analyzeFieldSetter(String className, String methodName,
			Field field, int depth, List<BytecodeInstruction> cascadingCallRelations,
			List<ValueSettings> setterList) {
		analyzeFieldSetter(className, methodName, className, methodName, field, depth, cascadingCallRelations, setterList);
	}
	
	/**
	 * 
	 * return a map from a field-setting instruction to a set of calls like <m1(), m2(), ..., mk()>,
	 * in which, 
	 * m1() is the method used in test;
	 * mk() is the method setting the field.
	 *  
	 * 
	 * @param className
	 * @param methodName
	 * @param field
	 * @param depth
	 * @param cascadingCallRelations
	 * @param setterList
	 * @return
	 */
	private static void analyzeFieldSetter(String targetClass, String targetSig, String className, String methodName,
			Field field, int depth, List<BytecodeInstruction> cascadingCallRelations,
			List<ValueSettings> setterList) {
		if (setterAnalysisTimedOut()) {
			return;
		}
		List<BytecodeInstruction> insList = null;

//		MockFramework.disable();
		try{
			ClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
			insList = BytecodeInstructionPool.getInstance(classLoader).getInstructionsIn(className, methodName);
			
			if(insList == null) {
				GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).registerClass(className);
//				RawControlFlowGraph cfg = GraphPool.getInstance(classLoader).getRawCFG(className, methodName);
				insList = BytecodeInstructionPool.getInstance(classLoader).getInstructionsIn(className, methodName);
				if(insList == null) {
					Class<?> c = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
					Class<?> superClass = c.getSuperclass();
					
					while(superClass != null) {
						String superClassName = superClass.getCanonicalName();
//						GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).registerClass(superClassName);
						insList = BytecodeInstructionPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
								.getInstructionsIn(superClassName, methodName);	
						
						if(insList != null) {
							break;
						}
						else {
							superClass = superClass.getSuperclass();
						}
					}
				}
				
			}
		}
		catch(Exception e){
			e.printStackTrace();
		}

		int fieldWriteOpcode =
				Modifier.isStatic(field.getModifiers()) ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
		String fieldOwner = field.getDeclaringClass().getName().replace('.', '/');
		if (insList != null) {
			for (BytecodeInstruction ins : insList) {
				if (setterAnalysisTimedOut()) {
					return;
				}
				if(isContainerType(field.getType())) {
					if(isCallElementModification(ins, field, cascadingCallRelations)) {
						ValueSettings settings = new ValueSettings(ins, cascadingCallRelations, targetClass, targetSig);
						setterList.add(settings);
//						setterMap.put(ins, new ArrayList<>(cascadingCallRelations));
					}
				}
				
				
				AbstractInsnNode node = ins.getASMNode();
				if (node instanceof FieldInsnNode && node.getOpcode() == fieldWriteOpcode) {
					FieldInsnNode fieldInstruction = (FieldInsnNode) node;
					if (fieldInstruction.name.equals(field.getName())
							&& fieldInstruction.owner.equals(fieldOwner)) {
						ValueSettings settings = new ValueSettings(ins,
								cascadingCallRelations, targetClass, targetSig);
						setterList.add(settings);
//						setterList.put(ins, new ArrayList<>(cascadingCallRelations));
					}
				}
				else if (node instanceof MethodInsnNode) {
					if (depth > 0) {
						MethodInsnNode mNode = (MethodInsnNode) node;

						String calledClass = mNode.owner;
						calledClass = calledClass.replace("/", ".");
						/**
						 * FIXME: we only analyze the callee method in the same class, but we
						 * need to consider the invocation in other class.
						 */
						if (calledClass.equals(className)) {
							String calledMethodName = mNode.name + mNode.desc;

							String confirmedCalledClass = confirmClassNameInParentClass(calledClass, mNode);
							if (calledMethodName != null) {
								cascadingCallRelations.add(ins);
								analyzeFieldSetter(targetClass, targetSig, confirmedCalledClass, calledMethodName, field, depth - 1,
										cascadingCallRelations, setterList);
							}
						} else {
						}
					} 
				}
			}
		}
		cascadingCallRelations.clear();

//		return setterList;

	}
	
	/**
	 * check the call to elements.
	 * @param ins
	 * @return
	 */
	private static boolean isCallElementModification(BytecodeInstruction ins, Field field,
			List<BytecodeInstruction> cascadingCallRelations) {
		// TODO aaron
//		java.lang.System.out.println(ins.getMethodName());
//		java.lang.System.out.println(ins.getASMNodeString());
		
		if(!ins.isMethodCall()) return false;
		
		BytecodeInstruction receiver = invocationReceiver(ins);
		boolean useCorrectField = receiverReferencesField(receiver, field,
				new HashSet<BytecodeInstruction>());
		
		if(!useCorrectField && !receiverComesFromFieldThroughCallChain(
				receiver, cascadingCallRelations, field)) {
			return false;
		} 
		
		// Check if instruction invokes another method
		AbstractInsnNode asmNode = ins.getASMNode();
		if (asmNode instanceof MethodInsnNode
				&& (asmNode.getOpcode() == Opcodes.INVOKEVIRTUAL
				|| asmNode.getOpcode() == Opcodes.INVOKEINTERFACE)) {
			MethodInsnNode methodNode = (MethodInsnNode) asmNode; 
			
			// Check if invoked method is a method from Collection class
			Class<?> methodClassType;
			try {
				String typeString = methodNode.owner.replace('/', '.');
				methodClassType = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(typeString);
//				methodClassType = Class.forName();
				if (isContainerType(methodClassType)) {
					return isContainerMutationName(methodClassType, methodNode.name);
				}
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		}
		
		return false;
	}

	private static boolean receiverComesFromFieldThroughCallChain(
			BytecodeInstruction receiver, List<BytecodeInstruction> callChain, Field field) {
		if (receiver == null || !receiver.isLocalVariableUse()
				|| callChain == null || callChain.isEmpty()) {
			return false;
		}
		int parameterPosition = receiver.getParameterPosition();
		if (parameterPosition < 0) {
			return false;
		}
		for (int index = callChain.size() - 1; index >= 0; index--) {
			BytecodeInstruction caller = callChain.get(index);
			int argumentCount = caller.getCalledMethodsArgumentCount();
			if (parameterPosition >= argumentCount || caller.getFrame() == null
					|| caller.getFrame().getStackSize() < argumentCount) {
				return false;
			}
			BytecodeInstruction argument = caller.getSourceOfStackInstruction(
					argumentCount - 1 - parameterPosition);
			if (receiverReferencesField(argument, field,
					new HashSet<BytecodeInstruction>())) {
				return true;
			}
			if (argument == null || !argument.isLocalVariableUse()) {
				return false;
			}
			parameterPosition = argument.getParameterPosition();
			if (parameterPosition < 0) {
				return false;
			}
		}
		return false;
	}

	private static BytecodeInstruction invocationReceiver(BytecodeInstruction invocation) {
		if (invocation == null || !invocation.isMethodCall()) {
			return null;
		}
		int argumentCount = invocation.getCalledMethodsArgumentCount();
		if (invocation.getFrame() == null
				|| invocation.getFrame().getStackSize() <= argumentCount) {
			return null;
		}
		return invocation.getSourceOfStackInstruction(argumentCount);
	}

	private static boolean receiverReferencesField(BytecodeInstruction instruction,
			Field field, Set<BytecodeInstruction> visited) {
		if (instruction == null || field == null || !visited.add(instruction)) {
			return false;
		}
		if (instruction.getASMNode() instanceof FieldInsnNode) {
			FieldInsnNode fieldInstruction = (FieldInsnNode) instruction.getASMNode();
			return fieldInstruction.name.equals(field.getName())
					&& fieldInstruction.owner.replace('/', '.')
							.equals(field.getDeclaringClass().getName());
		}
		if (instruction.isLocalVariableUse()
				&& instruction.getParameterPosition() >= 0) {
			/*
			 * The receiver is an external container supplied to this method. Its broad def-use
			 * slice may also contain values read from the field (for example
			 * output.add(fieldValue)), but mutating output does not mutate the field. A parameter
			 * is accepted only when the cascading call chain maps that parameter back to the
			 * field.
			 */
			return false;
		}
		if (instruction.isLocalVariableUse()
				&& instruction.getASMNode() instanceof VarInsnNode
				&& instruction.getFrame() != null) {
			int slot = ((VarInsnNode) instruction.getASMNode()).var;
			if (slot >= 0 && slot < instruction.getFrame().getLocals()) {
				Value localValue = instruction.getFrame().getLocal(slot);
				if (localValue instanceof SourceValue) {
					for (AbstractInsnNode source : ((SourceValue) localValue).insns) {
						BytecodeInstruction definition = BytecodeInstructionPool
								.getInstance(TestGenerationContext.getInstance()
										.getClassLoaderForSUT())
								.getInstruction(instruction.getClassName(),
										instruction.getMethodName(), source);
						if (receiverReferencesField(definition, field, visited)) {
							return true;
						}
					}
				}
			}
		}
		if (instruction.isMethodCall()) {
			// Do not mistake data observed by a call for the identity of its receiver/result.
			return false;
		}
		for (BytecodeInstruction operand : instruction.getOperands()) {
			if (receiverReferencesField(operand, field, visited)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isContainerMutationSetting(Field field, List<ValueSettings> settings) {
		if (field == null || settings == null || !isContainerType(field.getType())) {
			return false;
		}
		for (ValueSettings setting : settings) {
			if (setting == null || setting.valueSettingInstruction == null
					|| !(setting.valueSettingInstruction.getASMNode() instanceof MethodInsnNode)) {
				continue;
			}
			MethodInsnNode call =
					(MethodInsnNode) setting.valueSettingInstruction.getASMNode();
			try {
				Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
						.loadClass(call.owner.replace('/', '.'));
				if (isContainerType(owner) && isContainerMutationName(owner, call.name)) {
					return true;
				}
			} catch (ClassNotFoundException ignored) {
				// An unresolved call cannot be established as a container mutation.
			}
		}
		return false;
	}

	/**
	 * Whether the candidate only reaches a container field through an operation that removes
	 * contents. Such an action cannot establish a non-empty state on a freshly constructed empty
	 * receiver and must not compete equally with {@code add}/{@code put} during construction.
	 */
	public static boolean isDestructiveContainerMutationSetting(
			Field field, List<ValueSettings> settings) {
		if (field == null || settings == null || !isContainerType(field.getType())) {
			return false;
		}
		for (ValueSettings setting : settings) {
			if (setting == null || setting.valueSettingInstruction == null
					|| !(setting.valueSettingInstruction.getASMNode() instanceof MethodInsnNode)) {
				continue;
			}
			MethodInsnNode call =
					(MethodInsnNode) setting.valueSettingInstruction.getASMNode();
			try {
				Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
						.loadClass(call.owner.replace('/', '.'));
				if (isContainerType(owner) && isDestructiveContainerMutationName(owner, call.name)) {
					return true;
				}
			} catch (ClassNotFoundException ignored) {
				// An unresolved call cannot be classified safely.
			}
		}
		return false;
	}

	private static boolean isContainerMutationName(Class<?> owner, String name) {
		if (Map.class.isAssignableFrom(owner)) {
			return Arrays.asList("clear", "compute", "computeIfAbsent", "computeIfPresent",
					"merge", "put", "putAll", "putIfAbsent", "remove", "replace",
					"replaceAll").contains(name);
		}
		return Arrays.asList("add", "addAll", "addElement", "addFirst", "addLast",
				"clear", "ensureCapacity", "offer", "offerFirst", "offerLast", "poll",
				"pollFirst", "pollLast", "pop", "push", "remove", "removeAll",
				"removeElement", "removeElementAt", "removeFirst",
				"removeFirstOccurrence", "removeIf", "removeLast",
				"removeLastOccurrence", "removeRange", "replaceAll", "retainAll", "set",
				"setElementAt", "setSize", "sort", "trimToSize").contains(name);
	}

	private static boolean isDestructiveContainerMutationName(Class<?> owner, String name) {
		if (Map.class.isAssignableFrom(owner)) {
			return "clear".equals(name) || "remove".equals(name);
		}
		return "clear".equals(name) || "pop".equals(name) || "poll".equals(name)
				|| "pollFirst".equals(name) || "pollLast".equals(name)
				|| "remove".equals(name) || "removeAll".equals(name)
				|| "removeElement".equals(name) || "removeElementAt".equals(name)
				|| "removeFirst".equals(name) || "removeFirstOccurrence".equals(name)
				|| "removeIf".equals(name) || "removeLast".equals(name)
				|| "removeLastOccurrence".equals(name) || "removeRange".equals(name)
				|| "retainAll".equals(name);
	}
	
	
	/**
	 * all the java classes from java.util.List/Collection
	 * @param type
	 * @return
	 */
	private static boolean isContainerType(Class<?> type) {
		return Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
	}

	private static String confirmClassNameInParentClass(String calledClass, MethodInsnNode mNode) {
		ClassNode classNode = DependencyAnalysis.getClassNode(calledClass);
		List<String> superClassList = DependencyAnalysis.getInheritanceTree().getOrderedSuperclasses(calledClass);
		for (String superClass : superClassList) {
			ClassNode parentClassNode = DependencyAnalysis.getClassNode(superClass);
			if (!containMethod(classNode, mNode) && containMethod(parentClassNode, mNode)) {
				return superClass;
			}
		}


		return calledClass;
	}
	
	private static boolean containMethod(ClassNode classNode, MethodInsnNode mNode) {

//		if(!mNode.owner.equals(classNode.name)) {
//			return false;
//		}

		for (MethodNode methodNode : classNode.methods) {
			String methodNodeName = methodNode.name + methodNode.desc;
			String methodInsName = mNode.name + mNode.desc;
			if (methodNodeName.equals(methodInsName)) {
				return true;
			}
		}

		return false;
	}
}
