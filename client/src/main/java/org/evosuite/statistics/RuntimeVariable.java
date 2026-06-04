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
package org.evosuite.statistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * <p>This enumeration defines all the runtime variables we want to store in
 * the CSV files.
 * A runtime variable is either an output of the search (e.g., obtained branch coverage).
 * or something that can only be determined once the CUT is analyzed (e.g., the number of branches).</p>
 *
 * <p>Note, it is perfectly fine to add new runtime variables in this enum, in any position.
 * But it is essential to provide JavaDoc <b>descriptions</b> for each new variable.</p>
 *
 * <p>WARNING: do not change the name of any variable! If you do, current R
 * scripts will break. If you really need to change a name, please first
 * contact Andrea Arcuri.</p>
 *
 * @author arcuri
 */
public enum RuntimeVariable {

    /**
     * Number of predicates in CUT.
     */
    Predicates,
    /**
     * Number of added jump conditions through instrumentation.
     */
    Instrumented_Predicates,
    /**
     * Number of classes in classpath.
     */
    Classpath_Classes,
    /**
     * Number of classes analyzed for test cluster.
     */
    Analyzed_Classes,
    /**
     * Total number of generators.
     */
    Generators,
    /**
     * Total number of modifiers.
     */
    Modifiers,
    /**
     * Total number of branches in CUT.
     */
    Total_Branches,
    /**
     * Number of covered branches in CUT.
     */
    Covered_Branches,
    /**
     * Total number of gradient branches.
     */
    Gradient_Branches,
    /**
     * Total number of covered gradient branches.
     */
    Gradient_Branches_Covered,
    /**
     * The number of lines in the CUT.
     */
    Lines,
    /**
     * The actual covered line numbers.
     */
    Covered_Lines,
    /**
     * Total number of methods in CUT.
     */
    Total_Methods,
    /**
     * Number of methods covered.
     */
    Covered_Methods,
    /**
     * Number of methods without any predicates.
     */
    Branchless_Methods,
    /**
     * Number of methods without predicates covered.
     */
    Covered_Branchless_Methods,
    /**
     * Total number of coverage goals for current criterion.
     */
    Total_Goals,
    /**
     * Total number of covered goals.
     */
    Covered_Goals,
    /**
     * Final test generation status. Values mirror TestGenerationResult.Status
     * when available, with CLIENT_CRASH for master-observed client termination.
     */
    Test_Generation_Status,
    /**
     * Number of mutants.
     */
    Mutants,
    /**
     * Total number of statements executed.
     */
    Statements_Executed,
    /**
     * The total number of tests executed during the search.
     */
    Tests_Executed,
    /**
     * The total number of fitness evaluations during the search.
     */
    Fitness_Evaluations,
    /**
     * Number of generations the search algorithm has been evolving.
     */
    Generations,
    /**
     * Obtained coverage of the chosen testing criterion.
     */
    Coverage,
    /**
     * A bit string (0/1) representing whether goals (in order) are covered.
     */
    CoverageBitString,
    /**
     * Fitness value of the best individual.
     */
    Fitness,
    /**
     * Obtained coverage (of the chosen testing criterion) at different points in time.
     */
    CoverageTimeline,
    /**
     * Obtained fitness values at different points in time.
     */
    FitnessTimeline,
    /**
     * Population similarity values at different points in time.
     */
    DiversityTimeline,
    /**
     * Obtained size values at different points in time.
     */
    Size_T0,
    SizeTimeline,
    /**
     * Obtained length values at different points in time.
     */
    LengthTimeline,
    /**
     * The obtained statement coverage.
     */
    StatementCoverage,
    /**
     * A bit string (0/1) representing whether statements (in order) are covered.
     */
    StatementCoverageBitString,
    /**
     * The obtained rho coverage.
     */
    RhoScore,
    RhoScore_T0,
    RhoScoreTimeline,
    /**
     * The obtained ambiguity coverage.
     */
    AmbiguityScore,
    AmbiguityScore_T0,
    AmbiguityScoreTimeline,
    /**
     * Not only the covered branches ratio, but also including the branchless methods.
     * FIXME: this will need to be changed.
     */
    BranchCoverage,
    /**
     * Coverage of instrumented branches.
     */
    Total_Branches_Real,
    Total_Branches_Instrumented,
    Covered_Branches_Real,
    Covered_Branches_Instrumented,
    TryCatchCoverage,
    BranchCoverageTimeline,
    /**
     * A bit string (0/1) representing whether branches (in order) are covered.
     */
    BranchCoverageBitString,
    /**
     * Only the covered branches ratio.
     */
    OnlyBranchCoverage,
    OnlyBranchFitnessTimeline,
    OnlyBranchCoverageTimeline,
    OnlyBranchCoverageBitString,
    CBranchCoverage,
    CBranchFitnessTimeline,
    CBranchCoverageTimeline,
    CBranchCoverageBitString,
    IBranchCoverage,
    IBranchInitialGoals,
    IBranchInitialGoalsInTargetClass,
    IBranchGoalsTimeline,
    IBranchCoverageBitString,
    /**
     * The obtained method coverage (method calls anywhere in trace).
     */
    MethodTraceCoverage,
    MethodTraceFitnessTimeline,
    MethodTraceCoverageTimeline,
    MethodTraceCoverageBitString,
    /**
     * The obtained method coverage.
     */
    MethodCoverage,
    MethodFitnessTimeline,
    MethodCoverageTimeline,
    MethodCoverageBitString,
    /**
     * The obtained method coverage (only normal behaviour).
     */
    MethodNoExceptionCoverage,
    MethodNoExceptionFitnessTimeline,
    MethodNoExceptionCoverageTimeline,
    MethodNoExceptionCoverageBitString,
    /**
     * The obtained line coverage.
     */
    LineCoverage,
    LineFitnessTimeline,
    LineCoverageTimeline,
    LineCoverageBitString,
    /**
     * The obtained output value coverage.
     */
    OutputCoverage,
    OutputFitnessTimeline,
    OutputCoverageTimeline,
    OutputCoverageBitString,
    /**
     * The input value coverage.
     */
    InputCoverage,
    InputFitnessTimeline,
    InputCoverageTimeline,
    InputCoverageBitString,
    /**
     * The obtained exception coverage.
     */
    ExceptionCoverage,
    ExceptionFitnessTimeline,
    ExceptionCoverageTimeline,
    ExceptionCoverageBitString,
    /**
     * The obtained score for weak mutation testing.
     */
    WeakMutationScore,
    WeakMutationCoverageTimeline,
    WeakMutationCoverageBitString,
    /**
     * Only mutation = only infection distance.
     */
    OnlyMutationScore,
    OnlyMutationFitnessTimeline,
    OnlyMutationCoverageTimeline,
    OnlyMutationCoverageBitString,
    /**
     * The obtained score for (strong) mutation testing.
     */
    MutationScore,
    MutationCoverageBitString,
    /**
     * The total time EvoSuite spent generating the test cases.
     */
    Total_Time,
    /**
     * Number of tests in resulting test suite.
     */
    Size,
    /**
     * Total number of statements in final test suite.
     */
    Length,
    /**
     * Number of tests in resulting test suite before minimization.
     */
    Result_Size,
    /**
     * Total number of statements in final test suite before minimization.
     */
    Result_Length,
    /**
     * Either use {@link RuntimeVariable#Size}.
     */
    @Deprecated
    Minimized_Size,
    /**
     * Either use  {@link RuntimeVariable#Length}.
     */
    @Deprecated
    Minimized_Length,
    /**
     * The random seed used during the search. A random one was used if none was specified at the beginning.
     */
    Random_Seed,
    /**
     * How many tests were carved, ie used as input seeds for the search.
     */
    CarvedTests,
    /**
     * The branch coverage of the carved tests.
     */
    CarvedCoverage,
    /**
     * Was any test unstable in the generated JUnit files.
     */
    HadUnstableTests,
    /**
     * Number of unstable tests in the generated JUnit files.
     */
    NumUnstableTests,
    /**
     * An estimate (ie not precise) of the maximum number of threads running at the same time in the CUT.
     */
    Threads,
    /**
     * Number of top-level methods throwing an undeclared exception explicitly with a 'throw new'.
     */
    Explicit_MethodExceptions,
    /**
     * Number of undeclared exception types that were explicitly thrown with a 'throw new' at least once.
     */
    Explicit_TypeExceptions,
    /**
     * Number of top-level methods throwing an undeclared exception implicitly (ie, no 'new throw').
     */
    Implicit_MethodExceptions,
    /**
     * Number of undeclared exception types that were implicitly thrown (ie, no 'new throw') at least once.
     */
    Implicit_TypeExceptions,
    /**
     * Total number of exceptions covered.
     */
    TotalExceptionsTimeline,

