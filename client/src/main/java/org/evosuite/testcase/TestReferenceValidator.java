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
package org.evosuite.testcase;

import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Structural checks that a test case's variable references can be resolved and copied.
 * They guard offspring in the search, tests parsed from LLM responses and the final suite.
 */
public final class TestReferenceValidator {

    private TestReferenceValidator() {
    }

    /**
     * Returns descriptions of any orphaned {@link VariableReference}s in
     * {@code testCase}. A reference is "orphaned" when no statement in the
     * test case can resolve it — either as a return value (for ordinary refs)
     * or as a referenced parameter (for {@link ConstantValue}). Returns an
     * empty list when every reference resolves.
     *
     * <p>This check is intentionally <em>cache-independent</em>: it does not
     * call {@link VariableReference#getStPosition()}. The cached
     * {@code stPosition} can return a stale-but-in-range value if the
     * change listener was already consumed since the test case mutated, and
     * that stale value would mask the very orphan we are trying to catch
     * (it shows up later inside {@code copy()}/{@code clone()} where the
     * cache is invalidated and recompute throws). Instead, we mirror each
     * subclass's resolution rule against the current test case directly.
     */
    public static List<String> findOrphanedVariableReferences(TestCase testCase) {
        if (testCase == null) {
            return Collections.emptyList();
        }
        int size = testCase.size();
        List<String> orphans = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Statement stmt;
            try {
                stmt = testCase.getStatement(i);
            } catch (Throwable t) {
                orphans.add("statement " + i + ": cannot fetch statement (" + describeThrowable(t) + ")");
                continue;
            }
            Set<VariableReference> refs;
            try {
                refs = stmt.getVariableReferences();
            } catch (Throwable t) {
                orphans.add("statement " + i + " (" + stmt.getClass().getSimpleName()
                        + "): cannot inspect variable references (" + describeThrowable(t) + ")");
                continue;
            }
            if (refs == null) {
                continue;
            }
            for (VariableReference ref : refs) {
                if (ref == null) {
                    continue;
                }
                if (!isResolvable(ref, testCase, 0)) {
                    orphans.add(describeOrphan(i, stmt, ref, size,
                            "no matching defining statement in test case"));
                }
            }
        }
        return orphans;
    }

    /**
     * Mirrors the resolution rules used by each {@code VariableReference}
     * subclass's {@code getStPosition} but works directly against the current
     * test case state, without consulting any cached position. Recursion
     * handles ArrayIndex/FieldReference delegation to their source ref;
     * depth is bounded to defeat any pathological reference cycle.
     */
    private static boolean isResolvable(VariableReference ref, TestCase testCase, int depth) {
        if (ref == null || depth > 8) {
            return false;
        }
        int size = testCase.size();
        boolean staticFieldWithoutSource = ref instanceof FieldReference
                && ((FieldReference) ref).getSource() == null;
        if (ref instanceof ConstantValue || staticFieldWithoutSource) {
            // ConstantValue resolves when referenced as a parameter by some statement. So does a
            // static field read with no source object (eg Integer.MAX_VALUE passed as an
            // argument): FieldReference.getStPosition falls back to the statement using it.
            for (int i = 0; i < size; i++) {
                try {
                    if (testCase.getStatement(i).references(ref)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Fall through; treat as not-here.
                }
            }
            return false;
        }
        // Ordinary refs (incl. VariableReferenceImpl, NullReference, ArrayReference)
        // resolve when they equal some statement's return value.
        for (int i = 0; i < size; i++) {
            VariableReference rv;
            try {
                rv = testCase.getStatement(i).getReturnValue();
            } catch (Throwable ignored) {
                continue;
            }
            if (rv != null && rv.equals(ref)) {
                return true;
            }
        }
        if (ref instanceof ArrayIndex) {
            return isResolvable(((ArrayIndex) ref).getArray(), testCase, depth + 1);
        }
        if (ref instanceof FieldReference) {
            return isResolvable(((FieldReference) ref).getSource(), testCase, depth + 1);
        }
        return false;
    }

    /**
     * Returns descriptions of any statements whose used VariableReferences
     * resolve only via the statement itself (or a later statement), and would
     * therefore crash inside {@code Statement.copy(newTestCase, offset)} when
     * the test case is replayed positionally — most commonly during
     * ObjectPool sequence insertion or crossover-driven appends. Returns an
     * empty list when every used reference has an earlier defining statement.
     *
     * <p>Background: {@link AssignmentStatement#copy(TestCase, int)} resolves
     * each used reference via {@code getStatement(getStPosition() + offset)}
     * on the destination test case. That destination only holds positions
     * {@code [0, P + offset)} at the moment statement {@code P} is being
     * copied, so the lookup is safe only when the reference's first defining
     * statement sits strictly before {@code P}. For plain
     * {@link org.evosuite.testcase.variable.VariableReferenceImpl} retvals
     * (the LLM parser's {@code x = y} variable-assignment path), salvage
     * pipelines that {@code chop} or remove the original declaration leave
     * the AssignmentStatement as the only match — the lookup then lands at
     * {@code P + offset == destination.size()} and throws
     * {@code IllegalArgumentException} ("wrong position N, total N").
     */
    public static List<String> findUnsafelyCopyableStatements(TestCase testCase) {
        if (testCase == null) {
            return Collections.emptyList();
        }
        int size = testCase.size();
        List<String> issues = new ArrayList<>();
        for (int p = 0; p < size; p++) {
            Statement stmt;
            try {
                stmt = testCase.getStatement(p);
            } catch (Throwable t) {
                issues.add("statement " + p + ": cannot fetch statement (" + describeThrowable(t) + ")");
                continue;
            }
            if (!(stmt instanceof AssignmentStatement)) {
                // Other statement copies derive their retval freshly and only
                // consult parameter/callee positions, which are checked by
                // findOrphanedVariableReferences. The strict-ordering hazard
                // is unique to AssignmentStatement's reuse of an existing
                // VariableReference as its retval.
                continue;
            }
            AssignmentStatement as = (AssignmentStatement) stmt;
            // The value is copied like the retval: a field or array element value is
            // looked up through its source object or array, never by its own position.
            VariableReference param = as.getValue();
            VariableReference paramLookup = copyLookupReference(param);
            if (paramLookup != null && !hasDefiningStatementBefore(paramLookup, testCase, p)) {
                String label = (paramLookup == param) ? "parameter" :
                        (param instanceof ArrayIndex ? "array source of parameter"
                                : "source of parameter field");
                issues.add(describeUnsafeRef(p, stmt, paramLookup, label));
            }
            VariableReference retval = stmt.getReturnValue();
            VariableReference resolvable = copyLookupReference(retval);
            if (resolvable != null && !hasDefiningStatementBefore(resolvable, testCase, p)) {
                String label = (resolvable == retval) ? "retval" :
                        (retval instanceof ArrayIndex ? "array source of retval"
                                : "source of retval field");
                issues.add(describeUnsafeRef(p, stmt, resolvable, label));
            }
        }
        return issues;
    }

    /**
     * Returns the reference whose {@code getStPosition()} drives the
     * destination lookup inside {@code ref.copy()}. ArrayIndex and
     * FieldReference delegate to their underlying array/source position,
     * repeatedly for a chain such as {@code a.b.c}; a plain VariableReference
     * is consulted directly. Returns null when nothing is looked up, as for
     * a static field.
     */
    private static VariableReference copyLookupReference(VariableReference ref) {
        while (ref instanceof ArrayIndex || ref instanceof FieldReference) {
            ref = (ref instanceof ArrayIndex) ? ((ArrayIndex) ref).getArray()
                    : ((FieldReference) ref).getSource();
        }
        return ref;
    }

    private static boolean hasDefiningStatementBefore(VariableReference ref,
                                                      TestCase testCase, int boundary) {
        if (ref == null || ref instanceof ConstantValue) {
            return true;
        }
        for (int i = 0; i < boundary; i++) {
            VariableReference rv;
            try {
                rv = testCase.getStatement(i).getReturnValue();
            } catch (Throwable ignored) {
                continue;
            }
            if (rv != null && rv.equals(ref)) {
                return true;
            }
        }
        return false;
    }

    private static String describeUnsafeRef(int stmtIdx, Statement stmt, VariableReference ref,
                                            String role) {
        String typeName;
        try {
            typeName = ref.getType() == null ? "<unknown>" : ref.getType().getTypeName();
        } catch (Throwable t) {
            typeName = "<unknown>";
        }
        return "statement " + stmtIdx + " (" + stmt.getClass().getSimpleName()
                + ") " + role + " of type " + typeName
                + " [" + ref.getClass().getSimpleName()
                + "] has no defining statement before position " + stmtIdx
                + " (Statement.copy would fail at this position)";
    }

    private static String describeOrphan(int stmtIdx, Statement stmt, VariableReference ref,
                                         int size, String reason) {
        String typeName;
        try {
            typeName = ref.getType() == null ? "<unknown>" : ref.getType().getTypeName();
        } catch (Throwable t) {
            typeName = "<unknown>";
        }
        return "statement " + stmtIdx + " (" + stmt.getClass().getSimpleName()
                + ") references orphan variable of type " + typeName
                + " [" + ref.getClass().getSimpleName() + "]: " + reason
                + " (test case size " + size + ")";
    }

    private static String describeThrowable(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
