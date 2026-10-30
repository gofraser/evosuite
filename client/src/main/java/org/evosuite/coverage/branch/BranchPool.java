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
package org.evosuite.coverage.branch;

import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.CFGMethodAdapter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.PerClassLoader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// TODO: root branches should not be special cases
// every root branch should be a branch just
// like every other branch with it's own branchId and all

/**
 * This class is supposed to hold all the available information concerning
 * Branches.
 * <p>
 * The addBranch()-Method gets called during class analysis. Whenever the
 * BytecodeInstructionPool detects a BytecodeInstruction that corresponds to a
 * Branch in the class under test as defined in
 * BytecodeInstruction.isActualBranch() it calls the registerAsBranch() method
 * of this class which in turn properly registers the instruction within this
 * pool.
 * </p>
 * <p>
 * The registries (branches per method, branch ids, the instruction to branch maps, switch
 * labels, branchless methods, usable methods) describe the same set of branches and are guarded
 * by the monitor of the pool: a registration or a clearing changes all of them in one step, so
 * a reader never sees a branch in one registry and not in another. The getters return
 * snapshots, which stay valid when the pool changes afterwards, so callers can iterate them
 * while classes are loaded and instrumented on other threads. Clearing a class or a method
 * removes its branches from every registry, and the next branch id is the one after the highest
 * id still registered, so instrumenting the class again numbers its branches as a fresh pool
 * would (ids of other classes are never handed out twice).
 * </p>
 * <p>
 * There are two kinds of Branch objects: normal branches and switch case
 * branches. For more details about the difference between these two look at the
 * Branch class.
 * </p>
 *
 * @author Andre Mis
 */
public class BranchPool {

    private static final Logger logger = LoggerFactory.getLogger(BranchPool.class);

    // maps className -> method inside that class -> list of branches inside
    // that method
    private final Map<String, Map<String, List<Branch>>> branchMap = new HashMap<>();

    // set of all known methods without a Branch
    private final Map<String, Map<String, Integer>> branchlessMethods = new HashMap<>();

    // maps the branchIDs assigned by this pool to their respective Branches
    private final Map<Integer, Branch> branchIdMap = new HashMap<>();

    // maps all known branch instructions to their branchId
    private final Map<BytecodeInstruction, Integer> registeredNormalBranches = new HashMap<>();

    // maps all known switch instructions to a list containing all of their
    // associated Branch objects
    private final Map<BytecodeInstruction, List<Branch>> registeredSwitches = new HashMap<>();

    private final Map<BytecodeInstruction, Branch> registeredDefaultCases = new HashMap<>();

    private final Map<LabelNode, List<Branch>> switchLabels = new HashMap<>();

    // maps className -> ids of the usable methods of that class (excludes e.g. synthetic,
    // initializers, private and deprecated methods)
    private final Map<String, Set<String>> methods = new ConcurrentHashMap<>();

    // number of known Branches - used for actualBranchIds
    private int branchCounter = 0;

    private static final PerClassLoader<BranchPool> pools = new PerClassLoader<>();

    /**
     * Returns the BranchPool instance for the given class loader.
     *
     * @param classLoader the class loader.
     * @return the BranchPool instance.
     */
    public static BranchPool getInstance(ClassLoader classLoader) {
        return pools.get(classLoader, k -> new BranchPool());
    }

    /**
     * Drops the pool of the given class loader, so that the branches registered while it
     * instrumented classes (and, through them, the loader itself) can be garbage collected.
     *
     * @param classLoader the class loader whose pool is no longer needed
     */
    public static void release(ClassLoader classLoader) {
        pools.release(classLoader);
    }

    /**
     * Drops the pools of all class loaders.
     */
    public static void clearAll() {
        pools.clear();
    }

