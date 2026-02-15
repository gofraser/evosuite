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
package org.evosuite;

import org.evosuite.Properties.AssertionStrategy;
import org.evosuite.Properties.Criterion;
import org.evosuite.assertion.AssertionGenerator;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.SimpleMutationAssertionGenerator;
import org.evosuite.assertion.UnitAssertionGenerator;
import org.evosuite.contracts.ContractChecker;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchPool;
import org.evosuite.instrumentation.LinePool;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.strategy.*;
import org.evosuite.symbolic.dse.DSEStrategyFactory;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Created by sina on 06/04/2017.
 */
public class TestSuiteGeneratorHelper {

    /**
     * <p>getCriterionDisplayName.</p>
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return a {@link java.lang.String} object.
     */
    public static String getCriterionDisplayName(Criterion criterion) {
        switch (criterion) {
            case WEAKMUTATION:
                return "Mutation testing (weak)";
            case ONLYMUTATION:
                return "Only Mutation testing (weak)";
            case STRONGMUTATION:
            case MUTATION:
                return "Mutation testing (strong)";
            case DEFUSE:
                return "All DU Pairs";
            case STATEMENT:
                return "Statement Coverage";
            case RHO:
                return "Rho Coverage";
            case AMBIGUITY:
                return "Ambiguity Coverage";
            case ALLDEFS:
                return "All Definitions";
            case EXCEPTION:
                return "Exception";
            case ONLYBRANCH:
                return "Only-Branch Coverage";
            case METHODTRACE:
                return "Method Coverage";
            case METHOD:
                return "Top-Level Method Coverage";
            case METHODNOEXCEPTION:
                return "No-Exception Top-Level Method Coverage";
            case LINE:
                return "Line Coverage";
            case ONLYLINE:
                return "Only-Line Coverage";
            case OUTPUT:
                return "Method-Output Coverage";
            case INPUT:
                return "Method-Input Coverage";
            case BRANCH:
                return "Branch Coverage";
            case CBRANCH:
                return "Context Branch Coverage";
            case IBRANCH:
                return "Interprocedural Context Branch Coverage";
            case TRYCATCH:
                return "Try-Catch Branch Coverage";
            default:
                throw new IllegalArgumentException("Unrecognized criterion: " + criterion);
        }
    }

    static void printTestCriterion(Criterion criterion) {
        LoggingUtils.getEvoLogger().info("  - {}", getCriterionDisplayName(criterion));
    }

    private static int getBytecodeCount(RuntimeVariable v, Map<RuntimeVariable, Set<Integer>> m) {
        Set<Integer> branchSet = m.get(v);
        return (branchSet == null) ? 0 : branchSet.size();
    }