    /**
     * Map Elites.
     */
    DensityTimeline,
    FeaturePartitionCount,
    FeatureCount,
    FeaturesFound,

    /* ----- number of unique permissions that were denied for each kind --- */
    AllPermission,
    SecurityPermission,
    UnresolvedPermission,
    AWTPermission,
    FilePermission,
    SerializablePermission,
    ReflectPermission,
    RuntimePermission,
    NetPermission,
    SocketPermission,
    SQLPermission,
    PropertyPermission,
    LoggingPermission,
    SSLPermission,
    AuthPermission,
    AudioPermission,
    OtherPermission,
    /* -------------------------------------------------------------------- */
    /**
     * Timings.
     */
    Time_Assertion,
    Time_Coverage,
    Time_StateDistance,
    Time_Diversity,
    /* -------------------------------------------------------------------- */
    /**
     * Count of branch comparison types in bytecode (static).
     */
    Cmp_IntZero,
    Cmp_IntInt,
    Cmp_RefNull,
    Cmp_RefRef,
    /**
     * Count of branch comparisons reached (dynamic).
     */
    Reached_IntZero,
    Reached_IntInt,
    Reached_RefNull,
    Reached_RefRef,
    /**
     * Count of branch comparisons covered (dynamic).
     */
    Covered_IntZero,
    Covered_IntInt,
    Covered_RefNull,
    Covered_RefRef,
    /**
     * Count of bytecode instructions (static).
     */
    BC_lcmp,
    BC_fcmpl,
    BC_fcmpg,
    BC_dcmpl,
    BC_dcmpg,
    /**
     * Count of bytecode instructions reached (dynamic).
     */
    Reached_lcmp,
    Reached_fcmpl,
    Reached_fcmpg,
    Reached_dcmpl,
    Reached_dcmpg,
    /**
     * Count of bytecode instructions reached (dynamic).
     */
    Covered_lcmp,
    Covered_fcmpl,
    Covered_fcmpg,
    Covered_dcmpl,
    Covered_dcmpg,
    /**
     * For sanity-checking purposes.
     */
    RSM_OverMinimized,
    /* -------------------------------------------------------------------- */
    /* TODO following needs to be implemented/updated. Currently they are not (necessarily) supported */
    /**
     * (FIXME: need to be implemented) The number of serialized objects that EvoSuite is going
     * to use for seeding strategies.
     */
    NumberOfInputPoolObjects,
    Error_Predicates,
    Error_Branches_Covered,
    Error_Branchless_Methods,
    Error_Branchless_Methods_Covered,
    AssertionContract,
    EqualsContract,
    EqualsHashcodeContract,
    EqualsNullContract,
    EqualsSymmetricContract,
    HashCodeReturnsNormallyContract,
    JCrasherExceptionContract,
    NullPointerExceptionContract,
    ToStringReturnsNormallyContract,
    UndeclaredExceptionContract,
    Contract_Violations,
    Unique_Violations,
    Data_File,
    /* --- Dataflow stuff. FIXME: Is this stuff still valid? --- */
    AllDefCoverage,
    AllDefCoverageBitString,
    DefUseCoverage,
    DefUseCoverageBitString,
    Definitions,
    Uses,
    DefUsePairs,
    IntraMethodPairs,
    InterMethodPairs,
    IntraClassPairs,
    ParameterPairs,
    LCSAJs,
    AliasingIntraMethodPairs,
    AliasingInterMethodPairs,
    AliasingIntraClassPairs,
    AliasingParameterPairs,
    CoveredIntraMethodPairs,
    CoveredInterMethodPairs,
    CoveredIntraClassPairs,
    CoveredParameterPairs,
    CoveredAliasIntraMethodPairs,
    CoveredAliasInterMethodPairs,
    CoveredAliasIntraClassPairs,
    CoveredAliasParameterPairs,
    /* -------------------------------------------------------------------- */
    /**
     * The number of constraint made of integer constraints and no other type.
     */
    IntegerOnlyConstraints,
    /**
     * The number of constraint made of real constraints and no other type.
     */
    RealOnlyConstraints,
    /**
     * The number of constraint made of real constraints and no other type.
     */
    StringOnlyConstraints,
    /**
     * The number of constraint made of reference constraints and no other type.
     */
    ReferenceOnlyConstraints,
    /**
     * The number of constraint made of integer and real constraints.
     */
    IntegerAndRealConstraints,
    /**
     * The number of constraint made of integer and string constraints.
     */
    IntegerAndStringConstraints,
    /**
     * The number of constraint made of real and string constraints.
     */
    RealAndStringConstraints,
    /**
     * The number of constraint made of integer constraints and reference constraints.
     */
    IntegerAndReferenceConstraints,
    /**
     * The number of constraint made of real constraints and reference constraints.
     */
    RealAndReferenceConstraints,
    /**
     * The number of constraint made of real constraints and reference constraints.
     */
    StringAndReferenceConstraints,
    /**
     * The number of constraint made of integer, real and reference constraints.
     */
    IntegerRealAndReferenceConstraints,
    /**
     * The number of constraint made of integer, string and reference constraints.
     */
    IntegerStringAndReferenceConstraints,
    /**
     * The number of constraint made of real, string and reference constraints.
     */
    RealStringAndReferenceConstraints,
    /**
     * Number of constraints containing integer, real and string constraints.
     */
    IntegerRealAndStringConstraints,
    /**
     * Number of constraints containing all four types altogether.
     */
    IntegerRealStringAndReferenceConstraints,
    /**
     * The total number of constraints during the execution of the Genetic Algorithm.
     * This total should be the sum of all the other types of constraints.
     */
    TotalNumberOfConstraints,

