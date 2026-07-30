package org.evosuite.testcase.synthesizer.var;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.GenerationContext;
import org.evosuite.testcase.VariableResolutionConfig;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.synthesizer.VariableInTest;
import org.evosuite.testcase.synthesizer.ObjectConstructionActionTrace;
import org.evosuite.testcase.synthesizer.improvedsynth.Operation;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public class ParameterVariableWrapper extends DepVariableWrapper {

	protected ParameterVariableWrapper(DepVariable var) {
		super(var);
	}

	@Override
	public VarRelevance generateOrFindStatement(TestCase test, boolean isLeaf, VariableInTest variable,
			Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue, Operation recommendation) {
		List<VariableReference> list = new ArrayList<>();
		VariableReference var = generateOrFind(test, isLeaf, variable.callerObject, map, b, allowNullValue);
		if(var != null) {
			list.add(var);
		}
		
		return new VarRelevance(list, list);
	}
	
	
	/**
	 * parameter statement is supposed to be used only in the target method
	 * invocation
	 * 
	 * @param test
	 * @param position
	 * @param var
	 * @param callerObject
	 * @param map 
	 * @param castSubClass
	 * @return
	 * @throws ConstructionFailedException
	 * @throws ClassNotFoundException
	 */
	public VariableReference generateOrFind(TestCase test, boolean isLeaf, VariableReference callerObject,
			Map<DepVariableWrapper, VarRelevance> map, Branch b, boolean allowNullValue) {

		String castSubClass = checkCastClassForParameter();
		if(castSubClass == null) {
			int paramPosition = this.var.getParamOrder() - 1;
			List<String> recommendations = ObjectConstructionContext.recommendedClasses.get(paramPosition);
			if(recommendations!=null && !recommendations.isEmpty()) {
				castSubClass = Randomness.choice(recommendations);					
			}
		}

		VariableReference parameter = find(test, allowNullValue, callerObject, map);
		if(parameter != null) {
			Statement s = test.getStatement(parameter.getStPosition());
			if(!(s instanceof NullStatement)
					&& (castSubClass == null || isCompatibleWithCast(parameter, castSubClass))) {
				materializeContainer(test, parameter, allowNullValue);
				return parameter;
			}
		}
		
		if(castSubClass != null) {
			VariableReference newParameter = null;
			try {
				newParameter = generateParameter(test, castSubClass, allowNullValue && isLeaf);
			} catch (ConstructionFailedException e) {
				e.printStackTrace();
			}

			if (newParameter == null) {
				return callerObject;
			}

			EntityWithParametersStatement targetStatement =
					test.findTargetExecutableStatement();
			if (targetStatement != null) {
				VariableReference oldParamRef = targetStatement.getParameterReferences().get(this.var.getParamOrder() - 1);
				targetStatement.replace(oldParamRef, newParameter);
			}
			materializeDependentIndexedRead(test, newParameter, allowNullValue);
			materializeContainer(test, newParameter, allowNullValue);

			return newParameter;
		}
		else {
			/**
			 * find the existing parameters
			 */
			EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
			if (mStat == null) {
				return null;
			}
			int paramPosition = this.var.getParamOrder();
			VariableReference paramRef = mStat.getParameterReferences().get(paramPosition - 1);
			
			/**
			 * make sure the parameter obj is not null
			 */
			int paramPosInTest = ObjectConstructionActionTrace.definitionPosition(test, paramRef);
			if (paramPosInTest < 0) {
				return null;
			}
			Statement paramDef = test.getStatement(paramPosInTest);
			// A parameter with dependent state cannot remain null even when ordinary random
			// generation allows nulls: none of its child fields can be realized through it.
			if((!allowNullValue || !isLeaf) && paramDef instanceof NullStatement){
				TestFactory testFactory = TestFactory.getInstance();
				boolean isSuccess = testFactory.changeNullStatement(test, paramDef);
				if(isSuccess){
					paramRef = mStat.getParameterReferences().get(paramPosition - 1);
				} else {
					ObjectConstructionActionTrace.issue("required_parameter_remained_null", null,
							this.var.getName(),
							"The parameter has graph children but no non-null construction succeeded");
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
					TypeInsnNode tNode = (TypeInsnNode) insNode;
					String classType = tNode.desc;
					potentialCastType = org.objectweb.asm.Type.getObjectType(classType).getClassName();

					ActualControlFlowGraph actualControlFlowGraph = var.getInstruction().getActualCFG();
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
	
	private VariableReference generateParameter(TestCase test, String castSubClass, boolean allowNullValue) throws ConstructionFailedException{

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
			paramClass = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(paramType);
		} catch (ClassNotFoundException e) {
			return null;
		}
		paramClass = concreteContainerClass(paramClass);
		
		
		GenericClass<?> paramDeclaringClazz = GenericClassFactory.get(paramClass);
		VariableReference paramRef = null;
		Constructor<?> constructor = selectConstructor(paramDeclaringClazz.getRawClass().getConstructors());
		if (constructor != null) {
			GenericConstructor gc = new GenericConstructor(constructor, paramDeclaringClazz);
			EntityWithParametersStatement mStat = test.findTargetExecutableStatement();
			if (mStat == null) {
				return null;
			}
			
			if(allowNullValue) {
				if(Randomness.nextDouble() < Properties.NULL_PROBABILITY) {
					paramRef = TestFactory.getInstance().createNull(test, paramClass, mStat.getPosition() - 1, 0);
				}
			}
			else {
				paramRef = TestFactory.getInstance().addConstructor(test, gc, mStat.getPosition() - 1, 2);				
			}
		}

		return paramRef;
	}

	static Class<?> concreteContainerClass(Class<?> requestedClass) {
		if (requestedClass == null || (!requestedClass.isInterface()
				&& !Modifier.isAbstract(requestedClass.getModifiers()))) {
			return requestedClass;
		}
		if (java.util.Map.class.isAssignableFrom(requestedClass)) {
			return java.util.LinkedHashMap.class;
		}
		if (java.util.Set.class.isAssignableFrom(requestedClass)) {
			return java.util.LinkedHashSet.class;
		}
		if (java.util.Collection.class.isAssignableFrom(requestedClass)
				|| requestedClass == Iterable.class) {
			return java.util.ArrayList.class;
		}
		return requestedClass;
	}

	private void materializeContainer(TestCase test, VariableReference parameter,
			boolean allowNullValue) {
		Class<?> parameterClass = parameter == null ? null : parameter.getVariableClass();
		boolean mapCardinality = parameterClass != null
				&& java.util.Map.class.isAssignableFrom(parameterClass)
				&& hasCardinalityDependency();
		if (parameterClass == null
				|| (!hasContainerStateDependency() && !mapCardinality)) {
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
			EntityWithParametersStatement target = test.findTargetExecutableStatement();
			int position = target == null ? test.size() : target.getPosition();
			GenericMethod method = new GenericMethod(mutator, mutator.getDeclaringClass());
			MethodStatement existingMutation = findContainerMutation(test, parameter, mutator);
			if (existingMutation == null) {
				addDeterministicContainerMutation(test, parameter, method, position);
			} else if (java.util.Map.class.isAssignableFrom(parameterClass)) {
				ensureStringMapKey(test, existingMutation.getReturnValue());
			}
		} catch (Exception e) {
			ObjectConstructionActionTrace.issue("container_materialization_failed", null,
					parameterClass.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/**
	 * Build the smallest non-empty container without invoking the recursive generic object
	 * generator. Query evidence such as size/entrySet/iterator only requires cardinality; asking
	 * TestFactory for arbitrary Object values previously produced large SQL/RMI/JDK object trees
	 * and frequent pre-target exceptions.
	 */
	private static VariableReference addDeterministicContainerMutation(TestCase test,
			VariableReference container, GenericMethod mutator, int position) throws Exception {
		List<VariableReference> arguments = new ArrayList<>();
		if (java.util.Map.class.isAssignableFrom(container.getVariableClass())) {
			VariableReference key = test.addStatement(
					new StringPrimitiveStatement(test, "key"), position++);
			arguments.add(key);
			arguments.add(deterministicContainerValue(test, container, true, position++));
		} else {
			arguments.add(deterministicContainerValue(test, container, false, position++));
		}
		VariableReference result = test.addStatement(new MethodStatement(
				test, mutator.copyWithNewOwner(container.getGenericClass()),
				container, arguments), position);
		ObjectConstructionActionTrace.setupAction(
				"materialize_container_cardinality", null,
				container.getVariableClass().getName(), result.getStPosition());
		return result;
	}

	private static VariableReference deterministicContainerValue(TestCase test,
			VariableReference container, boolean mapValue, int position) throws Exception {
		List<GenericClass<?>> parameters = container.getGenericClass().getParameterClasses();
		int genericIndex = mapValue ? 1 : 0;
		GenericClass<?> requestedType = genericIndex < parameters.size()
				? parameters.get(genericIndex) : GenericClassFactory.get(Object.class);
		Class<?> requested = requestedType.getRawClass();
		if (requested == null || requested == Object.class || requested == String.class) {
			return test.addStatement(new StringPrimitiveStatement(test, "value"), position);
		}
		if (java.util.Collection.class.isAssignableFrom(requested)
				|| requested == Iterable.class) {
			Class<?> concrete = concreteContainerClass(requested);
			Constructor<?> constructor = concrete.getConstructor();
			VariableReference collection = test.addStatement(new ConstructorStatement(test,
					new GenericConstructor(constructor, concrete),
					Collections.emptyList()), position);
			List<GenericClass<?>> elementTypes = requestedType.getParameterClasses();
			if (!elementTypes.isEmpty()) {
				VariableReference element = deterministicElement(
						test, elementTypes.get(0), position + 1);
				if (element != null) {
					Method add = java.util.Collection.class.getMethod("add", Object.class);
					test.addStatement(new MethodStatement(test,
							new GenericMethod(add, collection.getGenericClass()),
							collection, Collections.singletonList(element)),
							position + 2);
				}
			}
			return collection;
		}
		// A typed null still establishes cardinality and is safer than recursively generating an
		// arbitrary graph. Object-typed collections use the non-null String path above.
		return test.addStatement(new NullStatement(test, requested), position);
	}

	private static VariableReference deterministicElement(TestCase test,
			GenericClass<?> elementType, int position) throws Exception {
		Class<?> requested = elementType.getRawClass();
		if (requested == Object.class || requested == String.class) {
			return test.addStatement(new StringPrimitiveStatement(test, "value"), position);
		}
		try {
			Constructor<?> constructor = requested.getConstructor();
			return test.addStatement(new ConstructorStatement(test,
					new GenericConstructor(constructor, requested),
					Collections.emptyList()), position);
		} catch (NoSuchMethodException ignored) {
			// Prefer a stable no-argument value factory over recursive arbitrary generation.
		}
		List<Method> factories = new ArrayList<>();
		for (Method method : requested.getMethods()) {
			if (Modifier.isStatic(method.getModifiers())
					&& method.getParameterCount() == 0
					&& requested.isAssignableFrom(method.getReturnType())) {
				factories.add(method);
			}
		}
		factories.sort((left, right) -> left.toGenericString()
				.compareTo(right.toGenericString()));
		if (!factories.isEmpty()) {
			Method factory = factories.get(0);
			return test.addStatement(new MethodStatement(test,
					new GenericMethod(factory, factory.getDeclaringClass()),
					null, Collections.emptyList()), position);
		}
		return null;
	}

	static VariableReference ensureStringMapKey(TestCase test, VariableReference mutationResult)
			throws ConstructionFailedException {
		MethodStatement mutation = (MethodStatement) test.getStatement(
				mutationResult.getStPosition());
		if (mutation.getParameterReferences().isEmpty()) {
			return null;
		}
		VariableReference oldKey = mutation.getParameterReferences().get(0);
		if (oldKey != null && oldKey.getVariableClass() == String.class) {
			return oldKey;
		}
		// Strings are primitive statements in EvoSuite and are intentionally absent from the
		// TestCluster generator registry. Going through createObject() therefore fails with
		// "Have no generator for class java.lang.String" and leaves a semantically useless
		// arbitrary Object key behind. Insert a stable literal immediately before put instead.
		VariableReference stringKey = test.addStatement(
				new StringPrimitiveStatement(test, "key"), mutation.getPosition());
		mutation.replace(oldKey, stringKey);
		return stringKey;
	}

	private static MethodStatement findContainerMutation(TestCase test, VariableReference parameter,
			Method mutator) {
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
				if ("iterator".equals(name) || "entrySet".equals(name)
						|| "hasNext".equals(name) || "next".equals(name)) {
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
	private void materializeDependentIndexedRead(TestCase test, VariableReference parameter,
			boolean allowNullValue) {
		Deque<DepVariableWrapper> pending = new ArrayDeque<>();
		pending.add(this);
		Set<DepVariableWrapper> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		int remaining = 96;
		while (!pending.isEmpty() && remaining-- > 0) {
			DepVariableWrapper node = pending.removeFirst();
			if (node == null || !visited.add(node)) {
				continue;
			}
			if (node instanceof OtherVariableWrapper && node.var.getInstruction() != null
					&& node.var.getInstruction().getASMNode() instanceof MethodInsnNode) {
				MethodInsnNode methodNode = (MethodInsnNode) node.var.getInstruction().getASMNode();
				if ("get".equals(methodNode.name)
						&& methodNode.desc.startsWith("(I)")) {
					try {
						Class<?> owner = TestGenerationContext.getInstance().getClassLoaderForSUT()
								.loadClass(methodNode.owner.replace('/', '.'));
						java.lang.reflect.Method get = owner.getMethod("get", int.class);
						VariableReference element = ((OtherVariableWrapper) node)
								.materializeIndexedListRead(test, get, parameter, allowNullValue);
						if (element != null) {
							return;
						}
					} catch (Exception ignored) {
						// This is an optional setup refinement; ordinary synthesis remains available.
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
			Class<?> castClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
					.loadClass(castClassName);
			return castClass.isAssignableFrom(parameter.getVariableClass());
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	private String[] extractParameter(String methodSig) {
		String parameters = methodSig.substring(methodSig.indexOf("(") + 1, methodSig.indexOf(")"));
		String[] args = parameters.split(";");
		for (int i = 0; i < args.length; i++) {
			args[i] = args[i].replace("/", ".").substring(1, args[i].length());
		}
		return args;
	}

	@Override
	public VariableReference find(TestCase test, boolean isLeaf, VariableReference callerObject,
			Map<DepVariableWrapper, VarRelevance> map) {
		Statement s = test.getStatement(test.size()-1);
		if(s instanceof MethodStatement) {
			
			int parameterOrder = this.var.getParamOrder();
			
			MethodStatement targetMethodStatement = (MethodStatement)s;
			
			List<VariableReference> paraList = targetMethodStatement.getParameterReferences();
			
			return paraList.get(parameterOrder-1);
		}
		
		return null;
	}
}
