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
package org.evosuite.coverage.method;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.evosuite.coverage.GuardBranchDistance;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.InterproceduralGraphAnalysis;
import org.evosuite.graphs.interprocedural.interestednode.OCGInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.objectweb.asm.tree.FieldInsnNode;

/**
 * Graded object-construction distance for {@code METHODNOEXCEPTION} goals (EvoObj enhancement E9).
 * Plain method-no-exception fitness is a binary signal (0 once the method is called without
 * throwing, 1 otherwise), which gives the search no gradient toward <em>building</em> the objects
 * and state a method needs before it can be called cleanly. This replaces that binary signal with a
 * layered distance whose bands never overlap:
 *
 * <ul>
 *   <li><b>(d) covered</b> — a clean call exists → {@code 0} (invariant: exactly the binary goal).</li>
 *   <li><b>(c) call threw inside the method</b> → {@code ν(guard)} in (0,1): normalized branch
 *       distance of the control-dependent branch of the throwing instruction ("how close to not
 *       throwing").</li>
 *   <li><b>(b) an earlier statement threw before the call</b> → {@code 1 + λ} in (1,2), with
 *       {@code λ = 0.5·(unexecuted prefix / prefix length) + 0.5·ν(guard of the failing statement)}
 *       (the legitimacy-distance idea).</li>
 *   <li><b>(a) no call to the method</b> → {@code 2 + σ} in [2,3], with
 *       {@code σ = 1 − matched entry-OCG field nodes / relevant entry-OCG field nodes}: mutation is
 *       rewarded for adding the constructors/setters that establish the method's entry state.</li>
 * </ul>
 *
 * <p>Notable simplifications (documented): the guard distance uses the plain trace-based context
 * branch distance (the optional FBranch F3 upgrade is not ported); layer (a) matching is a
 * read-only approximation of {@code UsedReferenceSearcher} (bean-style setter / direct field write)
 * since that searcher mutates the test and there is no receiver yet; leaves are not weighted by
 * setter score; and stagnation-based cost gating is not applied — grading is always
 * on when the flag is set. These affect efficiency/precision, not the band invariants.
 */
public final class ConstructionFitness {

    /** Safety cap on the number of entry-OCG nodes considered (anti-bloat guard). */
    private static final int MAX_ENTRY_NODES = 150;

    private ConstructionFitness() {
    }

    /**
     * The layered construction distance in {@code [0,3]} for the goal {@code className.methodName}
     * on {@code result}. {@code 0} if and only if the plain binary goal is covered.
     */
    public static double gradedDistance(String className, String methodName,
            TestCase test, ExecutionResult result) {
        Set<Integer> exceptionPositions = result.getPositionsWhereExceptionsWereThrown();
        int executed = result.getExecutedStatements();

        int cleanCall = -1;       // call that executed without throwing -> covered
        int callThrewItself = -1; // call executed but threw inside the method
        int callNotExecuted = -1; // call present but an earlier statement blocked it
        for (Statement stmt : test) {
            if (!(stmt instanceof MethodStatement || stmt instanceof ConstructorStatement)) {
                continue;
            }
            EntityWithParametersStatement ps = (EntityWithParametersStatement) stmt;
            String stmtMethod = ps.getMethodName() + ps.getDescriptor();
            if (!className.equals(ps.getDeclaringClassName()) || !methodName.equals(stmtMethod)) {
                continue;
            }
            int pos = stmt.getPosition();
            boolean threw = exceptionPositions.contains(pos);
            boolean didExecute = pos < executed;
            if (didExecute && !threw) {
                cleanCall = pos;
                break;
            }
            if (threw && callThrewItself < 0) {
                callThrewItself = pos;
            } else if (!didExecute && callNotExecuted < 0) {
                callNotExecuted = pos;
            }
        }

        // (d) covered
        if (cleanCall >= 0) {
            return 0.0;
        }

        // (c) the call itself executed and threw inside the method
        if (callThrewItself >= 0) {
            return clampOpen01(GuardBranchDistance.avoidanceDistance(result, callThrewItself));
        }

        // (b) the call is present but an earlier statement threw before it could execute
        if (callNotExecuted >= 0) {
            Integer firstException = result.getFirstPositionOfThrownException();
            double prefixLength = Math.max(1, callNotExecuted);
            double unexecuted = callNotExecuted - executed;
            double prefixFraction = clamp01(unexecuted / prefixLength);
            double guard = firstException == null
                    ? GuardBranchDistance.NO_GUARD_DISTANCE
                    : GuardBranchDistance.avoidanceDistance(result, firstException);
            double lambda = 0.5 * prefixFraction + 0.5 * guard;
            return 1.0 + clampOpen01(lambda);
        }

        // (a) no call to the method at all
        double sigma = 1.0 - matchedEntryFraction(className, methodName, test);
        return 2.0 + clamp01(sigma);
    }