    /* -------------------------------------------------------------------- */
    /**
     * The number of SAT answers to Solver queries.
     */
    NumberOfSATQueries,
    /**
     * The number of UNSAT answers to Solver queries.
     */
    NumberOfUNSATQueries,
    /**
     * The number of TIMEOUTs when solving queries.
     */
    NumberOfTimeoutQueries,
    /**
     * How many SAT queries led to Useful (i.e. better fitness) new tests.
     */
    NumberOfUsefulNewTests,
    /**
     * How many SAT queries led to Unuseful (i.e. no better fitness) new tests.
     */
    NumberOfUnusefulNewTests,
    /**
     * How much time was spent solving constraints on the SMT solver.
     */
    TotalTimeSpentSolvingConstraints,

    /* -------------------------------------------------------------------- */
    /**
     * Search budget needed to reach the maximum coverage.
     * Used in the comparison between LISP and MOSA.
     */
    Time2MaxCoverage,

    /* -------------------------------------------------------------------- */
    /*       DSE related section       */

    /**
     * Path condition related.
     */
    MaxPathConditionLength,
    MinPathConditionLength,
    AvgPathConditionLength,

    /**
     * Path explotarion related.
     */
    NumberOfPathsExplored,
    NumberOfPathsDiverged,

    /**
     * How much time was spent executing tests.
     */
    TotalTimeSpentExecutingConcolicaly,
    TotalTimeSpentExecutingTestCases,
    TotalTimeSpentExecutingNonConcolicTestCases,

