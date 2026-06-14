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
package org.evosuite.ga.diversity;

import org.evosuite.Properties;
import org.evosuite.Properties.SpeciationMetric;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.MethodCall;

import java.util.*;

/**
 * Jaccard-distance-based species distance implementation supporting
 * all speciation metrics defined in {@link SpeciationMetric}.
 */
public class JaccardSpeciesDistance implements SpeciesDistance {

    private final SpeciationMetric metric;
    private final double phenotypicWeight;

    public JaccardSpeciesDistance() {
        this(Properties.SPECIATION_METRIC, Properties.SPECIATION_HYBRID_PHENOTYPIC_WEIGHT);
    }

    public JaccardSpeciesDistance(SpeciationMetric metric, double phenotypicWeight) {
        this.metric = metric;
        this.phenotypicWeight = phenotypicWeight;
    }

    @Override
    public double distance(TestChromosome a, TestChromosome b) {
        switch (metric) {
            case TRACE_BRANCH_JACCARD:
                return traceBranchJaccard(a, b);
            case TRACE_LINE_JACCARD:
                return traceLineJaccard(a, b);
            case GOAL_JACCARD:
                return goalJaccard(a, b);
            case METHOD_CALL_JACCARD:
                return methodCallJaccard(a, b);
            case HYBRID:
                double pheno = traceBranchJaccard(a, b);
                double geno = methodCallJaccard(a, b);
                return phenotypicWeight * pheno + (1.0 - phenotypicWeight) * geno;
            default:
                return traceBranchJaccard(a, b);
        }
    }

    private double traceBranchJaccard(TestChromosome a, TestChromosome b) {
        Set<Integer> setA = getCoveredBranches(a);
        Set<Integer> setB = getCoveredBranches(b);
        return jaccardDistance(setA, setB);
    }

    private double traceLineJaccard(TestChromosome a, TestChromosome b) {
        Set<Integer> setA = getCoveredLines(a);
        Set<Integer> setB = getCoveredLines(b);
        return jaccardDistance(setA, setB);
    }

    private double goalJaccard(TestChromosome a, TestChromosome b) {
        Set<FitnessFunction<?>> setA = getCoveredGoals(a);
        Set<FitnessFunction<?>> setB = getCoveredGoals(b);
        return jaccardDistance(setA, setB);
    }

    private double methodCallJaccard(TestChromosome a, TestChromosome b) {
        Set<String> setA = getMethodSignatures(a);
        Set<String> setB = getMethodSignatures(b);
        return jaccardDistance(setA, setB);
    }

    /**
     * Get the set of branches covered by the chromosome.
     *
     * @param tc the chromosome
     * @return the set of covered branch IDs
     */
    public static Set<Integer> getCoveredBranches(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        ExecutionTrace trace = result.getTrace();
        Set<Integer> branches = new HashSet<>();
        branches.addAll(trace.getCoveredTrueBranches());
        branches.addAll(trace.getCoveredFalseBranches());
        return branches;
    }

    /**
     * Get the set of lines covered by the chromosome.
     *
     * @param tc the chromosome
     * @return the set of covered line numbers
     */
    public static Set<Integer> getCoveredLines(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(result.getTrace().getCoveredLines());
    }

    /**
     * Get the set of fitness goals covered by the chromosome.
     *
     * @param tc the chromosome
     * @return the set of covered fitness functions
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Set<FitnessFunction<?>> getCoveredGoals(TestChromosome tc) {
        Map<FitnessFunction<TestChromosome>, Double> fitnessValues = tc.getFitnessValues();
        Set<FitnessFunction<?>> covered = new HashSet<>();
        for (Map.Entry<FitnessFunction<TestChromosome>, Double> entry : fitnessValues.entrySet()) {
            if (entry.getValue() == 0.0) {
                covered.add((FitnessFunction) entry.getKey());
            }
        }
        return covered;
    }

    /**
     * Get the set of method signatures called during the chromosome execution.
     *
     * @param tc the chromosome
     * @return the set of method signatures
     */
    public static Set<String> getMethodSignatures(TestChromosome tc) {
        ExecutionResult result = tc.getLastExecutionResult();
        if (result == null || result.getTrace() == null) {
            return Collections.emptySet();
        }
        Set<String> sigs = new HashSet<>();
        for (MethodCall mc : result.getTrace().getMethodCalls()) {
            sigs.add(mc.className + "." + mc.methodName);
        }
        return sigs;
    }

    /** Cap on a single string literal's contribution, to bound feature length. */
    private static final int MAX_LITERAL_LEN = 24;

