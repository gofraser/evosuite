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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a method call in an execution trace.
 *
 * @author Gordon Fraser
 */
public class MethodCall implements Cloneable {
    public String className;
    public String methodName;
    public List<Integer> lineTrace;
    public List<Integer> branchTrace;
    public List<Double> trueDistanceTrace;
    public List<Double> falseDistanceTrace;
    public List<Integer> defuseCounterTrace;
    public int methodId;
    public int callingObjectId;
    public int callDepth;

    /**
     * Per-branch summary of the distance traces, computed lazily by
     * {@link #getMinBranchDistances()}. The trace lists are appended to while the
     * test executes and trimmed by the def-use analysis, so the summary is tied to
     * the trace length it was computed for and rebuilt when that changes.
     */
    private volatile Map<Integer, MinBranchDistances> minBranchDistances;
    private volatile int minBranchDistancesTraceSize = -1;

    /**
     * The smallest true and false distance recorded for one branch over all the
     * times it was executed in this call.
     */
    public static final class MinBranchDistances {
        private double trueMinDistance = Double.MAX_VALUE;
        private double falseMinDistance = Double.MAX_VALUE;

        public double getTrueMinDistance() {
            return trueMinDistance;
        }

        public double getFalseMinDistance() {
            return falseMinDistance;
        }
    }

    /**
     * <p>Constructor for MethodCall.</p>
     *
     * @param className       a {@link java.lang.String} object.
     * @param methodName      a {@link java.lang.String} object.
     * @param methodId        a int.
     * @param callingObjectId a int.
     * @param callDepth       a int.
     */
    public MethodCall(String className, String methodName, int methodId,
                      int callingObjectId, int callDepth) {
        this.className = className;
        this.methodName = methodName;
        lineTrace = new ArrayList<>();
        branchTrace = new ArrayList<>();
        trueDistanceTrace = new ArrayList<>();
        falseDistanceTrace = new ArrayList<>();
        defuseCounterTrace = new ArrayList<>();
        this.methodId = methodId;
        this.callingObjectId = callingObjectId;
        this.callDepth = callDepth;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuffer ret = new StringBuffer();
        ret.append(className);
        ret.append(":");
        ret.append(methodName);
        ret.append("\n");
        // ret.append("Lines: ");
        // for(Integer line : line_trace) {
        // ret.append(" "+line);
        // }
        // ret.append("\n");
        ret.append("Branches: ");
        for (Integer branch : branchTrace) {
            ret.append(" " + branch);
        }
        ret.append("\n");
        ret.append("True Distances: ");
        for (Double distance : trueDistanceTrace) {
            ret.append(" " + distance);
        }
        ret.append("\nFalse Distances: ");
        for (Double distance : falseDistanceTrace) {
            ret.append(" " + distance);
        }
        ret.append("\n");
        return ret.toString();
    }

    /**
     * <p>explain.</p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String explain() {
        // TODO StringBuilder-explain() functions to construct string templates like explainList()
        StringBuffer r = new StringBuffer();
        r.append(className);
        r.append(":");
        r.append(methodName);
        r.append("\n");
        r.append("Lines: ");
        if (lineTrace == null) {
            r.append("null");
        } else {
            for (Integer line : lineTrace) {
                r.append("\t" + line);
            }
            r.append("\n");
        }
        r.append("Branches: ");
        if (branchTrace == null) {
            r.append("null");
        } else {
            for (Integer branch : branchTrace) {
                r.append("\t" + branch);
            }
            r.append("\n");
        }
        r.append("True Distances: ");
        if (trueDistanceTrace == null) {
            r.append("null");
        } else {
            for (Double distance : trueDistanceTrace) {
                r.append("\t" + distance);
            }
            r.append("\n");
        }
        r.append("False Distances: ");
        if (falseDistanceTrace == null) {
            r.append("null");
        } else {
            for (Double distance : falseDistanceTrace) {
                r.append("\t" + distance);
            }
            r.append("\n");
        }
        r.append("DefUse Trace:");
        if (defuseCounterTrace == null) {
            r.append("null");
        } else {
            for (Integer duCounter : defuseCounterTrace) {
                r.append("\t" + duCounter);
            }
            r.append("\n");
        }
        return r.toString();
    }

    /**
     * {@inheritDoc}
     */
    /**
     * Returns, for every branch executed in this call, the minimum true and false
     * distance over all its executions.
     *
     * <p>The result depends only on the trace, not on the coverage goal asking for
     * it, so it is computed once and shared by every goal's fitness evaluation. A
     * test that drives a loop to the iteration limit leaves a trace of ~10^5 entries
     * here; rebuilding this map per goal (hundreds of goals per test) used to eat the
     * whole search budget on such classes.
     *
     * @return the per-branch minimum distances, keyed by actual branch id
     */
    public Map<Integer, MinBranchDistances> getMinBranchDistances() {
        int size = Math.min(branchTrace.size(),
                Math.min(trueDistanceTrace.size(), falseDistanceTrace.size()));
        Map<Integer, MinBranchDistances> cached = minBranchDistances;
        if (cached != null && minBranchDistancesTraceSize == size) {
            return cached;
        }
        Map<Integer, MinBranchDistances> result = new HashMap<>();
        for (int pos = 0; pos < size; pos++) {
            MinBranchDistances distances = result.computeIfAbsent(branchTrace.get(pos),
                    ignored -> new MinBranchDistances());
            distances.trueMinDistance = Math.min(distances.trueMinDistance, trueDistanceTrace.get(pos));
            distances.falseMinDistance = Math.min(distances.falseMinDistance, falseDistanceTrace.get(pos));
        }
        minBranchDistancesTraceSize = size;
        minBranchDistances = result;
        return result;
    }

    @Override
    public MethodCall clone() {
        MethodCall copy = new MethodCall(className, methodName, methodId,
                callingObjectId, callDepth);
        copy.lineTrace = new ArrayList<>(lineTrace);
        copy.branchTrace = new ArrayList<>(branchTrace);
        copy.trueDistanceTrace = new ArrayList<>(trueDistanceTrace);
        copy.falseDistanceTrace = new ArrayList<>(falseDistanceTrace);
        copy.defuseCounterTrace = new ArrayList<>(defuseCounterTrace);
        return copy;
    }
}