    /**
     * Solver Cache Statistics.
     */
    QueryCacheSize,
    QueryCacheCalls,
    QueryCacheHitRate,
    /**
     * The LLM model identifier used for this run (empty string when LLM is disabled).
     */
    LLM_Model,
    /**
     * Total number of LLM calls attempted by EvoSuite.
     */
    LLM_Calls,
    /**
     * Number of successful LLM calls.
     */
    LLM_Calls_Succeeded,
    /**
     * Number of failed LLM calls after retries.
     */
    LLM_Calls_Failed,
    /**
     * Number of LLM calls that timed out after retries.
     */
    LLM_Calls_TimedOut,
    /**
     * Total number of prompt tokens sent to providers.
     */
    LLM_Input_Tokens,
    /**
     * Total number of output tokens generated by providers.
     */
    LLM_Output_Tokens,
    /**
     * Total latency in milliseconds spent in LLM provider calls.
     */
    LLM_Latency_Millis,
    /**
     * Wall-clock milliseconds spent blocking on pre-search LLM pool
     * enrichment (cast classes, constants, objects). When
     * {@code LLM_FAIR_BUDGET_ACCOUNTING} is enabled, this value is deducted
     * from the search budget so seeding strategies can be compared on equal
     * footing.
     */
    LLM_Enrichment_Elapsed_Millis,
    /**
     * Number of cast-class suggestions parsed from LLM response.
     */
    LLM_Cast_Class_Suggestions,
    /**
     * Number of actual cast classes added to CastClassManager from LLM suggestions.
     */
    LLM_Cast_Class_Accepted,
    /**
     * Number of constants added to the SUT (system under test) constant pool via LLM enrichment.
     */
    LLM_Constants_Added_SUT,
    /**
     * Number of constants added for non-SUT dependency classes via LLM enrichment.
     */
    LLM_Constants_Added_NonSUT,
    /**
     * Number of object-pool sequences (type-key insertions) accepted via LLM enrichment.
     */
    LLM_Object_Pool_Sequences_Added,
    /**
     * Number of times an object-pool sequence was successfully used during test generation.
     */
    Object_Pool_Sequence_Used,
    /**
     * Number of tests renamed by LLM post-processing.
     */
    LLM_Tests_Renamed,
    /**
     * Number of tests where LLM naming fell back to baseline strategy.
     */
    LLM_Test_Naming_Fallbacks,
    /**
     * Number of variables renamed by LLM post-processing.
     */
    LLM_Variables_Renamed,
    /**
     * Number of variables where LLM naming fell back to type-based strategy.
     */
    LLM_Variable_Naming_Fallbacks,
    /**
     * Number of assertions added by LLM post-processing.
     */
    LLM_Assertions_Added,
    /**
     * Number of tests where LLM assertion strategy fell back to trace-based generation.
     */
    LLM_Assertion_Fallbacks,
    /**
     * Number of literals replaced by LLM niceification.
     */
    LLM_Literals_Niceified,
    /**
     * Number of compile-time failures while compiling fallback snippet code.
     */
    LLM_Fallback_Snippet_Compile_Failures,
    /**
     * Number of runtime failures while executing fallback snippet code.
     */
    LLM_Fallback_Snippet_Runtime_Failures,
    /**
     * Number of failed fallback statement executions (compile or runtime).
     */
    LLM_Fallback_Statement_Execution_Failures,
    /**
     * Number of failed fallback assertion evaluations (compile or runtime).
     */
    LLM_Fallback_Assertion_Evaluation_Failures,
    /**
     * Set to 1 for a SUT whose entire LLM-seeding batch (across all repair
     * attempts) was dropped by the executor — every parsed candidate test
     * threw an undeclared exception and no covering chromosome survived.
     * Set to 0 otherwise. Useful for triaging projects where the LLM seems to
     * succeed (LLM_Calls_Succeeded > 0) but the seeds never make it into the
     * population.
     */
    LLM_All_Candidates_Dropped_Execution,

    // ---- Phase 5: Operator & Diversity ----