    /**
     * Extract a structural genotype feature set from the chromosome's
     * <em>static</em> test code (its {@link org.evosuite.testcase.TestCase}
     * statements), as opposed to the execution-trace projection used by
     * {@link #getMethodSignatures}. Each feature is a short token; the Jaccard
     * distance over these sets is a finer, truer genotype distance that
     * separates tests differing only in input/constant values or call shape —
     * variation that the coarse called-method projection collapses.
     *
     * <p>Feature kinds (variable names are deliberately never included, so
     * {@code var0/var1} renaming and ordering noise cannot inflate distance):
     * <ul>
     *   <li>{@code M:Class.name(desc)} — method call, with JVM descriptor
     *       (parameter types)</li>
     *   <li>{@code C:Class(desc)} — constructor call, with descriptor</li>
     *   <li>{@code F:Class.field} — field access</li>
     *   <li>{@code V:type=value} — primitive/string/enum/class literal value
     *       (string values truncated to {@value #MAX_LITERAL_LEN} chars)</li>
     *   <li>{@code A:type}, {@code N:type}, {@code K:type}, {@code =} — array /
     *       null / functional-mock / assignment statements</li>
     *   <li>{@code BG:kindA>kindB} — bigram of consecutive statement kinds, a
     *       light ordering signal that keeps structurally similar tests near</li>
     * </ul>
     * Returns an empty set for a null/empty test case.
     */
    public static Set<String> getStructuralFeatures(TestChromosome tc) {
        org.evosuite.testcase.TestCase test = (tc != null) ? tc.getTestCase() : null;
        if (test == null || test.size() == 0) {
            return Collections.emptySet();
        }
        Set<String> features = new HashSet<>();
        String prevKind = null;
        for (org.evosuite.testcase.statements.Statement st : test) {
            String kind;
            try {
                if (st instanceof org.evosuite.testcase.statements.ConstructorStatement) {
                    org.evosuite.testcase.statements.ConstructorStatement cs =
                            (org.evosuite.testcase.statements.ConstructorStatement) st;
                    features.add("C:" + cs.getDeclaringClassName() + "."
                            + cs.getConstructor().getNameWithDescriptor());
                    kind = "C";
                } else if (st instanceof org.evosuite.testcase.statements.FunctionalMockStatement) {
                    features.add("K:" + safeName(st.getReturnClass()));
                    kind = "K";
                } else if (st instanceof org.evosuite.testcase.statements.MethodStatement) {
                    org.evosuite.testcase.statements.MethodStatement ms =
                            (org.evosuite.testcase.statements.MethodStatement) st;
                    features.add("M:" + ms.getDeclaringClassName() + "."
                            + ms.getMethod().getNameWithDescriptor());
                    kind = "M";
                } else if (st instanceof org.evosuite.testcase.statements.FieldStatement) {
                    org.evosuite.testcase.statements.FieldStatement fs =
                            (org.evosuite.testcase.statements.FieldStatement) st;
                    features.add("F:" + safeName(fs.getReturnClass()) + "."
                            + fs.getField().getName());
                    kind = "F";
                } else if (st instanceof org.evosuite.testcase.statements.PrimitiveStatement) {
                    Object value = ((org.evosuite.testcase.statements.PrimitiveStatement<?>) st).getValue();
                    features.add("V:" + safeName(st.getReturnClass()) + "=" + literal(value));
                    kind = "V";
                } else if (st instanceof org.evosuite.testcase.statements.ArrayStatement) {
                    features.add("A:" + safeName(st.getReturnClass()));
                    kind = "A";
                } else if (st instanceof org.evosuite.testcase.statements.NullStatement) {
                    features.add("N:" + safeName(st.getReturnClass()));
                    kind = "N";
                } else if (st instanceof org.evosuite.testcase.statements.AssignmentStatement) {
                    features.add("=");
                    kind = "=";
                } else {
                    kind = "?";
                }
            } catch (Exception e) {
                // A malformed/partially-constructed statement must not abort the
                // snapshot; skip its feature and continue.
                kind = "?";
            }
            if (prevKind != null) {
                features.add("BG:" + prevKind + ">" + kind);
            }
            prevKind = kind;
        }
        return features;
    }

    private static String safeName(Class<?> c) {
        return (c != null) ? c.getName() : "?";
    }

    /** Canonical, length-bounded rendering of a literal value. */
    private static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value);
        if (s.length() > MAX_LITERAL_LEN) {
            s = s.substring(0, MAX_LITERAL_LEN) + "…";
        }
        return s;
    }

    /**
     * Compute Jaccard distance = 1 - |A ∩ B| / |A ∪ B|.
     * Returns a configurable value when both sets are empty.
     */
    public static <T> double jaccardDistance(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return Math.max(0.0, Math.min(1.0, Properties.SPECIATION_EMPTY_PROFILE_DISTANCE));
        }
        int intersectionSize = 0;
        // iterate over the smaller set for efficiency
        Set<T> smaller = a.size() <= b.size() ? a : b;
        Set<T> larger = a.size() <= b.size() ? b : a;
        for (T elem : smaller) {
            if (larger.contains(elem)) {
                intersectionSize++;
            }
        }
        int unionSize = a.size() + b.size() - intersectionSize;
        return 1.0 - ((double) intersectionSize / unionSize);
    }
}