    /**
     * Returns the set in which the ids of the usable methods of the given class are recorded,
     * creating it if necessary. The caller keeps it while it instruments the class, so that
     * recording survives the pool being cleared from another thread (e.g. the Swing event queue
     * of the SUT loading a class during generation).
     *
     * @param className the class the methods belong to
     * @return the live set of method ids of that class
     */
    public Set<String> getMethodSet(String className) {
        return methods.computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Returns the number of usable methods of the classes whose name starts with the prefix.
     *
     * @param classNamePrefix class name prefix
     * @return number of methods
     */
    public int getNumMethodsPrefix(String classNamePrefix) {
        int num = 0;
        for (Map.Entry<String, Set<String>> entry : methods.entrySet()) {
            if (entry.getKey().startsWith(classNamePrefix)) {
                num += entry.getValue().size();
            }
        }
        return num;
    }

    /**
     * Returns the number of usable methods of all classes.
     *
     * @return number of methods
     */
    public int getNumMethods() {
        return getNumMethodsPrefix("");
    }

    /**
     * Returns the ids of the usable methods of the classes whose name starts with the prefix.
     *
     * @param classNamePrefix class name prefix
     * @return set of method ids
     */
    public Set<String> getMethodsPrefix(String classNamePrefix) {
        Set<String> matchingMethods = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : methods.entrySet()) {
            if (entry.getKey().startsWith(classNamePrefix)) {
                matchingMethods.addAll(entry.getValue());
            }
        }
        return matchingMethods;
    }
    // fill the pool

    /**
     * Gets called by the CFGMethodAdapter whenever it detects a method without
     * any branches.
     *
     * @param methodName Unique methodName - consisting of &lt;className&gt;.&lt;methodName&gt; -
     *                   of a method without Branches
     */
    public synchronized void addBranchlessMethod(String className, String methodName,
                                    int lineNumber) {
        if (!branchlessMethods.containsKey(className)) {
            branchlessMethods.put(className, new HashMap<>());
        }
        branchlessMethods.get(className).put(methodName, lineNumber);
    }

    /**
     * Called by the BytecodeInstructionPool whenever it detects an instruction
     * that corresponds to a Branch in the class under test as defined by
     * BytecodeInstruction.isActualBranch().
     */
    public void registerAsBranch(BytecodeInstruction instruction) {
        if (!(instruction.isActualBranch())) {
            throw new IllegalArgumentException("CFGVertex of a branch expected");
        }
        if (isKnownAsBranch(instruction)) {
            return;
        }
        if (!DependencyAnalysis.shouldInstrument(instruction.getClassName(),
                instruction.getMethodName())) {
            return;
        }
        if (CFGMethodAdapter.EXCLUDE.contains(instruction.getMethodName())) {
            // A branch in <clinit> or __STATIC_RESET can never be covered by a generated
            // test: both run on class initialization, outside the control of any test case.
            // CFGMethodAdapter already refuses to instrument them for that reason, but it is
            // not the only way instructions reach this pool - GraphPool.registerClass() walks
            // every method of a class to build CFGs for interprocedural analysis, and that
            // path would otherwise turn every static-initializer branch into a coverage goal
            // nothing can satisfy. Enums are the common case, because the compiler-generated
            // <clinit> that fills their values array is branch-heavy.
            return;
        }

        synchronized (this) {
            // another thread may have registered it while the checks above ran
            if (!isKnownAsBranch(instruction)) {
                registerInstruction(instruction);
            }
        }
    }

    private void registerInstruction(BytecodeInstruction v) {
        if (isKnownAsBranch(v)) {
            throw new IllegalStateException(
                    "expect registerInstruction() to be called at most once for each instruction");
        }

        if (v.isBranch()) {
            registerNormalBranchInstruction(v);
        } else if (v.isSwitch()) {
            registerSwitchInstruction(v);
        } else {
            throw new IllegalArgumentException(
                    "expect given instruction to be an actual branch");
        }
    }

    private void registerNormalBranchInstruction(BytecodeInstruction v) {
        if (!v.isBranch()) {
            throw new IllegalArgumentException("normal branch instruction expceted");
        }

        if (registeredNormalBranches.containsKey(v)) {
            throw new IllegalArgumentException(
                    "instruction already registered as a normal branch");
        }

        branchCounter++;
        registeredNormalBranches.put(v, branchCounter);

        Branch b = new Branch(v, branchCounter);
        addBranchToMap(b);
        branchIdMap.put(branchCounter, b);

        logger.info("Branch " + branchCounter + " at line " + v.getLineNumber());
    }