    /**
     * Number of LLM semantic mutations applied.
     */
    LLM_Semantic_Mutations,
    /**
     * Number of LLM semantic mutation fallbacks (LLM unavailable or failed).
     */
    LLM_Semantic_Mutation_Fallbacks,
    /**
     * Number of LLM semantic crossovers applied.
     */
    LLM_Semantic_Crossovers,
    /**
     * Number of LLM semantic crossover fallbacks (LLM unavailable or failed).
     */
    LLM_Semantic_Crossover_Fallbacks,
    /**
     * Number of stagnation LLM calls submitted (across both SYNC and ASYNC modes).
     */
    LLM_StagnationCalls,
    /**
     * Number of stagnation prompt attempts committed after consuming the stagnation window.
     */
    LLM_StagnationPromptsSubmitted,
    /**
     * Number of stagnation prompt executions that returned a response payload.
     */
    LLM_StagnationResponsesReceived,
    /**
     * Number of parsed stagnation tests published to the MOSA intake queue.
     */
    LLM_StagnationTestsPublished,
    /**
     * Total wall-clock LLM call latency (ms) for stagnation calls — mode-independent
     * (same LLM, same network). Useful as a sanity check across SYNC/ASYNC arms.
     */
    LLM_StagnationLatencyMsTotal,
    /**
     * Total time (ms) the GA evolve thread spent blocked on stagnation LLM calls.
     * Equals latency in SYNC mode, ~0 in ASYNC mode.
     */
    LLM_StagnationBlockedMsTotal,
    /**
     * ASYNC-only: GA generations evolved while a stagnation LLM call was in flight.
     * Quantifies the search progress that would have been blocked in SYNC mode.
     */
    LLM_StagnationInFlightGenerations,
    /**
     * Stagnation submissions skipped because the remaining search budget was below
     * llm_stagnation_budget_guard_seconds. Counted in both modes.
     */
    LLM_StagnationSkippedBudget,
    /**
     * ASYNC-only: stagnation submissions skipped because a previous call was still in flight.
     */
    LLM_StagnationSkippedInFlight,
    /**
     * LLM-injected candidates filtered out before fitness evaluation because they were null or orphaned.
     */
    LLM_Injected_Candidates_OrphanFiltered,
    /**
     * LLM-injected candidates deduplicated before fitness evaluation.
     */
    LLM_Injected_Candidates_Deduplicated,
    /**
     * LLM-injected candidates admitted into the MOSA union after evaluation.
     */
    LLM_Injected_Candidates_Admitted,
    /**
     * Freshly injected LLM candidates that survived the same generation's selection step.
     */
    LLM_Injected_Candidates_Survived,
    /**
     * Total number of diagnostic problem cards extracted before prompt selection.
     */
    LLM_Diagnostic_Cards_Extracted,
    /**
     * Number of extracted diagnostic cards of type UNREACHED_METHOD.
     */
    LLM_Diagnostic_Cards_Extracted_UnreachedMethod,
    /**
     * Number of extracted diagnostic cards of type BRANCH_POLARITY_GAP.
     */
    LLM_Diagnostic_Cards_Extracted_BranchPolarityGap,
    /**
     * Number of extracted diagnostic cards of type STATE_DIVERSIFICATION_GAP.
     */
    LLM_Diagnostic_Cards_Extracted_StateDiversificationGap,
    /**
     * Number of extracted diagnostic cards of type EXCEPTION_BARRIER.
     */
    LLM_Diagnostic_Cards_Extracted_ExceptionBarrier,
    /**
     * Number of extracted diagnostic cards of type CDG_BOTTLENECK.
     */
    LLM_Diagnostic_Cards_Extracted_CdgBottleneck,
    /**
     * Number of extracted diagnostic cards of type UNINSTANTIABLE_TYPE.
     */
    LLM_Diagnostic_Cards_Extracted_UninstantiableType,
    /**
     * Number of extracted diagnostic cards of type STATE_SETUP_BARRIER.
     */
    LLM_Diagnostic_Cards_Extracted_StateSetupBarrier,
    /**
     * Number of extracted diagnostic cards of type INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Cards_Extracted_IndirectReachabilityBarrier,
    /**
     * Upstream throwing helper/bootstrap calls that could not be attributed to a downstream blocked goal.
     */
    LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal,
    /**
     * UNINSTANTIABLE_TYPE candidates suppressed because meaningful same-type or goal-bearing progress exists.
     */
    LLM_Diagnostic_Extractor_Rejects_UninstantiableProgressBeyondCreation,
    /**
     * STATE_SETUP_BARRIER candidates suppressed because successful executions broke failure dominance.
     */
    LLM_Diagnostic_Extractor_Rejects_StateSetupDilutedSuccess,
    /**
     * Bootstrap/setup observations that could not be mapped onto a more specific blocked goal-bearing type.
     */
    LLM_Diagnostic_Extractor_Rejects_BlockedTypeMappingFailure,
    /**
     * Repeated upstream throwing helper/bootstrap sources observed before downstream blocked-goal attribution.
     */
    LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionRepeatedSources,
    /**
     * Distinct downstream blocked goal methods discovered behind upstream throwing helper/bootstrap calls.
     */
    LLM_Diagnostic_Extractor_Candidates_UpstreamExceptionBlockedGoalMethods,
    /**
     * Direct exception-barrier candidates that met method-level consistency thresholds.
     */
    LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierMethodCandidates,
    /**
     * Context-local exception-barrier candidates that met per-context consistency thresholds.
     */
    LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierContextCandidates,
    /**
     * Upstream exception-barrier candidates that met blocked-goal attribution and consistency thresholds.
     */
    LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierUpstreamCandidates,
    /**
     * Exception-barrier candidates suppressed because attempts or exception counts stayed below threshold.
     */
    LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierSuppressedInsufficientAttempts,
    /**
     * Exception-barrier candidates suppressed because exception dominance stayed below threshold.
     */
    LLM_Diagnostic_Extractor_Candidates_ExceptionBarrierSuppressedLowFailureRate,
    /**
     * Goal-bearing types that showed construction/factory failures before any blocker card extraction.
     */
    LLM_Diagnostic_Extractor_Candidates_TypeBarrierSignalsWithConstructionFailure,
    /**
     * Goal-bearing types that showed setup/lifecycle failures before any blocker card extraction.
     */
    LLM_Diagnostic_Extractor_Candidates_TypeBarrierSignalsWithSetupFailure,
    /**
     * UNINSTANTIABLE_TYPE candidate types that cleared the minimum-attempt gate.
     */
    LLM_Diagnostic_Extractor_Candidates_UninstantiableTypeCandidates,
    /**
     * UNINSTANTIABLE_TYPE candidates suppressed because acquisition failures were too sparse.
     */
    LLM_Diagnostic_Extractor_Candidates_UninstantiableTypeSuppressedInsufficientAttempts,
    /**
     * UNINSTANTIABLE_TYPE candidates suppressed because acquisition failures were not dominant enough.
     */
    LLM_Diagnostic_Extractor_Candidates_UninstantiableTypeSuppressedLowFailureRate,
    /**
     * STATE_SETUP_BARRIER candidate types that cleared the basic acquisition and setup-attempt gates.
     */
    LLM_Diagnostic_Extractor_Candidates_StateSetupBarrierCandidates,
    /**
     * STATE_SETUP_BARRIER candidates suppressed because no successful acquisition was observed first.
     */
    LLM_Diagnostic_Extractor_Candidates_StateSetupBarrierSuppressedNoSuccessfulAcquisition,
    /**
     * STATE_SETUP_BARRIER candidates suppressed because setup failures were too sparse.
     */
    LLM_Diagnostic_Extractor_Candidates_StateSetupBarrierSuppressedInsufficientAttempts,
    /**
     * STATE_SETUP_BARRIER candidates suppressed because no failing setup step was consistent enough.
     */
    LLM_Diagnostic_Extractor_Candidates_StateSetupBarrierSuppressedInconsistentFailingStep,
    /**
     * Total number of diagnostic problem cards selected into stagnation prompts.
     */
    LLM_Diagnostic_Cards_Selected,
    /**
     * Total number of extracted diagnostic cards discarded by selector policy.
     */
    LLM_Diagnostic_Cards_Discarded,
    /**
     * Number of discarded cards removed specifically due to root-cause overlap.
     */
    LLM_Diagnostic_Cards_Deduplicated,
    /**
     * Prompt targets skipped because the same evidence was selected too recently.
     */
    LLM_Repeated_Prompt_Targets_Suppressed_Recent,
    /**
     * Prompt targets skipped because an async attempt for them is still outstanding.
     */
    LLM_Repeated_Prompt_Targets_Suppressed_InFlight,
    /**
     * Prompt targets retried because the observed evidence fingerprint changed.
     */
    LLM_Repeated_Prompt_Targets_Retried_Changed,
    /**
     * Number of selected diagnostic cards of type UNREACHED_METHOD.
     */
    LLM_Diagnostic_Cards_UnreachedMethod,
    /**
     * Number of selected diagnostic cards of type BRANCH_POLARITY_GAP.
     */
    LLM_Diagnostic_Cards_BranchPolarityGap,
    /**
     * Number of selected diagnostic cards of type STATE_DIVERSIFICATION_GAP.
     */
    LLM_Diagnostic_Cards_StateDiversificationGap,
    /**
     * Number of selected diagnostic cards of type EXCEPTION_BARRIER.
     */
    LLM_Diagnostic_Cards_ExceptionBarrier,
    /**
     * Number of selected diagnostic cards of type CDG_BOTTLENECK.
     */
    LLM_Diagnostic_Cards_CdgBottleneck,
    /**
     * Number of selected diagnostic cards of type UNINSTANTIABLE_TYPE.
     */
    LLM_Diagnostic_Cards_UninstantiableType,
    /**
     * Number of selected diagnostic cards of type STATE_SETUP_BARRIER.
     */
    LLM_Diagnostic_Cards_StateSetupBarrier,
    /**
     * Number of selected diagnostic cards of type INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Cards_IndirectReachabilityBarrier,
    /**
     * Total diagnostic-prompt candidates published by the stagnation helper.
     */
    LLM_Diagnostic_Candidates_Published,
    /**
     * Published diagnostic-prompt candidates associated with UNREACHED_METHOD.
     */
    LLM_Diagnostic_Candidates_Published_UnreachedMethod,
    /**
     * Published diagnostic-prompt candidates associated with BRANCH_POLARITY_GAP.
     */
    LLM_Diagnostic_Candidates_Published_BranchPolarityGap,
    /**
     * Published diagnostic-prompt candidates associated with STATE_DIVERSIFICATION_GAP.
     */
    LLM_Diagnostic_Candidates_Published_StateDiversificationGap,
    /**
     * Published diagnostic-prompt candidates associated with EXCEPTION_BARRIER.
     */
    LLM_Diagnostic_Candidates_Published_ExceptionBarrier,
    /**
     * Published diagnostic-prompt candidates associated with CDG_BOTTLENECK.
     */
    LLM_Diagnostic_Candidates_Published_CdgBottleneck,
    /**
     * Published diagnostic-prompt candidates associated with UNINSTANTIABLE_TYPE.
     */
    LLM_Diagnostic_Candidates_Published_UninstantiableType,
    /**
     * Published diagnostic-prompt candidates associated with STATE_SETUP_BARRIER.
     */
    LLM_Diagnostic_Candidates_Published_StateSetupBarrier,
    /**
     * Published diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Published_IndirectReachabilityBarrier,
    /**
     * Total diagnostic-prompt candidates admitted into the MOSA union.
     */
    LLM_Diagnostic_Candidates_Admitted,
    /**
     * Admitted diagnostic-prompt candidates associated with UNREACHED_METHOD.
     */
    LLM_Diagnostic_Candidates_Admitted_UnreachedMethod,
    /**
     * Admitted diagnostic-prompt candidates associated with BRANCH_POLARITY_GAP.
     */
    LLM_Diagnostic_Candidates_Admitted_BranchPolarityGap,
    /**
     * Admitted diagnostic-prompt candidates associated with STATE_DIVERSIFICATION_GAP.
     */
    LLM_Diagnostic_Candidates_Admitted_StateDiversificationGap,
    /**
     * Admitted diagnostic-prompt candidates associated with EXCEPTION_BARRIER.
     */
    LLM_Diagnostic_Candidates_Admitted_ExceptionBarrier,
    /**
     * Admitted diagnostic-prompt candidates associated with CDG_BOTTLENECK.
     */
    LLM_Diagnostic_Candidates_Admitted_CdgBottleneck,
    /**
     * Admitted diagnostic-prompt candidates associated with UNINSTANTIABLE_TYPE.
     */
    LLM_Diagnostic_Candidates_Admitted_UninstantiableType,
    /**
     * Admitted diagnostic-prompt candidates associated with STATE_SETUP_BARRIER.
     */
    LLM_Diagnostic_Candidates_Admitted_StateSetupBarrier,
    /**
     * Admitted diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Admitted_IndirectReachabilityBarrier,
    /**
     * Total diagnostic-prompt candidates that survived the generation they were injected.
     */
    LLM_Diagnostic_Candidates_Survived,
    /**
     * Surviving diagnostic-prompt candidates associated with UNREACHED_METHOD.
     */
    LLM_Diagnostic_Candidates_Survived_UnreachedMethod,
    /**
     * Surviving diagnostic-prompt candidates associated with BRANCH_POLARITY_GAP.
     */
    LLM_Diagnostic_Candidates_Survived_BranchPolarityGap,
    /**
     * Surviving diagnostic-prompt candidates associated with STATE_DIVERSIFICATION_GAP.
     */
    LLM_Diagnostic_Candidates_Survived_StateDiversificationGap,
    /**
     * Surviving diagnostic-prompt candidates associated with EXCEPTION_BARRIER.
     */
    LLM_Diagnostic_Candidates_Survived_ExceptionBarrier,
    /**
     * Surviving diagnostic-prompt candidates associated with CDG_BOTTLENECK.
     */
    LLM_Diagnostic_Candidates_Survived_CdgBottleneck,
    /**
     * Surviving diagnostic-prompt candidates associated with UNINSTANTIABLE_TYPE.
     */
    LLM_Diagnostic_Candidates_Survived_UninstantiableType,
    /**
     * Surviving diagnostic-prompt candidates associated with STATE_SETUP_BARRIER.
     */
    LLM_Diagnostic_Candidates_Survived_StateSetupBarrier,
    /**
     * Surviving diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Survived_IndirectReachabilityBarrier,
    /**
     * Total uncovered-goal gains attributed to diagnostic stagnation prompts.
     */
    LLM_Diagnostic_Coverage_Gains,
    /**
     * Diagnostic attempts whose gains could not be assigned to a single card type.
     */
    LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous,
    /**
     * Uncovered goals gained by ambiguous diagnostic attempts.
     */
    LLM_Diagnostic_Coverage_Gain_Attribution_Ambiguous_Goals,
    /**
     * Attributed uncovered-goal gains for UNREACHED_METHOD diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_UnreachedMethod,
    /**
     * Attributed uncovered-goal gains for BRANCH_POLARITY_GAP diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_BranchPolarityGap,
    /**
     * Attributed uncovered-goal gains for STATE_DIVERSIFICATION_GAP diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_StateDiversificationGap,
    /**
     * Attributed uncovered-goal gains for EXCEPTION_BARRIER diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_ExceptionBarrier,
    /**
     * Attributed uncovered-goal gains for CDG_BOTTLENECK diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_CdgBottleneck,
    /**
     * Attributed uncovered-goal gains for UNINSTANTIABLE_TYPE diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_UninstantiableType,
    /**
     * Attributed uncovered-goal gains for STATE_SETUP_BARRIER diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_StateSetupBarrier,
    /**
     * Attributed uncovered-goal gains for INDIRECT_REACHABILITY_BARRIER diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_IndirectReachabilityBarrier,
    /**
     * Per-generation species count timeline.
     */
    Species_Count_Timeline,
    /**
     * Per-generation largest species share timeline.
     */
    Species_Largest_Share_Timeline,
    /**
     * Per-generation count of survivors admitted via species minimum-quota protection.
     */
    Species_Quota_Protected_Timeline,
    /**
     * Per-generation count of survivors admitted via newborn age protection.
     */
    Species_Newborn_Protected_Timeline,
    /**
     * Per-generation count of survivors admitted via incubator-species protection.
     */
    Species_Incubator_Protected_Timeline,
    /**
     * Per-generation count of individuals whose crowding score was adjusted by
     * species-density fitness sharing.
     */
    Species_Sharing_Adjusted_Timeline,

