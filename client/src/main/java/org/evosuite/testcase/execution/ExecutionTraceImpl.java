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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.FitnessFunctions;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.coverage.dataflow.DefUse;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.dataflow.Definition;
import org.evosuite.coverage.dataflow.Use;
import org.evosuite.setup.CallContext;
import org.evosuite.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;

/**
 * Keep a trace of the program execution.
 *
 * @author Gordon Fraser
 */
public class ExecutionTraceImpl implements ExecutionTrace, Cloneable {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionTraceImpl.class);

    /**
     * Constant <code>traceCalls=false</code>.
     */
    public static boolean traceCalls = false;

    /**
     * Constant <code>disableContext=false</code>.
     */
    public static boolean disableContext = false;

    /**
     * Constant <code>traceCoverage=true</code>.
     */
    public static boolean traceCoverage = true;

    /**
     * Whether the call contexts of method entries and branches are recorded. The context of
     * each pushed call is kept up to date exactly when it is, so a branch never has to rebuild
     * the context from the whole stack.
     */
    private static boolean isContextTracked() {
        return !disableContext
                && (Properties.INSTRUMENT_CONTEXT || Properties.INSTRUMENT_METHOD_CALLS
                || ArrayUtil.contains(Properties.CRITERION, Criterion.IBRANCH)
                || ArrayUtil.contains(Properties.CRITERION, Criterion.CBRANCH));
    }

    private static void checkSaneCall(MethodCall call) {
        if (!((call.trueDistanceTrace.size() == call.falseDistanceTrace.size())
                && (call.falseDistanceTrace.size() == call.defuseCounterTrace.size())
                && (call.defuseCounterTrace.size() == call.branchTrace.size()))) {
            throw new IllegalStateException("insane MethodCall: traces should all be of equal size. " + call.explain());
        }

    }

    /**
     * Enable context.
     */
    public static void enableContext() {
        disableContext = false;
    }

    /**
     * Disable context.
     */
    public static void disableContext() {
        disableTraceCalls();
        disableContext = true;
    }

    public static void disableTraceCalls() {
        traceCalls = false;
    }

    public static void enableTraceCalls() {
        traceCalls = true;
    }

    public static boolean isTraceCallsEnabled() {
        return traceCalls;
    }

    public static void enableTraceCoverage() {
        traceCoverage = true;
    }

    /**
     * Removes from the given ExecutionTrace all finished_calls with an index in
     * removableCalls.
     */
    private static void removeFinishCalls(ExecutionTraceImpl trace, ArrayList<Integer> removableCalls) {
        Collections.sort(removableCalls);
        for (int i = removableCalls.size() - 1; i >= 0; i--) {
            int toRemove = removableCalls.get(i);
            MethodCall removed = trace.finishedCalls.remove(toRemove);
            if (removed == null) {
                throw new IllegalStateException("trace.finished_calls not allowed to contain null");
            }
        }
    }

    /**
     * Removes from the given MethodCall all trace information with an index in
     * removableIndices.
     */
    private static void removeFromFinishCall(MethodCall call, ArrayList<Integer> removableIndices) {
        checkSaneCall(call);

        Collections.sort(removableIndices);
        for (int i = removableIndices.size() - 1; i >= 0; i--) {
            int removableIndex = removableIndices.get(i);
            Integer removedBranch = call.branchTrace.remove(removableIndex);
            Double removedTrue = call.trueDistanceTrace.remove(removableIndex);
            Double removedFalse = call.falseDistanceTrace.remove(removableIndex);
            Integer removedCounter = call.defuseCounterTrace.remove(removableIndex);
            if ((removedCounter == null) || (removedBranch == null) || (removedTrue == null)
                    || (removedFalse == null)) {
                throw new IllegalStateException("trace.finished_calls-traces not allowed to contain null");
            }
        }
    }

    // Coverage information
    private Map<String, Map<String, Map<Integer, Integer>>> coverage = Collections
            .synchronizedMap(new HashMap<>());

    private Map<Integer, Integer> coveredFalse = Collections.synchronizedMap(new HashMap<>());

    private Map<String, Integer> coveredMethods = Collections.synchronizedMap(new HashMap<>());

    private Map<String, Integer> coveredBranchlessMethods = Collections.synchronizedMap(new HashMap<>());

    private Map<Integer, Integer> coveredPredicates = Collections.synchronizedMap(new HashMap<>());

    private Map<Integer, Integer> coveredTrue = Collections.synchronizedMap(new HashMap<>());

    private Map<Integer, Integer> coveredDefs = Collections.synchronizedMap(new HashMap<>());

    private Map<Integer, Map<CallContext, Double>> coveredTrueContext = Collections
            .synchronizedMap(new HashMap<>());

    private Map<Integer, Map<CallContext, Double>> coveredFalseContext = Collections
            .synchronizedMap(new HashMap<>());

    private Map<Integer, Map<CallContext, Integer>> coveredPredicateContext = Collections
            .synchronizedMap(new HashMap<>());

    private Map<String, Map<CallContext, Integer>> coveredMethodContext = Collections
            .synchronizedMap(new HashMap<>());

    // number of seen Definitions and uses for indexing purposes
    private int duCounter = 0;
    // The last explicitly thrown exception is kept here
    private Throwable explicitException = null;

    private Map<Integer, Double> falseDistances = Collections.synchronizedMap(new HashMap<>());
    // finished_calls;
    private List<MethodCall> finishedCalls = Collections.synchronizedList(new ArrayList<>());

    /**
     * {@link #finishedCalls} grouped by "className.methodName", built on demand by
     * {@link #getMethodCalls(String, String)}. The list grows while the test runs and is
     * replaced by {@link #clear()} and trimmed by the def-use analysis, so the index
     * remembers which list object and length it was built from and is rebuilt when either
     * changes.
     */
    private volatile Map<String, List<MethodCall>> callsByMethod;
    private List<MethodCall> callsByMethodSource;
    private int callsByMethodSize = -1;
    private Map<Integer, Object> knownCallerObjects = Collections.synchronizedMap(new HashMap<>());
    // to differentiate between different MethodCalls
    private int methodId = 0;
    private Map<Integer, Double> mutantDistances = Collections.synchronizedMap(new HashMap<>());
    // for defuse-coverage it is important to keep track of all the objects that
    // called the ExecutionTracer
    private int objectCounter = 0;
    // for each Variable-Name these maps hold the data for which objectID
    // at which time (duCounter) which Definition or Use was passed
    private Map<String, HashMap<Integer, HashMap<Integer, Integer>>> passedDefinitions = Collections
            .synchronizedMap(new HashMap<>());
    private Map<String, HashMap<Integer, HashMap<Integer, Integer>>> passedUses = Collections
            .synchronizedMap(new HashMap<>());

    private Map<String, HashMap<Integer, HashMap<Integer, Object>>> passedDefinitionObject = Collections
            .synchronizedMap(new HashMap<>());
    private Map<String, HashMap<Integer, HashMap<Integer, Object>>> passedUseObject = Collections
            .synchronizedMap(new HashMap<>());

    private int proxyCount = 1;
    // Data information
    private Map<String, Map<String, Map<Integer, Integer>>> returnData = Collections
            .synchronizedMap(new HashMap<>());

    // active calls
    LinkedList<MethodCall> stack = new LinkedList<>();

    private Set<Integer> touchedMutants = Collections.synchronizedSet(new HashSet<>());

    private Map<Integer, Double> trueDistances = Collections.synchronizedMap(new HashMap<>());


    /**
     * Index i holds the most recent evaluation of branch id i, or null if that branch was
     * never evaluated (or operand recording is off). Lazily allocated: {@code null} until the
     * first record, so a trace that never records pays nothing. Branch ids are small dense
     * ints starting at 1 (see {@link org.evosuite.coverage.branch.BranchPool}), so a plain
     * array indexed by branch id avoids the boxing and hashing a {@code Map<Integer, ...>}
     * would cost on every recorded branch evaluation.
     */
    private BranchOperandSlot[] branchOperandSlots;

    public ExecutionTraceImpl() {
        stack.add(new MethodCall("", "", 0, 0, 0)); // Main method
    }

    public void addProxy() {
        proxyCount++;
    }

    public void removeProxy() {
        proxyCount--;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Add branch to currently active method call.</p>
     */
    @Override
    public void branchPassed(int branch, int bytecodeId, double trueDistance, double falseDistance) {

        assert (trueDistance >= 0.0);
        assert (falseDistance >= 0.0);
        updateTopStackMethodCall(branch, bytecodeId, trueDistance, falseDistance);

        if (traceCoverage) {
            coveredPredicates.merge(branch, 1, Integer::sum);

            if (trueDistance == 0.0) {
                coveredTrue.merge(branch, 1, Integer::sum);
            }

            if (falseDistance == 0.0) {
                coveredFalse.merge(branch, 1, Integer::sum);
            }
        }

        trueDistances.merge(branch, trueDistance, Math::min);
        falseDistances.merge(branch, falseDistance, Math::min);

        if (isContextTracked()) {
            updateBranchContextMaps(branch, trueDistance, falseDistance);
        }
    }

    @Override
    public void branchOperandsSeen(int branch, int left, int right, int opcode) {
        slotFor(branch).setInts(left, right, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, int value, int opcode) {
        slotFor(branch).setInt(value, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, Object left, Object right, int opcode) {
        slotFor(branch).setObjects(left, right, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, Object value, int opcode) {
        slotFor(branch).setObject(value, opcode);
    }

    /**
     * Returns the (reused) slot for {@code branch}, growing and lazily allocating
     * {@link #branchOperandSlots} as needed. Never allocates a new slot for a branch that
     * already has one, so recording the same branch repeatedly costs no more than the field
     * writes in {@link BranchOperandSlot}.
     */
    private BranchOperandSlot slotFor(int branch) {
        if (branch < 0) {
            throw new IllegalArgumentException("branch ids are non-negative pool ids, got " + branch);
        }
        if (branchOperandSlots == null) {
            branchOperandSlots = new BranchOperandSlot[Math.max(branch + 1, 16)];
        } else if (branch >= branchOperandSlots.length) {
            int newLength = Math.max(branchOperandSlots.length * 2, branch + 1);
            branchOperandSlots = Arrays.copyOf(branchOperandSlots, newLength);
        }
        BranchOperandSlot slot = branchOperandSlots[branch];
        if (slot == null) {
            slot = new BranchOperandSlot();
            branchOperandSlots[branch] = slot;
        }
        return slot;
    }

    /**
     * Summary.
     * @param branch the branch.
     * @param trueDistance the true branch distance.
     * @param falseDistance the false branch distance.
     */
    private void updateBranchContextMaps(int branch, double trueDistance, double falseDistance) {
        if (!coveredPredicateContext.containsKey(branch)) {
            coveredPredicateContext.put(branch, new HashMap<>());
            coveredTrueContext.put(branch, new HashMap<>());
            coveredFalseContext.put(branch, new HashMap<>());
        }
        CallContext context = new CallContext(stack);

        coveredPredicateContext.get(branch).merge(context, 1, Integer::sum);
        coveredTrueContext.get(branch).merge(context, trueDistance, Math::min);
        coveredFalseContext.get(branch).merge(context, falseDistance, Math::min);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reset to 0.</p>
     */
    @Override
    public void clear() {
        finishedCalls = new ArrayList<>();
        stack = new LinkedList<>();

        stack.add(new MethodCall("", "", 0, 0, 0)); // Main method
        coverage = new HashMap<>();
        returnData = new HashMap<>();

        methodId = 0;
        duCounter = 0;
        objectCounter = 0;
        knownCallerObjects = new HashMap<>();
        trueDistances = new HashMap<>();
        falseDistances = new HashMap<>();
        branchOperandSlots = null;
        mutantDistances = new HashMap<>();
        touchedMutants = new HashSet<>();
        coveredMethods = new HashMap<>();
        coveredBranchlessMethods = new HashMap<>();
        coveredPredicates = new HashMap<>();
        coveredTrue = new HashMap<>();
        coveredFalse = new HashMap<>();
        coveredDefs = new HashMap<>();
        passedDefinitions = new HashMap<>();
        passedUses = new HashMap<>();
        passedDefinitionObject = new HashMap<>();
        passedUseObject = new HashMap<>();
        coveredTrueContext = new HashMap<>();
        coveredFalseContext = new HashMap<>();
        coveredPredicateContext = new HashMap<>();

        synchronized (staticStateLock) {
            initializedClasses = new ArrayList<>();
            classesWithStaticReads = new HashSet<>();
            classesWithStaticWrites = new HashSet<>();
        }

        coveredMethodContext.clear();
        explicitException = null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create a deep copy.</p>
     */
    @Override
    public ExecutionTraceImpl clone() {
        return clone(true);
    }

    @Override
    public ExecutionTraceImpl withoutMethodCalls() {
        return clone(false);
    }

    private ExecutionTraceImpl clone(boolean includeMethodCalls) {

        ExecutionTraceImpl copy = new ExecutionTraceImpl();
        if (includeMethodCalls) {
            for (MethodCall call : finishedCalls) {
                copy.finishedCalls.add(call.clone());
            }
        }
        copy.coverage = new HashMap<>();
        if (coverage != null) {
            copy.coverage.putAll(coverage);
        }
        copy.returnData = new HashMap<>();
        copy.returnData.putAll(returnData);
        /*
         * if(stack != null && !stack.isEmpty() && stack.peek().method_name !=
         * null && stack.peek().method_name.equals("")) { logger.info(
         * "Copying main method"); copy.finished_calls.add(stack.peek());
     }
         */
        copy.trueDistances.putAll(trueDistances);
        copy.falseDistances.putAll(falseDistances);
        if (branchOperandSlots != null) {
            copy.branchOperandSlots = new BranchOperandSlot[branchOperandSlots.length];
            for (int i = 0; i < branchOperandSlots.length; i++) {
                if (branchOperandSlots[i] != null) {
                    copy.branchOperandSlots[i] = branchOperandSlots[i].copy();
                }
            }
        }
        copy.coveredMethods.putAll(coveredMethods);
        copy.coveredBranchlessMethods.putAll(coveredBranchlessMethods);
        copy.coveredPredicates.putAll(coveredPredicates);
        copy.coveredTrue.putAll(coveredTrue);
        copy.coveredFalse.putAll(coveredFalse);
        copy.coveredDefs.putAll(coveredDefs);
        copy.touchedMutants.addAll(touchedMutants);
        copy.mutantDistances.putAll(mutantDistances);
        copy.passedDefinitions.putAll(passedDefinitions);
        copy.passedUses.putAll(passedUses);
        copy.passedDefinitionObject.putAll(passedDefinitionObject);
        copy.passedUseObject.putAll(passedUseObject);

        copy.coveredTrueContext.putAll(coveredTrueContext);
        copy.coveredFalseContext.putAll(coveredFalseContext);
        copy.coveredPredicateContext.putAll(coveredPredicateContext);
        
        for (Entry<String, Map<CallContext, Integer>> entry : coveredMethodContext.entrySet()) {
            copy.coveredMethodContext.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }


        copy.explicitException = explicitException;

        synchronized (staticStateLock) {
            copy.initializedClasses.addAll(initializedClasses);
            copy.classesWithStaticReads.addAll(classesWithStaticReads);
            copy.classesWithStaticWrites.addAll(classesWithStaticWrites);
        }

        copy.methodId = methodId;
        copy.duCounter = duCounter;
        copy.objectCounter = objectCounter;
        copy.knownCallerObjects.putAll(knownCallerObjects);
        copy.proxyCount = 1;
        return copy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Adds Definition-Use-Coverage trace information for the given definition.</p>
     *
     * <p>Registers the given caller-Object Traces the occurrence of the given
     * definition in the passedDefs-field Sets the given definition as the
     * currently active one for the definitionVariable in the
     * activeDefinitions-field Adds fake trace information to the currently
     * active MethodCall in this.stack.
     */
    @Override
    public void definitionPassed(Object object, Object caller, int defId) {

        if (!traceCalls) {
            return;
        }

        Definition def = DefUsePool.getDefinitionByDefId(defId);
        if (def == null) {
            throw new IllegalStateException("expect DefUsePool to known defIDs that are passed by instrumented code");
        }
        coveredDefs.merge(defId, 0, (count, first) -> count + 1);
        String varName = def.getVariableName();

        int objectId = registerObject(caller);

        // if this is a static variable, treat objectID as zero for consistency
        // in the representation of static data
        if (objectId != 0 && def.isStaticDefUse()) {
            objectId = 0;
        }
        if (passedDefinitions.get(varName) == null) {
            passedDefinitions.put(varName, new HashMap<>());
            passedDefinitionObject.put(varName, new HashMap<>());
        }
        HashMap<Integer, Integer> defs = passedDefinitions.get(varName).get(objectId);
        HashMap<Integer, Object> defsObject = passedDefinitionObject.get(varName).get(objectId);
        if (defs == null) {
            defs = new HashMap<>();
            defsObject = new HashMap<>();
        }
        defs.put(duCounter, defId);
        defsObject.put(duCounter, object);
        passedDefinitions.get(varName).put(objectId, defs);
        passedDefinitionObject.get(varName).put(objectId, defsObject);

        // logger.trace(duCounter+": set active definition for var
        // "+def.getDUVariableName()+" on object "+objectID+" to Def "+defID);
        duCounter++;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Add a new method call to stack.</p>
     */
    @Override
    public void enteredMethod(String className, String methodName, Object caller) {
        if (traceCoverage) {
            String id = className + "." + methodName;
            coveredMethods.merge(id, 1, Integer::sum);

            if (BranchPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .isBranchlessMethod(className, id)) {
                coveredBranchlessMethods.merge(id, 1, Integer::sum);
            }
        }
        if (!className.isEmpty() && !methodName.isEmpty()) {
            int callingObjectId = registerObject(caller);
            MethodCall call = new MethodCall(className, methodName, methodId, callingObjectId, stack.size());
            methodId++;
            // TODO: Skip this?
            if (traceCalls) {
                if (FitnessFunctions.usesDefUse(Properties.CRITERION)) {
                    call.branchTrace.add(-1);
                    call.trueDistanceTrace.add(1.0);
                    call.falseDistanceTrace.add(0.0);
                    call.defuseCounterTrace.add(duCounter);
                    // TODO line_trace ?
                }
            }
            boolean contextEnabled = isContextTracked();
            if (contextEnabled && !stack.isEmpty()) {
                CallContext.extend(stack.peek(), call, stack.size() == 1);
            }
            stack.push(call);

            if (contextEnabled) {
                updateMethodContextMaps(className, methodName, caller);
            }
        }
    }

    /**
     * Summary.
     * @param className the name of the class.
     * @param methodName the name of the method.
     * @param caller the caller.
     */
    private void updateMethodContextMaps(String className, String methodName, Object caller) {
        String id = className + "." + methodName;
        CallContext context = new CallContext(stack);

        coveredMethodContext.computeIfAbsent(id, k -> new HashMap<>()).merge(context, 1, Integer::sum);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ExecutionTraceImpl other = (ExecutionTraceImpl) obj;
        if (coverage == null) {
            if (other.coverage != null) {
                return false;
            }
        } else if (!coverage.equals(other.coverage)) {
            return false;
        }
        if (finishedCalls == null) {
            if (other.finishedCalls != null) {
                return false;
            }
        } else if (!finishedCalls.equals(other.finishedCalls)) {
            return false;
        }
        if (returnData == null) {
            if (other.returnData != null) {
                return false;
            }
        } else if (!returnData.equals(other.returnData)) {
            return false;
        }
        if (stack == null) {
            return other.stack == null;
        } else {
            return stack.equals(other.stack);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pop last method call from stack.</p>
     */
    @Override
    public void exitMethod(String classname, String methodname) {
        if (!classname.isEmpty() && !methodname.isEmpty()) {
            if (!stack.isEmpty() && !(stack.peek().methodName.equals(methodname))) {
                // Handle cases where unexpected calls are on the stack
                if (stack.peek().methodName.isEmpty() && !stack.peek().branchTrace.isEmpty()) {
                    finishCall(popCall());
                } else {
                    // Usually, this happens if we use mutation testing and
                    // the mutation causes an unexpected exception or
                    // timeout
                    popCall();
                }
            } else {
                finishCall(popCall());
            }
        }
    }

    /**
     * Pops the call on top of the stack. Only calls on the stack read their context, and a
     * completed call kept in the trace would otherwise hold on to the context chain of every
     * call that was below it.
     */
    private MethodCall popCall() {
        MethodCall call = stack.pop();
        call.releaseContext();
        return call;
    }

    /**
     * Keeps a completed call only while per-call tracing is on. Without it the record
     * carries nothing beyond what {@link #getCoveredMethods()} already says, yet on a
     * call-heavy target (jackson's TokenBuffer: thousands of calls per test) the empty
     * records alone ran the minimiser out of heap.
     */
    private void finishCall(MethodCall call) {
        if (traceCalls) {
            finishedCalls.add(call);
        }
    }

    @Override
    public synchronized void finishCalls() {
        logger.debug("At the end, we have " + stack.size() + " calls left on stack");
        while (!stack.isEmpty()) {
            finishCall(popCall());
        }
    }

    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getCoverageData() {
        return coverage;
    }

    @Override
    public Set<Integer> getCoveredFalseBranches() {
        Set<Integer> covered = new HashSet<>();
        for (Entry<Integer, Double> entry : falseDistances.entrySet()) {
            if (entry.getValue() == 0.0) {
                covered.add(entry.getKey());
            }
        }

        return covered;
    }

    @Override
    public Set<Integer> getCoveredLines(String className) {
        Set<Integer> coveredLines = new HashSet<>();
        for (Entry<String, Map<String, Map<Integer, Integer>>> entry : coverage.entrySet()) {
            if ((entry.getKey().equals(className))
                    || // is it a internal class of 'className' ?
                    (entry.getKey().startsWith(className + "$"))) {
                for (Map<Integer, Integer> methodentry : entry.getValue().values()) {
                    coveredLines.addAll(methodentry.keySet());
                }
            }
        }
        return coveredLines;
    }

    @Override
    public Set<Integer> getCoveredLines() {
        return this.getCoveredLines(Properties.TARGET_CLASS);
    }

    @Override
    public Set<String> getCoveredMethods() {
        return coveredMethods.keySet();
    }

    @Override
    public Set<String> getCoveredBranchlessMethods() {
        return coveredBranchlessMethods.keySet();
    }

    @Override
    public Set<Integer> getCoveredPredicates() {
        return coveredPredicates.keySet();
    }

    @Override
    public Set<Integer> getCoveredTrueBranches() {
        Set<Integer> covered = new HashSet<>();
        for (Entry<Integer, Double> entry : trueDistances.entrySet()) {
            if (entry.getValue() == 0.0) {
                covered.add(entry.getKey());
            }
        }

        return covered;
    }

    @Override
    public Map<Integer, Integer> getDefinitionExecutionCount() {
        return coveredDefs;
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getDefinitionData() {
        return passedDefinitions;
    }

    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getDefinitionDataObjects() {
        return passedDefinitionObject;
    }

    @Override
    public Throwable getExplicitException() {
        return explicitException;
    }

    @Override
    public double getFalseDistance(int branchId) {
        return falseDistances.get(branchId);
    }

    @Override
    public Map<Integer, Double> getFalseDistances() {
        return falseDistances;
    }

    @Override
    public List<MethodCall> getMethodCalls() {
        return finishedCalls;
    }

    @Override
    public List<MethodCall> getMethodCalls(String className, String methodName) {
        List<MethodCall> calls = finishedCalls;
        Map<String, List<MethodCall>> index;
        synchronized (calls) {
            if (callsByMethod == null || callsByMethodSource != calls || callsByMethodSize != calls.size()) {
                index = new HashMap<>();
                for (MethodCall call : calls) {
                    index.computeIfAbsent(call.className + "." + call.methodName, k -> new ArrayList<>()).add(call);
                }
                callsByMethodSource = calls;
                callsByMethodSize = calls.size();
                callsByMethod = index;
            } else {
                index = callsByMethod;
            }
        }
        List<MethodCall> result = index.get(className + "." + methodName);
        return result == null ? Collections.emptyList() : result;
    }

    @Override
    public Map<String, Integer> getMethodExecutionCount() {
        return coveredMethods;
    }

    @Override
    public double getMutationDistance(int mutationId) {
        return mutantDistances.get(mutationId);
    }

    @Override
    public Map<Integer, Double> getMutationDistances() {
        return mutantDistances;
    }

    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedDefinitions(String variableName) {
        return passedDefinitions.get(variableName);
    }

    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedUses(String variableName) {
        return passedUses.get(variableName);
    }

    @Override
    public Map<Integer, Integer> getPredicateExecutionCount() {
        return coveredPredicates;
    }

    public int getProxyCount() {
        return proxyCount;
    }

    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getReturnData() {
        return returnData;
    }

    @Override
    public Set<Integer> getTouchedMutants() {
        return touchedMutants;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a copy of this trace where all MethodCall-information traced from
     * objects other then the one identified by the given objectID is removed
     * from the finished_calls-field.</p>
     *
     * <p>WARNING: this will not affect this.true_distances and other fields of
     * ExecutionTrace this only affects the finished_calls field (which should
     * suffice for BranchCoverageFitness-calculation).</p>
     */
    @Override
    public ExecutionTrace getTraceForObject(int objectId) {
        ExecutionTraceImpl r = clone();
        ArrayList<Integer> removableCalls = new ArrayList<>();
        for (int i = 0; i < r.finishedCalls.size(); i++) {
            MethodCall call = r.finishedCalls.get(i);
            if ((call.callingObjectId != objectId) && (call.callingObjectId != 0)) {
                removableCalls.add(i);
            }
        }
        removeFinishCalls(r, removableCalls);
        return new ExecutionTraceProxy(r);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a copy of this trace where all MethodCall-information associated
     * with duCounters outside the range of the given duCounter-Start and -End
     * is removed from the finished_calls-traces.</p>
     *
     * <p>finished_calls without any point in the trace at which the given
     * duCounter range is hit are removed completely.</p>
     *
     * <p>Also traces for methods other then the one that holds the given targetDU
     * are removed as well as trace information that would pass the branch of
     * the given targetDU If wantToCoverTargetDU is false instead those
     * targetDUBranch information is removed that would pass the alternative
     * branch of targetDU.</p>
     *
     * <p>The latter is because this method only gets called when the given
     * targetDU was not active in the given duCounter-range if and only if
     * wantToCoverTargetDU is set, and since useFitness calculation is on branch
     * level and the branch of the targetDU can be passed before the targetDU is
     * passed this can lead to a flawed branchFitness.</p>
     *
     * <p>WARNING: this will not affect this.true_distances and other fields of
     * ExecutionTrace this only affects the finished_calls field (which should
     * suffice for BranchCoverageFitness-calculation).</p>
     */
    @Override
    public ExecutionTrace getTraceInDUCounterRange(DefUse targetDU, boolean wantToCoverTargetDU, int duCounterStart,
                                                   int duCounterEnd) {

        if (duCounterStart > duCounterEnd) {
            throw new IllegalArgumentException("start has to be lesser or equal end");
            /*
             * // DONE: bug // this still has a major flaw: s.
             * MeanTestClass.mean(): // right now its like we map branches to
             * activeDefenitions // but especially in the root branch of a
             * method // activeDefenitions change during execution time // FIX:
             * in order to avoid these false positives remove all information //
             * for a certain branch if some information for that branch is
             * supposed to be removed // subTodo since branchPassed() only gets
             * called when a branch is passed initially // fake calls to
             * branchPassed() have to be made whenever a DU is passed // s.
             * definitionPassed(), usePassed() and
             * addFakeActiveMethodCallInformation()
             *
             * // DONE: new bug // turns out thats an over-approximation that
             * makes it // impossible to cover some potentially coverable goals
             *
             * // completely new: // if your definition gets overwritten in a
             * trace // the resulting fitness should be the fitness of not
             * taking the branch with the overwriting definition // DONE: in
             * order to do that don't remove older trace information for an
             * overwritten branch // but rather set the true and false distance
             * of that previous branch information to the distance of not taking
             * the overwriting branch // done differently: s.
             * DefUseCoverageTestFitness.getFitness()
             */
        }

        ExecutionTraceImpl r = clone();
        Branch targetDUBranch = targetDU.getControlDependentBranch();
        ArrayList<Integer> removableCalls = new ArrayList<>();
        for (int callPos = 0; callPos < r.finishedCalls.size(); callPos++) {
            MethodCall call = r.finishedCalls.get(callPos);
            // check if call is for the method of targetDU
            if (!call.methodName.equals(targetDU.getMethodName())) {
                removableCalls.add(callPos);
                continue;
            }
            ArrayList<Integer> removableIndices = new ArrayList<>();
            for (int i = 0; i < call.defuseCounterTrace.size(); i++) {
                int currentDUCounter = call.defuseCounterTrace.get(i);
                int currentBranchBytecode = call.branchTrace.get(i);

                if (currentDUCounter < duCounterStart || currentDUCounter > duCounterEnd) {
                    removableIndices.add(i);
                } else if (currentBranchBytecode == targetDUBranch.getInstruction().getInstructionId()) {
                    // only remove this point in the trace if it would cover
                    // targetDU
                    boolean targetExpressionValue = targetDU.getControlDependentBranchExpressionValue();
                    if (targetExpressionValue) {
                        if (call.trueDistanceTrace.get(i) == 0.0) {
                            removableIndices.add(i);
                        }
                    } else {
                        if (call.falseDistanceTrace.get(i) == 0.0) {
                            removableIndices.add(i);
                        }
                    }

                }
            }
            removeFromFinishCall(call, removableIndices);
            if (call.defuseCounterTrace.size() == 0) {
                removableCalls.add(callPos);
            }
        }
        removeFinishCalls(r, removableCalls);
        return new ExecutionTraceProxy(r);
    }

    @Override
    public double getTrueDistance(int branchId) {
        return trueDistances.get(branchId);
    }

    @Override
    public Map<Integer, Double> getTrueDistances() {
        return trueDistances;
    }

    @Override
    public Map<Integer, BranchOperands> getLastBranchOperands() {
        if (branchOperandSlots == null) {
            return Collections.emptyMap();
        }
        Map<Integer, BranchOperands> result = new HashMap<>();
        for (int branch = 0; branch < branchOperandSlots.length; branch++) {
            BranchOperandSlot slot = branchOperandSlots[branch];
            if (slot != null) {
                result.put(branch, slot.toBranchOperands());
            }
        }
        return result;
    }

    @Override
    public BranchOperands getLastBranchOperands(int branchId) {
        if (branchOperandSlots == null || branchId < 0 || branchId >= branchOperandSlots.length) {
            return null;
        }
        BranchOperandSlot slot = branchOperandSlots[branchId];
        return slot == null ? null : slot.toBranchOperands();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getUseData() {
        return passedUses;
    }

    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getUseDataObjects() {
        return passedUseObject;
    }

    @Override
    public boolean hasFalseDistance(int predicateId) {
        return falseDistances.containsKey(predicateId);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((coverage == null) ? 0 : coverage.hashCode());
        result = prime * result + ((finishedCalls == null) ? 0 : finishedCalls.hashCode());
        result = prime * result + ((returnData == null) ? 0 : returnData.hashCode());
        result = prime * result + ((stack == null) ? 0 : stack.hashCode());
        return result;
    }

    @Override
    public boolean hasTrueDistance(int predicateId) {
        return trueDistances.containsKey(predicateId);
    }

    @Override
    public ExecutionTrace lazyClone() {
        addProxy();
        return new ExecutionTraceProxy(this);
    }

    private boolean stackHasMethod(String methodName) {
        for (MethodCall call : stack) {
            if (call.methodName.equals(methodName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Add line to currently active method call.</p>
     */
    @Override
    public void linePassed(String className, String methodName, int line) {
        if (traceCalls) {
            if (stack.isEmpty()) {
                logger.info("Method stack is empty: " + className + "." + methodName + " - l" + line); // TODO
                // switch
                // back
                // logger.debug to
                // logger.warn
            } else {
                boolean empty = false;
                if (!stack.peek().methodName.equals(methodName)) {
                    if (stack.peek().methodName.equals("")) {
                        return;
                    }

                    if (stackHasMethod(methodName)) {
                        do {
                            logger.debug("Popping method " + stack.peek().methodName + " because we were looking for "
                                    + methodName);
                            finishedCalls.add(popCall());
                        } while (!stack.isEmpty() && !stack.peek().methodName.equals(methodName)
                                && !stack.peek().methodName.equals(""));
                    } else {

                        logger.warn("Popping method " + stack.peek().methodName + " because we were looking for "
                                + methodName);
                        logger.warn("Current stack: " + stack);
                        finishedCalls.add(popCall());
                    }
                    if (stack.isEmpty()) {
                        logger.warn("Method stack is empty: " + className + "." + methodName + " - l" + line); // TODO
                        // switch
                        // back
                        // logger.debug to
                        // logger.warn
                        empty = true;
                    }
                }
                if (!empty) {
                    stack.peek().lineTrace.add(line);
                }
            }
        }
        if (traceCoverage) {
            coverage.computeIfAbsent(className, k -> new HashMap<>())
                    .computeIfAbsent(methodName, k -> new HashMap<>())
                    .merge(line, 1, Integer::sum);
        }
    }

    @Override
    public void mutationPassed(int mutationId, double distance) {

        touchedMutants.add(mutationId);
        mutantDistances.merge(mutationId, distance, Math::min);
    }

    /**
     * Returns the objectId for the given object.
     *
     * <p>The ExecutionTracer keeps track of all objects it gets called from in
     * order to distinguish them later in the fitness calculation for the
     * defuse-Coverage-Criterion.</p>
     */
    private int registerObject(Object caller) {
        if (caller == null) {
            return 0;
        }
        for (Integer objectId : knownCallerObjects.keySet()) {
            if (knownCallerObjects.get(objectId) == caller) {
                return objectId;
            }
        }
        // object unknown so far
        objectCounter++;
        knownCallerObjects.put(objectCounter, caller);
        return objectCounter;
    }

    @Override
    public void returnValue(String className, String methodName, int value) {
        returnData.computeIfAbsent(className, k -> new HashMap<>())
                .computeIfAbsent(methodName, k -> new HashMap<>())
                .merge(value, 1, Integer::sum);
    }

    @Override
    public void setExplicitException(Throwable explicitException) {
        this.explicitException = explicitException;
    }

    @Override
    public String toString() {
        StringBuffer ret = new StringBuffer();
        for (MethodCall m : finishedCalls) {
            ret.append(m);
        }
        ret.append("\nCovered methods: ");
        for (Entry<String, Integer> entry : coveredMethods.entrySet()) {
            ret.append(entry.getKey() + ": " + entry.getValue() + ", ");
        }
        ret.append("\nCovered predicates: ");
        for (Entry<Integer, Integer> entry : coveredPredicates.entrySet()) {
            ret.append(entry.getKey() + ": " + entry.getValue() + ", ");
        }
        ret.append("\nTrue distances: ");
        for (Entry<Integer, Double> entry : trueDistances.entrySet()) {
            ret.append(entry.getKey() + ": " + entry.getValue() + ", ");
        }
        ret.append("\nFalse distances: ");
        for (Entry<Integer, Double> entry : falseDistances.entrySet()) {
            ret.append(entry.getKey() + ": " + entry.getValue() + ", ");
        }
        return ret.toString();
    }

    /**
     * Adds trace information to the active MethodCall in this.stack.
     */
    private void updateTopStackMethodCall(int branch, int bytecodeId, double trueDistance, double falseDistance) {

        if (traceCalls) {
            if (stack.isEmpty()) {
                return;
            }
            stack.peek().branchTrace.add(branch); // was: bytecode_id
            stack.peek().trueDistanceTrace.add(trueDistance);
            stack.peek().falseDistanceTrace.add(falseDistance);
            assert ((trueDistance == 0.0) || (falseDistance == 0.0));
            // TODO line_trace ?
            if (FitnessFunctions.usesDefUse(Properties.CRITERION)) {
                stack.peek().defuseCounterTrace.add(duCounter);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Adds Definition-Use-Coverage trace information for the given use.</p>
     *
     * <p>Registers the given caller-Object Traces the occurrence of the given use
     * in the passedUses-field.</p>
     */
    @Override
    public void usePassed(Object object, Object caller, int useId) {

        if (!traceCalls) { // TODO ???
            return;
        }

        Use use = DefUsePool.getUseByUseId(useId);

        int objectId = registerObject(caller);
        // if this is a static variable, treat objectID as zero for consistency
        // in the representation of static data
        if (objectId != 0) {
            if (use == null) {
                throw new IllegalStateException(
                        "expect DefUsePool to known defIDs that are passed by instrumented code");
            }
            if (use.isStaticDefUse()) {
                objectId = 0;
            }
        }
        String varName = use.getVariableName();
        if (passedUses.get(varName) == null) {
            passedUses.put(varName, new HashMap<>());
            passedUseObject.put(varName, new HashMap<>());
        }

        HashMap<Integer, Integer> uses = passedUses.get(varName).get(objectId);
        HashMap<Integer, Object> usesObject = passedUseObject.get(varName).get(objectId);
        if (uses == null) {
            uses = new HashMap<>();
            usesObject = new HashMap<>();
        }

        uses.put(duCounter, useId);
        usesObject.put(duCounter, object);
        passedUses.get(varName).put(objectId, uses);
        passedUseObject.get(varName).put(objectId, usesObject);
        duCounter++;
    }

    @Override
    public boolean wasMutationTouched(int mutationId) {
        return touchedMutants.contains(mutationId);
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getPassedUses() {
        return passedUses;
    }

    // Map<String, HashMap<Integer, HashMap<Integer, Integer>>>

    @Override
    public Set<Integer> getPassedDefIDs() {
        Set<Integer> defs = new HashSet<>();
        for (HashMap<Integer, HashMap<Integer, Integer>> classDefs : passedDefinitions.values()) {
            for (HashMap<Integer, Integer> currentDefs : classDefs.values()) {
                defs.addAll(currentDefs.values());
            }
        }
        return defs;
    }

    @Override
    public Map<Integer, Map<CallContext, Double>> getTrueDistancesContext() {
        return coveredTrueContext;
    }

    @Override
    public Map<Integer, Map<CallContext, Double>> getFalseDistancesContext() {
        return coveredFalseContext;
    }

    @Override
    public Map<Integer, Map<CallContext, Integer>> getPredicateContextExecutionCount() {
        return coveredPredicateContext;
    }

    @Override
    public Map<String, Map<CallContext, Integer>> getMethodContextCount() {
        return coveredMethodContext;
    }

    /**
     * This set keeps those classes that have a static write (i.e. PUTSTATIC)
     * during test execution.
     */
    private final Object staticStateLock = new Object();

    private Set<String> classesWithStaticWrites = new HashSet<>();

    @Override
    public void putStaticPassed(String classNameWithDots, String fieldName) {
        synchronized (staticStateLock) {
            classesWithStaticWrites.add(classNameWithDots);
        }
    }

    /**
     * This set keeps those classes that have a static read (i.e. GETSTATIC)
     * during test execution.
     */
    private Set<String> classesWithStaticReads = new HashSet<>();

    @Override
    public void getStaticPassed(String classNameWithDots, String fieldName) {
        synchronized (staticStateLock) {
            classesWithStaticReads.add(classNameWithDots);
        }
    }

    @Override
    public Set<String> getClassesWithStaticWrites() {
        synchronized (staticStateLock) {
            return new HashSet<>(classesWithStaticWrites);
        }
    }

    /**
     * This field keeps the names of those classes that were initialized (ie
     * clinit was completed during this test execution). The list has no
     * repetitions.
     */
    private List<String> initializedClasses = new LinkedList<>();

    /**
     * Adds the class to the list of those classes that were initialized during
     * this test execution. The class is not added if it was already contained
     * in the list.
     */
    @Override
    public void classInitialized(String classNameWithDots) {
        synchronized (staticStateLock) {
            if (!initializedClasses.contains(classNameWithDots)) {
                initializedClasses.add(classNameWithDots);
            }
        }
    }

    @Override
    public List<String> getInitializedClasses() {
        synchronized (staticStateLock) {
            return new LinkedList<>(initializedClasses);
        }
    }

    @Override
    public Set<String> getClassesWithStaticReads() {
        synchronized (staticStateLock) {
            return new HashSet<>(classesWithStaticReads);
        }
    }

}
