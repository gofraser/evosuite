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
package org.evosuite.ga.archive;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.dataflow.AllDefsCoverageTestFitness;
import org.evosuite.coverage.dataflow.DefUseCoverageTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.exception.TryCatchCoverageTestFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.method.MethodTraceCoverageTestFitness;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.coverage.mutation.OnlyMutationTestFitness;
import org.evosuite.coverage.mutation.StrongMutationTestFitness;
import org.evosuite.coverage.mutation.WeakMutationTestFitness;
import org.evosuite.coverage.rho.RhoCoverageTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.runtime.util.AtMostOnceLogger;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

public final class ArchiveUtils {

    private static final Logger logger = LoggerFactory.getLogger(ArchiveUtils.class);

    private static final Map<Properties.Criterion, Class<?>> criterionClasses =
            new EnumMap<>(Properties.Criterion.class);

    static {
        criterionClasses.put(Properties.Criterion.EXCEPTION, ExceptionCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.DEFUSE, DefUseCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.ALLDEFS, AllDefsCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.BRANCH, BranchCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.CBRANCH, CBranchTestFitness.class);
        criterionClasses.put(Properties.Criterion.STRONGMUTATION, StrongMutationTestFitness.class);
        criterionClasses.put(Properties.Criterion.WEAKMUTATION, WeakMutationTestFitness.class);
        criterionClasses.put(Properties.Criterion.MUTATION, MutationTestFitness.class);
        criterionClasses.put(Properties.Criterion.STATEMENT, StatementCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.RHO, RhoCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.AMBIGUITY, LineCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.IBRANCH, IBranchTestFitness.class);
        // READABILITY ignored
        criterionClasses.put(Properties.Criterion.ONLYBRANCH, OnlyBranchCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.ONLYMUTATION, OnlyMutationTestFitness.class);
        criterionClasses.put(Properties.Criterion.METHODTRACE, MethodTraceCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.METHOD, MethodCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.METHODNOEXCEPTION, MethodNoExceptionCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.LINE, LineCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.ONLYLINE, LineCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.OUTPUT, OutputCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.INPUT, InputCoverageTestFitness.class);
        criterionClasses.put(Properties.Criterion.TRYCATCH, TryCatchCoverageTestFitness.class);
    }

    /**
     * Checks whether a specific goal (i.e., a {@link org.evosuite.testcase.TestFitnessFunction}
     * object) is of an enabled criterion. A criterion is considered enabled if and only if defined in
     * {@link org.evosuite.Properties.CRITERION}.
     *
     * @param goal a {@link org.evosuite.testcase.TestFitnessFunction} object
     * @return true if criterion of goal is enabled, false otherwise
     */
    public static boolean isCriterionEnabled(FitnessFunction<TestChromosome> goal) {
        for (Properties.Criterion criterion : Properties.CRITERION) {
            Class<?> clazz = criterionClasses.get(criterion);
            if (clazz != null) {
                if (clazz.isInstance(goal)) {
                    return true;
                }
            } else if (criterion != Properties.Criterion.READABILITY) {
                AtMostOnceLogger.warn(logger, "Unknown criterion '" + criterion.name() + "'");
            }
        }
        if (ArrayUtil.contains(Properties.SECONDARY_OBJECTIVE, Properties.SecondaryObjective.IBRANCH)) {
            return goal instanceof IBranchTestFitness;
        }

        return false;
    }
}