    static void getBytecodeStatistics() {
        if (Properties.TRACK_BOOLEAN_BRANCHES) {
            int gradientBranchCount = ExecutionTraceImpl.getGradientBranches().size() * 2;
            ClientServices.track(RuntimeVariable.Gradient_Branches, gradientBranchCount);
        }
        if (Properties.TRACK_COVERED_GRADIENT_BRANCHES) {
            int coveredGradientBranchCount = ExecutionTraceImpl.getGradientBranchesCoveredTrue().size()
                    + ExecutionTraceImpl.getGradientBranchesCoveredFalse().size();
            ClientServices.track(RuntimeVariable.Gradient_Branches_Covered, coveredGradientBranchCount);
        }
        if (Properties.BRANCH_COMPARISON_TYPES) {
            int cmpIntZero = 0;
            int cmpIntInt = 0;
            int cmpRefRef = 0;
            int cmpRefNull = 0;
            int bcLcmp = 0;
            int bcFcmpl = 0;
            int bcFcmpg = 0;
            int bcDcmpl = 0;
            int bcDcmpg = 0;
            for (Branch b : BranchPool
                    .getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT())
                    .getAllBranches()) {
                int branchOpCode = b.getInstruction().getASMNode().getOpcode();
                int previousOpcode = -2;
                if (b.getInstruction().getASMNode().getPrevious() != null) {
                    previousOpcode = b.getInstruction().getASMNode().getPrevious().getOpcode();
                }
                switch (previousOpcode) {
                    case Opcodes.LCMP:
                        bcLcmp++;
                        break;
                    case Opcodes.FCMPL:
                        bcFcmpl++;
                        break;
                    case Opcodes.FCMPG:
                        bcFcmpg++;
                        break;
                    case Opcodes.DCMPL:
                        bcDcmpl++;
                        break;
                    case Opcodes.DCMPG:
                        bcDcmpg++;
                        break;
                    default:
                        // No comparison bytecode
                        break;
                }
                switch (branchOpCode) {
                    // copmpare int with zero
                    case Opcodes.IFEQ:
                    case Opcodes.IFNE:
                    case Opcodes.IFLT:
                    case Opcodes.IFGE:
                    case Opcodes.IFGT:
                    case Opcodes.IFLE:
                        cmpIntZero++;
                        break;
                    // copmpare int with int
                    case Opcodes.IF_ICMPEQ:
                    case Opcodes.IF_ICMPNE:
                    case Opcodes.IF_ICMPLT:
                    case Opcodes.IF_ICMPGE:
                    case Opcodes.IF_ICMPGT:
                    case Opcodes.IF_ICMPLE:
                        cmpIntInt++;
                        break;
                    // copmpare reference with reference
                    case Opcodes.IF_ACMPEQ:
                    case Opcodes.IF_ACMPNE:
                        cmpRefRef++;
                        break;
                    // compare reference with null
                    case Opcodes.IFNULL:
                    case Opcodes.IFNONNULL:
                        cmpRefNull++;
                        break;
                    default:
                        // No branch opcode
                        break;

                }
            }
            ClientServices.track(RuntimeVariable.Cmp_IntZero, cmpIntZero);
            ClientServices.track(RuntimeVariable.Cmp_IntInt, cmpIntInt);
            ClientServices.track(RuntimeVariable.Cmp_RefRef, cmpRefRef);
            ClientServices.track(RuntimeVariable.Cmp_RefNull, cmpRefNull);

            ClientServices.track(RuntimeVariable.BC_lcmp, bcLcmp);
            ClientServices.track(RuntimeVariable.BC_fcmpl, bcFcmpl);
            ClientServices.track(RuntimeVariable.BC_fcmpg, bcFcmpg);
            ClientServices.track(RuntimeVariable.BC_dcmpl, bcDcmpl);
            ClientServices.track(RuntimeVariable.BC_dcmpg, bcDcmpg);

            RuntimeVariable[] bytecodeVarsCovered = new RuntimeVariable[]{RuntimeVariable.Covered_lcmp,
                    RuntimeVariable.Covered_fcmpl, RuntimeVariable.Covered_fcmpg,
                    RuntimeVariable.Covered_dcmpl,
                    RuntimeVariable.Covered_dcmpg, RuntimeVariable.Covered_IntInt,
                    RuntimeVariable.Covered_IntInt,
                    RuntimeVariable.Covered_IntZero, RuntimeVariable.Covered_RefRef,
                    RuntimeVariable.Covered_RefNull};

            for (RuntimeVariable bcvar : bytecodeVarsCovered) {
                ClientServices.track(bcvar,
                        getBytecodeCount(bcvar, ExecutionTraceImpl.getBytecodeInstructionCoveredFalse())
                                + getBytecodeCount(bcvar, ExecutionTraceImpl.getBytecodeInstructionCoveredTrue()));
            }

            RuntimeVariable[] bytecodeVarsReached = new RuntimeVariable[]{RuntimeVariable.Reached_lcmp,
                    RuntimeVariable.Reached_fcmpl, RuntimeVariable.Reached_fcmpg,
                    RuntimeVariable.Reached_dcmpl,
                    RuntimeVariable.Reached_dcmpg, RuntimeVariable.Reached_IntInt,
                    RuntimeVariable.Reached_IntInt,
                    RuntimeVariable.Reached_IntZero, RuntimeVariable.Reached_RefRef,
                    RuntimeVariable.Reached_RefNull};

            for (RuntimeVariable bcvar : bytecodeVarsReached) {
                ClientServices.track(bcvar,
                        getBytecodeCount(bcvar, ExecutionTraceImpl.getBytecodeInstructionReached()) * 2);
            }

        }

    }

    static void printTestCriterion() {
        if (Properties.CRITERION.length > 1) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Test criteria:");
        } else {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Test criterion:");
        }
        for (int i = 0; i < Properties.CRITERION.length; i++) {
            printTestCriterion(Properties.CRITERION[i]);
        }
    }

    static boolean isLineDebugInfoDependentCriterion(Criterion criterion) {
        return criterion == Criterion.LINE || criterion == Criterion.ONLYLINE;
    }

    static boolean hasUsableLineNumbersForTargetClass() {
        for (String className : LinePool.getKnownClasses()) {
            if (!isCUT(className)) {
                continue;
            }
            for (Integer line : LinePool.getLines(className)) {
                if (line != null && line.intValue() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    static Criterion[] removeDebugInfoDependentCriteriaIfMissing(Criterion[] criteria) {
        if (criteria == null || criteria.length == 0) {
            return criteria;
        }

        boolean hasDebugInfoDependentCriterion = Arrays.stream(criteria)
                .anyMatch(TestSuiteGeneratorHelper::isLineDebugInfoDependentCriterion);
        if (!hasDebugInfoDependentCriterion) {
            return criteria;
        }

        if (hasUsableLineNumbersForTargetClass()) {
            return criteria;
        }

        return Arrays.stream(criteria)
                .filter(c -> !isLineDebugInfoDependentCriterion(c))
                .toArray(Criterion[]::new);
    }

    private static boolean isCUT(String className) {
        return Properties.TARGET_CLASS.isEmpty()
                || className.equals(Properties.TARGET_CLASS)
                || className.startsWith(Properties.TARGET_CLASS + "$");
    }

    static TestGenerationStrategy getTestGenerationStrategy() {
        switch (Properties.STRATEGY) {
            case EVOSUITE:
                return new WholeTestSuiteStrategy();
            case RANDOM:
                return new RandomTestStrategy();
            case RANDOM_FIXED:
                return new FixedNumRandomTestStrategy();
            case ONEBRANCH:
                return new IndividualTestStrategy();
            case ENTBUG:
                return new EntBugTestStrategy();
            case MOSUITE:
                return new MOSuiteStrategy();
            case DSE:
                return DSEStrategyFactory.getDSEStrategy(Properties.CURRENT_DseModuleVersion);
            case NOVELTY:
                return new NoveltyStrategy();
            case MAP_ELITES:
                return new MAPElitesStrategy();
            default:
                throw new RuntimeException("Unsupported strategy: " + Properties.STRATEGY);
        }
    }

    /**
     * Add assertions to the generated test suite.
     *
     * @param tests the test suite to add assertions to
     */
    public static void addAssertions(TestSuiteChromosome tests) {
        AssertionGenerator asserter;
        ContractChecker.setActive(false);

        if (Properties.ASSERTION_STRATEGY == AssertionStrategy.MUTATION) {
            asserter = new SimpleMutationAssertionGenerator();
        } else if (Properties.ASSERTION_STRATEGY == AssertionStrategy.ALL) {
            asserter = new CompleteAssertionGenerator();
        } else {
            asserter = new UnitAssertionGenerator();
        }

        asserter.addAssertions(tests);

        if (Properties.FILTER_ASSERTIONS) {
            asserter.filterFailingAssertions(tests);
        }
    }
}
