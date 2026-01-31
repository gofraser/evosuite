/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.ambiguity.AmbiguityCoverageFactory;
import org.evosuite.coverage.ambiguity.AmbiguityCoverageSuiteFitness;
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
import org.evosuite.coverage.readability.ReadabilitySuiteFitness;
import org.evosuite.coverage.rho.RhoCoverageFactory;
import org.evosuite.coverage.rho.RhoCoverageSuiteFitness;
import org.evosuite.coverage.statement.StatementCoverageFactory;
import org.evosuite.coverage.statement.StatementCoverageSuiteFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * factory class for fitness functions
 *
 * @author mattia
 */
public class FitnessFunctions {

    private static final Logger logger = LoggerFactory.getLogger(FitnessFunctions.class);

    private static class FitnessRegistryEntry {
        final Supplier<TestSuiteFitnessFunction> suiteFitnessSupplier;
        final Supplier<TestFitnessFactory<? extends TestFitnessFunction>> fitnessFactorySupplier;
        final Class<?> testFitnessClass;

        FitnessRegistryEntry(Supplier<TestSuiteFitnessFunction> suiteFitnessSupplier,
                             Supplier<TestFitnessFactory<? extends TestFitnessFunction>> fitnessFactorySupplier,
                             Class<?> testFitnessClass) {
            this.suiteFitnessSupplier = suiteFitnessSupplier;
            this.fitnessFactorySupplier = fitnessFactorySupplier;
            this.testFitnessClass = testFitnessClass;
        }
    }

    private static final Map<Criterion, FitnessRegistryEntry> registry = new EnumMap<>(Criterion.class);

    static {
        registry.put(Criterion.STRONGMUTATION, new FitnessRegistryEntry(
                StrongMutationSuiteFitness::new,
                MutationFactory::new,
                StrongMutationTestFitness.class
        ));
        registry.put(Criterion.WEAKMUTATION, new FitnessRegistryEntry(
                WeakMutationSuiteFitness::new,
                () -> new MutationFactory(false),
                WeakMutationTestFitness.class
        ));
        registry.put(Criterion.MUTATION, new FitnessRegistryEntry(
                StrongMutationSuiteFitness::new,
                MutationFactory::new,
                MutationTestFitness.class
        ));
        registry.put(Criterion.ONLYMUTATION, new FitnessRegistryEntry(
                OnlyMutationSuiteFitness::new,
                OnlyMutationFactory::new,
                OnlyMutationTestFitness.class
        ));
        registry.put(Criterion.DEFUSE, new FitnessRegistryEntry(
                DefUseCoverageSuiteFitness::new,
                DefUseCoverageFactory::new,
                DefUseCoverageTestFitness.class
        ));
        registry.put(Criterion.BRANCH, new FitnessRegistryEntry(
                BranchCoverageSuiteFitness::new,
                BranchCoverageFactory::new,
                BranchCoverageTestFitness.class
        ));
        registry.put(Criterion.CBRANCH, new FitnessRegistryEntry(
                CBranchSuiteFitness::new,
                CBranchFitnessFactory::new,
                CBranchTestFitness.class
        ));
        registry.put(Criterion.IBRANCH, new FitnessRegistryEntry(
                IBranchSuiteFitness::new,
                IBranchFitnessFactory::new,
                IBranchTestFitness.class
        ));
        registry.put(Criterion.STATEMENT, new FitnessRegistryEntry(
                StatementCoverageSuiteFitness::new,
                StatementCoverageFactory::new,
                StatementCoverageTestFitness.class
        ));
        registry.put(Criterion.RHO, new FitnessRegistryEntry(
                RhoCoverageSuiteFitness::new,
                RhoCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.AMBIGUITY, new FitnessRegistryEntry(
                AmbiguityCoverageSuiteFitness::new,
                AmbiguityCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.ALLDEFS, new FitnessRegistryEntry(
                AllDefsCoverageSuiteFitness::new,
                AllDefsCoverageFactory::new,
                AllDefsCoverageTestFitness.class
        ));
        registry.put(Criterion.EXCEPTION, new FitnessRegistryEntry(
                ExceptionCoverageSuiteFitness::new,
                ExceptionCoverageFactory::new,
                ExceptionCoverageTestFitness.class
        ));
        registry.put(Criterion.READABILITY, new FitnessRegistryEntry(
                ReadabilitySuiteFitness::new,
                BranchCoverageFactory::new, // Default behavior preserved
                null // Will throw exception as per original code
        ));
        registry.put(Criterion.ONLYBRANCH, new FitnessRegistryEntry(
                OnlyBranchCoverageSuiteFitness::new,
                OnlyBranchCoverageFactory::new,
                OnlyBranchCoverageTestFitness.class
        ));
        registry.put(Criterion.METHODTRACE, new FitnessRegistryEntry(
                MethodTraceCoverageSuiteFitness::new,
                MethodTraceCoverageFactory::new,
                MethodTraceCoverageTestFitness.class
        ));
        registry.put(Criterion.METHOD, new FitnessRegistryEntry(
                MethodCoverageSuiteFitness::new,
                MethodCoverageFactory::new,
                MethodCoverageTestFitness.class
        ));
        registry.put(Criterion.METHODNOEXCEPTION, new FitnessRegistryEntry(
                MethodNoExceptionCoverageSuiteFitness::new,
                MethodNoExceptionCoverageFactory::new,
                MethodNoExceptionCoverageTestFitness.class
        ));
        registry.put(Criterion.ONLYLINE, new FitnessRegistryEntry(
                OnlyLineCoverageSuiteFitness::new,
                LineCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.LINE, new FitnessRegistryEntry(
                LineCoverageSuiteFitness::new,
                LineCoverageFactory::new,
                LineCoverageTestFitness.class
        ));
        registry.put(Criterion.OUTPUT, new FitnessRegistryEntry(
                OutputCoverageSuiteFitness::new,
                OutputCoverageFactory::new,
                OutputCoverageTestFitness.class
        ));
        registry.put(Criterion.INPUT, new FitnessRegistryEntry(
                InputCoverageSuiteFitness::new,
                InputCoverageFactory::new,
                InputCoverageTestFitness.class
        ));
        registry.put(Criterion.TRYCATCH, new FitnessRegistryEntry(
                TryCatchCoverageSuiteFitness::new,
                TryCatchCoverageFactory::new,
                TryCatchCoverageTestFitness.class
        ));
    }

    /**
     * <p>
     * getFitnessFunction
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
        logger.warn("No TestSuiteFitnessFunction defined for {}; using default one (BranchCoverageSuiteFitness)", Arrays.toString(Properties.CRITERION));
        return new BranchCoverageSuiteFitness();
    }

    /**
     * <p>
     * getFitnessFactory
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
        logger.warn("No TestFitnessFactory defined for " + crit
                + " using default one (BranchCoverageFactory)");
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
        throw new RuntimeException("No test fitness function defined for " + criterion.name());
    }

}