    private void registerSwitchInstruction(BytecodeInstruction v) {
        if (!v.isSwitch()) {
            throw new IllegalArgumentException("expect a switch instruction");
        }

        LabelNode defaultLabel;

        switch (v.getASMNode().getOpcode()) {
            case Opcodes.TABLESWITCH:
                TableSwitchInsnNode tableSwitchNode = (TableSwitchInsnNode) v.getASMNode();
                registerTableSwitchCases(v, tableSwitchNode);
                defaultLabel = tableSwitchNode.dflt;

                break;
            case Opcodes.LOOKUPSWITCH:
                LookupSwitchInsnNode lookupSwitchNode = (LookupSwitchInsnNode) v.getASMNode();
                registerLookupSwitchCases(v, lookupSwitchNode);
                defaultLabel = lookupSwitchNode.dflt;
                break;
            default:
                throw new IllegalStateException(
                        "expect ASMNode of a switch to either be a LOOKUP- or TABLESWITCH");
        }

        registerDefaultCase(v, defaultLabel);
    }

    private void registerDefaultCase(BytecodeInstruction v, LabelNode defaultLabel) {

        if (defaultLabel == null) {
            throw new IllegalStateException("expect variable to bet set");
        }

        Branch defaultBranch = createSwitchCaseBranch(v, null, defaultLabel);
        if (!defaultBranch.isSwitchCaseBranch() || !defaultBranch.isDefaultCase()) {
            throw new IllegalStateException(
                    "expect created branch to be a default case branch of a switch");
        }
    }

    private void registerTableSwitchCases(BytecodeInstruction v,
                                          TableSwitchInsnNode tableSwitchNode) {

        int num = 0;

        for (int i = tableSwitchNode.min; i <= tableSwitchNode.max; i++) {
            LabelNode targetLabel = tableSwitchNode.labels.get(num);
            Branch switchBranch = createSwitchCaseBranch(v, i, targetLabel);
            if (!switchBranch.isSwitchCaseBranch() || !switchBranch.isActualCase()) {
                throw new IllegalStateException(
                        "expect created branch to be an actual case branch of a switch");
            }
            num++;
        }
    }

    private void registerLookupSwitchCases(BytecodeInstruction v,
                                           LookupSwitchInsnNode lookupSwitchNode) {

        for (int i = 0; i < lookupSwitchNode.keys.size(); i++) {
            LabelNode targetLabel = lookupSwitchNode.labels.get(i);
            Branch switchBranch = createSwitchCaseBranch(v,
                    lookupSwitchNode.keys.get(i),
                    targetLabel);
            if (!switchBranch.isSwitchCaseBranch() || !switchBranch.isActualCase()) {
                throw new IllegalStateException(
                        "expect created branch to be an actual case branch of a switch");
            }
        }
    }

    private Branch createSwitchCaseBranch(BytecodeInstruction v,
                                          Integer caseValue, LabelNode targetLabel) {

        branchCounter++;

        Branch switchBranch = new Branch(v, caseValue, branchCounter);
        registerSwitchBranch(v, switchBranch);
        addBranchToMap(switchBranch);
        branchIdMap.put(branchCounter, switchBranch);

        registerSwitchLabel(switchBranch, targetLabel);

        // default case
        if (caseValue == null) {
            if (registeredDefaultCases.containsKey(v)) {
                throw new IllegalStateException(
                        "instruction already registered as a branch");
            }
            registeredDefaultCases.put(v, switchBranch);
        }

        if (!switchBranch.isSwitchCaseBranch()) {
            throw new IllegalStateException("expect created Branch to be a switch branch");
        }

        return switchBranch;
    }

    private void registerSwitchLabel(Branch b, LabelNode targetLabel) {

        if (switchLabels.get(targetLabel) == null) {
            switchLabels.put(targetLabel, new ArrayList<>());
        }

        List<Branch> oldList = switchLabels.get(targetLabel);

        if (oldList.contains(b)) {
            throw new IllegalStateException(
                    "branch already registered for this switch label");
        }

        oldList.add(b);

        // TODO several Branches can map to one Label, so switchLabels should
        // either map from branches to labels, not the other way around. or it
        // should map labels to a list of branches
        // this stems from the fact that empty case: blocks do not have their
        // own label

        // TODO STOPPED HERE

        switchLabels.put(targetLabel, oldList);
    }

