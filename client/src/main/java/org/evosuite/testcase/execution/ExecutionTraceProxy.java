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

    @Override
    public void branchPassed(int branch, int bytecodeId, double trueDistance, double falseDistance) {
        copyOnWrite();
        trace.branchPassed(branch, bytecodeId, trueDistance, falseDistance);
    }

    @Override
    public void branchOperandsSeen(int branch, int left, int right, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, left, right, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, int value, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, value, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, Object left, Object right, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, left, right, opcode);
    }

    @Override
    public void branchOperandsSeen(int branch, Object value, int opcode) {
        copyOnWrite();
        trace.branchOperandsSeen(branch, value, opcode);
    }

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

    @Override
    public void definitionPassed(Object object, Object caller, int defId) {
        copyOnWrite();
        trace.definitionPassed(object, caller, defId);
    }

    @Override
    public void enteredMethod(String className, String methodName, Object caller) {
        copyOnWrite();
        trace.enteredMethod(className, methodName, caller);
    }

    @Override
    public void exitMethod(String classname, String methodname) {
        copyOnWrite();
        trace.exitMethod(classname, methodname);
    }

    @Override
    public void finishCalls() {
        copyOnWrite();
        trace.finishCalls();
    }

    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getCoverageData() {
        return trace.getCoverageData();
    }

    @Override
    public Set<Integer> getCoveredFalseBranches() {
        return trace.getCoveredFalseBranches();
    }

    @Override
    public Set<Integer> getCoveredLines(String className) {
        return trace.getCoveredLines(className);
    }

    @Override
    public Set<Integer> getCoveredLines() {
        return trace.getCoveredLines();
    }

    @Override
    public Set<String> getCoveredMethods() {
        return trace.getCoveredMethods();
    }

    @Override
    public Set<String> getCoveredBranchlessMethods() {
        return trace.getCoveredBranchlessMethods();
    }

    @Override
    public Set<Integer> getCoveredPredicates() {
        return trace.getCoveredPredicates();
    }

    @Override
    public Set<Integer> getCoveredTrueBranches() {
        return trace.getCoveredTrueBranches();
    }

    @Override
    public Map<Integer, Integer> getDefinitionExecutionCount() {
        return trace.getDefinitionExecutionCount();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getDefinitionData() {
        return trace.getDefinitionData();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getDefinitionDataObjects() {
        return trace.getDefinitionDataObjects();
    }

    @Override
    public Throwable getExplicitException() {
        return trace.getExplicitException();
    }

    @Override
    public double getFalseDistance(int branchId) {
        return trace.getFalseDistance(branchId);
    }

    @Override
    public Map<Integer, Double> getFalseDistances() {
        return trace.getFalseDistances();
    }

    @Override
    public List<MethodCall> getMethodCalls() {
        return trace.getMethodCalls();
    }

    @Override
    public List<MethodCall> getMethodCalls(String className, String methodName) {
        return trace.getMethodCalls(className, methodName);
    }

    @Override
    public Map<String, Integer> getMethodExecutionCount() {
        return trace.getMethodExecutionCount();
    }

    @Override
    public double getMutationDistance(int mutationId) {
        return trace.getMutationDistance(mutationId);
    }

    @Override
    public Map<Integer, Double> getMutationDistances() {
        return trace.getMutationDistances();
    }

    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedDefinitions(String variableName) {
        return trace.getPassedDefinitions(variableName);
    }

    @Override
    public Map<Integer, HashMap<Integer, Integer>> getPassedUses(String variableName) {
        return trace.getPassedUses(variableName);
    }

    @Override
    public Map<Integer, Integer> getPredicateExecutionCount() {
        return trace.getPredicateExecutionCount();
    }

    @Override
    public Map<String, Map<String, Map<Integer, Integer>>> getReturnData() {
        return trace.getReturnData();
    }

    @Override
    public Set<Integer> getTouchedMutants() {
        return trace.getTouchedMutants();
    }

    @Override
    public ExecutionTrace getTraceForObject(int objectId) {
        return trace.getTraceForObject(objectId);
    }

    @Override
    public ExecutionTrace getTraceInDUCounterRange(DefUse targetDU, boolean wantToCoverTargetDU, int duCounterStart,
                                                   int duCounterEnd) {
        return trace.getTraceInDUCounterRange(targetDU, wantToCoverTargetDU, duCounterStart, duCounterEnd);
    }

    @Override
    public double getTrueDistance(int branchId) {
        return trace.getTrueDistance(branchId);
    }

    @Override
    public Map<Integer, Double> getTrueDistances() {
        return trace.getTrueDistances();
    }

    @Override
    public Map<Integer, BranchOperands> getLastBranchOperands() {
        return trace.getLastBranchOperands();
    }

    @Override
    public BranchOperands getLastBranchOperands(int branchId) {
        return trace.getLastBranchOperands(branchId);
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Integer>>> getUseData() {
        return trace.getUseData();
    }

    @Override
    public Map<String, HashMap<Integer, HashMap<Integer, Object>>> getUseDataObjects() {
        return trace.getUseDataObjects();
    }

    @Override
    public boolean hasFalseDistance(int predicateId) {
        return trace.hasFalseDistance(predicateId);
    }

    @Override
    public boolean hasTrueDistance(int predicateId) {
        return trace.hasTrueDistance(predicateId);
    }

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

    @Override
    public void linePassed(String className, String methodName, int line) {
        copyOnWrite();
        trace.linePassed(className, methodName, line);
    }

    @Override
    public void mutationPassed(int mutationId, double distance) {
        copyOnWrite();
        trace.mutationPassed(mutationId, distance);
    }

    @Override
    public void returnValue(String className, String methodName, int value) {
        copyOnWrite();
        trace.returnValue(className, methodName, value);
    }

    @Override
    public void setExplicitException(Throwable explicitException) {
        copyOnWrite();
        trace.setExplicitException(explicitException);
    }

    @Override
    public void usePassed(Object object, Object caller, int useId) {
        copyOnWrite();
        trace.usePassed(object, caller, useId);
    }

    @Override
    public boolean wasMutationTouched(int mutationId) {
        return trace.wasMutationTouched(mutationId);
    }

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
