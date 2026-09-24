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
package org.evosuite.graphs;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.graphs.ccfg.ClassControlFlowGraph;
import org.evosuite.graphs.ccg.ClassCallGraph;
import org.evosuite.graphs.cdg.ControlDependenceGraph;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeAnalyzer;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.setup.DependencyAnalysis;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives access to all Graphs computed during CUT analysis such as CFGs created
 * by the CFGGenerator and BytcodeAnalyzer in the CFGMethodAdapter.
 *
 * <p>For each CUT and each of their methods a Raw- and an ActualControlFlowGraph
 * instance are stored within this pool. Additionally a ControlDependenceGraph
 * is computed and stored for each such method.
 *
 * <p>This pool also offers the possibility to generate the ClassCallGraph and
 * ClassControlFlowGraph for a CUT. They represents the call hierarchy and
 * interaction of different methods within a class.
 *
 * @author Andre Mis
 */
public class GraphPool {

    private static final Logger logger = LoggerFactory.getLogger(GraphPool.class);

    private static final Map<ClassLoader, GraphPool> instanceMap = new ConcurrentHashMap<>();

    private final ClassLoader classLoader;

    /**
     * Private constructor.
     */
    private GraphPool(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Retrieves the instance of GraphPool for the given class loader.
     *
     * @param classLoader class loader
     * @return GraphPool instance
     */
    public static GraphPool getInstance(ClassLoader classLoader) {
        // Handle null classLoader (bootstrap classloader) by using a sentinel key,
        // since ConcurrentHashMap doesn't allow null keys
        ClassLoader key = classLoader != null ? classLoader : ClassLoader.getSystemClassLoader();
        return instanceMap.computeIfAbsent(key, GraphPool::new);
    }

    /**
     * Complete control flow graph, contains each bytecode instruction, each
     * label and line number node Think of the direct Known Subclasses of
     * http://
     * asm.ow2.org/asm33/javadoc/user/org/objectweb/asm/tree/AbstractInsnNode
     * .html for a complete list of the nodes in this cfg.
     *
     * <p>Maps from classNames to methodNames to corresponding RawCFGs.
     */
    private final Map<String, Map<String, RawControlFlowGraph>> rawCFGs = new ConcurrentHashMap<>();

    /**
     * Minimized control flow graph. This graph only contains the first and last
     * node (usually a LABEL and IRETURN), nodes which create branches (all
     * jumps/switches except GOTO) and nodes which were mutated.
     *
     * <p>Maps from classNames to methodNames to corresponding ActualCFGs.
     */
    private final Map<String, Map<String, ActualControlFlowGraph>> actualCFGs = new ConcurrentHashMap<>();

    /**
     * Control Dependence Graphs for each method.
     *
     * <p>Maps from classNames to methodNames to corresponding CDGs.
     */
    private final Map<String, Map<String, ControlDependenceGraph>> controlDependencies = new ConcurrentHashMap<>();

    /**
     * Cache of all created CCFGs.
     *
     * <p>Maps from classNames to computed CCFG of that class.
     */
    private final Map<String, ClassControlFlowGraph> ccfgs = new ConcurrentHashMap<>();

    // retrieve graphs

    /**
     * Returns the {@link RawControlFlowGraph} of the specified method. To this end, one has to
     * provide
     * <ul>
     *     <li>the fully qualified name of the class containing the desired method, and</li>
     *     <li>a string consisting of the method name concatenated with the corresponding
     *     method descriptor.</li>
     * </ul>
     *
     * @param className  the fully qualified name of the containing class
     * @param methodName concatenation of method name and descriptor
     * @return the raw control flow graph
     */
    public RawControlFlowGraph getRawCFG(String className, String methodName) {

        Map<String, RawControlFlowGraph> methods = rawCFGs.get(className);
        if (methods == null) {
            logger.warn("Class unknown: " + className);
            logger.warn(rawCFGs.keySet().toString());
            return null;
        }

        return methods.get(methodName);
    }

    /**
     * Quiet availability check for a raw CFG: returns whether the given method's
     * {@link RawControlFlowGraph} is already registered, without logging a warning on a miss.
     * Used by EvoObj analysis code that probes before registering (see {@link #registerClass}).
     */
    public boolean hasRawCFG(String className, String methodName) {
        Map<String, RawControlFlowGraph> methods = rawCFGs.get(className);
        return methods != null && methods.containsKey(methodName);
    }

    /**
     * Returns the raw control flow graphs for the specified class.
     *
     * @param className a {@link java.lang.String} object.
     * @return a {@link java.util.Map} object.
     */
    public Map<String, RawControlFlowGraph> getRawCFGs(String className) {
        Map<String, RawControlFlowGraph> methods = rawCFGs.get(className);
        if (methods == null) {
            logger.warn("Class unknown: " + className);
            logger.warn(rawCFGs.keySet().toString());
            return null;
        }

        return methods;
    }

    /**
     * Returns the actual control flow graph for the given class and method.
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @return a {@link org.evosuite.graphs.cfg.ActualControlFlowGraph} object.
     */
    public ActualControlFlowGraph getActualCFG(String className, String methodName) {

        Map<String, ActualControlFlowGraph> methods = actualCFGs.get(className);
        if (methods == null) {
            return null;
        }

        return methods.get(methodName);
    }

    /**
     * Returns the control dependence graph for the given class and method.
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @return a {@link org.evosuite.graphs.cdg.ControlDependenceGraph} object.
     */
    public ControlDependenceGraph getCDG(String className, String methodName) {

        Map<String, ControlDependenceGraph> methods = controlDependencies.get(className);
        if (methods == null) {
            return null;
        }

        return methods.get(methodName);
    }

    // register graphs

    /**
     * Registers a raw control flow graph.
     *
     * @param cfg a {@link org.evosuite.graphs.cfg.RawControlFlowGraph} object.
     */
    public void registerRawCFG(RawControlFlowGraph cfg) {
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();

        if (className == null || methodName == null) {
            throw new IllegalStateException(
                    "expect class and method name of CFGs to be set before entering the GraphPool");
        }

        Map<String, RawControlFlowGraph> methods = rawCFGs.computeIfAbsent(className, k -> new ConcurrentHashMap<>());
        logger.debug("Added complete CFG for class " + className + " and method "
                + methodName);
        methods.put(methodName, cfg);

        if (Properties.WRITE_CFG) {
            cfg.toDot();
        }
    }

    /**
     * Registers an actual control flow graph.
     *
     * @param cfg a {@link org.evosuite.graphs.cfg.ActualControlFlowGraph}
     *            object.
     */
    public void registerActualCFG(ActualControlFlowGraph cfg) {
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();

        if (className == null || methodName == null) {
            throw new IllegalStateException(
                    "expect class and method name of CFGs to be set before entering the GraphPool");
        }

        Map<String, ActualControlFlowGraph> methods = actualCFGs.computeIfAbsent(className,
                k -> new ConcurrentHashMap<>());
        logger.debug("Added CFG for class " + className + " and method " + methodName);
        cfg.finalise();
        methods.put(methodName, cfg);

        if (Properties.WRITE_CFG) {
            cfg.toDot();
        }

        if (DependencyAnalysis.shouldInstrument(cfg.getClassName(), cfg.getMethodName())) {
            createAndRegisterControlDependence(cfg);
        }
    }

    /**
     * Force CFG registration for every method of the given class, even if the class is
     * outside the instrumented scope. Used by EvoObj analysis to obtain CFGs of callee
     * classes during interprocedural slicing. No-op for methods whose CFG is already known.
     *
     * @param className the (dot-separated) name of the class to register
     */
    public void registerClass(String className) {
        try (InputStream is = ResourceList
                .getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                .getClassAsStream(className)) {
            if (is == null) {
                logger.debug("Unable to register class {}: class bytes were not found on the SUT classpath",
                        className);
                return;
            }
            ClassReader reader = new ClassReader(is);
            ClassNode cn = new ClassNode();
            reader.accept(cn, ClassReader.SKIP_FRAMES);
            List<MethodNode> l = cn.methods;

            for (MethodNode m : l) {
                String fullName = m.name + m.desc;
                // Probe quietly: the class is being registered here, so a miss is expected and must
                // not produce a "Class unknown" warning.
                boolean known = GraphPool.getInstance(classLoader).hasRawCFG(className, fullName);
                if (!known && m.instructions.size() != 0) {
                    BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
                    bytecodeAnalyzer.analyze(classLoader, className, fullName, m);
                    bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
                }
            }
        } catch (IOException | AnalyzerException e) {
            logger.debug("Unable to read or analyze class " + className, e);
        }
    }

    /**
     * Register an actual CFG unconditionally, i.e. also creating and registering its control
     * dependence graph regardless of whether the method is in the instrumented scope. Used by
     * EvoObj analysis to register CFGs of classes outside the SUT.
     *
     * @param cfg the CFG to register
     */
    public void alwaysRegisterActualCFG(ActualControlFlowGraph cfg) {
        String className = cfg.getClassName();
        String methodName = cfg.getMethodName();

        if (className == null || methodName == null) {
            throw new IllegalStateException(
                    "expect class and method name of CFGs to be set before entering the GraphPool");
        }

        Map<String, ActualControlFlowGraph> methods = actualCFGs.computeIfAbsent(className,
                k -> new ConcurrentHashMap<>());
        logger.debug("Added CFG for class " + className + " and method " + methodName);
        cfg.finalise();
        methods.put(methodName, cfg);

        if (Properties.WRITE_CFG) {
            cfg.toDot();
        }

        createAndRegisterControlDependence(cfg);
    }

    private void createAndRegisterControlDependence(ActualControlFlowGraph cfg) {

        ControlDependenceGraph cd = new ControlDependenceGraph(cfg);

        String className = cd.getClassName();
        String methodName = cd.getMethodName();

        if (className == null || methodName == null) {
            throw new IllegalStateException(
                    "expect class and method name of CFGs to be set before entering the GraphPool");
        }

        Map<String, ControlDependenceGraph> cds = controlDependencies.computeIfAbsent(className,
                k -> new ConcurrentHashMap<>());

        cds.put(methodName, cd);
        if (Properties.WRITE_CFG) {
            cd.toDot();
        }
    }

    /**
     * Ensures this GraphPool knows the CCFG for the given class and then
     * returns it.
     *
     * @param className the name of the class of the CCFG as a
     *                  {@link java.lang.String}
     * @return The cached CCFG of type
     *     {@link org.evosuite.graphs.ccfg.ClassControlFlowGraph}
     */
    public ClassControlFlowGraph getCCFG(String className) {
        return ccfgs.computeIfAbsent(className, this::computeCCFG);
    }

    /**
     * Checks if a CCFG can be created for the given class.
     *
     * @param className class name
     * @return true if it can be created
     */
    public boolean canMakeCCFGForClass(String className) {
        // if(!rawCFGs.containsKey(className))
        //     LoggingUtils.getEvoLogger().info("unable to create CCFG for "+className);
        return rawCFGs.containsKey(className);
    }

    /**
     * Computes the CCFG for the given class.
     *
     * <p>If no CFG is known for the given class, an IllegalArgumentException is
     * thrown.
     *
     * @param className a {@link java.lang.String} object.
     * @return a {@link org.evosuite.graphs.ccfg.ClassControlFlowGraph} object.
     */
    private ClassControlFlowGraph computeCCFG(String className) {
        if (rawCFGs.get(className) == null) {
            throw new IllegalArgumentException(
                    "can't compute CCFG, don't know CFGs for class " + className);
        }

        ClassCallGraph ccg = new ClassCallGraph(classLoader, className);
        if (Properties.WRITE_CFG) {
            ccg.toDot();
        }

        ClassControlFlowGraph ccfg = new ClassControlFlowGraph(ccg);
        if (Properties.WRITE_CFG) {
            ccfg.toDot();
        }

        return ccfg;
    }

    /**
     * Clears all graphs from the pool.
     */
    public void clear() {
        rawCFGs.clear();
        actualCFGs.clear();
        controlDependencies.clear();
    }

    /**
     * Clears graphs for the specified class.
     *
     * @param className a {@link java.lang.String} object.
     */
    public void clear(String className) {
        rawCFGs.remove(className);
        actualCFGs.remove(className);
        controlDependencies.remove(className);
    }

    /**
     * Clears graphs for the specified class and method.
     *
     * @param className  a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     */
    public void clear(String className, String methodName) {
        if (rawCFGs.containsKey(className)) {
            rawCFGs.get(className).remove(methodName);
        }
        if (actualCFGs.containsKey(className)) {
            actualCFGs.get(className).remove(methodName);
        }
        if (controlDependencies.containsKey(className)) {
            controlDependencies.get(className).remove(methodName);
        }
    }

    public static void clearAll(String className) {
        instanceMap.values().forEach(pool -> pool.clear(className));
    }

    public static void clearAll(String className, String methodName) {
        instanceMap.values().forEach(pool -> pool.clear(className, methodName));
    }

    public static void clearAll() {
        instanceMap.clear();
    }

    /**
     * Drops the pool of the given class loader, so that the graphs built while it instrumented
     * classes (and, through them, the loader itself) can be garbage collected.
     *
     * @param classLoader the class loader whose pool is no longer needed
     */
    public static void release(ClassLoader classLoader) {
        if (classLoader != null) {
            instanceMap.remove(classLoader);
        }
    }

}