    private void registerSwitchBranch(BytecodeInstruction v, Branch switchBranch) {
        if (!v.isSwitch()) {
            throw new IllegalArgumentException("switch instruction expected");
        }

        if (registeredSwitches.get(v) == null) {
            registeredSwitches.put(v, new ArrayList<>());
        }

        List<Branch> oldList = registeredSwitches.get(v);

        if (oldList.contains(switchBranch)) {
            throw new IllegalArgumentException("switch branch already registered  "
                    + switchBranch.toString());
        }

        oldList.add(switchBranch);

        registeredSwitches.put(v, oldList);
    }

    private void addBranchToMap(Branch b) {

        logger.info("Adding to map the branch {}", b);

        String className = b.getClassName();
        String methodName = b.getMethodName();

        if (!branchMap.containsKey(className)) {
            branchMap.put(className, new HashMap<>());
        }
        if (!branchMap.get(className).containsKey(methodName)) {
            branchMap.get(className).put(methodName, new ArrayList<>());
        }
        branchMap.get(className).get(methodName).add(b);
    }

    // retrieve information from the pool

    /**
     * Checks whether the given instruction has Branch objects associated with
     * it.
     * <p>
     * Returns true if the given BytecodeInstruction previously passed a call to
     * registerAsBranch(instruction), false otherwise.
     * </p>
     */
    public synchronized boolean isKnownAsBranch(BytecodeInstruction instruction) {
        return isKnownAsNormalBranchInstruction(instruction)
                || isKnownAsSwitchBranchInstruction(instruction);
    }

    public synchronized boolean isKnownAsNormalBranchInstruction(BytecodeInstruction ins) {

        return registeredNormalBranches.containsKey(ins);
    }

    public synchronized boolean isKnownAsSwitchBranchInstruction(BytecodeInstruction instruction) {

        return registeredSwitches.containsKey(instruction);
    }

    public synchronized int getActualBranchIdForNormalBranchInstruction(BytecodeInstruction ins) {
        if (!isKnownAsNormalBranchInstruction(ins)) {
            throw new IllegalArgumentException(
                    "instruction not registered as a normal branch");
        }

        if (registeredNormalBranches.containsKey(ins)) {
            return registeredNormalBranches.get(ins);
        }

        throw new IllegalStateException(
                "expect registeredNormalBranches to contain a key for each known normal branch instruction");
    }

    public synchronized List<Branch> getCaseBranchesForSwitch(BytecodeInstruction instruction) {
        if (instruction == null) {
            throw new IllegalArgumentException("null given");
        }
        if (!instruction.isSwitch()) {
            throw new IllegalArgumentException("switch instruction expected");
        }
        if (!isKnownAsSwitchBranchInstruction(instruction)) {
            throw new IllegalArgumentException("not registered as a switch instruction");
        }

        return new ArrayList<>(registeredSwitches.get(instruction));
    }

    public synchronized Branch getBranchForInstruction(BytecodeInstruction instruction) {
        if (instruction == null) {
            throw new IllegalArgumentException("null given");
        }
        if (!isKnownAsNormalBranchInstruction(instruction)) {
            throw new IllegalArgumentException(
                    "expect given instruction to be known as a normal branch");
        }

        return getBranch(registeredNormalBranches.get(instruction));
    }

    public synchronized List<Branch> getBranchForLabel(LabelNode label) {

        // TODO see registerSwitchLabel()!

        List<Branch> branches = switchLabels.get(label);
        return branches == null ? null : new ArrayList<>(branches);
    }

    /**
     * Returns the number of known Branches for a given methodName in a given
     * class.
     *
     * @return The number of currently known Branches inside the given method.
     */
    public synchronized int getBranchCountForMethod(String className, String methodName) {
        if (branchMap.get(className) == null) {
            return 0;
        }
        if (branchMap.get(className).get(methodName) == null) {
            return 0;
        }

        return branchMap.get(className).get(methodName).size();
    }

    /**
     * Returns the number of non-artificial (instrumented) branches in a method.
     *
     * @param className the class name.
     * @param methodName the method name.
     * @return the number of non-artificial branches.
     */
    public synchronized int getNonArtificialBranchCountForMethod(String className,
                                                    String methodName) {
        if (branchMap.get(className) == null) {
            return 0;
        }
        if (branchMap.get(className).get(methodName) == null) {
            return 0;
        }

        int num = 0;
        for (Branch b : branchMap.get(className).get(methodName)) {
            if (!b.isInstrumented()) {
                num++;
            }
        }

        return num;
    }

