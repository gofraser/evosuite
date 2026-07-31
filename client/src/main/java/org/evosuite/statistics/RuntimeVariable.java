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
    Minimization_Status,
    Minimization_Stop_Cause,
    Minimization_Original_Tests,
    Minimization_Original_Length,
    Minimization_Final_Tests,
    Minimization_Final_Length,
    Minimization_Elapsed_Millis,
    /** Whether this run successfully exported a structural-suite artifact. */
    Structural_Suite_Exported,
    /** Structural fingerprint written by an export run. */
    Structural_Suite_Export_Fingerprint,
    /** Number of tests written by a structural-suite export run. */
    Structural_Suite_Export_Tests,
    /** Number of statements written by a structural-suite export run. */
    Structural_Suite_Export_Statements,
    /** Oracle arm selected for a structural-suite replay. */
    Oracle_Replay_Strategy,
    /** Structural fingerprint stored in, and validated against, the replay artifact. */
    Oracle_Replay_Input_Fingerprint,
    /** Number of tests loaded from the replay artifact. */
    Oracle_Replay_Input_Tests,
    /** Number of statements loaded from the replay artifact. */
    Oracle_Replay_Input_Statements,
    /** Structural fingerprint after oracle generation and JUnit validation. */
    Oracle_Replay_Output_Fingerprint,
    /** Number of tests remaining after oracle generation and JUnit validation. */
    Oracle_Replay_Output_Tests,
    /** Number of statements remaining after oracle generation and JUnit validation. */
    Oracle_Replay_Output_Statements,
    /** Whether oracle materialization preserved the input suite structure. */
    Oracle_Replay_Structure_Preserved,
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
     * Legacy alias for {@link #LLM_Pool_Enrichment_Elapsed_Millis}.
     */
    LLM_Enrichment_Elapsed_Millis,
    /**
     * Wall-clock milliseconds spent blocking on pre-search LLM pool
     * enrichment (cast classes, constants, objects).
     */
    LLM_Pool_Enrichment_Elapsed_Millis,
    /**
     * Wall-clock milliseconds spent blocking while awaiting initial-population
     * LLM seeds.
     */
    LLM_Initial_Population_Elapsed_Millis,
    /**
     * Total wall-clock milliseconds spent blocking on all pre-search LLM work.
     */
    LLM_Total_Pre_Search_Elapsed_Millis,
    /**
     * Final executable test cases returned by the successful initial-population
     * parse/repair pipeline, before deduplication.
     */
    LLM_Initial_Population_Candidates_Validated,
    /**
     * Unique initial-population test chromosomes accepted into the seed queue.
     */
    LLM_Initial_Population_Candidates_Queued,
    /**
     * Initial-population test chromosomes actually incorporated into the GA
     * population or suite.
     */
    LLM_Initial_Population_Candidates_Injected,
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
     * Number of async-producer iterations in diagnostic mode, including iterations
     * that fell back to a goal-only prompt because no cards were available.
     */
    LLM_AsyncProducer_DiagnosticCalls,
    /**
     * Total number of diagnostic cards included across async-producer prompts.
     */
    LLM_AsyncProducer_Cards_Used,
    /**
     * Total number of iterations executed by the async-producer loop.
     */
    LLM_AsyncProducer_LoopIterations,
    /**
     * Stable enum name describing why the async producer terminated.
     */
    LLM_AsyncProducer_Stopped_Reason,
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
     * Brood variants bred from LLM-injected candidates (all blend channels).
     */
    LLM_Blend_Variants_Bred,
    /**
     * Brood variants admitted into the MOSA union (all blend channels).
     */
    LLM_Blend_Variants_Admitted,
    /**
     * Brood variants that survived the same generation's selection step (all blend channels).
     */
    LLM_Blend_Variants_Survived,
    /**
     * Mutation-burst variants admitted into the MOSA union.
     */
    LLM_Blend_Mutant_Admitted,
    /**
     * Mutation-burst variants that survived the same generation's selection step.
     */
    LLM_Blend_Mutant_Survived,
    /**
     * Goal-directed crossover variants admitted into the MOSA union.
     */
    LLM_Blend_XoverGoal_Admitted,
    /**
     * Goal-directed crossover variants that survived the same generation's selection step.
     */
    LLM_Blend_XoverGoal_Survived,
    /**
     * Tournament-partner crossover variants admitted into the MOSA union.
     */
    LLM_Blend_XoverTournament_Admitted,
    /**
     * Tournament-partner crossover variants that survived the same generation's selection step.
     */
    LLM_Blend_XoverTournament_Survived,
    /**
     * Fitness evaluations spent on brood variants.
     */
    LLM_Blend_Evals_Spent,
    /**
     * Brood crossover attempts that failed with ConstructionFailedException.
     */
    LLM_Blend_Crossover_Failed,
    /**
     * Goal-directed blend channel runs that resolved a goal-directed partner.
     */
    LLM_Blend_GoalPartner_Resolved,
    /**
     * Goal-directed blend channel runs that fell back to tournament partner selection.
     */
    LLM_Blend_GoalPartner_FallbackTournament,
    /**
     * Lineage-elitism re-insertions of injected-lineage members into the population.
     */
    LLM_Lineage_Elitism_Reinserted,
    /**
     * Drained LLM candidates whose metadata target goals were all already covered on arrival.
     */
    LLM_Async_Candidates_StaleTarget,
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
     * Number of extracted diagnostic cards of type INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Cards_Extracted_IndirectReachabilityBarrier,
    /**
     * Number of extracted diagnostic cards of type TYPE_NEVER_ATTEMPTED.
     */
    LLM_Diagnostic_Cards_Extracted_TypeNeverAttempted,
    /**
     * Number of extracted diagnostic cards of type MOCK_NEEDED_DEPENDENCY.
     */
    LLM_Diagnostic_Cards_Extracted_MockNeededDependency,
    /**
     * Number of extracted diagnostic cards of type ENVIRONMENT_BARRIER.
     */
    LLM_Diagnostic_Cards_Extracted_EnvironmentBarrier,
    /**
     * Upstream throwing helper/bootstrap calls that could not be attributed to a downstream blocked goal.
     */
    LLM_Diagnostic_Extractor_Rejects_UpstreamExceptionWithoutBlockedGoal,
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
     * Distinct interface/abstract collaborators flagged as never supplied to a goal method.
     */
    LLM_Diagnostic_Extractor_Candidates_MockNeededDependencyCandidates,
    /**
     * MOCK_NEEDED_DEPENDENCY candidates suppressed because a concrete instance or functional mock
     * of the collaborator was materialized in the population.
     */
    LLM_Diagnostic_Extractor_Candidates_MockNeededDependencySuppressedMaterialized,
    /**
     * Goal methods flagged as sitting behind an external-environment read (file/network/property).
     */
    LLM_Diagnostic_Extractor_Candidates_EnvironmentBarrierCandidates,
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
     * Number of selected diagnostic cards of type INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Cards_IndirectReachabilityBarrier,
    /**
     * Number of selected diagnostic cards of type TYPE_NEVER_ATTEMPTED.
     */
    LLM_Diagnostic_Cards_TypeNeverAttempted,
    /**
     * Number of selected diagnostic cards of type MOCK_NEEDED_DEPENDENCY.
     */
    LLM_Diagnostic_Cards_MockNeededDependency,
    /**
     * Number of selected diagnostic cards of type ENVIRONMENT_BARRIER.
     */
    LLM_Diagnostic_Cards_EnvironmentBarrier,
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
     * Published diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Published_IndirectReachabilityBarrier,
    /**
     * Published diagnostic-prompt candidates associated with TYPE_NEVER_ATTEMPTED.
     */
    LLM_Diagnostic_Candidates_Published_TypeNeverAttempted,
    /**
     * Published diagnostic-prompt candidates associated with MOCK_NEEDED_DEPENDENCY.
     */
    LLM_Diagnostic_Candidates_Published_MockNeededDependency,
    /**
     * Published diagnostic-prompt candidates associated with ENVIRONMENT_BARRIER.
     */
    LLM_Diagnostic_Candidates_Published_EnvironmentBarrier,
    /**
     * Total diagnostic-prompt candidates admitted into the MOSA union.
     * Counts both SYNC (stagnation) and ASYNC card-informed injections.
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
     * Admitted diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Admitted_IndirectReachabilityBarrier,
    /**
     * Admitted diagnostic-prompt candidates associated with TYPE_NEVER_ATTEMPTED.
     */
    LLM_Diagnostic_Candidates_Admitted_TypeNeverAttempted,
    /**
     * Admitted diagnostic-prompt candidates associated with MOCK_NEEDED_DEPENDENCY.
     */
    LLM_Diagnostic_Candidates_Admitted_MockNeededDependency,
    /**
     * Admitted diagnostic-prompt candidates associated with ENVIRONMENT_BARRIER.
     */
    LLM_Diagnostic_Candidates_Admitted_EnvironmentBarrier,
    /**
     * Total diagnostic-prompt candidates that survived the generation they were injected.
     * Counts both SYNC (stagnation) and ASYNC card-informed injections.
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
     * Surviving diagnostic-prompt candidates associated with INDIRECT_REACHABILITY_BARRIER.
     */
    LLM_Diagnostic_Candidates_Survived_IndirectReachabilityBarrier,
    /**
     * Surviving diagnostic-prompt candidates associated with TYPE_NEVER_ATTEMPTED.
     */
    LLM_Diagnostic_Candidates_Survived_TypeNeverAttempted,
    /**
     * Surviving diagnostic-prompt candidates associated with MOCK_NEEDED_DEPENDENCY.
     */
    LLM_Diagnostic_Candidates_Survived_MockNeededDependency,
    /**
     * Surviving diagnostic-prompt candidates associated with ENVIRONMENT_BARRIER.
     */
    LLM_Diagnostic_Candidates_Survived_EnvironmentBarrier,
    /**
     * Total uncovered-goal gains attributed to card-informed diagnostic prompts,
     * from both SYNC (stagnation) and ASYNC injections.
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
     * Attributed uncovered-goal gains for INDIRECT_REACHABILITY_BARRIER diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_IndirectReachabilityBarrier,
    /**
     * Attributed uncovered-goal gains for TYPE_NEVER_ATTEMPTED diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_TypeNeverAttempted,
    /**
     * Attributed uncovered-goal gains for MOCK_NEEDED_DEPENDENCY diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_MockNeededDependency,
    /**
     * Attributed uncovered-goal gains for ENVIRONMENT_BARRIER diagnostics.
     */
    LLM_Diagnostic_Coverage_Gains_EnvironmentBarrier,
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
    /**
     * Per-generation fraction of the population carrying genetic material descended
     * from an injected lineage (descent marks plus own injection lineage).
     */
    LLM_Descent_Population_Share_Timeline,

    // ---- ITERATIVE_BUDGETED strategy observability ----

    /** Number of follow-up iterations executed by ITERATIVE_BUDGETED. */
    LLM_Iterative_Iterations,
    /** Total LLM rounds, including the initial broad-coverage request. */
    LLM_Iterative_Rounds,
    /**
     * Why the ITERATIVE_BUDGETED loop terminated.
     * One of: STOPPING_CONDITION, ALL_GOALS_COVERED, LLM_BUDGET_EXHAUSTED,
     * TIME_BUDGET_EXHAUSTED, MAX_ITERATIONS, NO_PROGRESS, PARSE_FAIL_STREAK.
     */
    LLM_Iterative_Exit_Reason,
    /** Total iterations that produced zero parsed tests. */
    LLM_Iterative_Parse_Failures,
    /** Per-iteration count of newly-covered goals (semicolon-separated). */
    LLM_Iterative_New_Goals_Timeline,
    /** Per-iteration cumulative-covered goal count (semicolon-separated). */
    LLM_Iterative_Coverage_Timeline,
    /** Total tests accepted by parsing/repair across iterative rounds. */
    LLM_Iterative_Tests_Parsed,
    /** Total non-duplicate tests admitted to the iterative suite. */
    LLM_Iterative_Tests_Unique,
    /** Total duplicate tests rejected during iterative suite construction. */
    LLM_Iterative_Duplicates,
    /** Total assertions retained in the final LLMSTRATEGY suite. */
    LLM_Strategy_Assertions_Retained,
    /** Number of final LLMSTRATEGY tests containing at least one assertion. */
    LLM_Strategy_Tests_With_Assertions,
    /** Per-round elapsed milliseconds (semicolon-separated). */
    LLM_Iterative_Round_Elapsed_Millis_Timeline,
    /** Per-round query plus repair latency in milliseconds (semicolon-separated). */
    LLM_Iterative_Query_Latency_Millis_Timeline,
    /** Per-round suite size after admission (semicolon-separated). */
    LLM_Iterative_Suite_Size_Timeline,
    /** Per-round suite length after admission (semicolon-separated). */
    LLM_Iterative_Suite_Length_Timeline,
    /** Per-round number of parsed tests returned by parse/repair (semicolon-separated). */
    LLM_Iterative_Tests_Parsed_Timeline,
    /** Per-round number of unique tests admitted (semicolon-separated). */
    LLM_Iterative_Tests_Unique_Timeline,
    /** Per-round number of target goals included in the request. */
    LLM_Iterative_Target_Goals_Timeline,

    /** Unified LLM post-processing skip/no-op reason for the run. */
    LLM_PostProcessing_Skip_Reason,
    /** Minimization status seen by unified LLM post-processing. */
    LLM_PostProcessing_Minimization_Status,
    /** Minimization stop cause seen by unified LLM post-processing. */
    LLM_PostProcessing_Minimization_Stop_Cause,
    /** Number of tests for which unified LLM post-processing issued a request. */
    LLM_PostProcessing_Requested_Tests,
    /** Number of statements included in issued unified LLM post-processing requests. */
    LLM_PostProcessing_Requested_Statements,
    /** Number of initial unified LLM post-processing calls issued. */
    LLM_PostProcessing_Initial_Calls,
    /** Number of assertion-repair calls issued by unified LLM post-processing. */
    LLM_PostProcessing_Repair_Calls,
    /** Number of otherwise-eligible assertion-repair calls skipped for lack of call budget. */
    LLM_PostProcessing_Repair_Calls_Skipped_Budget,
    /** Number of parsed unified LLM post-processing responses accepted for application. */
    LLM_PostProcessing_Accepted_Responses,
    /** Number of tests skipped by unified LLM post-processing eligibility/scope gates. */
    LLM_PostProcessing_Skipped_Tests,
    /** Number of tests not processed because unified LLM post-processing caps stopped the phase. */
    LLM_PostProcessing_Cap_Skipped_Tests,
    /** Number of unified LLM post-processing responses rejected as infrastructure/whole-response failures. */
    LLM_PostProcessing_Infrastructure_Failures,
    /** Number of rejected unified LLM post-processing sub-edits reported by parser diagnostics. */
    LLM_PostProcessing_Rejected_Edits,
    /** Number of rejected unified LLM post-processing sub-edits with unknown statement/variable IDs. */
    LLM_PostProcessing_Rejected_Unknown_Ids,
    /** Number of duplicate unified LLM post-processing sub-edits rejected. */
    LLM_PostProcessing_Rejected_Duplicates,
    /** Number of unified LLM post-processing sub-edits rejected for invalid fields or values. */
    LLM_PostProcessing_Rejected_Invalid_Fields,
    /** Number of unified LLM post-processing assertions rejected for unsupported kinds. */
    LLM_PostProcessing_Rejected_Unsupported_Kinds,
    /** Number of unified LLM post-processing sub-edits rejected for configured limits. */
    LLM_PostProcessing_Rejected_Limit_Exceeded,
    /** Number of unified LLM post-processing assertions rejected during snippet compilation. */
    LLM_PostProcessing_Rejected_Compile,
    /** Number of unified LLM post-processing assertions rejected against the original observed scope. */
    LLM_PostProcessing_Rejected_Observed_Execution,
    /** Number of unified LLM post-processing assertions rejected against the stability re-execution scope. */
    LLM_PostProcessing_Rejected_Stability_Execution,
    /** Number of tests where unified LLM post-processing used trace-based assertion fallback. */
    LLM_PostProcessing_Assertion_Fallbacks,
    /** Number of trace-based assertion fallbacks triggered by LLM infrastructure failures. */
    LLM_PostProcessing_Assertion_Fallbacks_Infrastructure,
    /** Number of trace-based assertion fallbacks triggered by zero accepted LLM assertions. */
    LLM_PostProcessing_Assertion_Fallbacks_No_Accepted,
    /** Number of trace-based assertion fallbacks using the ALL strategy. */
    LLM_PostProcessing_Assertion_Fallbacks_All,
    /** Number of trace-based assertion fallbacks using the MUTATION strategy. */
    LLM_PostProcessing_Assertion_Fallbacks_Mutation,
    /** Number of fallback assertions applied by unified LLM post-processing. */
    LLM_PostProcessing_Fallback_Assertions_Applied,
    /** Number of tests with a parsed response and at least one applied edit. */
    LLM_PostProcessing_Processed_Tests,
    /** Number of tests with a parsed response but no applied edits. */
    LLM_PostProcessing_Partially_Processed_Tests,
    /** Number of test names proposed by unified LLM post-processing. */
    LLM_PostProcessing_Test_Names_Proposed,
    /** Number of test names applied by unified LLM post-processing. */
    LLM_PostProcessing_Test_Names_Applied,
    /** Number of variable names proposed by unified LLM post-processing. */
    LLM_PostProcessing_Variable_Names_Proposed,
    /** Number of variable names applied by unified LLM post-processing. */
    LLM_PostProcessing_Variable_Names_Applied,
    /** Number of comments proposed by unified LLM post-processing. */
    LLM_PostProcessing_Comments_Proposed,
    /** Number of comments applied by unified LLM post-processing. */
    LLM_PostProcessing_Comments_Applied,
    /** Number of section breaks proposed by unified LLM post-processing. */
    LLM_PostProcessing_Section_Breaks_Proposed,
    /** Number of section breaks applied by unified LLM post-processing. */
    LLM_PostProcessing_Section_Breaks_Applied,
    /** Number of assertions proposed by initial unified LLM post-processing responses. */
    LLM_PostProcessing_Assertions_Proposed,
    /** Number of assertions accepted after initial parsing and validation, before repair. */
    LLM_PostProcessing_Assertions_Accepted_Initial,
    /** Number of rejected assertions included in assertion-repair requests. */
    LLM_PostProcessing_Assertions_Repair_Requested,
    /** Number of raw assertion proposals returned by assertion-repair responses. */
    LLM_PostProcessing_Assertions_Proposed_After_Repair,
    /** Number of repaired assertions accepted after parsing and validation. */
    LLM_PostProcessing_Assertions_Accepted_After_Repair,
    /** Number of assertions applied by unified LLM post-processing. */
    LLM_PostProcessing_Assertions_Applied,
    /** Number of unified LLM post-processing assertions removed or commented by final JUnit instability handling. */
    LLM_PostProcessing_Assertions_Removed_Unstable,
    /** Number of unified LLM post-processing assertions removed by the final JUnit compilation filter. */
    LLM_PostProcessing_Assertions_Removed_Compile,
    /** Number of unified LLM post-processing assertions shipped in the final generated suite. */
    LLM_PostProcessing_Assertions_Shipped,

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

    // ---- Stagnation Diagnostics (offspring fate / turnover / age) ----

    /**
     * Per-generation ratio of bred offspring strictly better than their parent
     * (new coverage or improved shared-goal fitness sum).
     */
    Offspring_Beneficial_Ratio_Timeline,
    /**
     * Per-generation ratio of bred offspring fitness-neutral vs. their parent.
     */
    Offspring_Neutral_Ratio_Timeline,
    /**
     * Per-generation ratio of bred offspring strictly worse than their parent.
     */
    Offspring_Worse_Ratio_Timeline,
    /**
     * Per-generation ratio of this generation's offspring (bred + random
     * insertions) that survived selection into the next population.
     */
    Offspring_Survival_Ratio_Timeline,
    /**
     * Per-generation fraction of population slots holding individuals that were
     * not in the previous generation's population.
     */
    Population_Turnover_Timeline,
    /**
     * Per-generation mean individual age (generations since last change).
     */
    Population_Mean_Age_Timeline,

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
