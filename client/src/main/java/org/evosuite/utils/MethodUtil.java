package org.evosuite.utils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeAnalyzer;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signature/descriptor utilities used throughout the EvoObj object-construction-graph
 * analysis. Ported from the {@code evosuite-plus-plus} fork.
 */
public class MethodUtil {
	private static Logger log = LoggerFactory.getLogger(MethodUtil.class);

	public static String convertType(String headType) {
		String sig = "(" + headType + ")V";
		String[] array = parseSignature(sig);
		return array[0];
	}

	/**
	 * Return a list of parameter types followed by the return type.
	 *
	 * <p>{@code m(III)V => [int, int, int, void]}
	 *
	 * <p>Class names are given as {@code com.a.b.Class}; primitive types as {@code int},
	 * {@code double}, etc.
	 *
	 * @param desc the method descriptor
	 * @return parameter types followed by the return type
	 */
	public static String[] parseSignature(String desc) {
		// input:()
		String input = desc.split("[)]")[0];
		input = input.substring(input.indexOf('(') + 1, input.length());
		// output:after ')'
		String[] outputTypes = desc.split("[)]")[1].split(";");

		String[] inputTypes = input.split(";");
		List<String> allTypes = new ArrayList<>();
		if (inputTypes.length >= 1) {
			if (!(inputTypes.length == 1 && inputTypes[0].equals(""))) {
				prase(allTypes, inputTypes);
			}
		}

		prase(allTypes, outputTypes);
		String[] localSeparateTypes = allTypes.toArray(new String[0]);

		for (int i = 0; i < localSeparateTypes.length; i++) {
			if (localSeparateTypes[i].startsWith("L")) {
				localSeparateTypes[i] = localSeparateTypes[i].substring(1, localSeparateTypes[i].length());
				localSeparateTypes[i] = localSeparateTypes[i].replace("/", ".");
			}
		}
		return localSeparateTypes;
	}

	private static void prase(List<String> inputs, String[] inputTypes) {
		if (inputTypes.length == 1) {// void
			if (inputTypes[0].equals("") || inputTypes[0].equals("V")) {
				inputs.add("void");
				return;
			}
		}
		for (int i = 0; i < inputTypes.length; i++) {
			if (!inputTypes[i].startsWith("L")) {
				// && !inputTypes[i].startsWith("[L")
				// start with primitive type || '[' type
				char[] charList = inputTypes[i].toCharArray();
				boolean isList = false;
				int startIndex = 0;
				for (int j = 0; j < charList.length; j++) {
					char c = charList[j];
					String clas = null;
					if (!isList && c == '[') {
						startIndex = j;
						isList = true;
						continue;
					}
					switch (c) {
					case '[':
						continue;
					case 'I':
						clas = "int";
						break;
					case 'J':
						clas = "long";
						break;
					case 'F':
						clas = "float";
						break;
					case 'D':
						clas = "double";
						break;
					case 'C':
						clas = "char";
						break;
					case 'Z':
						clas = "boolean";
						break;
					case 'B':
						clas = "byte";
						break;
					case 'S': {
						clas = "short";
						break;
					}
					case 'L': {
						clas = inputTypes[i].substring(j, charList.length);
						j = charList.length - 1;
						break;
					}
					}
					String type = praseInstructionType(isList, j, clas, startIndex);
					inputs.add(type);
					isList = false;
				}
			} else {
				// start with 'L'
				inputs.add(inputTypes[i]);
			}
		}
	}

	private static String praseInstructionType(boolean isList, int j, String string, int startIndex) {
		if (isList) {
			if (j - startIndex == 2)
				return string + "[][]";
			else
				return string + "[]";
		} else
			return string;
	}

	/*
	 * Same shape, and same fix, as DefUseAnalyzer.getMethodNode: this used to re-parse the whole
	 * class with ASM on every call (and again for every superclass on a miss), which is expensive
	 * when EvoObj's interprocedural analysis calls it repeatedly for the same handful of classes.
	 * Cache both the parsed ClassNode and the resolved MethodNode (including the superclass walk)
	 * per classloader; resetCache() is invoked from ObjectRuleRuntime.reset() so a new
	 * test-generation context never reuses another context's parsed bytecode.
	 */
	private static final Map<ClassLoader, Map<String, ClassNode>> CLASS_NODE_CACHE =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<ClassLoader, Map<String, MethodNode>> METHOD_NODE_CACHE =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static final ClassNode MISSING_CLASS_NODE = new ClassNode();
	private static final MethodNode MISSING_METHOD_NODE = new MethodNode();