    /**
     * Returns the number of known Branches for a given class.
     *
     * @return The number of currently known Branches inside the given class.
     */
    public synchronized int getBranchCountForClass(String className) {
        if (branchMap.get(className) == null) {
            return 0;
        }
        int total = 0;
        for (String method : branchMap.get(className).keySet()) {
            total += branchMap.get(className).get(method).size();
        }
        return total;
    }

    /**
     * Returns the number of known Branches for a given class.
     *
     * @return The number of currently known Branches inside the given class.
     */
    public synchronized int getBranchCountForPrefix(String prefix) {
        int num = 0;
        for (String className : branchMap.keySet()) {
            if (className.startsWith(prefix)) {
                logger.info("Found matching class for branch count: " + className + "/"
                        + prefix);
                for (String method : branchMap.get(className).keySet()) {
                    num += branchMap.get(className).get(method).size();
                }
            }
        }
        return num;
    }

    /**
     * Returns the number of currently known Branches.
     *
     * @return The number of currently known Branches.
     */
    public synchronized int getBranchCounter() {
        return branchCounter;
    }

    /**
     * Returns the total number of artificial branches in the pool.
     *
     * @return the number of artificial branches.
     */
    public synchronized int getNumArtificialBranches() {
        int num = 0;
        for (Branch b : branchIdMap.values()) {
            if (b.isInstrumented()) {
                num++;
            }
        }

        return num;
    }

    /**
     * Returns the Branch object associated with the given branchID.
     *
     * @param branchId The ID of a branch
     * @return The branch, or null if it does not exist.
     */
    public synchronized Branch getBranch(int branchId) {

        return branchIdMap.get(branchId);
    }

    public synchronized Collection<Branch> getAllBranches() {
        return new ArrayList<>(branchIdMap.values());
    }

    /**
     * Returns a set with all unique methodNames of methods without Branches.
     *
     * @return A set with all unique methodNames of methods without Branches.
     */
    public synchronized Set<String> getBranchlessMethods(String className) {
        if (!branchlessMethods.containsKey(className)) {
            return new LinkedHashSet<>();
        }

        return new LinkedHashSet<>(branchlessMethods.get(className).keySet());
    }

    /**
     * Returns a set with all unique methodNames of methods without Branches.
     *
     * @return A set with all unique methodNames of methods without Branches.
     */
    public synchronized Set<String> getBranchlessMethodsPrefix(String className) {
        Set<String> methods = new LinkedHashSet<>();

        for (String name : branchlessMethods.keySet()) {
            if (name.equals(className) || name.startsWith(className + "$")) {
                methods.addAll(branchlessMethods.get(name).keySet());
            }
        }

        return methods;
    }

    /**
     * Returns a set with all unique methodNames of methods without Branches.
     *
     * @return A set with all unique methodNames of methods without Branches.
     */
    public synchronized Set<String> getBranchlessMethods() {
        Set<String> methods = new LinkedHashSet<>();

        for (String name : branchlessMethods.keySet()) {
            methods.addAll(branchlessMethods.get(name).keySet());
        }

        return methods;
    }

    /**
     * Checks if the given method is known to have no branches.
     *
     * @param className the class name.
     * @param methodName the method name.
     * @return true if the method is branchless.
     */
    public synchronized boolean isBranchlessMethod(String className, String methodName) {
        Map<String, Integer> methodMap = branchlessMethods.get(className);
        if (methodMap != null) {
            return methodMap.containsKey(methodName);
        }
        return false;
    }

    /**
     * Returns the number of methods without Branches for class className.
     *
     * @return The number of methods without Branches.
     */
    public synchronized int getNumBranchlessMethods(String className) {
        if (!branchlessMethods.containsKey(className)) {
            return 0;
        }
        return branchlessMethods.get(className).size();
    }

    /**
     * Returns the total number of methods without branches in the instrumented
     * classes.
     */
    public synchronized int getNumBranchlessMethods() {
        int num = 0;
        for (String name : branchlessMethods.keySet()) {
            num += branchlessMethods.get(name).size();
        }
        return num;
    }

    /**
     * Returns a Set containing all classes for which this pool knows Branches
     * for as Strings.
     */
    public synchronized Set<String> knownClasses() {
        Set<String> r = new LinkedHashSet<>();
        r.addAll(branchMap.keySet());
        r.addAll(branchlessMethods.keySet());

        return r;
    }

