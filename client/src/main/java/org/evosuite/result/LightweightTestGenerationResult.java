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
package org.evosuite.result;

import org.evosuite.ga.Chromosome;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.symbolic.dse.algorithm.ExplorationAlgorithmBase;
import org.evosuite.testcase.TestCase;

import java.util.*;

/**
 * Serialization-safe TestGenerationResult used for RMI transfer to master.
 * It intentionally omits heavyweight fields (GA, DSE, TestCase objects) that
 * can pull CUT-specific classes into the serialization graph.
 */
public class LightweightTestGenerationResult<T extends Chromosome<T>> implements TestGenerationResult<T> {

    private static final long serialVersionUID = -9040874193900383254L;

    private Status status = Status.ERROR;
    private String errorMessage = "";
    private String classUnderTest = "";
    private String[] targetCriterion = new String[0];
    private String testSuiteCode = "";

    private final Map<String, String> testCode = new LinkedHashMap<>();
    private final Map<String, String> comments = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> coveredLinesByTest = new LinkedHashMap<>();
    private final Map<String, Set<BranchInfo>> coveredBranchesByTest = new LinkedHashMap<>();
    private final Map<String, Set<MutationInfo>> coveredMutantsByTest = new LinkedHashMap<>();

    private final Set<Integer> coveredLines = new LinkedHashSet<>();
    private final Set<Integer> uncoveredLines = new LinkedHashSet<>();
    private final Set<BranchInfo> coveredBranches = new LinkedHashSet<>();
    private final Set<BranchInfo> uncoveredBranches = new LinkedHashSet<>();
    private final Set<MutationInfo> coveredMutants = new LinkedHashSet<>();
    private final Set<MutationInfo> uncoveredMutants = new LinkedHashSet<>();
    private final Set<MutationInfo> exceptionMutants = new LinkedHashSet<>();

    private final Map<String, Set<Failure>> contractViolations = new LinkedHashMap<>();
    private final Map<FitnessFunction<?>, Double> targetCoverages = new LinkedHashMap<>();

