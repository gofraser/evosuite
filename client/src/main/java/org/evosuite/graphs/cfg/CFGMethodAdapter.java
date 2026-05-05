/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.graphs.cfg;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.graphs.GraphPool;
import org.evosuite.instrumentation.InstrumentationSelector;
import org.evosuite.instrumentation.coverage.MethodInstrumentation;
import org.evosuite.runtime.annotation.EvoSuiteExclude;
import org.evosuite.runtime.classhandling.ClassResetter;
import org.evosuite.runtime.instrumentation.AnnotatedMethodNode;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.util.Printer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Create a minimized control flow graph for the method and store it. In
 * addition, this adapter also adds instrumentation for branch distance
 * measurement.
 *
 * <p>defUse, concurrency and LCSAJs instrumentation is also added (if the
 * properties are set).
 *
 * @author Gordon Fraser
 */
public class CFGMethodAdapter extends MethodVisitor {

    private static final Logger logger = LoggerFactory.getLogger(CFGMethodAdapter.class);

    /**
     * A list of Strings representing method signatures. Methods matching those
     * signatures are not instrumented and no CFG is generated for them. Except
     * if some MethodInstrumentation requests it.
     */
    public static final List<String> EXCLUDE = Arrays.asList("<clinit>()V",
            ClassResetter.STATIC_RESET + "()V",
            ClassResetter.STATIC_RESET);
    /**
     * The set of all methods which can be used during test case generation This
     * excludes e.g. synthetic, initializers, private and deprecated methods.
     */
    private static Map<ClassLoader, Map<String, Set<String>>> methods = new HashMap<>();

    /**
     * Clears the stored methods.
     */
    public static void clear() {
        for (ClassLoader cl : methods.keySet()) {
            methods.get(cl).clear();
        }
        methods.clear();
    }

    /**
     * This is the name + the description of the method. It is more like the
     * signature and less like the name. The name of the method can be found in
     * this.plainName.
     */
    private final String methodName;

    private final MethodVisitor next;
    private final String plainName;
    private final int access;
    private final String className;
    private final ClassLoader classLoader;

    private int lineNumber = 0;

    /**
     * Can be set by annotation.
     */
    private boolean excludeMethod = false;

    /**
     * Constructor for CFGMethodAdapter.
     *
     * @param classLoader a {@link java.lang.ClassLoader} object.
     * @param className   a {@link java.lang.String} object.
     * @param access      a int.
     * @param name        a {@link java.lang.String} object.
     * @param desc        a {@link java.lang.String} object.
     * @param signature   a {@link java.lang.String} object.
     * @param exceptions  an array of {@link java.lang.String} objects.
     * @param mv          a {@link org.objectweb.asm.MethodVisitor} object.
     */
    public CFGMethodAdapter(ClassLoader classLoader, String className, int access,
                            String name, String desc, String signature, String[] exceptions,
                            MethodVisitor mv) {

        // super(new MethodNode(access, name, desc, signature, exceptions),
        // className,
        // name.replace('/', '.'), null, desc);

        super(Opcodes.ASM9, new AnnotatedMethodNode(access, name, desc, signature,
                exceptions));

        this.next = mv;
        this.className = className; // .replace('/', '.');
        this.access = access;
        this.methodName = name + desc;
        this.plainName = name;
        this.classLoader = classLoader;

        if (!methods.containsKey(classLoader)) {
            methods.put(classLoader, new HashMap<>());
        }
    }

