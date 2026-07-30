package org.evosuite.graphs.interprocedural;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.dataflow.DefUseFactory;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.dataflow.Definition;
import org.evosuite.coverage.dataflow.Use;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-variable/field def-use helpers for the EvoObj interprocedural analysis.
 * Ported from the {@code evosuite-plus-plus} fork.
 */
public class DefUseAnalyzer {

	private static final Logger logger = LoggerFactory.getLogger(DefUseAnalyzer.class);

	private static Set<String> analizedList = new HashSet<>();

	public static void resetSingleton() {
		analizedList.clear();
	}

	/**
	 * Return the reaching definitions of the local-variable/field {@code use} that are also
	 * intraprocedurally reachable from the definition to the use. Needed by the M2
	 * slicer to link definitions of a local into the object construction graph.
	 */
	public static List<BytecodeInstruction> getDefFromUse(BytecodeInstruction insOfuse) {
		String className = insOfuse.getClassName();
		String methodName = insOfuse.getMethodName();
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode node = getMethodNode(classLoader, className, methodName);
		if (node == null) {
			logger.debug("Unable to resolve bytecode for {}#{} while following def-use information",
					className, methodName);
			return new ArrayList<>();
		}

		DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
		defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);
		Use use = null;
		try {
			use = DefUseFactory.makeUse(insOfuse);
		}
		catch (IllegalArgumentException e) {
			logger.debug("unable to build a Use for instruction " + insOfuse, e);
		}

		if (use == null) {
			return new ArrayList<BytecodeInstruction>();
		}

		// Ignore method parameter
		List<Definition> defs = getDefinitions(use);

		if (defs == null) return new ArrayList<BytecodeInstruction>();

		List<BytecodeInstruction> list = new ArrayList<BytecodeInstruction>();
		for (Definition def : defs) {
			ActualControlFlowGraph cfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
			BytecodeInstruction defInstruction = convert2BytecodeInstruction(cfg, node, def.getASMNode());

			if (InterproceduralGraphAnalysis.isReachableInClass(def, use)) {
				list.add(defInstruction);
			}

		}

		return list;
	}

	/**
	 * All registered definitions of the use's variable within the same class and method. Upstream
	 * has no {@code DefUsePool.getDefinitions(Use)}; reconstruct it from the registered definitions
	 * rather than modifying {@code DefUsePool}.
	 */
	public static List<Definition> getDefinitions(Use use) {
		List<Definition> result = new ArrayList<Definition>();
		String className = use.getClassName();
		String methodName = use.getMethodName();
		String variableName = use.getVariableName();
		for (Definition def : DefUsePool.retrieveRegisteredDefinitions()) {
			if (className.equals(def.getClassName()) && methodName.equals(def.getMethodName())
					&& variableName.equals(def.getVariableName())) {
				result.add(def);
			}
		}
		return result;
	}

	public static BytecodeInstruction convert2BytecodeInstruction(ActualControlFlowGraph cfg, MethodNode node,
			AbstractInsnNode ins) {
		// SourceValue frames normally retain the exact ASM nodes used to build the CFG. Resolve
		// those by identity first. Looking up the node only in a freshly parsed MethodNode is not
		// reliable for pre-built project JARs: AbstractInsnNode uses identity equality, so indexOf
		// returns -1 even though the instruction is present. That was the reason the real Gangup
		// BasicRules graph lost actor/target/action dependencies while the source fixture passed.
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		RawControlFlowGraph rawCfg = GraphPool.getInstance(classLoader)
				.getRawCFG(cfg.getClassName(), cfg.getMethodName());
		if (rawCfg != null) {
			for (BytecodeInstruction candidate : rawCfg.vertexSet()) {
				if (candidate.getASMNode() == ins) {
					return candidate;
				}
			}
		}

		int instructionIndex = node == null ? -1 : node.instructions.indexOf(ins);
		return instructionIndex < 0 ? null : cfg.getInstruction(instructionIndex);
	}

	public static MethodNode getMethodNode(InstrumentingClassLoader classLoader, String className, String methodName) {
		try (InputStream is = ResourceList.getInstance(classLoader).getClassAsStream(className)) {
			if (is == null) {
				logger.debug("No class bytes found for {} while resolving {}", className, methodName);
				return null;
			}
			ClassReader reader = new ClassReader(is);
			ClassNode cn = new ClassNode();
			reader.accept(cn, ClassReader.SKIP_FRAMES);
			List<MethodNode> l = cn.methods;

			for (MethodNode n : l) {
				String methodSig = n.name + n.desc;
				if (methodSig.equals(methodName)) {
					return n;
				}
			}

			// Can't find the method in current class
			// Check its parent class
			try {
				Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
				if (clazz.getSuperclass() != null) {
					Class<?> superClazz = clazz.getSuperclass();
					return getMethodNode(classLoader, superClazz.getName(), methodName);
				}
			} catch (ClassNotFoundException e) {
				// class not available on the SUT classloader; give up
			}

		} catch (IOException e) {
			logger.debug("Unable to read class bytes for {} while resolving {}", className,
					methodName, e);
		}

		return null;
	}

	public void analyze(ClassLoader classLoader, MethodNode mn, String className,
	        String methodName, int access) {
		if (mn == null) {
			logger.debug("Skipping def-use analysis for {}#{} because its MethodNode is unavailable",
					className, methodName);
			return;
		}
		String methodId = String.format("%s#%s%s", className, methodName, mn.desc);
		if (analizedList.contains(methodId)) {
			return;
		}

		RawControlFlowGraph completeCFG = GraphPool.getInstance(classLoader).getRawCFG(className, methodName);
		if (completeCFG == null) {
			logger.debug("Skipping def-use analysis for {}#{} because its raw CFG is unavailable",
					className, methodName);
			return;
		}
		Iterator<AbstractInsnNode> j = mn.instructions.iterator();
		while (j.hasNext()) {
			AbstractInsnNode in = j.next();
			for (BytecodeInstruction v : completeCFG.vertexSet()) {
				if (in.equals(v.getASMNode()) && v.isDefUse()) {
					if (v.isMethodCallOfField()) {
						// keep track of field method calls, though we do not
						// know how to handle them at this point during the
						// analysis (need complete CCFGs first)
						DefUsePool.addAsFieldMethodCall(v);
					} else {
						// keep track of uses
						if (v.isUse())
							DefUsePool.addAsUse(v);
						// keep track of definitions
						if (v.isDefinition())
							DefUsePool.addAsDefinition(v);
					}
				}
			}
		}
		analizedList.add(methodId);
	}

	@SuppressWarnings("unused")
	private int getNextLocalVariable(MethodNode mn) {
		int var = 1;
		List<LocalVariableNode> nodes = mn.localVariables;
		for (LocalVariableNode varNode : nodes) {
			if (varNode.index >= var) {
				var = varNode.index + 1;
			}
		}
		return var;
	}

}