    /**
     * Fraction of the method's relevant entry-OCG field nodes that the test already establishes
     * (constructs or writes). {@code 1.0} when there is no object state to build.
     */
    static double matchedEntryFraction(String className, String methodName, TestCase test) {
        Set<String> relevantFields = relevantEntryFields(className, methodName);
        if (relevantFields.isEmpty()) {
            return 1.0;
        }
        int matched = 0;
        for (String ownerAndName : relevantFields) {
            int sep = ownerAndName.lastIndexOf('#');
            String owner = ownerAndName.substring(0, sep);
            String name = ownerAndName.substring(sep + 1);
            if (testWritesField(test, owner, name)) {
                matched++;
            }
        }
        return matched / (double) relevantFields.size();
    }

    /** {@code owner#name} keys of the field state variables the method's branches depend on. */
    private static Set<String> relevantEntryFields(String className, String methodName) {
        Map<Branch, Set<DepVariable>> perBranch =
                InterproceduralGraphAnalysis.analyzeMethodOnDemand(className, methodName, new OCGInterestedNodeFilter());
        Set<String> fields = new LinkedHashSet<>();
        for (Set<DepVariable> vars : perBranch.values()) {
            if (vars == null) {
                continue;
            }
            for (DepVariable var : vars) {
                if (fields.size() >= MAX_ENTRY_NODES) {
                    return fields;
                }
                if ((var.isInstaceField() || var.isStaticField())
                        && var.getInstruction() != null
                        && var.getInstruction().getASMNode() instanceof FieldInsnNode) {
                    FieldInsnNode fieldNode = (FieldInsnNode) var.getInstruction().getASMNode();
                    fields.add(fieldNode.owner.replace('/', '.') + "#" + fieldNode.name);
                }
            }
        }
        return fields;
    }

    /**
     * Read-only check that {@code test} establishes {@code owner.name}: a direct field assignment or
     * a bean-style {@code setName(...)} call on the owning type. (Approximation of the mutating
     * {@code UsedReferenceSearcher}; see class note.)
     */
    private static boolean testWritesField(TestCase test, String owner, String name) {
        String beanSetter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Statement stmt : test) {
            if (stmt instanceof AssignmentStatement) {
                VariableReference ref = stmt.getReturnValue();
                if (ref instanceof FieldReference) {
                    FieldReference fieldRef = (FieldReference) ref;
                    if (fieldRef.getField() != null && name.equals(fieldRef.getField().getName())) {
                        return true;
                    }
                }
            } else if (stmt instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) stmt;
                if (beanSetter.equals(ms.getMethodName()) && owner.equals(ms.getDeclaringClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double clamp01(double x) {
        if (x < 0.0) {
            return 0.0;
        }
        return Math.min(x, 1.0);
    }

    /** Clamp strictly into (0,1) so a band's value never collides with the next band's boundary. */
    private static double clampOpen01(double x) {
        if (x <= 0.0) {
            return 1e-3;
        }
        if (x >= 1.0) {
            return 1.0 - 1e-3;
        }
        return x;
    }
}