    /* (non-Javadoc)
     * @see org.objectweb.asm.MethodVisitor#visitLineNumber(int, org.objectweb.asm.Label)
     */
    @Override
    public void visitLineNumber(int line, Label start) {
        lineNumber = line;
        super.visitLineNumber(line, start);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (Type.getDescriptor(EvoSuiteExclude.class).equals(desc)) {
            logger.info("Method has EvoSuite annotation: " + desc);
            excludeMethod = true;
        }
        return super.visitAnnotation(desc, visible);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitEnd() {
        logger.debug("Creating CFG of " + className + "." + methodName);
        boolean isExcludedMethod = excludeMethod || EXCLUDE.contains(methodName);
        boolean isMainMethod = plainName.equals("main") && Modifier.isStatic(access);

        List<MethodInstrumentation> instrumentations = InstrumentationSelector.getInstrumentations(
                className, methodName);

        boolean executeOnMain = false;
        boolean executeOnExcluded = false;

        for (MethodInstrumentation instrumentation : instrumentations) {
            executeOnMain = executeOnMain || instrumentation.executeOnMainMethod();
            executeOnExcluded = executeOnExcluded
                    || instrumentation.executeOnExcludedMethods();
        }

        // super.visitEnd();
        // Generate CFG of method
        MethodNode mn = (AnnotatedMethodNode) mv;

        boolean checkForMain = false;
        if (Properties.CONSIDER_MAIN_METHODS) {
            checkForMain = true;
        } else {
            checkForMain = !isMainMethod || executeOnMain;
        }

        // Only instrument if the method is (not main and not excluded) or (the
        // MethodInstrumentation wants it anyway)
        if (checkForMain && (!isExcludedMethod || executeOnExcluded)
                && (access & Opcodes.ACC_ABSTRACT) == 0
                && (access & Opcodes.ACC_NATIVE) == 0) {

            logger.info("Analyzing method " + methodName + " in class " + className);

            // MethodNode mn = new CFGMethodNode((MethodNode)mv);
            // System.out.println("Generating CFG for "+ className+"."+mn.name +
            // " ("+mn.desc +")");

            BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
            logger.info("Generating CFG for method " + methodName);

            try {

                bytecodeAnalyzer.analyze(classLoader, className, methodName, mn);
                logger.trace("Method graph for "
                        + className
                        + "."
                        + methodName
                        + " contains "
                        + bytecodeAnalyzer.retrieveCFGGenerator().getRawGraph().vertexSet().size()
                        + " nodes for " + bytecodeAnalyzer.getFrames().length
                        + " instructions");
                // compute Raw and ActualCFG and put both into GraphPool
                bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
                logger.info("Created CFG for method " + methodName);

                if (DependencyAnalysis.shouldInstrument(className, methodName)) {
                    if (!methods.get(classLoader).containsKey(className)) {
                        methods.get(classLoader).put(className, new HashSet<>());
                    }

                    // add the actual instrumentation
                    logger.info("Instrumenting method " + methodName + " in class "
                            + className);
                    for (MethodInstrumentation instrumentation : instrumentations) {
                        instrumentation.analyze(classLoader, mn, className, methodName, access);
                    }

                    handleBranchlessMethods();
                    String id = className + "." + methodName;
                    if (isUsable()) {
                        methods.get(classLoader).get(className).add(id);
                        logger.debug("Counting: " + id);
                    }
                }
            } catch (AnalyzerException e) {
                logger.error("Analyzer exception while analyzing " + className + "."
                        + methodName + ": " + e);
                dumpAnalyzerFailure(mn, e);
                e.printStackTrace();
                // CFGGenerator registers method instructions before ASM analysis starts.
                // If analysis fails, we may end up with stale BytecodeInstructions but
                // no corresponding CFG in GraphPool, which later crashes coverage-goal
                // setup (eg LineCoverageTestFitness.getControlDependencies()).
                BytecodeInstructionPool.getInstance(classLoader).clear(className, methodName);
                GraphPool.getInstance(classLoader).clear(className, methodName);
                BranchPool.getInstance(classLoader).clear(className, methodName);
            }

        } else {
            logger.debug("NOT Creating CFG of " + className + "." + methodName + ": " + checkForMain + ", "
                    + (!isExcludedMethod || executeOnExcluded) + ", " + ((access & Opcodes.ACC_ABSTRACT) == 0) + ", "
                    + ((access & Opcodes.ACC_NATIVE) == 0));
            super.visitEnd();
        }
        mn.accept(next);
    }

    private void dumpAnalyzerFailure(MethodNode mn, AnalyzerException e) {
        if (e == null || mn == null || mn.instructions == null) {
            return;
        }

        int failingIndex = -1;
        AbstractInsnNode failingNode = e.node;
        if (failingNode != null) {
            int idx = 0;
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn == failingNode) {
                    failingIndex = idx;
                    break;
                }
                idx++;
            }
        }

