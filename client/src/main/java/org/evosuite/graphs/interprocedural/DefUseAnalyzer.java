package org.evosuite.graphs.interprocedural;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.dataflow.DefUsePool;
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

/**
 * Local-variable/field def-use helpers for the EvoObj interprocedural analysis (plan §2.1).
 * Ported from the {@code evosuite-plus-plus} fork.
 *
 * <p>Note (M0): {@code getDefFromUse} is intentionally not ported yet — it depends on
 * {@code InterproceduralGraphAnalysis.isReachableInClass}, which arrives with the slicer in
 * milestone M2. Only the pieces required by the M0 infrastructure ({@code getMethodNode},
 * {@code convert2BytecodeInstruction}, {@code analyze}) are present here.
 */
public class DefUseAnalyzer {

	private static Set<String> analizedList = new HashSet<>();

	public static void resetSingleton() {
		analizedList.clear();
	}

	public static BytecodeInstruction convert2BytecodeInstruction(ActualControlFlowGraph cfg, MethodNode node,
			AbstractInsnNode ins) {
		BytecodeInstruction defIns = cfg.getInstruction(node.instructions.indexOf(ins));
		return defIns;
	}

	public static MethodNode getMethodNode(InstrumentingClassLoader classLoader, String className, String methodName) {
		InputStream is = ResourceList.getInstance(classLoader).getClassAsStream(className);
		try {
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
			// class bytes not readable; give up
		}

		return null;
	}

	public void analyze(ClassLoader classLoader, MethodNode mn, String className,
	        String methodName, int access) {
		String methodId = String.format("%s#%s%s", className, methodName, mn.desc);
		if (analizedList.contains(methodId)) {
			return;
		}

		RawControlFlowGraph completeCFG = GraphPool.getInstance(classLoader).getRawCFG(className, methodName);
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
