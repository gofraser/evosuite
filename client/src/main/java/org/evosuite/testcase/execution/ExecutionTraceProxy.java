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

import org.evosuite.coverage.dataflow.DefUse;
import org.evosuite.setup.CallContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * ExecutionTraceProxy class.
 * </p>
 *
 * @author gordon
 */
public class ExecutionTraceProxy implements ExecutionTrace, Cloneable {

    private ExecutionTraceImpl trace;

    /**
     * <p>
     * Constructor for ExecutionTraceProxy.
     * </p>
     */
    public ExecutionTraceProxy() {
        this.trace = new ExecutionTraceImpl();
    }

    /**
     * <p>
     * Constructor for ExecutionTraceProxy.
     * </p>
     *
     * @param trace a {@link org.evosuite.testcase.execution.ExecutionTraceImpl}
     *              object.
     */
    public ExecutionTraceProxy(ExecutionTraceImpl trace) {
        this.trace = trace;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void branchPassed(int branch, int bytecodeId, double trueDistance, double falseDistance) {
        copyOnWrite();
        trace.branchPassed(branch, bytecodeId, trueDistance, falseDistance);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void branchOperandsSeen(int branch, int left, int right, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, left, right, opcode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void branchOperandsSeen(int branch, int value, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, value, opcode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void branchOperandsSeen(int branch, Object left, Object right, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, left, right, opcode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void branchOperandsSeen(int branch, Object value, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, value, opcode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        if (trace.getProxyCount() > 1) {
            trace.removeProxy();
            trace = new ExecutionTraceImpl();
        } else {
            trace.clear();
        }
    }

    /**
     * <p>
     * copyOnWrite.
     * </p>
     */
    public void copyOnWrite() {
        if (trace.getProxyCount() > 1) {
            trace.removeProxy();
            trace = trace.clone();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void definitionPassed(Object object, Object caller, int defId) {
        copyOnWrite();
        trace.definitionPassed(object, caller, defId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void enteredMethod(String className, String methodName, Object caller) {
        copyOnWrite();
        trace.enteredMethod(className, methodName, caller);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exitMethod(String classname, String methodname) {
        copyOnWrite();
        trace.exitMethod(classname, methodname);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finishCalls() {
        copyOnWrite();
        trace.finishCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getCoverageData() {
        return trace.getCoverageData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getCoveredFalseBranches() {
        return trace.getCoveredFalseBranches();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getCoveredLines(String className) {
        return trace.getCoveredLines(className);
    }

    @Override
    public Set<Integer> getCoveredLines() {
        return trace.getCoveredLines();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getCoveredMethods() {
        return trace.getCoveredMethods();
    }

    @Override
    public Set<String> getCoveredBranchlessMethods() {
        return trace.getCoveredBranchlessMethods();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getCoveredPredicates() {
        return trace.getCoveredPredicates();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getCoveredTrueBranches() {
        return trace.getCoveredTrueBranches();
    }

    @Override
    public Map<Integer, Integer> getDefinitionExecutionCount() {
        return trace.getDefinitionExecutionCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getDefinitionData() {
        return trace.getDefinitionData();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getDefinitionDataObjects() {
        return trace.getDefinitionDataObjects();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable getExplicitException() {
        return trace.getExplicitException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getFalseDistance(int branchId) {
        return trace.getFalseDistance(branchId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Double> getFalseDistances() {
        return trace.getFalseDistances();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MethodCall> getMethodCalls() {
        return trace.getMethodCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MethodCall> getMethodCalls(String className, String methodName) {
        return trace.getMethodCalls(className, methodName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Integer> getMethodExecutionCount() {
        return trace.getMethodExecutionCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getMutationDistance(int mutationId) {
        return trace.getMutationDistance(mutationId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Double> getMutationDistances() {
        return trace.getMutationDistances();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedDefinitions(String variableName) {
        return trace.getPassedDefinitions(variableName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedUses(String variableName) {
        return trace.getPassedUses(variableName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Integer> getPredicateExecutionCount() {
        return trace.getPredicateExecutionCount();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getReturnData() {
        return trace.getReturnData();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Integer> getTouchedMutants() {
        return trace.getTouchedMutants();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionTrace getTraceForObject(int objectId) {
        return trace.getTraceForObject(objectId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionTrace getTraceInDUCounterRange(DefUse targetDU, boolean wantToCoverTargetDU, int duCounterStart,
                                                   int duCounterEnd) {
        return trace.getTraceInDUCounterRange(targetDU, wantToCoverTargetDU, duCounterStart, duCounterEnd);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getTrueDistance(int branchId) {
        return trace.getTrueDistance(branchId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, Double> getTrueDistances() {
        return trace.getTrueDistances();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Integer, BranchOperands> getLastBranchOperands() {
        return trace.getLastBranchOperands();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BranchOperands getLastBranchOperands(int branchId) {
        return trace.getLastBranchOperands(branchId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getUseData() {
        return trace.getUseData();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getUseDataObjects() {
        return trace.getUseDataObjects();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasFalseDistance(int predicateId) {
        return trace.hasFalseDistance(predicateId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasTrueDistance(int predicateId) {
        return trace.hasTrueDistance(predicateId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExecutionTrace lazyClone() {
        ExecutionTraceProxy copy = new ExecutionTraceProxy(trace);
        trace.addProxy();
        return copy;
    }

    @Override
    public ExecutionTrace withoutMethodCalls() {
        return new ExecutionTraceProxy(trace.withoutMethodCalls());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void linePassed(String className, String methodName, int line) {
        copyOnWrite();
        trace.linePassed(className, methodName, line);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mutationPassed(int mutationId, double distance) {
        copyOnWrite();
        trace.mutationPassed(mutationId, distance);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void returnValue(String className, String methodName, int value) {
        copyOnWrite();
        trace.returnValue(className, methodName, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setExplicitException(Throwable explicitException) {
        copyOnWrite();
        trace.setExplicitException(explicitException);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void usePassed(Object object, Object caller, int useId) {
        copyOnWrite();
        trace.usePassed(object, caller, useId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean wasMutationTouched(int mutationId) {
        return trace.wasMutationTouched(mutationId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getPassedUses() {
        return trace.getPassedUses();
    }

    @Override
    public Set<Integer> getPassedDefIDs() {
        return trace.getPassedDefIDs();
    }

    @Override
    public Map<Integer, Map<CallContext, Double>> getTrueDistancesContext() {
        return trace.getTrueDistancesContext();
    }

    @Override
    public Map<Integer, Map<CallContext, Double>> getFalseDistancesContext() {
        return trace.getFalseDistancesContext();
    }

    @Override
    public Map<Integer, Map<CallContext, Integer>> getPredicateContextExecutionCount() {
        return trace.getPredicateContextExecutionCount();
    }

    @Override
    public Map<String, Map<CallContext, Integer>> getMethodContextCount() {
        return trace.getMethodContextCount();
    }

    @Override
    public void putStaticPassed(String classNameWithDots, String fieldName) {
        trace.putStaticPassed(classNameWithDots, fieldName);
    }

    @Override
    public void getStaticPassed(String classNameWithDots, String fieldName) {
        trace.getStaticPassed(classNameWithDots, fieldName);
    }

    @Override
    public Set<String> getClassesWithStaticWrites() {
        return trace.getClassesWithStaticWrites();
    }

    @Override
    public void classInitialized(String classNameWithDots) {
        trace.classInitialized(classNameWithDots);
    }

    @Override
    public Set<String> getClassesWithStaticReads() {
        return trace.getClassesWithStaticReads();
    }

    @Override
    public List<String> getInitializedClasses() {
        return trace.getInitializedClasses();
    }
}