    // ---- Phase 6: Parsed-Statement Provenance ----

    /**
     * Ratio of LLM-parsed statements to total statements in the final population.
     */
    LLM_Parsed_Statement_Ratio,
    /**
     * Per-iteration or per-generation timeline of the LLM-parsed statement ratio.
     */
    LLM_Parsed_Statement_Ratio_Timeline,

    // ---- ITERATIVE_BUDGETED strategy observability ----

    /** Number of follow-up iterations executed by ITERATIVE_BUDGETED. */
    LLM_Iterative_Iterations,
    /**
     * Why the ITERATIVE_BUDGETED loop terminated.
     * One of: STOPPING_CONDITION, ALL_GOALS_COVERED, LLM_BUDGET_EXHAUSTED,
     * MAX_ITERATIONS, NO_PROGRESS, PARSE_FAIL_STREAK, INITIAL_QUERY_FAILED.
     */
    LLM_Iterative_Exit_Reason,
    /** Total iterations that produced zero parsed tests. */
    LLM_Iterative_Parse_Failures,
    /** Per-iteration count of newly-covered goals (semicolon-separated). */
    LLM_Iterative_New_Goals_Timeline,
    /** Per-iteration cumulative-covered goal count (semicolon-separated). */
    LLM_Iterative_Coverage_Timeline,