    /**
     * Returns a Set containing all methods in the class represented by the
     * given String for which this pool knows Branches for as Strings.
     */
    public synchronized Set<String> knownMethods(String className) {
        Set<String> r = new LinkedHashSet<>();
        Map<String, List<Branch>> methods = branchMap.get(className);
        if (methods != null) {
            r.addAll(methods.keySet());
        }

        return r;
    }

    /**
     * Returns a List containing all Branches in the given class and method.
     * <p>
     * Should no such Branch exist an empty List is returned.
     * </p>
     */
    public synchronized List<Branch> retrieveBranchesInMethod(String className,
                                                 String methodName) {
        List<Branch> r = new ArrayList<>();
        if (branchMap.get(className) == null) {
            return r;
        }
        List<Branch> branches = branchMap.get(className).get(methodName);
        if (branches != null) {
            r.addAll(branches);
        }
        return r;
    }

    public synchronized Branch getDefaultBranchForSwitch(BytecodeInstruction v) {
        if (!v.isSwitch()) {
            throw new IllegalArgumentException("switch instruction expected");
        }
        if (!isKnownAsSwitchBranchInstruction(v)) {
            throw new IllegalArgumentException(
                    "instruction not known to be a switch instruction");
        }
        if (!registeredDefaultCases.containsKey(v)) {
            throw new IllegalArgumentException(
                    "there is no registered default case for this instruction");
        }

        return registeredDefaultCases.get(v);
    }

    /**
     * Forgets all the branch information and the recorded methods of this pool.
     */
    public synchronized void clear() {
        methods.clear();
        branchCounter = 0;
        branchMap.clear();
        branchIdMap.clear();
        branchlessMethods.clear();
        switchLabels.clear();
        registeredDefaultCases.clear();
        registeredNormalBranches.clear();
        registeredSwitches.clear();
    }

    /**
     * Forgets everything this pool knows about the given class.
     *
     * @param className the class to forget
     */
    public synchronized void clear(String className) {
        Map<String, List<Branch>> classBranches = branchMap.remove(className);
        if (classBranches != null) {
            for (List<Branch> branches : classBranches.values()) {
                forgetBranches(branches);
            }
        }
        branchlessMethods.remove(className);
        methods.remove(className);
    }

    /**
     * Forgets everything this pool knows about the given method of a class.
     *
     * @param className  the class of the method
     * @param methodName the method to forget, as name and descriptor
     */
    public synchronized void clear(String className, String methodName) {
        Map<String, List<Branch>> classBranches = branchMap.get(className);
        if (classBranches != null) {
            List<Branch> branches = classBranches.remove(methodName);
            if (branches != null) {
                forgetBranches(branches);
            }
            if (classBranches.isEmpty()) {
                branchMap.remove(className);
            }
        }
        Map<String, Integer> classBranchless = branchlessMethods.get(className);
        if (classBranchless != null) {
            classBranchless.remove(methodName);
            if (classBranchless.isEmpty()) {
                branchlessMethods.remove(className);
            }
        }
        Set<String> classMethods = methods.get(className);
        if (classMethods != null) {
            classMethods.remove(className + "." + methodName);
        }
    }

    // Removes the branches from every registry but branchMap, and lets the next branch id follow
    // the highest one still registered. That reuses the ids of the branches removed last, which
    // is what instrumenting the cleared class again needs to match a fresh pool, and never
    // hands out an id that is still in use.
    private void forgetBranches(List<Branch> branches) {
        Set<Branch> forgotten = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Branch b : branches) {
            forgotten.add(b);
            branchIdMap.remove(b.getActualBranchId());
            registeredNormalBranches.remove(b.getInstruction());
            registeredSwitches.remove(b.getInstruction());
            registeredDefaultCases.remove(b.getInstruction());
        }
        Iterator<List<Branch>> labels = switchLabels.values().iterator();
        while (labels.hasNext()) {
            List<Branch> labelled = labels.next();
            labelled.removeIf(forgotten::contains);
            if (labelled.isEmpty()) {
                labels.remove();
            }
        }
        branchCounter = branchIdMap.isEmpty() ? 0 : Collections.max(branchIdMap.keySet());
    }

}
