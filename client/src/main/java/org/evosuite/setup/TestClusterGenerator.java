/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.setup;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.TimeController;
import org.evosuite.classpath.ResourceList;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.PrivateAccess;
import org.evosuite.runtime.mock.MockList;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.seeding.CastClassAnalyzer;
import org.evosuite.seeding.CastClassManager;
import org.evosuite.setup.PutStaticMethodCollector.MethodIdentifier;
import org.evosuite.setup.callgraph.CallGraph;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.utils.ArrayUtil;
import org.evosuite.utils.generic.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map.Entry;

/**
 * Generator for the test cluster.
 *
 * @author Gordon Fraser
 */
public class TestClusterGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TestClusterGenerator.class);

    private final Set<GenericAccessibleObject<?>> dependencyCache = new LinkedHashSet<>();

    private final Set<GenericClass<?>> genericCastClasses = new LinkedHashSet<>();

    private final Set<Class<?>> concreteCastClasses = new LinkedHashSet<>();

    private final Set<Class<?>> containerClasses = new LinkedHashSet<>();

    private final Set<DependencyPair> dependencies = new LinkedHashSet<>();

    private final Set<GenericClass<?>> analyzedAbstractClasses = new LinkedHashSet<>();

    private final Set<Class<?>> analyzedClasses = new LinkedHashSet<>();

    private final InheritanceTree inheritanceTree;

    private final MemberAnalyzer memberAnalyzer;

    // -------- public methods -----------------

    /**
     * Constructor.
     *
     * @param tree the inheritance tree to use
     */
    public TestClusterGenerator(InheritanceTree tree) {
        inheritanceTree = tree;
        memberAnalyzer = new MemberAnalyzer(
                TestCluster.getInstance(),
                dependencyCache,
                dependencies,
                analyzedAbstractClasses,
                analyzedClasses,
                inheritanceTree);
    }

    /**
     * Generate the test cluster from the call graph.
     *
     * @param callGraph the call graph to use
     * @throws RuntimeException if an error occurs
     * @throws ClassNotFoundException if a class is not found
     */
    public void generateCluster(CallGraph callGraph) throws RuntimeException, ClassNotFoundException {

        TestCluster.setInheritanceTree(inheritanceTree);

        if (Properties.INSTRUMENT_CONTEXT
                || ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.IBRANCH)) {
            for (String callTreeClass : callGraph.getClasses()) {
                try {
                    if (callGraph.isCalledClass(callTreeClass)) {
                        if (!Properties.INSTRUMENT_LIBRARIES && !DependencyAnalysis.isTargetProject(callTreeClass)) {
                            continue;
                        }
                        TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(callTreeClass);
                    }
                } catch (ClassNotFoundException e) {
                    logger.info("Class not found: " + callTreeClass + ": " + e);
                }
            }
        }

        dependencyCache.clear();

        /*
         * If we fail to load a class, we skip it, and avoid to try to load it
         * again (which would result in extra unnecessary logging)
         */
        Set<String> blackList = new LinkedHashSet<>(SetupConstants.BLACKLIST_EVOSUITE_PRIMITIVES);

        logger.info("Handling cast classes");
        handleCastClasses();

        logger.info("Initialising target class");
        initializeTargetMethods();

        logger.info("Resolving dependencies");
        resolveDependencies(blackList);

        handleSpecialCases();

        logger.info("Removing unusable generators");
        TestCluster.getInstance().removeUnusableGenerators();

        if (logger.isDebugEnabled()) {
            logger.debug(TestCluster.getInstance().toString());
        }

        gatherStatistics();
    }

    /**
     * Add new dependencies to the test cluster.
     *
     * @param rawTypes the types to add
     */
    public void addNewDependencies(Collection<Class<?>> rawTypes) {

        Inputs.checkNull(rawTypes);

        Set<String> blackList = new LinkedHashSet<>(SetupConstants.BLACKLIST_EVOSUITE_PRIMITIVES);

        rawTypes.stream().forEach(
                c -> dependencies.add(new DependencyPair(0, GenericClassFactory.get(c).getRawClass())));

        resolveDependencies(blackList);
    }

    // -----------------------------------------------------------------------------

    /**
     * Handle special cases for the test cluster.
     */
    private void handleSpecialCases() {

        if (Properties.P_REFLECTION_ON_PRIVATE > 0 && Properties.REFLECTION_START_PERCENT < 1) {

            // Check if we should add
            // PrivateAccess.callDefaultConstructorOfTheClassUnderTest()

            Class<?> target = Properties.getTargetClassAndDontInitialise();

            Constructor<?> constructor = null;
            try {
                constructor = target.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                // ignore
            }

            if (constructor != null && Modifier.isPrivate(constructor.getModifiers())
                    && target.getDeclaredConstructors().length == 1
                    // Not enums
                    && !target.isEnum()) {

                Method m = null;
                try {
                    m = PrivateAccess.class.getDeclaredMethod("callDefaultConstructorOfTheClassUnderTest");
                } catch (NoSuchMethodException e) {
                    logger.error("Missing method: " + e);
                    return;
                }

                GenericMethod gm = new GenericMethod(m, PrivateAccess.class);

                // It is not really an environment method, but not sure how else
                // to handle it...
                TestCluster.getInstance().addEnvironmentTestCall(gm);
            }
        }

    }

    private void handleCastClasses() {
        Set<String> blackList = new LinkedHashSet<>(SetupConstants.PRIMITIVE_TYPES);
        Set<GenericClass<?>> existingCastClasses = new LinkedHashSet<>(CastClassManager.getInstance().getCastClasses());
        logger.info("Handling cast classes. Found " + existingCastClasses.size()
                + " existing classes in CastClassManager.");
        for (GenericClass<?> clazz : existingCastClasses) {
            logger.info("Adding existing cast class as dependency: " + clazz.getClassName());
            addCastClassDependencyIfAccessible(clazz.getClassName(), blackList);
        }

        // If we include type seeding, then we analyze classes to find types in
        // instanceof and cast instructions
        if (Properties.SEED_TYPES) {

            Set<String> classNames = new LinkedHashSet<>();
            CastClassAnalyzer analyzer = new CastClassAnalyzer();
            Map<Type, Integer> castMap = analyzer.analyze(Properties.TARGET_CLASS);

            for (Entry<Type, Integer> castEntry : castMap.entrySet()) {
                String className = castEntry.getKey().getClassName();
                if (blackList.contains(className)) {
                    continue;
                }
                if (addCastClassDependencyIfAccessible(className, blackList)) {
                    CastClassManager.getInstance().addCastClass(className, castEntry.getValue());
                    classNames.add(castEntry.getKey().getClassName());
                }
            }

            // If SEED_TYPES is false, only Object is a cast class
            // logger.info("Handling cast classes");
            // addCastClasses(classNames, blackList);
            logger.debug("Cast classes used: " + classNames);
        }

    }

    private void gatherStatistics() {
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Analyzed_Classes,
                analyzedClasses.size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Generators,
                TestCluster.getInstance().getGenerators().size());
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Modifiers,
                TestCluster.getInstance().getModifiers().size());
    }

    private boolean addCastClassDependencyIfAccessible(String className, Set<String> blackList) {
        if (className.equals(java.lang.String.class.getName())) {
            return true;
        }

        if (blackList.contains(className)) {
            logger.info("Cast class in blacklist: " + className);
            return false;
        }
        try {
            Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
            if (!TestUsageChecker.canUse(clazz)) {
                logger.debug("Cannot use cast class: " + className);
                return false;
            }
            // boolean added =
            memberAnalyzer.addDependency(GenericClassFactory.get(clazz), 1);
            genericCastClasses.add(GenericClassFactory.get(clazz));
            concreteCastClasses.add(clazz);

            blackList.add(className);
            return true;

        } catch (ClassNotFoundException e) {
            logger.error("Problem for " + Properties.TARGET_CLASS + ". Class not found", e);
            blackList.add(className);
            return false;
        }
    }

    /**
     * Continue adding generators for classes that are needed.
     */
    private void resolveDependencies(Set<String> blackList) {

        while (!dependencies.isEmpty() && TimeController.getInstance().isThereStillTimeInThisPhase()) {
            logger.debug("Dependencies left: {}", dependencies.size());

            Iterator<DependencyPair> iterator = dependencies.iterator();
            DependencyPair dependency = iterator.next();
            iterator.remove();

            if (analyzedClasses.contains(dependency.getDependencyClass().getRawClass())) {
                continue;
            }

            String className = dependency.getDependencyClass().getClassName();
            if (blackList.contains(className)) {
                continue;
            }
            boolean added = false;
            /*
             * if (dependency.getDependencyClass().isParameterizedType()) { for
             * (List<GenericClass> parameterTypes :
             * getAssignableTypes(dependency.getDependencyClass())) {
             * GenericClass copy = new GenericClass(
             * dependency.getDependencyClass().getType());
             * copy.setParameterTypes(parameterTypes); boolean success =
             * addDependencyClass(copy, dependency.getRecursion()); if (success)
             * added = true; } } else
             */
            added = addDependencyClass(dependency.getDependencyClass(), dependency.getRecursion());
            if (!added) {
                blackList.add(className);
            }
            // }
        }

    }

    private void addDeclaredClasses(Set<Class<?>> targetClasses, Class<?> currentClass) {
        for (Class<?> c : currentClass.getDeclaredClasses()) {
            logger.info("Adding declared class " + c);
            targetClasses.add(c);
            addDeclaredClasses(targetClasses, c);
        }
    }

    private boolean isInterfaceWithDefaultMethods(Class<?> clazz) {
        if (!clazz.isInterface()) {
            return false;
        }

        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isDefault()) {
                return true;
            }
        }
        return false;
    }

    /**
     * All public methods defined directly in the SUT should be covered.
     *
     * <p>
     * TODO: What if we use instrument_parent?
     * </p>
     */
    @SuppressWarnings("unchecked")
    private void initializeTargetMethods() throws RuntimeException, ClassNotFoundException {

        logger.info("Analyzing target class");
        Class<?> targetClass = Properties.getTargetClassAndDontInitialise();

        TestCluster cluster = TestCluster.getInstance();

        Set<Class<?>> targetClasses = new LinkedHashSet<>();
        if (targetClass == null) {
            throw new RuntimeException("Failed to load " + Properties.TARGET_CLASS);
        }
        targetClasses.add(targetClass);
        addDeclaredClasses(targetClasses, targetClass);
        if ((!targetClass.isInterface() && Modifier.isAbstract(targetClass.getModifiers()))
                || isInterfaceWithDefaultMethods(targetClass)) {
            logger.info("SUT is an abstract class");

            Set<Class<?>> subclasses = ConcreteClassAnalyzer.getInstance().getConcreteClasses(targetClass,
                    inheritanceTree);
            logger.info("Found " + subclasses.size() + " concrete subclasses");
            targetClasses.addAll(subclasses);
        }

        // To make sure we also have anonymous inner classes double check inner
        // classes using ASM

        // because the loop changes 'targetClasses' set we cannot iterate over
        // it, not even
        // using an iterator. a simple workaround is to create a temporary set
        // with the content
        // of 'targetClasses' and iterate that one
        Set<Class<?>> tempTargetClasses = new LinkedHashSet<>(targetClasses);
        for (Class<?> targetClazz : tempTargetClasses) {
            ClassNode targetClassNode = DependencyAnalysis.getClassNode(targetClazz.getName());
            Queue<InnerClassNode> innerClasses = new LinkedList<>(targetClassNode.innerClasses);
            while (!innerClasses.isEmpty()) {
                InnerClassNode icn = innerClasses.poll();
                try {
                    logger.debug("Loading inner class: " + icn.innerName + ", " + icn.name + "," + icn.outerName);
                    String innerClassName = ResourceList.getClassNameFromResourcePath(icn.name);
                    if (!innerClassName.startsWith(Properties.TARGET_CLASS)) {
                        // TODO: Why does ASM report inner classes that are not actually inner classes?
                        // Let's ignore classes that don't start with the SUT name for now.
                        logger.debug("Ignoring inner class that is outside SUT {}", innerClassName);
                        continue;
                    }
                    Class<?> innerClass = TestGenerationContext.getInstance().getClassLoaderForSUT()
                            .loadClass(innerClassName);
                    // if (!canUse(innerClass))
                    // continue;

                    // Sometimes strange things appear such as Map$Entry
                    if (!targetClasses.contains(innerClass)
                            /*
                             * FIXME: why all the checks were removed? without
                             * the following, for example
                             * com.google.javascript.jscomp.IdMappingUtil in
                             * 124_closure-compiler is not testable
                             */
                            && !innerClassName.contains("Map$Entry")) {
                        // && !innerClassName.matches(".*\\$\\d+(\\$.*)?$")) {

                        logger.info("Adding inner class {}", innerClassName);
                        targetClasses.add(innerClass);
                        ClassNode innerClassNode = DependencyAnalysis.getClassNode(innerClassName);
                        innerClasses.addAll(innerClassNode.innerClasses);
                    }

                } catch (Throwable t) {
                    logger.error("Problem for " + Properties.TARGET_CLASS + ". Error loading inner class: "
                            + icn.innerName + ", " + icn.name + "," + icn.outerName + ": " + t);
                }
            }
        }

        for (Class<?> clazz : targetClasses) {
            logger.info("Current SUT class: " + clazz);

            GenericClass<?> genericClazz = GenericClassFactory.get(clazz);
            memberAnalyzer.analyze(genericClazz,
                    MemberAnalyzer.AnalysisMode.TARGET, 1);

            analyzedClasses.add(clazz);
            // TODO: Set to generic type rather than class?
            cluster.getAnalyzedClasses().add(clazz);
        }
        if (Properties.INSTRUMENT_PARENT) {
            for (String superClass : inheritanceTree.getSuperclasses(Properties.TARGET_CLASS)) {
                try {
                    Class<?> superClazz = TestGenerationContext.getInstance().getClassLoaderForSUT()
                            .loadClass(superClass);
                    dependencies.add(new DependencyPair(0, superClazz));
                } catch (ClassNotFoundException e) {
                    logger.error("Problem for " + Properties.TARGET_CLASS + ". Class not found: " + superClass, e);
                }

            }
        }

        if (Properties.HANDLE_STATIC_FIELDS) {

            GetStaticGraph getStaticGraph = GetStaticGraphGenerator.generate(Properties.TARGET_CLASS);

            Map<String, Set<String>> staticFields = getStaticGraph.getStaticFields();
            for (String className : staticFields.keySet()) {
                logger.info("Adding static fields to cluster for class " + className);

                Class<?> clazz;
                try {
                    Sandbox.goingToExecuteUnsafeCodeOnSameThread();
                    clazz = TestClusterUtils.getClass(className);
                } catch (ExceptionInInitializerError ex) {
                    logger.debug("Class class init caused exception " + className);
                    continue;
                } finally {
                    Sandbox.doneWithExecutingUnsafeCodeOnSameThread();
                }
                if (clazz == null) {
                    logger.debug("Class not found " + className);
                    continue;
                }

                if (!TestUsageChecker.canUse(clazz)) {
                    continue;
                }

                Set<String> fields = staticFields.get(className);
                for (Field field : TestClusterUtils.getFields(clazz)) {
                    if (!TestUsageChecker.canUse(field, clazz)) {
                        continue;
                    }

                    if (fields.contains(field.getName())) {
                        if (!TestClusterUtils.isFinalField(field)) {
                            logger.debug("Is not final");
                            // cluster.addTestCall(new GenericField(field, clazz));
                            // Count static field as modifier of SUT, not as test call:
                            GenericField genericField = new GenericField(field, clazz);
                            cluster.addModifier(GenericClassFactory.get(Properties.getTargetClassAndDontInitialise()),
                                    genericField);
                        }
                    }
                }
            }

            PutStaticMethodCollector collector = new PutStaticMethodCollector(Properties.TARGET_CLASS, staticFields);

            Set<MethodIdentifier> methodIdentifiers = collector.collectMethods();

            for (MethodIdentifier methodId : methodIdentifiers) {

                Class<?> clazz = TestClusterUtils.getClass(methodId.getClassName());
                if (clazz == null) {
                    continue;
                }

                if (!TestUsageChecker.canUse(clazz)) {
                    continue;
                }

                Method method = TestClusterUtils.getMethod(clazz, methodId.getMethodName(), methodId.getDesc());

                if (method == null) {
                    continue;
                }

                GenericMethod genericMethod = new GenericMethod(method, clazz);

                // Setting static fields is a modifier of a SUT
                // cluster.addTestCall(genericMethod);
                cluster.addModifier(GenericClassFactory.get(Properties.getTargetClassAndDontInitialise()),
                        genericMethod);

            }
        }

        logger.info("Finished analyzing target class");
    }

    /**
     * Determine if a given field is final or not.
     *
     * @param field field to check
     * @return true if the field is final
     * @deprecated Use {@link TestClusterUtils#isFinalField(Field)} instead.
     */
    @Deprecated
    public static boolean isFinalField(Field field) {
        return TestClusterUtils.isFinalField(field);
    }

    private boolean addDependencyClass(GenericClass<?> clazz, int recursionLevel) {
        if (recursionLevel > Properties.CLUSTER_RECURSION) {
            logger.debug("Maximum recursion level reached, not adding dependency {}",
                    clazz.getClassName());
            return false;
        }

        clazz = clazz.getRawGenericClass();

        if (analyzedClasses.contains(clazz.getRawClass())) {
            return true;
        }
        analyzedClasses.add(clazz.getRawClass());

        // We keep track of generic containers in case we find other concrete
        // generic components during runtime
        if (clazz.isAssignableTo(Collection.class)
                || clazz.isAssignableTo(Map.class)) {
            if (clazz.getNumParameters() > 0) {
                containerClasses.add(clazz.getRawClass());
            }
        }

        if (clazz.isString()) {
            return false;
        }

        logger.debug("Adding dependency class " + clazz.getClassName());
        return memberAnalyzer.analyze(clazz,
                MemberAnalyzer.AnalysisMode.DEPENDENCY, recursionLevel);
    }

    // ----------------------
    // unused old methods
    // ----------------------

    private static Set<Class<?>> loadClasses(Collection<String> classNames) {
        Set<Class<?>> loadedClasses = new LinkedHashSet<>();
        for (String subClass : classNames) {
            try {
                Class<?> subClazz = Class.forName(subClass, false,
                        TestGenerationContext.getInstance().getClassLoaderForSUT());
                if (!TestUsageChecker.canUse(subClazz)) {
                    continue;
                }
                if (subClazz.isInterface()) {
                    continue;
                }
                if (Modifier.isAbstract(subClazz.getModifiers())) {
                    if (!TestClusterUtils.hasStaticGenerator(subClazz)) {
                        continue;
                    }
                }
                Class<?> mock = MockList.getMockClass(subClazz.getCanonicalName());
                if (mock != null) {
                    /*
                     * If we are mocking this class, then such class should not
                     * be used in the generated JUnit test cases, but rather its
                     * mock.
                     */
                    // logger.debug("Adding mock " + mock + " instead of "
                    // + clazz);
                    subClazz = mock;
                } else {

                    if (!TestClusterUtils.checkIfCanUse(subClazz.getCanonicalName())) {
                        continue;
                    }
                }

                loadedClasses.add(subClazz);

            } catch (ClassNotFoundException e) {
                logger.error("Problem for " + Properties.TARGET_CLASS + ". Class not found: " + subClass, e);
                logger.error("Removing class from inheritance tree");
            }
        }
        return loadedClasses;
    }

    /**
     * Update the container classes.
     *
     * @param clazz the class to add
     */
    private void addCastClassForContainer(Class<?> clazz) {
        if (concreteCastClasses.contains(clazz)) {
            return;
        }

        concreteCastClasses.add(clazz);
        // TODO: What if this is generic again?
        genericCastClasses.add(GenericClassFactory.get(clazz));

        CastClassManager.getInstance().addCastClass(clazz, 1);
        TestCluster.getInstance().clearGeneratorCache(GenericClassFactory.get(clazz));
    }

    private List<GenericClass<?>> getAssignableTypes(java.lang.reflect.Type type) {
        List<GenericClass<?>> types = new ArrayList<>();
        for (GenericClass<?> clazz : genericCastClasses) {
            if (clazz.isAssignableTo(type)) {
                logger.debug(clazz + " is assignable to " + type);
                types.add(clazz);
            }
        }
        return types;
    }
}