	public static void resetCache() {
		CLASS_NODE_CACHE.clear();
		METHOD_NODE_CACHE.clear();
	}

	public static MethodNode getMethodNode(InstrumentingClassLoader classLoader, String className, String methodName) {
		Map<String, MethodNode> methodCache = perLoaderCache(METHOD_NODE_CACHE, classLoader);
		String cacheKey = className + "#" + methodName;
		MethodNode cached = methodCache.get(cacheKey);
		if (cached != null) {
			return cached == MISSING_METHOD_NODE ? null : cached;
		}

		MethodNode resolved = resolveMethodNode(classLoader, className, methodName);
		methodCache.put(cacheKey, resolved == null ? MISSING_METHOD_NODE : resolved);
		return resolved;
	}

	private static MethodNode resolveMethodNode(InstrumentingClassLoader classLoader, String className,
			String methodName) {
		ClassNode cn = getOrParseClassNode(classLoader, className);
		if (cn != null) {
			for (MethodNode n : cn.methods) {
				String methodSig = n.name + n.desc;
				if (methodSig.equals(methodName)) {
					return n;
				}
			}
		}

		// Can't find the method in current class
		// Check its parent class
		try {
			Class<?> clazz = Class.forName(className);
			if (clazz.getSuperclass() != null) {
				Class<?> superClazz = clazz.getSuperclass();
				return getMethodNode(classLoader, superClazz.getName(), methodName);
			}
		} catch (ClassNotFoundException e) {
			log.debug("Could not load class " + className, e);
		}

		return null;
	}

	private static ClassNode getOrParseClassNode(InstrumentingClassLoader classLoader, String className) {
		Map<String, ClassNode> classCache = perLoaderCache(CLASS_NODE_CACHE, classLoader);
		ClassNode cached = classCache.get(className);
		if (cached != null) {
			return cached == MISSING_CLASS_NODE ? null : cached;
		}

		ClassNode parsed = parseClassNode(classLoader, className);
		classCache.put(className, parsed == null ? MISSING_CLASS_NODE : parsed);
		return parsed;
	}

	private static ClassNode parseClassNode(InstrumentingClassLoader classLoader, String className) {
		try (InputStream is = ResourceList.getInstance(classLoader).getClassAsStream(className)) {
			if (is == null) {
				log.debug("Could not read class " + className);
				return null;
			}
			ClassReader reader = new ClassReader(is);
			ClassNode cn = new ClassNode();
			reader.accept(cn, ClassReader.SKIP_FRAMES);
			return cn;
		} catch (IOException e) {
			log.debug("Could not read class " + className, e);
			return null;
		}
	}

	private static <V> Map<String, V> perLoaderCache(Map<ClassLoader, Map<String, V>> cache,
			ClassLoader classLoader) {
		return cache.computeIfAbsent(classLoader, cl -> new ConcurrentHashMap<>());
	}

	public static ActualControlFlowGraph registerMethod(String className, String methodName) {
		Class<?> clazz;
		try {
			clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
			return registerMethod(clazz, methodName);
		} catch (ClassNotFoundException e) {
			log.debug("Could not load class " + className, e);
		}

		return null;
	}

	public static ActualControlFlowGraph registerMethod(Class<?> fieldDeclaringClass, String methodName) {
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		String className = fieldDeclaringClass.getName();
		ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
		MethodNode innerNode = MethodUtil.getMethodNode(classLoader, className, methodName);
		BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
		if (cfg == null && innerNode != null) {
			try {
				bytecodeAnalyzer.analyze(classLoader, className, methodName, innerNode);
				bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
				cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
			} catch (AnalyzerException e) {
				log.debug("Could not analyze method {}#{}", className, methodName, e);
				return null;
			}
		}

		return cfg;
	}

	public static String getSignature(Method m) {
		String sig;
		StringBuilder sb = new StringBuilder("(");
		for (Class<?> c : m.getParameterTypes())
			sb.append((sig = Array.newInstance(c, 0).toString()).substring(1, sig.indexOf('@')));
		String str = sb.append(')')
				.append(m.getReturnType() == void.class ? "V"
						: (sig = Array.newInstance(m.getReturnType(), 0).toString()).substring(1, sig.indexOf('@')))
				.toString();
		return str.replace(".", "/");
	}

	@SuppressWarnings("rawtypes")
	public static String getSignature(Constructor m) {
		String sig;

		StringBuilder sb = new StringBuilder("(");
		for (Class<?> c : m.getParameterTypes())
			sb.append((sig = Array.newInstance(c, 0).toString()).substring(1, sig.indexOf('@')));
		String str = sb.append(')').append("V").toString();

		return str.replace(".", "/");
	}

}
