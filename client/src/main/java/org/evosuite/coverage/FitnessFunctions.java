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
package org.evosuite.coverage;

import org.evosuite.Properties.Criterion;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.coverage.branch.*;
import org.evosuite.coverage.cbranch.CBranchFitnessFactory;
import org.evosuite.coverage.cbranch.CBranchSuiteFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.dataflow.*;
import org.evosuite.coverage.exception.*;
import org.evosuite.coverage.ibranch.IBranchFitnessFactory;
import org.evosuite.coverage.ibranch.IBranchSuiteFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.coverage.io.input.InputCoverageFactory;
import org.evosuite.coverage.io.input.InputCoverageSuiteFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageFactory;
import org.evosuite.coverage.io.output.OutputCoverageSuiteFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageFactory;
import org.evosuite.coverage.line.LineCoverageSuiteFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.line.OnlyLineCoverageSuiteFitness;
import org.evosuite.coverage.method.*;
import org.evosuite.coverage.mutation.*;
import org.evosuite.coverage.statement.StatementCoverageFactory;
import org.evosuite.coverage.statement.StatementCoverageSuiteFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.EvosuiteError;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * factory class for fitness functions.
 *
 * @author mattia
 */
public class FitnessFunctions {

    private static final Logger logger = LoggerFactory.getLogger(FitnessFunctions.class);

    private static final class FitnessRegistryEntry {
        final String displayName;
        final RuntimeVariable coverageVariable;
        /** Null for criteria that do not report a coverage bit string. */
        final RuntimeVariable bitStringVariable;
        final Supplier<TestSuiteFitnessFunction> suiteFitnessSupplier;
        final Supplier<TestFitnessFactory<? extends TestFitnessFunction>> fitnessFactorySupplier;
        final Class<?> testFitnessClass;
        boolean usesDefUse;
        boolean requiresTraceCalls;
        boolean requiresTraceCallsForManyObjectives;

        FitnessRegistryEntry(String displayName, RuntimeVariable coverageVariable,
                             RuntimeVariable bitStringVariable,
                             Supplier<TestSuiteFitnessFunction> suiteFitnessSupplier,
                             Supplier<TestFitnessFactory<? extends TestFitnessFunction>> fitnessFactorySupplier,
                             Class<?> testFitnessClass) {
            this.displayName = displayName;
            this.coverageVariable = coverageVariable;
            this.bitStringVariable = bitStringVariable;
            this.suiteFitnessSupplier = suiteFitnessSupplier;
            this.fitnessFactorySupplier = fitnessFactorySupplier;
            this.testFitnessClass = testFitnessClass;
        }

        /** The criterion needs the def-use instrumentation and the def-use counters in the traces. */
        FitnessRegistryEntry usesDefUse() {
            this.usesDefUse = true;
            return this;
        }

        /**
         * The search records per-invocation method call traces for this criterion. Implies the
         * many-objective variant.
         */
        FitnessRegistryEntry requiresTraceCalls() {
            this.requiresTraceCalls = true;
            this.requiresTraceCallsForManyObjectives = true;
            return this;
        }

        /** Like {@link #requiresTraceCalls()}, but only for the many-objective search strategy. */
        FitnessRegistryEntry requiresTraceCallsForManyObjectives() {
            this.requiresTraceCallsForManyObjectives = true;
            return this;
        }
    }

    private static final Map<Criterion, FitnessRegistryEntry> registry = new EnumMap<>(Criterion.class);