    public static <T extends Chromosome<T>> LightweightTestGenerationResult<T> from(TestGenerationResult<?> source) {
        LightweightTestGenerationResult<T> out = new LightweightTestGenerationResult<>();
        if (source == null) {
            return out;
        }

        try {
            out.status = source.getTestGenerationStatus();
        } catch (Throwable ignored) {
        }
        try {
            out.errorMessage = safe(source.getErrorMessage());
        } catch (Throwable ignored) {
        }
        try {
            out.classUnderTest = safe(source.getClassUnderTest());
        } catch (Throwable ignored) {
        }
        try {
            String[] criterion = source.getTargetCriterion();
            out.targetCriterion = criterion == null ? new String[0] : Arrays.copyOf(criterion, criterion.length);
        } catch (Throwable ignored) {
        }
        try {
            out.testSuiteCode = safe(source.getTestSuiteCode());
        } catch (Throwable ignored) {
        }

        // Do not copy per-test TestCase objects.
        // Copy only code/comments/coverage keyed by names visible in test code map.
        Set<String> testNames = new LinkedHashSet<>();
        // Best-effort name discovery: parse from suite code fallback is empty.
        // If no names are found here, aggregate sets are still transferred.
        testNames.addAll(extractLikelyTestNames(out.testSuiteCode));

        for (String name : testNames) {
            try {
                String code = source.getTestCode(name);
                if (code != null) {
                    out.testCode.put(name, code);
                }
            } catch (Throwable ignored) {
            }
            try {
                String comment = source.getComment(name);
                if (comment != null) {
                    out.comments.put(name, comment);
                }
            } catch (Throwable ignored) {
            }
            try {
                Set<Integer> lines = source.getCoveredLines(name);
                if (lines != null) {
                    out.coveredLinesByTest.put(name, new LinkedHashSet<>(lines));
                }
            } catch (Throwable ignored) {
            }
            try {
                Set<BranchInfo> branches = source.getCoveredBranches(name);
                if (branches != null) {
                    out.coveredBranchesByTest.put(name, new LinkedHashSet<>(branches));
                }
            } catch (Throwable ignored) {
            }
            try {
                Set<MutationInfo> mutants = source.getCoveredMutants(name);
                if (mutants != null) {
                    out.coveredMutantsByTest.put(name, new LinkedHashSet<>(mutants));
                }
            } catch (Throwable ignored) {
            }
            try {
                Set<Failure> failures = source.getContractViolations(name);
                if (failures != null) {
                    out.contractViolations.put(name, new LinkedHashSet<>(failures));
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            Set<Integer> lines = source.getCoveredLines();
            if (lines != null) {
                out.coveredLines.addAll(lines);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<Integer> lines = source.getUncoveredLines();
            if (lines != null) {
                out.uncoveredLines.addAll(lines);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<BranchInfo> branches = source.getCoveredBranches();
            if (branches != null) {
                out.coveredBranches.addAll(branches);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<BranchInfo> branches = source.getUncoveredBranches();
            if (branches != null) {
                out.uncoveredBranches.addAll(branches);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<MutationInfo> mutants = source.getCoveredMutants();
            if (mutants != null) {
                out.coveredMutants.addAll(mutants);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<MutationInfo> mutants = source.getUncoveredMutants();
            if (mutants != null) {
                out.uncoveredMutants.addAll(mutants);
            }
        } catch (Throwable ignored) {
        }
        try {
            Set<MutationInfo> mutants = source.getExceptionMutants();
            if (mutants != null) {
                out.exceptionMutants.addAll(mutants);
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Set<String> extractLikelyTestNames(String suiteCode) {
        if (suiteCode == null || suiteCode.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        String[] lines = suiteCode.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("public void ")) {
                continue;
            }
            int start = "public void ".length();
            int idx = t.indexOf('(', start);
            if (idx > start) {
                names.add(t.substring(start, idx).trim());
            }
        }
        return names;
    }

    @Override
    public Status getTestGenerationStatus() {
        return status;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public ExplorationAlgorithmBase getDSEAlgorithm() {
        return null;
    }

    @Override
    public GeneticAlgorithm<T> getGeneticAlgorithm() {
        return null;
    }

    @Override
    public Set<Failure> getContractViolations(String name) {
        return contractViolations.get(name);
    }

    @Override
    public String getClassUnderTest() {
        return classUnderTest;
    }

    @Override
    public String[] getTargetCriterion() {
        return Arrays.copyOf(targetCriterion, targetCriterion.length);
    }

    @Override
    public double getTargetCoverage(FitnessFunction<?> function) {
        return targetCoverages.getOrDefault(function, 0.0);
    }

    @Override
    public TestCase getTestCase(String name) {
        return null;
    }

    @Override
    public String getTestCode(String name) {
        return testCode.get(name);
    }

    @Override
    public String getTestSuiteCode() {
        return testSuiteCode;
    }

    @Override
    public Set<Integer> getCoveredLines(String name) {
        return coveredLinesByTest.get(name);
    }

    @Override
    public Set<BranchInfo> getCoveredBranches(String name) {
        return coveredBranchesByTest.get(name);
    }

    @Override
    public Set<MutationInfo> getCoveredMutants(String name) {
        return coveredMutantsByTest.get(name);
    }

    @Override
    public Set<MutationInfo> getExceptionMutants() {
        return exceptionMutants;
    }

    @Override
    public Set<Integer> getCoveredLines() {
        return coveredLines;
    }

    @Override
    public Set<BranchInfo> getCoveredBranches() {
        return coveredBranches;
    }

    @Override
    public Set<MutationInfo> getCoveredMutants() {
        return coveredMutants;
    }

    @Override
    public Set<Integer> getUncoveredLines() {
        return uncoveredLines;
    }

    @Override
    public Set<BranchInfo> getUncoveredBranches() {
        return uncoveredBranches;
    }

    @Override
    public Set<MutationInfo> getUncoveredMutants() {
        return uncoveredMutants;
    }

    @Override
    public String getComment(String name) {
        return comments.get(name);
    }
}
