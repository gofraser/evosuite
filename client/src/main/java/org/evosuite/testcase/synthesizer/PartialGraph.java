package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapperFactory;
import org.evosuite.testcase.synthesizer.var.OtherVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VariableCodeGenerationUtil;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.setup.TestCluster;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

public class PartialGraph {
	Map<DepVariableWrapper, DepVariableWrapper> allRelevantNodes = new HashMap<DepVariableWrapper, DepVariableWrapper>();
	
	/**
	 * Provides a defensive copy of references to all the nodes in the graph.
	 * @return A defensive copy of references to all the nodes in the graph.
	 */
	public List<DepVariableWrapper> getNodes() {
		return new ArrayList<>(allRelevantNodes.keySet());
		
	}
	
	public int getGraphSize() {
		return allRelevantNodes.keySet().size();
	}

	/**
	 * Whether the canonical construction dependencies contain a cycle.
	 *
	 * <p>The synthesizer resolves parents before children. A cyclic dependency therefore has no
	 * valid construction order; attempting it only produces a partial template from whichever
	 * nodes happen to be visited first. Recursive program behaviour is fine, but it must not remain
	 * a cycle in the pre-call object-construction graph.</p>
	 */
	public boolean hasDependencyCycle() {
		Set<DepVariableWrapper> visited = new HashSet<>();
		Set<DepVariableWrapper> active = new HashSet<>();
		for (DepVariableWrapper node : allRelevantNodes.values()) {
			if (hasDependencyCycle(node, visited, active)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasDependencyCycle(DepVariableWrapper node,
			Set<DepVariableWrapper> visited, Set<DepVariableWrapper> active) {
		if (node == null) {
			return false;
		}
		if (active.contains(node)) {
			return true;
		}
		if (!visited.add(node)) {
			return false;
		}
		active.add(node);
		for (DepVariableWrapper child : node.children) {
			if (hasDependencyCycle(child, visited, active)) {
				return true;
			}
		}
		active.remove(node);
		return false;
	}

	/**
	 * Whether this graph contains state that a test can control before entering the target method.
	 *
	 * <p>Raw data-flow treats every array load and static-field read as state. That is too broad for
	 * object synthesis: an array produced locally by {@code String.split}, for example, exists only
	 * after the target call has begun, and assigning to a separately replayed array in the test
	 * cannot affect it. Likewise, a {@code static final Pattern} is an immutable dependency rather
	 * than construction work. Retain the useful cases: instance fields, writable static fields,
	 * direct array inputs, and observable calls on mutable object inputs (including functional-mock
	 * collaborators).</p>
	 */
	public boolean hasExternallyConstructibleState() {
		for (DepVariableWrapper node : allRelevantNodes.values()) {
			if (node == null || node.var == null) {
				continue;
			}
			if (node.var.isInstaceField() || isWritableStaticField(node.var)) {
				return true;
			}
			if (node.var.isLoadArrayElement() && hasMutableTargetInputAncestor(node)) {
				return true;
			}
			if (node.var.isMethodCall() && node.children.isEmpty()) {
				if (hasMutableTargetInputAncestor(node)) {
					return true;
				}
				// Scalar/String observations are one-way computations. They are useful only when
				// synthesis knows how to invert the complete pipeline into a source input.
				if (hasControllableTargetInputAncestor(node)
						&& SemanticStringInputSynthesizer.supports(this)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Whether graph-to-code synthesis can emit at least one setup action under its runtime policy.
	 *
	 * <p>This is intentionally stricter than {@link #hasExternallyConstructibleState()}. A nested
	 * private field may be constructor-controlled in principle, but once the containing object is
	 * already part of the entry prefix EvoObj refuses to replace it. Likewise, a chain consisting
	 * only of getters/queries describes the state but cannot create it. Classifying those graphs as
	 * eligible merely produces {@code no_synthesis_actions} attempts.</p>
	 */
	public boolean hasPotentialSynthesisAction() {
		if (SemanticStringInputSynthesizer.supports(this)) {
			return true;
		}
		for (DepVariableWrapper node : allRelevantNodes.values()) {
			if (node == null || node.var == null || node.var.getInstruction() == null) {
				continue;
			}
			if (node.var.isLoadArrayElement() && hasMutableTargetInputAncestor(node)) {
				return true;
			}
			if (node.var.isStaticField() && isWritableStaticField(node.var)) {
				return true;
			}
			if (node.var.isInstaceField() && hasUsableFieldAction(node.var)) {
				return true;
			}
			if (node.var.isMethodCall() && hasUsableMethodAction(node)) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasUsableFieldAction(DepVariable variable) {
		if (!(variable.getInstruction().getASMNode() instanceof FieldInsnNode)) {
			return false;
		}
		FieldInsnNode fieldNode =
				(FieldInsnNode) variable.getInstruction().getASMNode();
		try {
			Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(fieldNode.owner.replace('/', '.'));
			Field field = VariableCodeGenerationUtil.searchForField(owner, fieldNode.name);
			if (field == null || Modifier.isStatic(field.getModifiers())) {
				return false;
			}
			if (Modifier.isPublic(field.getModifiers())
					&& !Modifier.isFinal(field.getModifiers())) {
				return true;
			}
			return VariableCodeGenerationUtil.hasUsableSetterForExistingReceiver(
					field, owner.getName());
		} catch (ClassNotFoundException | RuntimeException | LinkageError failure) {
			return false;
		}
	}

	private static boolean hasUsableMethodAction(DepVariableWrapper wrapper) {
		DepVariable variable = wrapper.var;
		if (!(variable.getInstruction().getASMNode() instanceof MethodInsnNode)) {
			return false;
		}
		MethodInsnNode invocation =
				(MethodInsnNode) variable.getInstruction().getASMNode();
		if ("<init>".equals(invocation.name)) {
			return false;
		}
		/*
		 * Graph-to-code can only replay an instance action when operand zero (the receiver) is
		 * represented in the partial graph. A method may retain only its ordinary argument edge
		 * after interprocedural slicing; classifying that shape as mock/setup-capable produces an
		 * attempt that can never emit a call. METSProfile's FileNameMap observation exposed this.
		 */
		try {
			Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(invocation.owner.replace('/', '.'));
			if (invocation.getOpcode() != org.objectweb.asm.Opcodes.INVOKESTATIC
					&& !hasCompatibleReceiverDependency(wrapper, owner)) {
				return false;
			}
			Type[] argumentTypes = Type.getArgumentTypes(invocation.desc);
			Class<?>[] arguments = new Class<?>[argumentTypes.length];
			for (int i = 0; i < argumentTypes.length; i++) {
				arguments[i] = VariableCodeGenerationUtil.getClassForType(argumentTypes[i]);
			}
			Method method;
			try {
				method = owner.getMethod(invocation.name, arguments);
			} catch (NoSuchMethodException notPublic) {
				method = owner.getDeclaredMethod(invocation.name, arguments);
				if (Modifier.isPrivate(method.getModifiers())) {
					return false;
				}
			}
			if (OtherVariableWrapper.canMaterializeObservation(method, owner)) {
				return true;
			}
			return org.evosuite.Properties.OBJECT_RULE_MOCK_SETUP
					&& (owner.isInterface() || Modifier.isAbstract(owner.getModifiers()))
					&& !TestCluster.getInstance().hasGenerator(owner)
					&& FunctionalMockStatement.canBeFunctionalMocked(owner);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
			return false;
		}
	}

	private static boolean hasCompatibleReceiverDependency(
			DepVariableWrapper method, Class<?> owner) {
		for (DepVariableWrapper parent : method.parents) {
			if (parent != null && parent.findRelationPosition(method) == 0) {
				try {
					Class<?> type = producedReferenceType(parent.var);
					if (owner.isAssignableFrom(type)) {
						return true;
					}
				} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
					// An unresolved or incompatible operand cannot be emitted as the receiver.
				}
			}
		}
		return false;
	}

	private static Class<?> producedReferenceType(DepVariable variable)
			throws ClassNotFoundException {
		org.objectweb.asm.tree.AbstractInsnNode instruction =
				variable.getInstruction().getASMNode();
		Type type;
		if (instruction instanceof MethodInsnNode) {
			type = Type.getReturnType(((MethodInsnNode) instruction).desc);
		} else if (instruction instanceof FieldInsnNode) {
			type = Type.getType(((FieldInsnNode) instruction).desc);
		} else {
			String name = variable.getDataType();
			return TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(name);
		}
		if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
			throw new ClassNotFoundException(type.getClassName());
		}
		return TestGenerationContext.getInstance().getClassLoaderForSUT()
				.loadClass(type.getClassName());
	}

	/**
	 * Whether a dependency is rooted in the receiver or an argument of the selected entry.
	 *
	 * <p>All entry arguments are controllable, including scalar/String values. Requiring a
	 * "mutable" reference incorrectly discarded useful parser graphs such as
	 * {@code String -> JsonFactory.createParser -> JsonParser.nextToken}. Conversely, a compiler
	 * enum-switch table in a private callee has no path to the selected caller's arguments and must
	 * not make that graph eligible.</p>
	 */
	private static boolean hasControllableTargetInputAncestor(DepVariableWrapper start) {
		return hasTargetInputAncestor(start, false);
	}

	private static boolean hasMutableTargetInputAncestor(DepVariableWrapper start) {
		return hasTargetInputAncestor(start, true);
	}

	private static boolean hasTargetInputAncestor(DepVariableWrapper start,
			boolean requireMutableParameter) {
		ArrayDeque<DepVariableWrapper> pending = new ArrayDeque<>();
		pending.add(start);
		Set<DepVariableWrapper> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			DepVariableWrapper current = pending.removeFirst();
			if (current == null || !visited.add(current) || current.var == null) {
				continue;
			}
			if (current.var.referenceToThis()
					&& current.var.getInstruction() != null
					&& TargetContext.activeClassName().equals(
							current.var.getInstruction().getClassName())) {
				return true;
			}
			if (current.var.isParameter()
					&& current.var.getInstruction() != null
					&& TargetContext.activeClassName().equals(
							current.var.getInstruction().getClassName())
					&& TargetContext.activeMethodSignature().equals(
							current.var.getInstruction().getMethodName())
					&& (!requireMutableParameter || isMutableInputType(current.var))) {
				return true;
			}
			if (current.var.isInstaceField() || isWritableStaticField(current.var)) {
				return true;
			}
			pending.addAll(current.parents);
		}
		return false;
	}

	private static boolean isMutableInputType(DepVariable parameter) {
		try {
			String method = parameter.getInstruction().getMethodName();
			int descriptor = method.indexOf('(');
			Type[] arguments = Type.getArgumentTypes(method.substring(descriptor));
			int index = parameter.getParamOrder() - 1;
			if (index < 0 || index >= arguments.length) {
				return false;
			}
			Type type = arguments[index];
			if (type.getSort() == Type.ARRAY) {
				return true;
			}
			if (type.getSort() != Type.OBJECT) {
				return false;
			}
			Class<?> input = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(type.getClassName());
			return input != String.class
					&& input != Class.class
					&& !input.isEnum()
					&& !isBoxedPrimitive(input);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static boolean isBoxedPrimitive(Class<?> type) {
		return type == Boolean.class || type == Byte.class || type == Character.class
				|| type == Short.class || type == Integer.class || type == Long.class
				|| type == Float.class || type == Double.class || type == Void.class;
	}

	private static boolean isWritableStaticField(DepVariable variable) {
		if (variable == null || !variable.isStaticField()
				|| variable.getInstruction() == null
				|| !(variable.getInstruction().getASMNode() instanceof FieldInsnNode)) {
			return false;
		}
		FieldInsnNode fieldNode = (FieldInsnNode) variable.getInstruction().getASMNode();
		String resource = fieldNode.owner + ".class";
		try (InputStream stream = TestGenerationContext.getInstance()
				.getClassLoaderForSUT().getResourceAsStream(resource)) {
			if (stream == null) {
				return false;
			}
			ClassNode owner = new ClassNode();
			new ClassReader(stream).accept(owner,
					ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			for (FieldNode field : owner.fields) {
				if (fieldNode.name.equals(field.name)
						&& fieldNode.desc.equals(field.desc)) {
					return Modifier.isStatic(field.access)
							&& !Modifier.isFinal(field.access);
				}
			}
			return false;
		} catch (Throwable ignored) {
			return false;
		}
	}

	/**
	 * Return the descriptors of target-instance fields on which this graph depends.  Whole-class
	 * synthesis uses this before constructing the entry receiver, so it can prefer a constructor
	 * which exposes those collaborators instead of silently hiding them behind a no-arg constructor.
	 */
	public Set<String> getRequiredInstanceFieldDescriptors(String targetClass) {
		if (targetClass == null || targetClass.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> descriptors = new LinkedHashSet<>();
		for (DepVariableWrapper wrapper : allRelevantNodes.values()) {
			DepVariable variable = wrapper.var;
			if (variable == null || variable.getType() != DepVariable.INSTANCE_FIELD
					|| variable.getInstruction() == null
					|| !(variable.getInstruction().getASMNode()
					instanceof org.objectweb.asm.tree.FieldInsnNode)) {
				continue;
			}
			org.objectweb.asm.tree.FieldInsnNode field =
					(org.objectweb.asm.tree.FieldInsnNode) variable.getInstruction().getASMNode();
			if (targetClass.replace('.', '/').equals(field.owner)) {
				descriptors.add(field.desc);
			}
		}
		return Collections.unmodifiableSet(descriptors);
	}
	
	/**
	 * In the original computation graph, multiple node can represent the same variable.
	 * Therefore, we need to merge those nodes when generating the partial graph.
	 * 
	 * @param var
	 * @return
	 */
	public DepVariableWrapper fetchAndMerge(DepVariable var) {
		DepVariableWrapper tempWrapper = DepVariableWrapperFactory.createWrapperInstance(var);
		
		if(var.toString().contains("ALOAD")) {
//			System.currentTimeMillis();
		}
		
		DepVariableWrapper wrapper = allRelevantNodes.get(tempWrapper);
		if(wrapper == null) {
			wrapper = DepVariableWrapperFactory.createWrapperInstance(var);
			allRelevantNodes.put(wrapper, wrapper);
		}
		else{
			for(int i=0; i<wrapper.var.getRelations().length; i++){
				List<DepVariable> list = wrapper.var.getRelations()[i];
				List<DepVariable> list0 = var.getRelations()[i];
				
				if(list0 != null){
					if(list == null) list = new ArrayList<>();
					
					for(DepVariable v: list0){
						if(!list.contains(v)){
							list.add(v);
						}
					}
				}
				
				wrapper.var.getRelations()[i] = list;
			}
			
			for(int i=0; i<wrapper.var.getReverseRelations().length; i++){
				List<DepVariable> list = wrapper.var.getReverseRelations()[i];
				List<DepVariable> list0 = var.getReverseRelations()[i];
				
				if(list0 != null){
					if(list == null) list = new ArrayList<>();
					
					for(DepVariable v: list0){
						if(!list.contains(v)){
							list.add(v);
						}
					}
				}
				
				wrapper.var.getReverseRelations()[i] = list;
			}
			
		}
		
		return wrapper;
	}
	
	public List<DepVariableWrapper> getTopLayer(){
		List<DepVariableWrapper> list = new ArrayList<DepVariableWrapper>();
		for(DepVariableWrapper node: allRelevantNodes.values()) {
			if(node.parents.isEmpty()) {
				list.add(node);
			}
		}
		
		return list;
	}
	
	public List<DepVariableWrapper> getLeaves(){
		List<DepVariableWrapper> list = new ArrayList<DepVariableWrapper>();
		for(DepVariableWrapper node: allRelevantNodes.values()) {
			if(node.children.isEmpty()) {
				list.add(node);
			}
		}
		
		return list;
	}

	private Branch targetBranch;
	
	public void setBranch(Branch b) {
		this.targetBranch = b;
	}
	
	public Branch getBranch() {
		return this.targetBranch;
	}

//	/**
//	 * 
//	 * @param map 
//	 * @param node 
//	 * @param test
//	 */
//	public void taint(Map<DepVariableWrapper, List<VariableReference>> map, 
//			DepVariableWrapper node, TestCase test, VariableReference callerObject) {
//		node.processed = true;
//		
//		List<DepVariableWrapper> leaves = getLeaves();
//		for(DepVariableWrapper leaf: leaves) {
//			if(map.containsKey(leaf)) {
//				leaf.processed = true;
//			}
//			else {
//				DepVariableWrapper taintedParent = leaf.findTaintedParent();
//				if(taintedParent != null && map.containsKey(taintedParent)) {
//					
//					/**
//					 * cannot use this, aaload
//					 */
//					VariableReference callerObj = map.get(taintedParent).get(0);
//					List<VariableReference> varList = leaf.findCorrespondingVariables(test, true, callerObj, map);
//					
//					if(!varList.isEmpty()) {
//						leaf.processed = true;
//					}
//					
//				}
//			}
//		}
//		
//		propagateTaint();
//	}
	
//	private void propagateTaint() {
//		boolean change = true;
//		
//		while(change) {
//			change = false;
//			for(DepVariableWrapper node: allRelevantNodes.keySet()) {
//				if(!node.processed && !node.children.isEmpty()) {
//					boolean isAllChildrenTaint = true;
//					for(DepVariableWrapper child: node.children) {
//						if(!child.processed) {
//							isAllChildrenTaint = false;
//							break;
//						}						
//					}
//					
//					if(isAllChildrenTaint) {
//						node.processed = true;
//						change = true;
//					}
//				}
//			}
//		}
//	}
}