    static {
        registry.put(Criterion.STRONGMUTATION, new FitnessRegistryEntry(
                "Mutation testing (strong)", RuntimeVariable.MutationScore, RuntimeVariable.MutationCoverageBitString,
                StrongMutationSuiteFitness::new,
                MutationFactory::new,
                StrongMutationTestFitness.class
        ));
        registry.put(Criterion.WEAKMUTATION, new FitnessRegistryEntry(
                "Mutation testing (weak)", RuntimeVariable.WeakMutationScore, RuntimeVariable.WeakMutationCoverageBitString,
                WeakMutationSuiteFitness::new,
                () -> new MutationFactory(false),
                WeakMutationTestFitness.class
        ));
        registry.put(Criterion.MUTATION, new FitnessRegistryEntry(
                "Mutation testing (strong)", RuntimeVariable.MutationScore, RuntimeVariable.MutationCoverageBitString,
                StrongMutationSuiteFitness::new,
                MutationFactory::new,
                MutationTestFitness.class
        ));
        registry.put(Criterion.ONLYMUTATION, new FitnessRegistryEntry(
                "Only Mutation testing (weak)", RuntimeVariable.OnlyMutationScore, RuntimeVariable.OnlyMutationCoverageBitString,
                OnlyMutationSuiteFitness::new,
                OnlyMutationFactory::new,
                OnlyMutationTestFitness.class
        ));
        registry.put(Criterion.DEFUSE, new FitnessRegistryEntry(
                "All DU Pairs", RuntimeVariable.DefUseCoverage, RuntimeVariable.DefUseCoverageBitString,
                DefUseCoverageSuiteFitness::new,
                DefUseCoverageFactory::new,
                DefUseCoverageTestFitness.class
        ).usesDefUse().requiresTraceCalls());
        registry.put(Criterion.BRANCH, new FitnessRegistryEntry(
                "Branch Coverage", RuntimeVariable.BranchCoverage, RuntimeVariable.BranchCoverageBitString,
                BranchCoverageSuiteFitness::new,
                BranchCoverageFactory::new,
                BranchCoverageTestFitness.class
        ).requiresTraceCallsForManyObjectives());
        registry.put(Criterion.CBRANCH, new FitnessRegistryEntry(
                "Context Branch Coverage", RuntimeVariable.CBranchCoverage, RuntimeVariable.CBranchCoverageBitString,
                CBranchSuiteFitness::new,
                CBranchFitnessFactory::new,
                CBranchTestFitness.class
        ));
        registry.put(Criterion.IBRANCH, new FitnessRegistryEntry(
                "Interprocedural Context Branch Coverage", RuntimeVariable.IBranchCoverage, RuntimeVariable.IBranchCoverageBitString,
                IBranchSuiteFitness::new,
                IBranchFitnessFactory::new,
                IBranchTestFitness.class
        ));
        registry.put(Criterion.STATEMENT, new FitnessRegistryEntry(
                "Statement Coverage", RuntimeVariable.StatementCoverage, RuntimeVariable.StatementCoverageBitString,
                StatementCoverageSuiteFitness::new,
                StatementCoverageFactory::new,
                StatementCoverageTestFitness.class
        ).requiresTraceCalls());
        registry.put(Criterion.ALLDEFS, new FitnessRegistryEntry(
                "All Definitions", RuntimeVariable.AllDefCoverage, RuntimeVariable.AllDefCoverageBitString,
                AllDefsCoverageSuiteFitness::new,
                AllDefsCoverageFactory::new,
                AllDefsCoverageTestFitness.class
        ).usesDefUse().requiresTraceCalls());
        registry.put(Criterion.EXCEPTION, new FitnessRegistryEntry(
                "Exception", RuntimeVariable.ExceptionCoverage, RuntimeVariable.ExceptionCoverageBitString,
                ExceptionCoverageSuiteFitness::new,
                ExceptionCoverageFactory::new,
                ExceptionCoverageTestFitness.class
        ));
        registry.put(Criterion.ONLYBRANCH, new FitnessRegistryEntry(
                "Only-Branch Coverage", RuntimeVariable.OnlyBranchCoverage, RuntimeVariable.OnlyBranchCoverageBitString,
                OnlyBranchCoverageSuiteFitness::new,
                OnlyBranchCoverageFactory::new,
                OnlyBranchCoverageTestFitness.class
        ));
        registry.put(Criterion.METHODTRACE, new FitnessRegistryEntry(
                "Method Coverage", RuntimeVariable.MethodTraceCoverage, RuntimeVariable.MethodTraceCoverageBitString,
                MethodTraceCoverageSuiteFitness::new,
                MethodTraceCoverageFactory::new,
                MethodTraceCoverageTestFitness.class
        ));
        registry.put(Criterion.METHOD, new FitnessRegistryEntry(
                "Top-Level Method Coverage", RuntimeVariable.MethodCoverage, RuntimeVariable.MethodCoverageBitString,
                MethodCoverageSuiteFitness::new,
                MethodCoverageFactory::new,
                MethodCoverageTestFitness.class
        ));
        registry.put(Criterion.METHODNOEXCEPTION, new FitnessRegistryEntry(
                "No-Exception Top-Level Method Coverage", RuntimeVariable.MethodNoExceptionCoverage, RuntimeVariable.MethodNoExceptionCoverageBitString,
                MethodNoExceptionCoverageSuiteFitness::new,
                MethodNoExceptionCoverageFactory::new,
                MethodNoExceptionCoverageTestFitness.class
        ));
        registry.put(Criterion.ONLYLINE, new FitnessRegistryEntry(
                "Only-Line Coverage", RuntimeVariable.LineCoverage, RuntimeVariable.LineCoverageBitString,
                OnlyLineCoverageSuiteFitness::new,
                LineCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.LINE, new FitnessRegistryEntry(
                "Line Coverage", RuntimeVariable.LineCoverage, RuntimeVariable.LineCoverageBitString,
                LineCoverageSuiteFitness::new,
                LineCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.OUTPUT, new FitnessRegistryEntry(
                "Method-Output Coverage", RuntimeVariable.OutputCoverage, RuntimeVariable.OutputCoverageBitString,
                OutputCoverageSuiteFitness::new,
                OutputCoverageFactory::new,
                OutputCoverageTestFitness.class
        ));
        registry.put(Criterion.INPUT, new FitnessRegistryEntry(
                "Method-Input Coverage", RuntimeVariable.InputCoverage, RuntimeVariable.InputCoverageBitString,
                InputCoverageSuiteFitness::new,
                InputCoverageFactory::new,
                InputCoverageTestFitness.class
        ));
        registry.put(Criterion.TRYCATCH, new FitnessRegistryEntry(
                "Try-Catch Branch Coverage", RuntimeVariable.TryCatchCoverage, null,
                TryCatchCoverageSuiteFitness::new,
                TryCatchCoverageFactory::new,
                TryCatchCoverageTestFitness.class
        ));
    }

    /**
     * <p>
     * getFitnessFunction.
     * </p>
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return a {@link org.evosuite.testsuite.TestSuiteFitnessFunction} object.
     */
    public static TestSuiteFitnessFunction getFitnessFunction(Criterion criterion) {
        FitnessRegistryEntry entry = registry.get(criterion);
        if (entry != null) {
            return entry.suiteFitnessSupplier.get();
        }
        logger.warn("No TestSuiteFitnessFunction defined for {}; using default one "
                + "(BranchCoverageSuiteFitness)", criterion);
        return new BranchCoverageSuiteFitness();
    }

    /**
     * <p>
     * getFitnessFactory.
     * </p>
     *
     * @param crit a {@link org.evosuite.Properties.Criterion} object.
     * @return a {@link org.evosuite.coverage.TestFitnessFactory} object.
     */
    public static TestFitnessFactory<? extends TestFitnessFunction> getFitnessFactory(
            Criterion crit) {
        FitnessRegistryEntry entry = registry.get(crit);
        if (entry != null) {
            return entry.fitnessFactorySupplier.get();
        }
        logger.warn("No TestFitnessFactory defined for {} using default one (BranchCoverageFactory)", crit);
        return new BranchCoverageFactory();
    }

    /**
     * Converts a {@link org.evosuite.Properties.Criterion} object to a
     * {@link org.evosuite.testcase.TestFitnessFunction} class.
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return a {@link java.lang.Class} object.
     */
    public static Class<?> getTestFitnessFunctionClass(Criterion criterion) {
        FitnessRegistryEntry entry = registry.get(criterion);
        if (entry != null && entry.testFitnessClass != null) {
            return entry.testFitnessClass;
        }
        throw new EvosuiteError("No test fitness function defined for " + criterion.name());
    }

    /**
     * Returns the human-readable name of a criterion, as printed in the logs and reports.
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return the display name
     */
    public static String displayName(Criterion criterion) {
        return entry(criterion).displayName;
    }

    /**
     * Returns the runtime variable holding the coverage of a criterion.
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return the coverage variable
     */
    public static RuntimeVariable coverageVariable(Criterion criterion) {
        return entry(criterion).coverageVariable;
    }

    /**
     * Returns the runtime variable holding the coverage bit string of a criterion.
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return the bit string variable, or null if the criterion reports none
     */
    public static RuntimeVariable bitStringVariable(Criterion criterion) {
        return entry(criterion).bitStringVariable;
    }

    /**
     * Checks whether any of the criteria needs the def-use instrumentation and traces.
     */
    public static boolean usesDefUse(Criterion[] criteria) {
        for (Criterion criterion : criteria) {
            if (entry(criterion).usesDefUse) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the search must record per-invocation method call traces for any of the
     * criteria.
     */
    public static boolean requiresTraceCalls(Criterion[] criteria) {
        for (Criterion criterion : criteria) {
            if (entry(criterion).requiresTraceCalls) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like {@link #requiresTraceCalls(Criterion[])}, for the many-objective search strategy,
     * which also records them for branch coverage.
     */
    public static boolean requiresTraceCallsForManyObjectives(Criterion[] criteria) {
        for (Criterion criterion : criteria) {
            if (entry(criterion).requiresTraceCallsForManyObjectives) {
                return true;
            }
        }
        return false;
    }

    private static FitnessRegistryEntry entry(Criterion criterion) {
        FitnessRegistryEntry entry = registry.get(criterion);
        if (entry == null) {
            throw new IllegalArgumentException("Unrecognized criterion: " + criterion);
        }
        return entry;
    }

}