    // ---- Phase 8: Diversity Observability ----

    /**
     * Per-generation number of ranking fronts in MOSA/DynaMOSA.
     */
    Fronts_Count_Timeline,
    /**
     * Per-generation remaining (uncovered) goals count in MOSA/DynaMOSA.
     */
    Remaining_Goals_Timeline,
    /**
     * Per-generation covered goals count in MOSA/DynaMOSA.
     */
    Covered_Goals_Timeline,

    // ---- Operator Disruption Analysis ----

    /**
     * Total disruption events recorded (all operator kinds and sources).
     */
    Disruption_Events_Total,
    /**
     * Disruption events for standard mutation.
     */
    Disruption_Standard_Mutations,
    /**
     * Disruption events for semantic (LLM) mutation.
     */
    Disruption_Semantic_Mutations,
    /**
     * Disruption events for standard crossover.
     */
    Disruption_Standard_Crossovers,
    /**
     * Disruption events for semantic (LLM) crossover.
     */
    Disruption_Semantic_Crossovers,
    /**
     * Number of semantic operator fallbacks to standard operator.
     */
    Disruption_Semantic_Fallbacks,
    /**
     * Path to sidecar disruption CSV artifact for this run.
     */
    Disruption_Sidecar_Path,
    /**
     * Number of offspring chromosomes dropped after operators because they
     * contained orphaned VariableReferences (would crash TestCase.clone()
     * during fitness evaluation). Bumped by the tripwire in
     * {@code AbstractMOSA.processOffspringMutation}.
     */
    Orphaned_Offspring_Dropped,
    /**
     * Number of archive add operations aborted because cloning the candidate
     * solution threw (e.g., an orphaned VariableReference that slipped past
     * the offspring tripwire). Bumped by the defensive guard in
     * {@code CoverageArchive.addToArchive}; non-zero values indicate a
     * malformed chromosome reached fitness evaluation despite earlier checks.
     */
    Archive_Clone_Failures;

    /* -------------------------------------------------- */

    private static final Logger logger = LoggerFactory.getLogger(RuntimeVariable.class);

    /**
     * Checks if the variables do satisfy a set of predefined constraints: eg, the
     * number of covered targets cannot be higher than their total number.
     *
     * @param map from (key->variable name) to (value -> output variable)
     * @return true if valid
     * @deprecated Use {@link StatisticsValidator#validateRuntimeVariables(Map)} instead.
     */
    @Deprecated
    public static boolean validateRuntimeVariables(Map<String, OutputVariable<?>> map) {
        return StatisticsValidator.validateRuntimeVariables(map);
    }
}