        logger.error("ASM analyzer failure context for {}.{}: index={}, node={}",
                className, methodName, failingIndex, formatInsn(failingNode));
        if (failingIndex < 0) {
            return;
        }

        int from = Math.max(0, failingIndex - 10);
        int to = failingIndex + 10;
        int idx = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (idx >= from && idx <= to) {
                String marker = idx == failingIndex ? ">>" : "  ";
                logger.error("{} [{}] {}", marker, idx, formatInsn(insn));
            }
            if (idx > to) {
                break;
            }
            idx++;
        }
    }

    private String formatInsn(AbstractInsnNode insn) {
        if (insn == null) {
            return "<null>";
        }
        int opcode = insn.getOpcode();
        String op = opcode >= 0 && opcode < Printer.OPCODES.length ? Printer.OPCODES[opcode] : "NOOP";

        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return op + " " + m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return op + " " + f.owner + "." + f.name + " :" + f.desc;
        }
        if (insn instanceof TypeInsnNode) {
            TypeInsnNode t = (TypeInsnNode) insn;
            return op + " " + t.desc;
        }
        if (insn instanceof VarInsnNode) {
            VarInsnNode v = (VarInsnNode) insn;
            return op + " var=" + v.var;
        }
        if (insn instanceof IntInsnNode) {
            IntInsnNode i = (IntInsnNode) insn;
            return op + " " + i.operand;
        }
        if (insn instanceof LdcInsnNode) {
            LdcInsnNode l = (LdcInsnNode) insn;
            return op + " " + String.valueOf(l.cst);
        }
        if (insn instanceof JumpInsnNode) {
            JumpInsnNode j = (JumpInsnNode) insn;
            return op + " -> " + Integer.toHexString(System.identityHashCode(j.label));
        }
        if (insn instanceof IincInsnNode) {
            IincInsnNode i = (IincInsnNode) insn;
            return "IINC var=" + i.var + " inc=" + i.incr;
        }
        if (insn instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode m = (MultiANewArrayInsnNode) insn;
            return "MULTIANEWARRAY " + m.desc + " dims=" + m.dims;
        }
        if (insn instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode t = (TableSwitchInsnNode) insn;
            return "TABLESWITCH " + t.min + ".." + t.max;
        }
        if (insn instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode l = (LookupSwitchInsnNode) insn;
            return "LOOKUPSWITCH keys=" + l.keys;
        }
        if (insn instanceof LineNumberNode) {
            LineNumberNode l = (LineNumberNode) insn;
            return "LINE " + l.line;
        }
        if (insn instanceof LabelNode) {
            return "LABEL " + Integer.toHexString(System.identityHashCode(insn));
        }
        if (insn instanceof InsnNode) {
            return op;
        }
        return insn.getClass().getSimpleName() + " opcode=" + op;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.objectweb.asm.commons.LocalVariablesSorter#visitMaxs(int, int)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        int maxNum = 7;
        super.visitMaxs(Math.max(maxNum, maxStack), maxLocals);
    }

    private void handleBranchlessMethods() {
        String id = className + "." + methodName;
        if (BranchPool.getInstance(classLoader).getNonArtificialBranchCountForMethod(className, methodName) == 0) {
            if (isUsable()) {
                logger.debug("Method has no branches: " + id);
                BranchPool.getInstance(classLoader).addBranchlessMethod(className, id, lineNumber);
            }
        }
    }

    /**
     * Checks if the method is usable for test generation.
     *
     * <p>See description of CFGMethodAdapter.EXCLUDE.
     *
     * @return true if usable
     */
    private boolean isUsable() {
        if ((this.access & Opcodes.ACC_SYNTHETIC) != 0) {
            return false;
        }

        if ((this.access & Opcodes.ACC_BRIDGE) != 0) {
            return false;
        }

        if ((this.access & Opcodes.ACC_NATIVE) != 0) {
            return false;
        }

        if (methodName.contains("<clinit>")) {
            return false;
        }

        // If we are not using reflection, covering private constructors is difficult?
        if (Properties.P_REFLECTION_ON_PRIVATE <= 0.0) {
            return !methodName.contains("<init>") || (access & Opcodes.ACC_PRIVATE) != Opcodes.ACC_PRIVATE;
        }

        return true;
    }

    /**
     * Returns the methods for the given class.
     *
     * @param className class name
     * @return set of method IDs
     */
    public Set<String> getMethods(String className) {
        return getMethods(classLoader, className);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @param className a {@link java.lang.String} object.
     * @return A set with all unique methodNames of methods.
     */
    public static Set<String> getMethods(ClassLoader classLoader, String className) {
        Set<String> targetMethods = new HashSet<>();
        if (!methods.containsKey(classLoader)) {
            return targetMethods;
        }

        for (String currentClass : methods.get(classLoader).keySet()) {
            if (currentClass.equals(className)
                    || currentClass.startsWith(className + "$")) {
                targetMethods.addAll(methods.get(classLoader).get(currentClass));
            }
        }

        return targetMethods;
    }

    /**
     * Returns the methods for the current class loader.
     *
     * @return set of method IDs
     */
    public Set<String> getMethods() {
        return getMethods(classLoader);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @return A set with all unique methodNames of methods.
     */
    public static Set<String> getMethods(ClassLoader classLoader) {
        Set<String> targetMethods = new HashSet<>();
        if (!methods.containsKey(classLoader)) {
            return targetMethods;
        }

        for (String currentClass : methods.get(classLoader).keySet()) {
            targetMethods.addAll(methods.get(classLoader).get(currentClass));
        }

        return targetMethods;
    }


    /**
     * Returns matching methods for the given class name prefix.
     *
     * @param className class name prefix
     * @return set of method IDs
     */
    public Set<String> getMethodsPrefix(String className) {
        return getMethodsPrefix(classLoader, className);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @param className a {@link java.lang.String} object.
     * @return A set with all unique methodNames of methods.
     */
    public static Set<String> getMethodsPrefix(ClassLoader classLoader, String className) {
        Set<String> matchingMethods = new HashSet<>();
        if (!methods.containsKey(classLoader)) {
            return matchingMethods;
        }

        for (String name : methods.get(classLoader).keySet()) {
            if (name.startsWith(className)) {
                matchingMethods.addAll(methods.get(classLoader).get(name));
            }
        }

        return matchingMethods;
    }

    /**
     * Returns the number of methods for the given class name prefix.
     *
     * @param className class name prefix
     * @return number of methods
     */
    public int getNumMethodsPrefix(String className) {
        return getNumMethodsPrefix(classLoader, className);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @param className a {@link java.lang.String} object.
     * @return A set with all unique methodNames of methods.
     */
    public static int getNumMethodsPrefix(ClassLoader classLoader, String className) {
        int num = 0;
        if (!methods.containsKey(classLoader)) {
            return num;
        }

        for (String name : methods.get(classLoader).keySet()) {
            if (name.startsWith(className)) {
                num += methods.get(classLoader).get(name).size();
            }
        }

        return num;
    }

    /**
     * Returns the total number of methods.
     *
     * @return number of methods
     */
    public int getNumMethods() {
        return getNumMethods(classLoader);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @return A set with all unique methodNames of methods.
     */
    public static int getNumMethods(ClassLoader classLoader) {
        int num = 0;
        if (!methods.containsKey(classLoader)) {
            return num;
        }

        for (String name : methods.get(classLoader).keySet()) {
            num += methods.get(classLoader).get(name).size();
        }

        return num;
    }

    /**
     * Returns the number of methods for member classes of the given class.
     *
     * @param className class name
     * @return number of methods
     */
    public int getNumMethodsMemberClasses(String className) {
        return getNumMethodsMemberClasses(classLoader, className);
    }

    /**
     * Returns a set with all unique methodNames of methods.
     *
     * @param classLoader class loader
     * @param className a {@link java.lang.String} object.
     * @return A set with all unique methodNames of methods.
     */
    public static int getNumMethodsMemberClasses(ClassLoader classLoader, String className) {
        int num = 0;
        if (!methods.containsKey(classLoader)) {
            return num;
        }

        for (String name : methods.get(classLoader).keySet()) {
            if (name.equals(className) || name.startsWith(className + "$")) {
                num += methods.get(classLoader).get(name).size();
            }
        }

        return num;
    }
}
