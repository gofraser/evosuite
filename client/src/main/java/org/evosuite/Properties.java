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
package org.evosuite;

import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.lm.MutationType;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.symbolic.dse.algorithm.DSEAlgorithms;
import org.evosuite.utils.FileIOUtils;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Central property repository. All global parameters of EvoSuite should be
 * declared as fields here, using the appropriate annotation. Access is possible
 * directly via the fields, or with getter/setter methods.
 *
 * @author Gordon Fraser
 */
public class Properties {

    public static final String JAVA_VERSION_WARN_MSG = "EvoSuite does not support Java versions > 8 yet";

    private static final Logger logger = LoggerFactory.getLogger(Properties.class);

    /**
     * Parameters are fields of the Properties class, annotated with this
     * annotation. The key parameter is used to identify values in property
     * files or on the command line, the group is used in the config file or
     * input plugins to organize parameters, and the description is also
     * displayed there.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface Parameter {
        /**
         * The unique key identifying the parameter.
         *
         * @return the parameter key
         */
        String key();

        /**
         * The group to which the parameter belongs.
         *
         * @return the parameter group
         */
        String group() default "Experimental";

        /**
         * A detailed description of the parameter's purpose and usage.
         *
         * @return the parameter description
         */
        String description();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface IntValue {
        /**
         * The minimum allowed value for the integer parameter.
         *
         * @return the minimum value
         */
        int min() default Integer.MIN_VALUE;

        /**
         * The maximum allowed value for the integer parameter.
         *
         * @return the maximum value
         */
        int max() default Integer.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface LongValue {
        /**
         * The minimum allowed value for the long parameter.
         *
         * @return the minimum value
         */
        long min() default Long.MIN_VALUE;

        /**
         * The maximum allowed value for the long parameter.
         *
         * @return the maximum value
         */
        long max() default Long.MAX_VALUE;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface DoubleValue {
        /**
         * The minimum allowed value for the double parameter.
         *
         * @return the minimum value
         */
        double min() default -(Double.MAX_VALUE - 1); // FIXXME: Check

        /**
         * The maximum allowed value for the double parameter.
         *
         * @return the maximum value
         */
        double max() default Double.MAX_VALUE;
    }

    // ---------------------------------------------------------------
    // Test sequence creation
    @Parameter(key = "test_excludes", group = "Test Creation",
            description = "File containing methods that should not be used in testing")
    public static String TEST_EXCLUDES = "test.excludes";

    @Parameter(key = "test_includes", group = "Test Creation",
            description = "File containing methods that should be included in testing")
    public static String TEST_INCLUDES = "test.includes";

    @Parameter(key = "evosuite_use_uispec", group = "Test Creation",
            description = "If set to true EvoSuite test generation inits UISpec in order to avoid display of UI")
    public static boolean EVOSUITE_USE_UISPEC = false;

    @Deprecated
    @Parameter(key = "make_accessible", group = "TestCreation",
            description = "Change default package rights to public package rights")
    public static boolean MAKE_ACCESSIBLE = false;

    @Parameter(key = "string_replacement", group = "Test Creation",
            description = "Replace string.equals with levenshtein distance")
    public static boolean STRING_REPLACEMENT = true;

    @Parameter(key = "reset_static_fields", group = "Test Creation",
            description = "Call static constructors only after each static field was modified")
    public static boolean RESET_STATIC_FIELDS = true;

    @Parameter(key = "reset_static_final_fields", group = "Test Creation",
            description = "Remove the static modifier in target fields")
    public static boolean RESET_STATIC_FINAL_FIELDS = true;

    @Parameter(key = "reset_static_field_gets", group = "Test Creation",
            description = "Call static constructors also after each static field was read")
    public static boolean RESET_STATIC_FIELD_GETS = false;

    @Parameter(key = "reset_all_classes_during_test_generation", group = "Test Creation",
            description = "Test Generation does not apply the selective method of selection of class "
                    + "re-initalization")
    public static boolean RESET_ALL_CLASSES_DURING_TEST_GENERATION = false;

    @Parameter(key = "reset_all_classes_during_assertion_generation", group = "Test Creation",
            description = "Test Generation does not apply the selective method of selection of class "
                    + "re-initalization")
    public static boolean RESET_ALL_CLASSES_DURING_ASSERTION_GENERATION = true;


    @Parameter(key = "reset_standard_streams", group = "Test Creation",
            description = "Restore System.out, System.in and DebugGraphics.logStream after test execution")
    public static boolean RESET_STANDARD_STREAMS = false;

    /**
     * TODO: this option is off by default because still experimental and not
     * fully tested.
     */
    @Parameter(key = "test_carving", group = "Test Creation", description = "Enable test carving")
    public static boolean TEST_CARVING = false;

    @Parameter(key = "chop_carved_exceptions", group = "Test Creation",
            description = "If a carved test throws an exception, either chop it off, or drop it")
    public static boolean CHOP_CARVED_EXCEPTIONS = true;

    @Parameter(key = "null_probability", group = "Test Creation",
            description = "Probability to use null instead of constructing an object")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double NULL_PROBABILITY = 0.1;

    @Parameter(key = "object_reuse_probability", group = "Test Creation",
            description = "Probability to reuse an existing reference, if available")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double OBJECT_REUSE_PROBABILITY = 0.9;

    @Parameter(key = "primitive_reuse_probability", group = "Test Creation",
            description = "Probability to reuse an existing primitive, if available")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double PRIMITIVE_REUSE_PROBABILITY = 0.5;

    @Parameter(key = "primitive_pool", group = "Test Creation",
            description = "Probability to use a primitive from the pool rather than a random value")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double PRIMITIVE_POOL = 0.5;

    @Parameter(key = "dynamic_pool", group = "Test Creation",
            description = "Probability to use a primitive from the dynamic pool rather than a random value")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double DYNAMIC_POOL = 0.5;

    @Parameter(key = "variable_pool", group = "Test Creation",
            description = "Set probability of a constant based on the number of occurrences")
    @DoubleValue(min = 0.0, max = 1.0)
    public static boolean VARIABLE_POOL = false;

    @Deprecated
    @Parameter(key = "dynamic_seeding", group = "Test Creation", description = "Use numeric dynamic seeding")
    public static boolean DYNAMIC_SEEDING = true;

    @Parameter(key = "dynamic_pool_size", group = "Test Creation", description = "Number of dynamic constants to keep")
    public static int DYNAMIC_POOL_SIZE = 50;

    @Parameter(key = "p_special_type_call", group = "Test Creation",
            description = "Probability of using a non-standard call on a special case (collection/numeric)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_SPECIAL_TYPE_CALL = 0.05;

    @Parameter(key = "p_object_pool", group = "Test Creation",
            description = "Probability to use a predefined sequence from the pool rather than a random generator")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_OBJECT_POOL = 0.3;

    @Parameter(key = "object_pools", group = "Test Creation", description = "List of object pools")
    public static String OBJECT_POOLS = "";

    @Parameter(key = "carve_object_pool", group = "Test Creation", description = "Carve junit tests for pool")
    public static boolean CARVE_OBJECT_POOL = false;

    @Parameter(key = "seed_types", group = "Test Creation",
            description = "Use type information gathered from casts to instantiate generics")
    public static boolean SEED_TYPES = true;

    @Parameter(key = "max_generic_depth", group = "Test Creation",
            description = "Maximum level of nesting for generic types")
    public static int MAX_GENERIC_DEPTH = 3;

    @Parameter(key = "string_length", group = "Test Creation",
            description = "Maximum length of randomly generated strings")
    public static int STRING_LENGTH = 20;

    @Parameter(key = "max_string", group = "Test Creation", description = "Maximum length of strings in assertions")
    @IntValue(min = 1, max = 32767) // String literals may not be longer than 32767
    public static int MAX_STRING = 1000;


    @Parameter(key = "epsilon", group = "Test Creation", description = "Epsilon for floats in local search")
    @Deprecated
    // does not seem to be used anywhere
    public static double EPSILON = 0.001;

    @Parameter(key = "max_int", group = "Test Creation",
            description = "Maximum size of randomly generated integers (minimum range = -1 * max)")
    public static int MAX_INT = 2048;

    @Parameter(key = "max_seeded_constant", group = "Test Creation",
            description = "Maximum absolute value for seeded numeric constants in the pools "
                    + "(<=0 disables this extra cap; when restrict_pool=true, max_int is used)")
    public static long MAX_SEEDED_CONSTANT = 0L;

    @Parameter(key = "adaptive_seeded_constants_on_oom", group = "Test Creation",
            description = "After OOM, enable and progressively tighten a runtime cap for seeded numeric constants")
    public static boolean ADAPTIVE_SEEDED_CONSTANTS_ON_OOM = true;

    @Parameter(key = "adaptive_seeded_constant_initial_limit", group = "Test Creation",
            description = "Initial absolute cap for seeded numeric constants after first OOM")
    public static long ADAPTIVE_SEEDED_CONSTANT_INITIAL_LIMIT = 8192L;

    @Parameter(key = "adaptive_seeded_constant_min_limit", group = "Test Creation",
            description = "Minimum absolute cap when adaptively tightening seeded numeric constants after repeated OOMs")
    public static long ADAPTIVE_SEEDED_CONSTANT_MIN_LIMIT = 64L;

    @Parameter(key = "restrict_pool", group = "Test Creation",
            description = "Prohibit seeded numeric constants in the pool whose absolute value is >= max_int")
    public static boolean RESTRICT_POOL = false;

    @Parameter(key = "max_delta", group = "Test Creation",
            description = "Maximum size of delta for numbers during mutation")
    public static int MAX_DELTA = 20;

    @Parameter(key = "random_perturbation", group = "Test Creation",
            description = "Probability to replace a primitive with a random new value rather than adding a delta")
    public static double RANDOM_PERTURBATION = 0.2;

    @Parameter(key = "max_array", group = "Test Creation",
            description = "Maximum length of randomly generated arrays")
    public static int MAX_ARRAY = 10;

    @Parameter(key = "max_array_elements", group = "Test Creation",
            description = "Maximum total number of elements (product of all dimension lengths) for a "
                    + "randomly generated array. Caps multi-dimensional arrays whose element type was "
                    + "resolved to a deeply/pathologically nested generic type, to avoid OutOfMemoryError "
                    + "during test execution. Does not affect arrays with few enough dimensions that "
                    + "max_array alone already keeps the total under this bound.")
    public static int MAX_ARRAY_ELEMENTS = 1_000_000;

    @Parameter(key = "max_attempts", group = "Test Creation",
            description = "Number of attempts when generating an object before giving up")
    public static int MAX_ATTEMPTS = 1000;

    @Parameter(key = "max_recursion", group = "Test Creation",
            description = "Recursion depth when trying to create objects")
    public static int MAX_RECURSION = 10;

    @Parameter(key = "max_length", group = "Test Creation",
            description = "Maximum length of test suites (0 = no check)")
    public static int MAX_LENGTH = 0;

    @Parameter(key = "max_size", group = "Test Creation",
            description = "Maximum number of test cases in a test suite")
    public static int MAX_SIZE = 100;

    @Parameter(key = "num_tests", group = "Test Creation", description = "Number of tests in initial test suites")
    public static int NUM_TESTS = 2;

    @Parameter(key = "num_random_tests", group = "Test Creation", description = "Number of random tests")
    public static int NUM_RANDOM_TESTS = 20;

    @Parameter(key = "min_initial_tests", group = "Test Creation",
            description = "Minimum number of tests in initial test suites")
    public static int MIN_INITIAL_TESTS = 1;

    @Parameter(key = "max_initial_tests", group = "Test Creation",
            description = "Maximum number of tests in initial test suites")
    public static int MAX_INITIAL_TESTS = 10;

    @Parameter(key = "use_deprecated", group = "Test Creation",
            description = "Include deprecated methods in tests")
    public static boolean USE_DEPRECATED = false;

    @Parameter(key = "insertion_score_uut", group = "Test Creation",
            description = "Score for selection of insertion of UUT calls")
    public static int INSERTION_SCORE_UUT = 1;

    @Parameter(key = "insertion_uut", group = "Test Creation",
            description = "Score for selection of insertion of UUT calls")
    public static double INSERTION_UUT = 0.5;

    @Parameter(key = "insertion_uut", group = "Test Creation",
            description = "Score for selection of insertion of call to a input parameter")
    public static double INSERTION_PARAMETER = 0.4;

    @Parameter(key = "insertion_uut", group = "Test Creation",
            description = "Score for selection of insertion of call on the environment")
    public static double INSERTION_ENVIRONMENT = 0.1;

    @Parameter(key = "new_object_selection", group = "Test Creation",
            description = "Score for selection of insertion of UUT calls")
    public static boolean NEW_OBJECT_SELECTION = true;

    @Parameter(key = "insertion_score_object", group = "Test Creation",
            description = "Score for selection of insertion of call on existing object")
    public static int INSERTION_SCORE_OBJECT = 1;

    @Parameter(key = "insertion_score_parameter", group = "Test Creation",
            description = "Score for selection of insertion call with existing object")
    public static int INSERTION_SCORE_PARAMETER = 1;

    @Parameter(key = "consider_main_methods", group = "Test Creation",
            description = "Generate unit tests for 'main(String[] args)' methods as well")
    public static boolean CONSIDER_MAIN_METHODS = true;
    // should be on by default, otherwise unnecessary lower coverage: up to user if wants to skip them

    @Parameter(key = "headless_mode", group = "Test Generation", description = "Run Java in AWT Headless mode")
    public static boolean HEADLESS_MODE = true;

    @Parameter(key = "headless_filter_cut_calls", group = "Test Generation",
            description = "If true, filter out headless-incompatible constructors/methods of the class under test")
    public static boolean HEADLESS_FILTER_CUT_CALLS = false;

    @Parameter(key = "p_reflection_on_private", group = "Test Creation",
            description = "Probability [0,1] of using reflection to set private fields or call private methods")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_REFLECTION_ON_PRIVATE = 0.0; // Optimal value: 0.5

    @Parameter(key = "reflection_start_percent", group = "Test Creation",
            description = "Percentage [0,1] of search budget after which reflection fields/methods handling "
                    + "is activated")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double REFLECTION_START_PERCENT = 0.8;

    @Parameter(key = "p_functional_mocking", group = "Test Creation",
            description = "Probability [0,1] of using functional mocking (eg Mockito) when creating "
                    + "object instances")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_FUNCTIONAL_MOCKING = 0.0; // Optimal value: 0.8

    @Parameter(key = "mock_if_no_generator", group = "Test Creation",
            description = "Allow mock objects if there are no generators")
    public static boolean MOCK_IF_NO_GENERATOR = true;

    @Parameter(key = "functional_mocking_percent", group = "Test Creation",
            description = "Percentage [0,1] of search budget after which functional mocking can be activated. "
                    + "Mocking of missing concrete classes will be activated immediately regardless of "
                    + "this parameter")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double FUNCTIONAL_MOCKING_PERCENT = 0.5;

    @Parameter(key = "functional_mocking_input_limit", group = "Test Creation",
            description = "When mocking a method, define max number of mocked return values for that method. "
                    + "Calls after the last will just re-use the last specified value")
    @DoubleValue(min = 1)
    public static int FUNCTIONAL_MOCKING_INPUT_LIMIT = 5;

    public enum FunctionalMockingFailoverMode {
        OFF,
        CLASS,
        GLOBAL
    }

    @Parameter(key = "functional_mocking_failover_mode", group = "Test Creation",
            description = "How to react to functional mocking initialization failures")
    public static FunctionalMockingFailoverMode FUNCTIONAL_MOCKING_FAILOVER_MODE =
            FunctionalMockingFailoverMode.CLASS;

    @Parameter(key = "functional_mocking_failure_threshold_count", group = "Test Creation",
            description = "Minimum number of functional mocking initialization failures before "
                    + "global failover can disable functional mocking")
    @IntValue(min = 1)
    public static int FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_COUNT = 20;

    @Parameter(key = "functional_mocking_failure_threshold_ratio", group = "Test Creation",
            description = "Failure ratio threshold [0,1] to disable functional mocking globally")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double FUNCTIONAL_MOCKING_FAILURE_THRESHOLD_RATIO = 0.30;

    @Parameter(key = "dmon_enabled", group = "Test Creation",
            description = "Enable Dynamic Mock-on-Null (DMoN) support for constructor NPE bottlenecks")
    public static boolean DMON_ENABLED = true;

    @Parameter(key = "dmon_only_target_class_constructor", group = "Test Creation",
            description = "Restrict DMoN detection to constructors declared in the target class")
    public static boolean DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;

    @Parameter(key = "dmon_helpful_npe_parse", group = "Test Creation",
            description = "Use Java 14+ helpful NPE messages to extract null dereference hints for DMoN")
    public static boolean DMON_HELPFUL_NPE_PARSE = true;

    @Parameter(key = "dmon_asm_fallback", group = "Test Creation",
            description = "Fallback to bytecode-based mapping when helpful NPE parsing is unavailable or weak")
    public static boolean DMON_ASM_FALLBACK = true;

    @Parameter(key = "dmon_allow_reflection_fallback", group = "Test Creation",
            description = "Allow reflection-based injection fallback for DMoN promotion")
    public static boolean DMON_ALLOW_REFLECTION_FALLBACK = true;

    @Parameter(key = "dmon_cache_analysis", group = "Test Creation",
            description = "Cache DMoN post-mortem analysis results for repeated crashes")
    public static boolean DMON_CACHE_ANALYSIS = true;

    @Parameter(key = "dmon_promote_in_place", group = "Test Creation",
            description = "Apply DMoN promotion in-place on the evaluated chromosome (Option 1 integration)")
    public static boolean DMON_PROMOTE_IN_PLACE = true;

    @Parameter(key = "dmon_validate_promoted_once", group = "Test Creation",
            description = "Immediately re-run once with DMoN disabled after promotion")
    public static boolean DMON_VALIDATE_PROMOTED_ONCE = false;

    @Parameter(key = "dmon_max_ephemeral_retries", group = "Test Creation",
            description = "Maximum number of DMoN assist+retry attempts for constructor NPEs within one execution")
    @IntValue(min = 1)
    public static int DMON_MAX_EPHEMERAL_RETRIES = 2;

    @Parameter(key = "num_parallel_clients", group = "Test Creation",
            description = "Number of EvoSuite clients to run in parallel")
    public static int NUM_PARALLEL_CLIENTS = 1;

    @Parameter(key = "migrants_iteration_frequency", group = "Test Creation",
            description = "Determines amount of iterations between sending migrants to other client "
                    + "(-1 to disable any iterations between clients)")
    public static int MIGRANTS_ITERATION_FREQUENCY = 2;

    @Parameter(key = "migrants_communication_rate", group = "Test Creation",
            description = "Determines amount of migrants per communication step")
    public static int MIGRANTS_COMMUNICATION_RATE = 3;

    // ---------------------------------------------------------------
    // Search algorithm
    public enum Algorithm {
        // random
        RANDOM_SEARCH,
        // GAs
        STANDARD_GA, MONOTONIC_GA, STEADY_STATE_GA, BREEDER_GA, CELLULAR_GA, STANDARD_CHEMICAL_REACTION, MAP_ELITES,
        // mu-lambda
        ONE_PLUS_LAMBDA_LAMBDA_GA, ONE_PLUS_ONE_EA, MU_PLUS_LAMBDA_EA, MU_LAMBDA_EA,
        // many-objective algorithms
        MOSA, DYNAMOSA, LIPS, MIO,
        // multiple-objective optimisation algorithms
        NSGAII, SPEA2
    }

    // MOSA PROPERTIES
    public enum RankingType {
        // Preference sorting is the ranking strategy proposed in
        PREFERENCE_SORTING,
        FAST_NON_DOMINATED_SORTING
    }

    @Parameter(key = "ranking_type", group = "Runtime", description = "type of ranking to use in MOSA")
    public static RankingType RANKING_TYPE = RankingType.PREFERENCE_SORTING;

    public enum MapElitesChoice {
        ALL,
        SINGLE,
        SINGLE_AVG
    }

    @Parameter(key = "map_elites_choice", group = "Search Algorithm",
            description = "Selection of chromosome branches to mutate")
    public static MapElitesChoice MAP_ELITES_CHOICE = MapElitesChoice.SINGLE_AVG;

    @Parameter(key = "map_elites_mosa_mutations", group = "Search Algorithm",
            description = "Enable mosa style mutations for map elites")
    public static boolean MAP_ELITES_MOSA_MUTATIONS = true;

    @Parameter(key = "map_elites_random", group = "Search Algorithm",
            description = "Probability used for adding new chromosomes")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double MAP_ELITES_RANDOM = 0.5;

    @Parameter(key = "map_elites_ignore_features", group = "Search Algorithm",
            description = "Enable this to disable feature based mapping")
    public static boolean MAP_ELITES_IGNORE_FEATURES = false;

    @Parameter(key = "algorithm", group = "Search Algorithm", description = "Search algorithm")
    public static Algorithm ALGORITHM = Algorithm.DYNAMOSA;

    /**
     * Different models of neighbourhoods in the Cellular GA.
     **/
    public enum CgaModels {
        ONE_DIMENSION,
        LINEAR_FIVE,
        COMPACT_NINE,
        COMPACT_THIRTEEN
    }

    @Parameter(key = "neighborhood_model", group = "Search Algorithm",
            description = "The model of neighborhood used in case of CGA. L5 is default")
    public static CgaModels MODEL = CgaModels.LINEAR_FIVE;

    @Parameter(key = "random_seed", group = "Search Algorithm",
            description = "Seed used for random generator. If left empty, use current time")
    public static Long RANDOM_SEED = null;

    @Parameter(key = "check_best_length", group = "Search Algorithm",
            description = "Check length against length of best individual")
    public static boolean CHECK_BEST_LENGTH = true;

    @Parameter(key = "check_parents_length", group = "Search Algorithm",
            description = "Check length against length of parents")
    public static boolean CHECK_PARENTS_LENGTH = false; // note, based on STVR experiments

    // @Parameter(key = "check_rank_length", group = "Search Algorithm", description = "Use length in rank selection")
    // public static boolean CHECK_RANK_LENGTH = false;

    @Parameter(key = "parent_check", group = "Search Algorithm",
            description = "Check against parents in Mu+Lambda algorithm")
    public static boolean PARENT_CHECK = true;

    @Parameter(key = "check_max_length", group = "Search Algorithm",
            description = "Check length against fixed maximum")
    public static boolean CHECK_MAX_LENGTH = true;

    @Parameter(key = "chop_max_length", group = "Search Algorithm",
            description = "Chop statements after exception if length has reached maximum")
    public static boolean CHOP_MAX_LENGTH = true;

    //----------- DSE, which is a special case of LS ---------------

    /**
     * ilebrero: Mostly for benchmarks for new module, I dont think the legacy strategy is gonna be used anymore.
     **/
    public enum DseModuleVersion {
        LEGACY,
        NEW
    }

    /**
     * ilebrero: Hope it doesn't make a lot of confusion that there are two versions of arrays supported.
     * - ARRAYS_THEORY: Supports Integers and Reals.
     * - LAZY_VARIABLES: Supports Integers and Reals.
     **/
    public enum DseArraysMemoryModelVersion {
        SELECT_STORE_EXPRESSIONS,
        LAZY_VARIABLES
    }

    /**
     * TODO (ilebrero) : Implement private fields tracking.
     **/
    public enum DseObjectsModelVersion {
        PUBLIC_FIELDS_NO_SUBCLASSES,
        PUBLIC_FIELDS_AND_SUBCLASSES // TODO: Implement me!
    }

    @Parameter(key = "dse_enable_objects_support", group = "DSE",
            description = "If objects should be supported by the concolic engine")
    public static boolean IS_DSE_OBJECTS_SUPPORT_ENABLED = false;

    @Parameter(key = "selected_dse_module_objects_model_version", group = "DSE",
            description = "Which implementation of objects is used on the concolic engine.")
    public static DseObjectsModelVersion SELECTED_DseObjectsModelVersion =
            DseObjectsModelVersion.PUBLIC_FIELDS_NO_SUBCLASSES;

    @Parameter(key = "dse_module_version", group = "DSE",
            description = "Module version of DSE, mostly used for benchmarking between modules. "
                    + "For other things the new one is recomended.")
    public static DseModuleVersion CURRENT_DseModuleVersion = DseModuleVersion.NEW;

    @Parameter(key = "dse_enable_arrays_support", group = "DSE",
            description = "If arrays should be supported by the concolic engine")
    public static boolean IS_DSE_ARRAYS_SUPPORT_ENABLED = true;

    @Parameter(key = "selected_dse_module_arrays_support_version", group = "DSE",
            description = "Which implementation of arrays is used on the concolic engine.")
    public static DseArraysMemoryModelVersion SELECTED_DseArraysMemoryModelVersion =
            DseArraysMemoryModelVersion.SELECT_STORE_EXPRESSIONS;

    @Parameter(key = "dse_probability", group = "DSE",
            description = "Probability used to specify when to use DSE instead of regular LS when LS is applied")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double DSE_PROBABILITY = 0.5;

    @Parameter(key = "dse_constraint_solver_timeout_millis", group = "DSE",
            description = "Maximum number of solving time for Constraint solver in milliseconds")
    public static long DSE_CONSTRAINT_SOLVER_TIMEOUT_MILLIS = 1000;

    @Parameter(key = "dse_rank_branch_conditions", group = "DSE", description = "Rank branch conditions")
    public static boolean DSE_RANK_BRANCH_CONDITIONS = true;

    @Parameter(key = "dse_negate_all_conditions", group = "DSE",
            description = "Negate all branch conditions in the path condition (covered or not)")
    public static boolean DSE_NEGATE_ALL_CONDITIONS = true;

    @Parameter(key = "dse_constraint_length", group = "DSE",
            description = "Maximal length of the constraints in DSE")
    public static int DSE_CONSTRAINT_LENGTH = 100000;

    @Parameter(key = "dse_constant_probability", group = "DSE",
            description = "Probability with which to use constants from the constraints "
                    + "when resetting variables during search")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double DSE_CONSTANT_PROBABILITY = 0.5;

    @Parameter(key = "dse_variable_resets", group = "DSE",
            description = "Times DSE resets the int and real variables with random values")
    public static int DSE_VARIABLE_RESETS = 2;

    // By default the target is 100
    @Parameter(key = "dse_target_coverage", group = "DSE",
            description = "Percentage (out of 100) of target coverage to cover")
    public static int DSE_TARGET_COVERAGE = 100;

    public enum DSEType {
        /**
         * apply DSE per statement.
         */
        STATEMENT,
        /**
         * apply DSE with all primitives in a test.
         */
        TEST,
        /**
         * DSE on whole suites.
         */
        SUITE
    }

    // NOTE (ilebrero): This is the current method name being explored. This is NOT a good practice, but it's
    //         the only way I can imagine to get the current method name for saving the bytecodeLogging info in a file.
    //         TODO: Is there a better way of doing this?
    public static String CURRENT_TARGET_METHOD = "";

    // NOTE: by default we use the sage implementation of the algorithm
    @Parameter(key = "dse_exploration_algorithm", group = "DSE", description = "Type of DSE algorithm to use.")
    public static DSEAlgorithms DSE_EXPLORATION_ALGORITHM_TYPE = DSEAlgorithms.GENERATIONAL_SEARCH;

    @Parameter(key = "local_search_dse", group = "DSE", description = "Granularity of DSE application")
    public static DSEType LOCAL_SEARCH_DSE = DSEType.TEST;

    @Deprecated
    @Parameter(key = "dse_keep_all_tests", group = "DSE",
            description = "Keep tests even if they do not increase fitness")
    public static boolean DSE_KEEP_ALL_TESTS = false;

    public enum SolverType {
        EVOSUITE_SOLVER, Z3_SOLVER, CVC4_SOLVER
    }

    @Parameter(key = "dse_solver", group = "DSE",
            description = "Specify which constraint solver to use. "
                    + "Note: external solver will need to be installed and cofigured separately")
    public static SolverType DSE_SOLVER = SolverType.EVOSUITE_SOLVER;

    @Parameter(key = "z3_path", group = "DSE", description = "Indicates the path to the Z3 solver")
    public static String Z3_PATH = null;

    @Parameter(key = "cvc4_path", group = "DSE", description = "Indicates the path to the CVC4 solver")
    public static String CVC4_PATH = null;

    public enum DSEStoppingConditionCriterion {
        TARGETCOVERAGE,
        MAXTIME,
        /**
         * In seconds.
         */
        ZEROFITNESS,
        MAXTESTS,
        /**
         * The ones that are setted by default on the algorithm + Strategy.
         */
        DEFAULTS
    }

    @Parameter(key = "dse_stopping_condition", group = "DSE",
            description = "Indicate which stopping condition to use.")
    public static DSEStoppingConditionCriterion DSE_STOPPING_CONDITION = DSEStoppingConditionCriterion.DEFAULTS;

    @Parameter(key = "bytecode_logging_enabled", group = "DSE",
            description = "Indicates whether bytecode instructions that are being executed should be logged.")
    public static boolean BYTECODE_LOGGING_ENABLED = false;

    @Parameter(key = "bytecode_logging_mode", group = "DSE", description = "How to log executed bytecode")
    public static DSEBytecodeLoggingMode BYTECODE_LOGGING_MODE = DSEBytecodeLoggingMode.STD_OUT;

    // TODO (ilebrero): add other modes
    public enum DSEBytecodeLoggingMode {
        STD_OUT,
        FILE_DUMP
    }

    // --------- LS ---------

    @Parameter(key = "local_search_rate", group = "Local Search",
            description = "Apply local search at every X generation")
    public static int LOCAL_SEARCH_RATE = -1;

    @Parameter(key = "local_search_probability", group = "Local Search",
            description = "Probability of applying local search at every X generation")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LOCAL_SEARCH_PROBABILITY = 1.0;

    @Deprecated
    @Parameter(key = "local_search_selective", group = "Local Search",
            description = "Apply local search only to individuals that changed fitness")
    public static boolean LOCAL_SEARCH_SELECTIVE = false;

    @Parameter(key = "local_search_selective_primitives", group = "Local Search",
            description = "Only check primitives for selective LS")
    public static boolean LOCAL_SEARCH_SELECTIVE_PRIMITIVES = false; //TODO what is this? unclear

    @Parameter(key = "local_search_expand_tests", group = "Local Search",
            description = "Expand test cases before applying local search such that each primitive is used only once")
    public static boolean LOCAL_SEARCH_EXPAND_TESTS = true;

    @Parameter(key = "local_search_ensure_double_execution", group = "Local Search",
            description = "If a branch is only executed once by a test suite, duplicate that test")
    public static boolean LOCAL_SEARCH_ENSURE_DOUBLE_EXECUTION = true;

    @Parameter(key = "local_search_restore_coverage", group = "Local Search",
            description = "Add tests that cover branches already covered in the past")
    public static boolean LOCAL_SEARCH_RESTORE_COVERAGE = false; // Not needed with archive

    @Parameter(key = "local_search_adaptation_rate", group = "Local Search",
            description = "Parameter used to adapt at runtime the probability of applying local search")
    public static double LOCAL_SEARCH_ADAPTATION_RATE = 2.0;

    @Parameter(key = "local_search_budget", group = "Local Search",
            description = "Maximum budget usable for improving individuals per local search")
    public static long LOCAL_SEARCH_BUDGET = 5;

    public enum LocalSearchBudgetType {
        STATEMENTS, TESTS,
        /**
         * Time expressed in seconds.
         */
        TIME,
        SUITES, FITNESS_EVALUATIONS
    }

    @Parameter(key = "local_search_budget_type", group = "Local Search",
            description = "Interpretation of local_search_budget")
    public static LocalSearchBudgetType LOCAL_SEARCH_BUDGET_TYPE = LocalSearchBudgetType.TIME;

    @Parameter(key = "local_search_probes", group = "Local Search",
            description = "How many mutations to apply to a string to check whether it improves coverage")
    public static int LOCAL_SEARCH_PROBES = 10;

    @Parameter(key = "local_search_primitives", group = "Local Search",
            description = "Perform local search on primitive values")
    public static boolean LOCAL_SEARCH_PRIMITIVES = true;

    @Parameter(key = "local_search_strings", group = "Local Search",
            description = "Perform local search on primitive values")
    public static boolean LOCAL_SEARCH_STRINGS = true;

    @Parameter(key = "local_search_arrays", group = "Local Search",
            description = "Perform local search on array statements")
    public static boolean LOCAL_SEARCH_ARRAYS = true;

    @Parameter(key = "local_search_references", group = "Local Search",
            description = "Perform local search on reference types")
    public static boolean LOCAL_SEARCH_REFERENCES = true;

    //--------------------------

    @Parameter(key = "crossover_rate", group = "Search Algorithm",
            description = "Probability of crossover")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double CROSSOVER_RATE = 0.75;

    @Parameter(key = "headless_chicken_test", group = "Search Algorithm",
            description = "Activate headless chicken test")
    public static boolean HEADLESS_CHICKEN_TEST = false;

    @Parameter(key = "mutation_rate", group = "Search Algorithm",
            description = "Probability of mutation")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double MUTATION_RATE = 0.75;

    @Parameter(key = "breeder_truncation", group = "Search Algorithm",
            description = "Percentage of population to use for breeding in breeder GA")
    @DoubleValue(min = 0.01, max = 1.0)
    public static double TRUNCATION_RATE = 0.5;

    @Parameter(key = "number_of_mutations", group = "Search Algorithm",
            description = "Number of single mutations applied on an individual when a mutation event occurs")
    public static int NUMBER_OF_MUTATIONS = 1;

    @Parameter(key = "p_test_insertion", group = "Search Algorithm",
            description = "Initial probability of inserting a new test in a test suite")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_TEST_INSERTION = 0.1;

    @Parameter(key = "p_statement_insertion", group = "Search Algorithm",
            description = "Initial probability of inserting a new statement in a test case")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_STATEMENT_INSERTION = 0.5;

    @Parameter(key = "p_change_parameter", group = "Search Algorithm",
            description = "Probability of replacing parameters when mutating a method or "
                    + "constructor statementa in a test case")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_CHANGE_PARAMETER = 0.1;

    @Parameter(key = "p_test_delete", group = "Search Algorithm",
            description = "Probability of deleting statements during mutation")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_TEST_DELETE = 1d / 3d;

    @Parameter(key = "p_test_change", group = "Search Algorithm",
            description = "Probability of changing statements during mutation")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_TEST_CHANGE = 1d / 3d;

    @Parameter(key = "p_test_insert", group = "Search Algorithm",
            description = "Probability of inserting new statements during mutation")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_TEST_INSERT = 1d / 3d;

    @Parameter(key = "kincompensation", group = "Search Algorithm",
            description = "Penalty for duplicate individuals")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double KINCOMPENSATION = 1.0;

    @Parameter(key = "elite", group = "Search Algorithm",
            description = "Elite size for search algorithm")
    public static int ELITE = 1;

    @Parameter(key = "mu", group = "Search Algorithm",
            description = "Number of individuals selected by Mu + Lambda EA for the next generation")
    public static int MU = 1;

    @Parameter(key = "lambda", group = "Search Algorithm",
            description = "Number of individuals produced by Mu + Lambda EA at each generation")
    public static int LAMBDA = 1;

    @Parameter(key = "tournament_size", group = "Search Algorithm",
            description = "Number of individuals for tournament selection")
    public static int TOURNAMENT_SIZE = 10;

    @Parameter(key = "rank_bias", group = "Search Algorithm",
            description = "Bias for better individuals in rank selection")
    public static double RANK_BIAS = 1.7;

    @Parameter(key = "chromosome_length", group = "Search Algorithm",
            description = "Maximum length of chromosomes during search")
    @IntValue(min = 1, max = 100000)
    public static int CHROMOSOME_LENGTH = 40;

    @Parameter(key = "number_of_tests_per_target", group = "Search Algorithm",
            description = "Number of test cases for each target goal to keep in an archive")
    public static int NUMBER_OF_TESTS_PER_TARGET = 10;

    @Parameter(key = "p_random_test_or_from_archive", group = "Search Algorithm",
            description = "Probability [0,1] of sampling a new test at random or choose an existing one in an archive")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double P_RANDOM_TEST_OR_FROM_ARCHIVE = 0.5;

    @Parameter(key = "exploitation_starts_at_percent", group = "Search Algorithm",
            description = "Percentage [0,1] of search budget after which exploitation is activated")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double EXPLOITATION_STARTS_AT_PERCENT = 0.5;

    @Parameter(key = "max_num_mutations_before_giving_up", group = "Search Algorithm",
            description = "Maximum number of mutations allowed to be done on the same "
                    + "individual before sampling a new one")
    public static int MAX_NUM_MUTATIONS_BEFORE_GIVING_UP = 10;

    @Parameter(key = "max_num_fitness_evaluations_before_giving_up", group = "Search Algorithm",
            description = "Maximum number of fitness evaluations allowed to be done on the "
                    + "same individual before sampling a new one")
    public static int MAX_NUM_FITNESS_EVALUATIONS_BEFORE_GIVING_UP = 10;

    @Parameter(key = "population", group = "Search Algorithm", description = "Population size of genetic algorithm")
    @IntValue(min = 1)
    public static int POPULATION = 50;

    public enum PopulationLimit {
        INDIVIDUALS, TESTS, STATEMENTS
    }

    @Parameter(key = "population_limit", group = "Search Algorithm",
            description = "What to use as limit for the population size")
    public static PopulationLimit POPULATION_LIMIT = PopulationLimit.INDIVIDUALS;

    @Parameter(key = "write_individuals", group = "Search Algorithm",
            description = "Write to a file all fitness values of each individual on "
                    + "each iteration of a GA")
    public static boolean WRITE_INDIVIDUALS = false;

    @Parameter(key = "search_budget", group = "Search Algorithm", description = "Maximum search duration")
    @LongValue(min = 1)
    public static long SEARCH_BUDGET = 60;

    @Parameter(key = "OUTPUT_DIR", group = "Runtime",
            description = "Directory in which to put generated files")
    public static String OUTPUT_DIR = "evosuite-files";

    public static String PROPERTIES_FILE = OUTPUT_DIR + File.separator + "evosuite.properties";

    public enum StoppingCondition {
        MAXSTATEMENTS, MAXTESTS,
        /**
         * Max time in seconds.
         */
        MAXTIME,
        MAXGENERATIONS, MAXFITNESSEVALUATIONS, TIMEDELTA
    }

    @Parameter(key = "stopping_condition", group = "Search Algorithm",
            description = "What condition should be checked to end the search")
    public static StoppingCondition STOPPING_CONDITION = StoppingCondition.MAXTIME;

    public enum CrossoverFunction {
        SINGLEPOINTRELATIVE, SINGLEPOINTFIXED, SINGLEPOINT, COVERAGE, UNIFORM
    }

    @Parameter(key = "crossover_function", group = "Search Algorithm", description = "Crossover function during search")
    public static CrossoverFunction CROSSOVER_FUNCTION = CrossoverFunction.SINGLEPOINTRELATIVE;

    public enum TheReplacementFunction {
        /**
         * Indicates a replacement function which works for all chromosomes
         * because it solely relies on fitness values.
         */
        FITNESSREPLACEMENT,
        /**
         * EvoSuite's default replacement function which only works on subtypes
         * of the default chromosome types. Relies on fitness plus secondary
         * goals such as length.
         */
        DEFAULT
    }

    /**
     * During search the genetic algorithm has to decide whether the parent
     * chromosomes or the freshly created offspring chromosomes should be
     * preferred. If you use EvoSuite with its default chromosomes the
     * TheReplacementFunction.DEFAULT is what you want. If your chromosomes are
     * not a subclass of the default chromosomes your have to write your own
     * replacement function or use TheReplacementFunction.FITNESSREPLACEMENT.
     */
    @Parameter(key = "replacement_function", group = "Search Algorithm",
            description = "Replacement function for comparing offspring to parents "
                    + "during search")
    public static TheReplacementFunction REPLACEMENT_FUNCTION = TheReplacementFunction.DEFAULT;

    public enum SelectionFunction {
        RANK, ROULETTEWHEEL, TOURNAMENT, BINARY_TOURNAMENT, RANK_CROWD_DISTANCE_TOURNAMENT, BESTK, RANDOMK
    }

    @Parameter(key = "selection_function", group = "Search Algorithm",
            description = "Selection function during search")
    public static SelectionFunction SELECTION_FUNCTION = SelectionFunction.RANK;

    @Parameter(key = "emigrant_selection_function", group = "Search Algorithm",
            description = "Selection function for emigrant selection during search")
    public static SelectionFunction EMIGRANT_SELECTION_FUNCTION = SelectionFunction.RANDOMK;

    public enum MutationProbabilityDistribution {
        UNIFORM, BINOMIAL
    }

    /**
     * Constant <code>MUTATION_PROBABILITY_DISTRIBUTION</code>.
     */
    @Parameter(key = "mutation_probability_distribution", group = "Search Algorithm",
            description = "Mutation probability distribution")
    public static MutationProbabilityDistribution MUTATION_PROBABILITY_DISTRIBUTION =
            MutationProbabilityDistribution.UNIFORM;

    public enum SecondaryObjective {
        AVG_LENGTH, MAX_LENGTH, TOTAL_LENGTH, SIZE, EXCEPTIONS, IBRANCH, RHO
    }

    @Parameter(key = "secondary_objectives", group = "Search Algorithm",
            description = "Secondary objective during search")
    public static SecondaryObjective[] SECONDARY_OBJECTIVE =
            new SecondaryObjective[]{SecondaryObjective.TOTAL_LENGTH};

    @Parameter(key = "enable_secondary_objective_after", group = "Search Algorithm",
            description = "Activate the second secondary objective after a certain amount of search budget")
    public static int ENABLE_SECONDARY_OBJECTIVE_AFTER = 0;

    @Parameter(key = "enable_secondary_starvation", group = "Search Algorithm",
            description = "Activate the second secondary objective after a certain amount of search budget")
    public static boolean ENABLE_SECONDARY_OBJECTIVE_STARVATION = false;

    @Parameter(key = "starvation_after_generation", group = "Search Algorithm",
            description = "Activate the second secondary objective after a certain amount of "
                    + "search budget")
    public static int STARVATION_AFTER_GENERATION = 500;

    @Parameter(key = "bloat_factor", group = "Search Algorithm", description = "Maximum relative increase in length")
    public static int BLOAT_FACTOR = 2;

    @Parameter(key = "stop_zero", group = "Search Algorithm", description = "Stop optimization once goal is covered")
    public static boolean STOP_ZERO = true;

    @Parameter(key = "dynamic_limit", group = "Search Algorithm",
            description = "Multiply search budget by number of test goals")
    public static boolean DYNAMIC_LIMIT = false;

    @Parameter(key = "global_timeout", group = "Search Algorithm",
            description = "Maximum seconds allowed for entire search when not using time as stopping criterion")
    @IntValue(min = 0)
    public static int GLOBAL_TIMEOUT = 120;

    @Parameter(key = "minimization_timeout", group = "Search Algorithm",
            description = "Seconds allowed for minimization at the end")
    @IntValue(min = 0)
    public static int MINIMIZATION_TIMEOUT = 60;

    @Parameter(key = "minimization_per_test_timeout_ms", group = "Search Algorithm",
            description = "Maximum milliseconds spent minimizing a single test case before "
                    + "returning the best-so-far. 0 disables the per-test cap (only the global "
                    + "minimization_timeout applies).")
    @IntValue(min = 0)
    public static int MINIMIZATION_PER_TEST_TIMEOUT_MS = 5000;

    @Parameter(key = "assertion_timeout", group = "Search Algorithm",
            description = "Seconds allowed for assertion generation at the end")
    @IntValue(min = 0)
    public static int ASSERTION_TIMEOUT = 60;

    @Parameter(key = "assertion_minimization_fallback", group = "Search Algorithm",
            description = "Percentage of tests expected to have assertions at fallback check time")
    public static double ASSERTION_MINIMIZATION_FALLBACK = 1 / 2d;

    @Parameter(key = "assertion_minimization_fallback_time", group = "Search Algorithm",
            description = "Percentage of tests applied to minimisation before checking fallback. "
                    + "1.0 for no fallback.")
    public static double ASSERTION_MINIMIZATION_FALLBACK_TIME = 2 / 3d;

    @Parameter(key = "junit_check_timeout", group = "Search Algorithm",
            description = "Seconds allowed for checking the generated JUnit files (e.g., compilation and stability)")
    @IntValue(min = 0)
    public static int JUNIT_CHECK_TIMEOUT = 60;

    @Parameter(key = "write_junit_timeout", group = "Search Algorithm",
            description = "Seconds allowed to write on disk the generated JUnit files")
    @IntValue(min = 0)
    public static int WRITE_JUNIT_TIMEOUT = 60;
    //Note: we need it, as we currently first run the tests before we write them

    @Parameter(key = "carving_timeout", group = "Search Algorithm",
            description = "Seconds allowed for carving JUnit tests")
    @IntValue(min = 0)
    public static int CARVING_TIMEOUT = 120;

    @Parameter(key = "initialization_timeout", group = "Search Algorithm",
            description = "Seconds allowed for initializing the search")
    @IntValue(min = 0)
    public static int INITIALIZATION_TIMEOUT = 120;

    @Parameter(key = "extra_timeout", group = "Search Algorithm",
            description = "Extra seconds allowed for the search")
    @IntValue(min = 0)
    public static int EXTRA_TIMEOUT = 60;

    @Parameter(key = "reuse_leftover_time", group = "Search Algorithm",
            description = "If a phase is ended before its timeout, allow the next phase to run over its timeout")
    public static boolean REUSE_LEFTOVER_TIME = false;

    @Parameter(key = "track_boolean_branches", group = "Search Algorithm",
            description = "Track branches that have a distance of either 0 or 1")
    public static boolean TRACK_BOOLEAN_BRANCHES = false;

    @Parameter(key = "track_covered_gradient_branches", group = "Search Algorithm",
            description = "Track gradient branches that were covered")
    public static boolean TRACK_COVERED_GRADIENT_BRANCHES = false;

    @Parameter(key = "branch_comparison_types", group = "Search Algorithm",
            description = "Track branch comparison types based on the bytecode")
    public static boolean BRANCH_COMPARISON_TYPES = false;

    @Parameter(key = "track_diversity", group = "Search Algorithm",
            description = "Track population diversity")
    public static boolean TRACK_DIVERSITY = false;

    @Parameter(key = "analysis_criteria", group = "Output",
            description = "List of criteria which should be measured on the completed test suite")
    public static String ANALYSIS_CRITERIA = "";

    @Parameter(key = "use_existing_coverage", group = "Experimental",
            description = "Use the coverage of existing test cases")
    public static boolean USE_EXISTING_COVERAGE = false;

    @Parameter(key = "epson", group = "Experimental", description = "Epson")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double EPSON = 0.01;

    // ---------------------------------------------------------------
    // Chemical Reaction Optimization Parameters

    @Parameter(key = "kinetic_energy_loss_rate", group = "Chemical Reaction Optimization",
            description = "Rate at which molecules lose kinetic energy")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double KINETIC_ENERGY_LOSS_RATE = 0.2;

    @Parameter(key = "molecular_collision_rate", group = "Chemical Reaction Optimization",
            description = "Rate of inter molecular collisions")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double MOLECULAR_COLLISION_RATE = 0.2;

    @Parameter(key = "initial_kinetic_energy", group = "Chemical Reaction Optimization",
            description = "Initial kinetic energy of each molecule")
    public static double INITIAL_KINETIC_ENERGY = 1000.0;

    @Parameter(key = "decomposition_threshold", group = "Chemical Reaction Optimization",
            description = "Threshold to be checked to decide when to trigger decomposition")
    public static int DECOMPOSITION_THRESHOLD = 500;

    @Parameter(key = "synthesis_threshold", group = "Chemical Reaction Optimization",
            description = "Threshold to be checked to decide when to trigger synthesis")
    public static int SYNTHESIS_THRESHOLD = 10;

    //----------------------------------------------------------------
    // Continuous Test Generation

    @Parameter(key = "ctg_memory", group = "Continuous Test Generation",
            description = "Total Memory (in MB) that CTG will use")
    public static int CTG_MEMORY = 1000;

    @Parameter(key = "ctg_cores", group = "Continuous Test Generation",
            description = "Number of cores CTG will use")
    public static int CTG_CORES = 1;

    @Parameter(key = "ctg_time", group = "Continuous Test Generation",
            description = "How many minutes in total CTG will run")
    public static int CTG_TIME = 3;

    @Parameter(key = "ctg_time_per_class", group = "Continuous Test Generation",
            description = "How many minutes to allocate for each class. If this parameter is set, "
                    + "then ctg_time is going to be ignored. This parameter is mainly meant for "
                    + "debugging purposes.")
    public static Integer CTG_TIME_PER_CLASS = null;

    @Parameter(key = "ctg_min_time_per_job", group = "Continuous Test Generation",
            description = "How many minutes each class under test should have at least")
    public static int CTG_MIN_TIME_PER_JOB = 1;

    @Parameter(key = "ctg_dir", group = "Continuous Test Generation",
            description = "Where generated files will be stored")
    public static String CTG_DIR = ".evosuite";

    @Parameter(key = "ctg_bests_folder", group = "Continuous Test Generation",
            description = "Folder where all the best test suites generated so far in all CTG runs "
                    + "are stored")
    public static String CTG_BESTS_DIR_NAME = "best-tests";

    @Parameter(key = "ctg_generation_dir_prefix", group = "Continuous Test Generation", description = "")
    public static String CTG_GENERATION_DIR_PREFIX = null;

    @Parameter(key = "ctg_delete_old_tmp_folders", group = "Continuous Test Generation",
            description = "If true, delete all the tmp folders before starting a new CTG run")
    public static boolean CTG_DELETE_OLD_TMP_FOLDERS = true;

    @Parameter(key = "ctg_tmp_logs_dir_name", group = "Continuous Test Generation", description = "")
    public static String CTG_TMP_LOGS_DIR_NAME = "logs";

    @Parameter(key = "ctg_tmp_pools_dir_name", group = "Continuous Test Generation", description = "")
    public static String CTG_TMP_POOLS_DIR_NAME = "pools";

    @Parameter(key = "ctg_tmp_reports_dir_name", group = "Continuous Test Generation", description = "")
    public static String CTG_TMP_REPORTS_DIR_NAME = "reports";

    @Parameter(key = "ctg_tmp_tests_dir_name", group = "Continuous Test Generation", description = "")
    public static String CTG_TMP_TESTS_DIR_NAME = "tests";

    /**
     * If specified, load serialized tests from that file.
     */
    @Parameter(key = "ctg_seeds_file_in", group = "Continuous Test Generation",
            description = "If specified, load serialized tests from that file")
    public static String CTG_SEEDS_FILE_IN = null;

    @Parameter(key = "ctg_seeds_file_out", group = "Continuous Test Generation",
            description = "If specified, save serialized tests to that file")
    public static String CTG_SEEDS_FILE_OUT = null;

    @Parameter(key = "ctg_seeds_dir_name", group = "Continuous Test Generation",
            description = "Name of seed folder where the serialized tests are stored")
    public static String CTG_SEEDS_DIR_NAME = "seeds";

    @Parameter(key = "ctg_seeds_ext", group = "Continuous Test Generation",
            description = "File extension for serialized test files")
    public static String CTG_SEEDS_EXT = "seed";

    @Parameter(key = "ctg_project_info", group = "Continuous Test Generation",
            description = "XML file which stores stats about all CTG executions")
    public static String CTG_PROJECT_INFO = "project_info.xml";

    @Parameter(key = "ctg_history_file", group = "Continuous Test Generation",
            description = "File with the list of new(A)/modified(M)/deleted(D) files")
    public static String CTG_HISTORY_FILE = null;

    @Parameter(key = "ctg_selected_cuts", group = "Continuous Test Generation",
            description = "Comma ',' separated list of CUTs to use in CTG. If none specified, then test all classes")
    public static String CTG_SELECTED_CUTS = null;

    @Parameter(key = "ctg_selected_cuts_file_location", group = "Continuous Test Generation",
            description = "Absolute path of text file where classes to test are specified. "
                    + "This is needed for operating systems like Windows where there are hard "
                    + "limits on parameters' size")
    public static String CTG_SELECTED_CUTS_FILE_LOCATION = null;

    @Parameter(key = "ctg_export_folder", group = "Continuous Test Generation",
            description = "If specified, make a copy of all tests into the target export folder")
    public static String CTG_EXPORT_FOLDER = null;

    @Parameter(key = "ctg_debug_port", group = "Continuous Test Generation",
            description = "Port for remote debugging of 'Master' spawn processes. 'Clinet' process "
                    + "will have port+1. This only applies when for a single CUT.")
    public static Integer CTG_DEBUG_PORT = null;

    /**
     * The types of CTG schedules that can be used.
     */
    public enum AvailableSchedule {
        SIMPLE, BUDGET, SEEDING, BUDGET_AND_SEEDING, HISTORY
    }

    /*
     * FIXME choose best schedule for default
     * Note: most likely we ll use this parameter only for testing/experiments.
     * Maven plugin will use the default, best one
     */
    @Parameter(key = "ctg_schedule", group = "Continuous Test Generation", description = "Schedule used to run jobs")
    public static AvailableSchedule CTG_SCHEDULE = AvailableSchedule.BUDGET;


    @Parameter(key = "ctg_extra_args", group = "Continuous Test Generation",
            description = "Extra '-D' arguments to pass to EvoSuite test generation processes")
    public static String CTG_EXTRA_ARGS = null;


    // ---------------------------------------------------------------
    // Single branch mode
    @Parameter(key = "random_tests", group = "Single Branch Mode",
            description = "Number of random tests to run before test generation (Single branch mode)")
    public static int RANDOM_TESTS = 0;

    @Parameter(key = "skip_covered", group = "Single Branch Mode",
            description = "Skip coverage goals that have already been (coincidentally) covered")
    public static boolean SKIP_COVERED = true;

    @Parameter(key = "reuse_budget", group = "Single Branch Mode",
            description = "Use leftover budget on unsatisfied test goals (Single branch mode)")
    public static boolean REUSE_BUDGET = true;

    @Parameter(key = "shuffle_goals", group = "Single Branch Mode",
            description = "Shuffle test goals before test generation (Single branch mode)")
    public static boolean SHUFFLE_GOALS = true;

    @Parameter(key = "recycle_chromosomes", group = "Single Branch Mode",
            description = "Seed initial population with related individuals (Single branch mode)")
    public static boolean RECYCLE_CHROMOSOMES = true;

    // ---------------------------------------------------------------
    // Output
    public enum OutputFormat {
        JUNIT3, JUNIT4, TESTNG, JUNIT5
    }

    @Parameter(key = "test_format", group = "Output", description = "Format of the resulting test cases")
    public static OutputFormat TEST_FORMAT = OutputFormat.JUNIT5;

    @Parameter(key = "test_comments", group = "Output",
            description = "Include a header with coverage information for each test")
    public static boolean TEST_COMMENTS = false;

    @Parameter(key = "test_scaffolding", group = "Output",
            description = "Generate all the scaffolding needed to run EvoSuite JUnit tests in a separate file")
    public static boolean TEST_SCAFFOLDING = false;

    @Parameter(key = "max_length_test_case", group = "Output",
            description = "Maximum number of statements (normal statements and assertions)")
    public static int MAX_LENGTH_TEST_CASE = 2500;

    @Parameter(key = "no_runtime_dependency", group = "Output",
            description = "Avoid runtime dependencies in JUnit test")
    public static boolean NO_RUNTIME_DEPENDENCY = false;

    @Parameter(key = "test_extension_mode", group = "Output",
            description = "Use experimental JUnit5 extension output mode (legacy scaffolding remains available)")
    public static boolean TEST_EXTENSION_MODE = true;

    @Parameter(key = "test_extension_preload_initialized_classes", group = "Output",
            description = "In JUnit5 extension mode, preload classes observed during generation before tests run")
    public static boolean TEST_EXTENSION_PRELOAD_INITIALIZED_CLASSES = false;

    @Parameter(key = "test_extension_preload_max_classes", group = "Output",
            description = "Maximum number of classes to preload in JUnit5 extension mode when preloading is enabled")
    public static int TEST_EXTENSION_PRELOAD_MAX_CLASSES = 64;

    @Parameter(key = "print_to_system", group = "Output",
            description = "Allow test output on console")
    public static boolean PRINT_TO_SYSTEM = false;

    @Parameter(key = "plot", group = "Output",
            description = "Deprecated, no effect (legacy size/fitness plotting)")
    @Deprecated
    public static boolean PLOT = false;

    @Parameter(key = "coverage_matrix", group = "Output",
            description = "Create a coverage matrix (each row represents the coverage a test case, "
                    + "and each column represents one goal")
    public static boolean COVERAGE_MATRIX = false;

    @Parameter(key = "coverage_matrix_filename", group = "Output",
            description = "File to which the coverage matrix is written")
    public static String COVERAGE_MATRIX_FILENAME = "matrix";

    @Parameter(key = "junit_tests", group = "Output",
            description = "Create JUnit test suites")
    public static boolean JUNIT_TESTS = true;

    @Parameter(key = "structural_suite_export", group = "Output",
            description = "Serialize the post-minimization suite before oracle generation and stop before JUnit output")
    public static String STRUCTURAL_SUITE_EXPORT = "";

    @Parameter(key = "oracle_replay_input", group = "Output",
            description = "Load a structural-suite artifact and run only the selected oracle post-processing")
    public static String ORACLE_REPLAY_INPUT = "";

    public enum OracleReplayStrategy {
        NONE, ALL, MUTATION, LLM, MUTATION_LLM
    }

    @Parameter(key = "oracle_replay_strategy", group = "Output",
            description = "Oracle strategy to apply when replaying a structural-suite artifact")
    public static OracleReplayStrategy ORACLE_REPLAY_STRATEGY = OracleReplayStrategy.NONE;

    public enum JUnitCheckValues {
        TRUE, OPTIONAL, FALSE
    }

    @Parameter(key = "junit_check", group = "Output",
            description = "Compile and run resulting JUnit test suite (if any was created)")
    public static JUnitCheckValues JUNIT_CHECK = JUnitCheckValues.TRUE;

    @Parameter(key = "junit_check_on_separate_process", group = "Output",
            description = "Compile and run resulting JUnit test suite on a separate process")
    @Deprecated
    //this gives quite a few issues. and hopefully the problems it was aimed to fix are no longer
    public static boolean JUNIT_CHECK_ON_SEPARATE_PROCESS = false;

    @Parameter(key = "junit_unstable_diagnostics", group = "Output",
            description = "Print detailed origin diagnostics for unstable tests detected during JUnit check")
    public static boolean JUNIT_UNSTABLE_DIAGNOSTICS = false;

    @Parameter(key = "junit_suffix", group = "Output",
            description = "Suffix that is appended at each generated JUnit file name")
    public static String JUNIT_SUFFIX = "_ESTest";

    @Parameter(key = "junit_failed_suffix", group = "Output",
            description = "Suffix that is appended at each generated JUnit file name for failing tests")
    public static String JUNIT_FAILED_SUFFIX = "_Failed_ESTest";

    //WARN: do not change this value, as had to be hardcoded in quite a few places :( if really need to change it,
    // all that code has to be changed as well
    /**
     * Suffix used to specify scaffolding files.
     */
    @Parameter(key = "scaffolding_suffix", group = "Output",
            description = "Suffix used to specify scaffolding files")
    public static String SCAFFOLDING_SUFFIX = "scaffolding";

    @Parameter(key = "tools_jar_location", group = "Output", description = "Location of where to locate tools.jar")
    public static String TOOLS_JAR_LOCATION = null;

    @Parameter(key = "pure_inspectors", group = "Output",
            description = "Selects only an underapproximation of all inspectors that are also pure (no side-effects)")
    public static boolean PURE_INSPECTORS = true;

    @Parameter(key = "pure_equals", group = "Output",
            description = "Selects only an underapproximation of equals(Object) that are also "
                    + "known to be pure (no side-effects)")
    public static boolean PURE_EQUALS = false;

    /**
     * TODO: this functionality is not implemented yet.
     */
    @Parameter(key = "junit_extend", group = "Output",
            description = "Extend existing JUnit test suite")
    public static String JUNIT_EXTEND = "";

    @Parameter(key = "junit", group = "Experimental",
            description = "A colon(:) separated list of JUnit suites to execute. Can be a prefix "
                    + "(i.e., package name), a directory, a jar file, or the full name of a "
                    + "JUnit suite.")
    public static String JUNIT = "";

    @Parameter(key = "log_goals", group = "Output", description = "Create a CSV file for each individual evolution")
    public static boolean LOG_GOALS = false;

    @Parameter(key = "log.level", group = "Output",
            description = "Verbosity level of logger")
    public static String LOG_LEVEL = null;

    @Parameter(key = "log.target", group = "Output",
            description = "Target logger - all logging if not set")
    public static String LOG_TARGET = null;

    @Parameter(key = "minimize", group = "Output",
            description = "Minimize test suite after generation")
    public static boolean MINIMIZE = true;

    @Parameter(key = "minimize_second_pass", group = "Output",
            description = "Perform a second minimization pass as the first one may retain subsumed tests")
    public static boolean MINIMIZE_SECOND_PASS = true;

    @Parameter(key = "minimize_sort", group = "Output",
            description = "Sort goals before Minimization")
    public static boolean MINIMIZE_SORT = true;


    @Parameter(key = "minimize_skip_coincidental", group = "Output",
            description = "Minimize test suite after generation")
    public static boolean MINIMIZE_SKIP_COINCIDENTAL = true;

    @Parameter(key = "minimize_old", group = "Output",
            description = "Minimize test suite using old algorithm")
    @Deprecated
    public static boolean MINIMIZE_OLD = false;

    @Parameter(key = "minimize_values", group = "Output",
            description = "Minimize constants and method calls")
    public static boolean MINIMIZE_VALUES = false;

    @Parameter(key = "lm_strings", group = "Output",
            description = "Use language model on strings.  The parameter minimize_values must also be true.")
    public static boolean LM_STRINGS = false;

    @Parameter(key = "minimize_strings", group = "Output",
            description = "Try to minimise strings by deleting non-printables. The parameter "
                    + "minimize_values must also be true,")
    public static boolean MINIMIZE_STRINGS = true;

    @Parameter(key = "lm_src", description = "Text file for the language model.")
    public static String LM_SRC = "ukwac_char_lm";

    @Parameter(key = "lm_iterations",
            description = "Number of 1+1EA generations PER STRING PRIMITIVE for language model optimiser.")
    public static int LM_ITERATIONS = 1000;

    @Parameter(key = "lm_mutation_type", description = "Type of mutation to use in language model string optimiser.")
    public static MutationType LM_MUTATION_TYPE = MutationType.EVOSUITE;

    @Parameter(key = "coverage", group = "Output",
            description = "Calculate coverage after test suite generation")
    public static boolean COVERAGE = true;

    @Parameter(key = "inline", group = "Output",
            description = "Inline all constants")
    public static boolean INLINE = true;

    @Parameter(key = "write_pool", group = "Output",
            description = "Keep sequences for object pool")
    public static String WRITE_POOL = "";

    @Parameter(key = "report_dir", group = "Output",
            description = "Directory in which to put HTML and CSV reports")
    public static String REPORT_DIR = "evosuite-report";

    @Parameter(key = "bytecode_logging_report_dir", group = "Output",
            description = "Directory in which to put TXT executed bytecode logs.")
    public static String BYTECODE_LOGGING_REPORT_DIR = "executed-bytecode-logs";

    @Parameter(key = "output_variables", group = "Output",
            description = "List of variables to output to CSV file. Variables are separated by "
                    + "commas. Null represents default values")
    public static String OUTPUT_VARIABLES = null;

    @Parameter(key = "configuration_id", group = "Output",
            description = "Label that identifies the used configuration of EvoSuite. This is only "
                    + "done when running experiments.")
    public static String CONFIGURATION_ID = null;

    @Parameter(key = "group_id", group = "Output",
            description = "Label that specifies a group the SUT belongs to. This is only needed "
                    + "for running experiments.")
    public static String GROUP_ID = "none";

    @Parameter(key = "save_all_data", group = "Output",
            description = "Generate and store all data reports")
    public static boolean SAVE_ALL_DATA = true;

    @Parameter(key = "print_goals", group = "Output",
            description = "Print out goals of class under test")
    public static boolean PRINT_GOALS = false;

    @Parameter(key = "all_goals_file", group = "Output",
            description = "File to which the list of all goals is written")
    public static String ALL_GOALS_FILE = REPORT_DIR + File.separator + "all.goals";

    @Parameter(key = "write_all_goals_file", group = "Output",
            description = "If enabled, the list of all goals is written to a file")
    public static boolean WRITE_ALL_GOALS_FILE = false;

    @Parameter(key = "print_current_goals", group = "Output",
            description = "Print out current goal during test generation")
    public static boolean PRINT_CURRENT_GOALS = true;

    @Parameter(key = "print_covered_goals", group = "Output",
            description = "Print out covered goals during test generation")
    public static boolean PRINT_COVERED_GOALS = false;

    @Parameter(key = "print_missed_goals", group = "Output",
            description = "Print out missed goals at the end")
    public static boolean PRINT_MISSED_GOALS = false;

    @Parameter(key = "write_covered_goals_file", group = "Output",
            description = "Write covered goals file")
    public static boolean WRITE_COVERED_GOALS_FILE = false;

    @Parameter(key = "covered_goals_file", group = "Output",
            description = "File with relation of tests and covered goals")
    public static String COVERED_GOALS_FILE = REPORT_DIR + File.separator + "covered.goals";

    @Parameter(key = "assertions", group = "Output", description = "Create assertions")
    public static boolean ASSERTIONS = true;

    public enum AssertionStrategy {
        ALL, MUTATION, UNIT
    }

    @Parameter(key = "assertion_strategy", group = "Output",
            description = "Which assertions to generate")
    public static AssertionStrategy ASSERTION_STRATEGY = AssertionStrategy.MUTATION;

    @Parameter(key = "filter_assertions", group = "Output",
            description = "Filter flaky assertions")
    public static boolean FILTER_ASSERTIONS = false;

    @Parameter(key = "max_mutants_per_test", group = "Output",
            description = "How many mutants to use when trying to find assertions for a test")
    public static int MAX_MUTANTS_PER_TEST = 100;

    @Parameter(key = "max_mutants_per_method", group = "Output",
            description = "How many mutants can be inserted into a single method")
    public static int MAX_MUTANTS_PER_METHOD = 700;

    @Parameter(key = "max_mutants_per_class", group = "Output",
            description = "How many mutants can be used as target for a single class")
    public static int MAX_MUTANTS_PER_CLASS = 1000;

    @Parameter(key = "max_replace_mutants", group = "Output",
            description = "How many replacement mutants can be inserted for any one variable")
    public static int MAX_REPLACE_MUTANTS = 100;

    /**
     * Directory in which to place JUnit tests.
     */
    @Parameter(key = "test_dir", group = "Output", description = "Directory in which to place JUnit tests")
    public static String TEST_DIR = "evosuite-tests";

    @Parameter(key = "write_cfg", group = "Output", description = "Create CFG graphs")
    public static boolean WRITE_CFG = false;

    @Parameter(key = "shutdown_hook", group = "Output", description = "Store test suite on Ctrl+C")
    public static boolean SHUTDOWN_HOOK = true;

    @Parameter(key = "show_progress", group = "Output", description = "Show progress bar on console")
    public static boolean SHOW_PROGRESS = true;

    @Parameter(key = "serialize_result", group = "Output", description = "Serialize result of search to main process")
    public static boolean SERIALIZE_RESULT = false;

    @Parameter(key = "new_statistics", group = "Output", description = "Use the new statistics backend on the master")
    public static boolean NEW_STATISTICS = true;

    @Parameter(key = "ignore_missing_statistics", group = "Output",
            description = "Return an empty string for missing output variables")
    public static boolean IGNORE_MISSING_STATISTICS = false;

    @Parameter(key = "float_precision", group = "Output",
            description = "Precision to use in float comparisons and assertions")
    public static float FLOAT_PRECISION = 0.01F;

    @Parameter(key = "double_precision", group = "Output",
            description = "Precision to use in double comparisons and assertions")
    public static double DOUBLE_PRECISION = 0.01;

    //@Parameter(key = "old_statistics", group = "Output", description = "Use the old statistics backend on the master")
    //public static boolean OLD_STATISTICS = false;

    @Parameter(key = "validate_runtime_variables", group = "Output",
            description = "Validate runtime values before writing statistics")
    public static boolean VALIDATE_RUNTIME_VARIABLES = true;

    @Parameter(key = "serialize_ga", group = "Output",
            description = "Include the GA instance in the test generation result")
    public static boolean SERIALIZE_GA = false;

    @Parameter(key = "serialize_dse", group = "Output",
            description = "Include the DSE instance in the test generation result")
    public static boolean SERIALIZE_DSE = false;

    public enum StatisticsBackend {
        NONE, CONSOLE, CSV, HTML, DEBUG
    }

    @Parameter(key = "statistics_backend", group = "Output", description = "Which backend to use to collect data")
    public static StatisticsBackend STATISTICS_BACKEND = StatisticsBackend.CSV;

    @Parameter(key = "timeline_interval", group = "Output",
            description = "Time interval in milliseconds for timeline statistics")
    public static long TIMELINE_INTERVAL = 60 * 1000;

    @Parameter(key = "timeline_interpolation", group = "Output", description = "Interpolate timeline values")
    public static boolean TIMELINE_INTERPOLATION = true;

    public enum OutputGranularity {
        MERGED, TESTCASE
    }

    @Parameter(key = "output_granularity", group = "Output",
            description = "Write all test cases for a class into a single file or to separate files.")
    public static OutputGranularity OUTPUT_GRANULARITY = OutputGranularity.MERGED;

    @Parameter(key = "max_coverage_depth", group = "Output",
            description = "Maximum depth in the calltree to count a branch as covered")
    public static int MAX_COVERAGE_DEPTH = -1;

    // ---------------------------------------------------------------
    // Naming
    public enum TestNamingStrategy {
        NUMBERED, COVERAGE
    }

    @Parameter(key = "test_naming_strategy", group = "Output",
            description = "What strategy to use to derive names for tests")
    public static TestNamingStrategy TEST_NAMING_STRATEGY = TestNamingStrategy.NUMBERED;

    public enum VariableNamingStrategy {
        TYPE_BASED, HEURISTICS_BASED
    }

    @Parameter(key = "variable_naming_strategy", group = "Output",
            description = "What strategy to use to derive names for variables")
    public static VariableNamingStrategy VARIABLE_NAMING_STRATEGY = VariableNamingStrategy.TYPE_BASED;

    // ---------------------------------------------------------------
    // LLM

    public enum LlmProvider {
        NONE, OPENAI, OLLAMA, ANTHROPIC
    }

    @Parameter(key = "llm_provider", group = "LLM",
            description = "LLM provider to use. NONE disables all LLM features")
    public static LlmProvider LLM_PROVIDER = LlmProvider.NONE;

    @Parameter(key = "llm_model", group = "LLM",
            description = "Model name for the configured provider")
    public static String LLM_MODEL = "gpt-4o";

    @Parameter(key = "llm_api_key", group = "LLM",
            description = "API key for provider-authenticated LLM services")
    public static String LLM_API_KEY = "";

    @Parameter(key = "llm_base_url", group = "LLM",
            description = "Base URL for LLM APIs (required for local providers like Ollama)")
    public static String LLM_BASE_URL = "";

    @Parameter(key = "llm_temperature", group = "LLM",
            description = "Sampling temperature for LLM generations")
    @DoubleValue(min = 0.0, max = 2.0)
    public static double LLM_TEMPERATURE = 0.7;

    @Parameter(key = "llm_max_tokens", group = "LLM",
            description = "Maximum output tokens per LLM response")
    @IntValue(min = 1)
    public static int LLM_MAX_TOKENS = 32768;

    @Parameter(key = "llm_timeout_seconds", group = "LLM",
            description = "Per-LLM-call HTTP timeout in seconds (applies to one request to the model). "
                    + "Orchestrator-level waits for repair-capable producers (initial seeding, pool enrichment, "
                    + "stagnation injection, single-prompt strategy) are derived as "
                    + "llm_timeout_seconds * (llm_repair_attempts + 1) via LlmWaitBudget, then clamped to the "
                    + "remaining search budget. To change the per-call cap, change this; to change the orchestrator "
                    + "wait, change llm_repair_attempts.")
    @IntValue(min = 1)
    public static int LLM_TIMEOUT_SECONDS = 60;

    @Parameter(key = "llm_require_jdk_compiler", group = "LLM",
            description = "If true, fail fast when LLM is enabled but no JDK compiler (jdk.compiler) is available; "
                    + "if false, disable LLM features for the run and fall back to non-LLM behavior")
    public static boolean LLM_REQUIRE_JDK_COMPILER = false;

    @Parameter(key = "llm_retry_max_attempts", group = "LLM",
            description = "Retry count for retryable LLM failures")
    @IntValue(min = 0)
    public static int LLM_RETRY_MAX_ATTEMPTS = 2;

    @Parameter(key = "llm_retry_base_delay_ms", group = "LLM",
            description = "Base delay in milliseconds for exponential retry backoff")
    @IntValue(min = 1)
    public static int LLM_RETRY_BASE_DELAY_MS = 250;

    public enum LlmPromptTechnique {
        NONE, CHAIN_OF_THOUGHT, FEW_SHOT
    }

    @Parameter(key = "llm_prompt_technique", group = "LLM",
            description = "Prompting technique used across LLM requests")
    public static LlmPromptTechnique LLM_PROMPT_TECHNIQUE = LlmPromptTechnique.NONE;

    @Parameter(key = "llm_few_shot_use_parsed_junit", group = "LLM",
            description = "When FEW_SHOT is active, include parsed external JUnit tests as examples when available")
    public static boolean LLM_FEW_SHOT_USE_PARSED_JUNIT = true;

    @Parameter(key = "llm_few_shot_use_archive", group = "LLM",
            description = "When FEW_SHOT is active, include successful internal tests from archive/population")
    public static boolean LLM_FEW_SHOT_USE_ARCHIVE = true;

    @Parameter(key = "llm_few_shot_max_examples", group = "LLM",
            description = "Maximum number of example tests included in a FEW_SHOT prompt")
    @IntValue(min = 1)
    public static int LLM_FEW_SHOT_MAX_EXAMPLES = 3;

    public enum LlmFewShotArchiveStrategy {
        /** Favor archive tests covering goals related to the currently uncovered goals. */
        GOAL_OVERLAP,
        /** Favor tests with the broadest coverage (most covered goals, then largest size). */
        COVERAGE_BREADTH
    }

    @Parameter(key = "llm_few_shot_archive_strategy", group = "LLM",
            description = "Ranking strategy for selecting archive examples under FEW_SHOT")
    public static LlmFewShotArchiveStrategy LLM_FEW_SHOT_ARCHIVE_STRATEGY =
            LlmFewShotArchiveStrategy.GOAL_OVERLAP;

    @Parameter(key = "llm_few_shot_max_chars_total", group = "LLM",
            description = "Maximum total characters for all FEW_SHOT example blocks combined (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_FEW_SHOT_MAX_CHARS_TOTAL = 8000;

    @Parameter(key = "llm_few_shot_max_chars_per_example", group = "LLM",
            description = "Maximum characters per individual FEW_SHOT example (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_FEW_SHOT_MAX_CHARS_PER_EXAMPLE = 3000;

    @Parameter(key = "llm_source_path", group = "LLM",
            description = "Optional explicit source path for including SUT source code in prompts")
    public static String LLM_SOURCE_PATH = "";

    public enum LlmSutContextMode {
        SIGNATURE_ONLY, BYTECODE_DISASSEMBLED, DECOMPILED_SOURCE, SOURCE_CODE
    }

    @Parameter(key = "llm_sut_context_mode", group = "LLM",
            description = "CUT context representation in LLM prompts: SIGNATURE_ONLY (default, always available), "
                    + "BYTECODE_DISASSEMBLED, DECOMPILED_SOURCE, SOURCE_CODE")
    public static LlmSutContextMode LLM_SUT_CONTEXT_MODE = LlmSutContextMode.SIGNATURE_ONLY;

    @Parameter(key = "llm_context_fallback_enabled", group = "LLM",
            description = "If true, degrade to SIGNATURE_ONLY when selected context mode is unavailable; "
                    + "if false, leave context empty")
    public static boolean LLM_CONTEXT_FALLBACK_ENABLED = true;

    @Parameter(key = "llm_context_max_chars", group = "LLM",
            description = "Maximum characters of CUT context included in prompts "
                    + "(0 means unlimited; default 32000 for cost control)")
    @IntValue(min = 0)
    public static int LLM_CONTEXT_MAX_CHARS = 32000;

    @Parameter(key = "llm_cluster_summary_max_chars", group = "LLM",
            description = "Maximum characters for the test cluster dependency summary in LLM prompts "
                    + "(0 means unlimited; default 4000)")
    @IntValue(min = 0)
    public static int LLM_CLUSTER_SUMMARY_MAX_CHARS = 4000;

    @Parameter(key = "llm_cluster_summary_dynamic_scaling", group = "LLM",
            description = "Scale dependency summary budget with CUT context budget. "
                    + "If true, uses ratio/min/max settings below.")
    public static boolean LLM_CLUSTER_SUMMARY_DYNAMIC_SCALING = true;

    @Parameter(key = "llm_cluster_summary_dynamic_ratio", group = "LLM",
            description = "Fraction of LLM_CONTEXT_MAX_CHARS allocated to dependency summary "
                    + "when dynamic scaling is enabled")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LLM_CLUSTER_SUMMARY_DYNAMIC_RATIO = 0.12;

    @Parameter(key = "llm_cluster_summary_dynamic_min_chars", group = "LLM",
            description = "Lower bound for dynamically scaled dependency summary budget")
    @IntValue(min = 0)
    public static int LLM_CLUSTER_SUMMARY_DYNAMIC_MIN_CHARS = 4000;

    @Parameter(key = "llm_cluster_summary_dynamic_max_chars", group = "LLM",
            description = "Upper bound for dynamically scaled dependency summary budget (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_CLUSTER_SUMMARY_DYNAMIC_MAX_CHARS = 32000;

    @Parameter(key = "llm_cluster_summary_absolute_override_chars", group = "LLM",
            description = "Absolute override for dependency summary budget (0 = no override)")
    @IntValue(min = 0)
    public static int LLM_CLUSTER_SUMMARY_ABSOLUTE_OVERRIDE_CHARS = 0;

    @Parameter(key = "llm_cluster_summary_per_class_soft_cap_chars", group = "LLM",
            description = "Soft cap per dependency class in summary output "
                    + "(0 = auto-compute from effective dependency budget)")
    @IntValue(min = 0)
    public static int LLM_CLUSTER_SUMMARY_PER_CLASS_SOFT_CAP_CHARS = 0;

    @Parameter(key = "llm_cluster_summary_compact_signatures", group = "LLM",
            description = "Use compact dependency signatures (strip redundant keywords/type qualifiers)")
    public static boolean LLM_CLUSTER_SUMMARY_COMPACT_SIGNATURES = true;

    // --- LLM Goal Format ---
    public enum LlmGoalFormat {
        /** Raw goal.toString() output (backward-compatible). */
        RAW,
        /** Human-readable descriptions grouped by method (default). */
        LLM_FRIENDLY
    }

    @Parameter(key = "llm_goal_format", group = "LLM",
            description = "Format for uncovered goals in LLM prompts: RAW uses toString(), "
                    + "LLM_FRIENDLY uses human-readable descriptions grouped by method")
    public static LlmGoalFormat LLM_GOAL_FORMAT = LlmGoalFormat.LLM_FRIENDLY;

    @Parameter(key = "llm_annotate_existing_tests", group = "LLM",
            description = "Annotate existing tests in prompts with their covered goals")
    public static boolean LLM_ANNOTATE_EXISTING_TESTS = true;

    @Parameter(key = "llm_existing_tests_max_chars_total", group = "LLM",
            description = "Maximum total characters for existing tests in LLM prompts "
                    + "(0 means unlimited; default 6000)")
    @IntValue(min = 0)
    public static int LLM_EXISTING_TESTS_MAX_CHARS_TOTAL = 6000;

    @Parameter(key = "llm_existing_tests_max_chars_per_test", group = "LLM",
            description = "Maximum characters per individual existing test in LLM prompts "
                    + "(0 means unlimited; default 2500)")
    @IntValue(min = 0)
    public static int LLM_EXISTING_TESTS_MAX_CHARS_PER_TEST = 2500;

    @Parameter(key = "llm_async_producer_include_tests", group = "LLM",
            description = "Include existing test context in async LLM producer prompts")
    public static boolean LLM_ASYNC_PRODUCER_INCLUDE_TESTS = true;

    @Parameter(key = "llm_relevance_based_test_selection", group = "LLM",
            description = "Select tests most relevant to uncovered goals for LLM prompts (vs first-N)")
    public static boolean LLM_RELEVANCE_BASED_TEST_SELECTION = true;

    @Parameter(key = "llm_relevance_method_overlap_weight", group = "LLM",
            description = "Weight for method-overlap signal in relevance-based test selection")
    public static double LLM_RELEVANCE_METHOD_OVERLAP_WEIGHT = 2.0;

    @Parameter(key = "llm_decompiler_timeout_seconds", group = "LLM",
            description = "Timeout in seconds for decompiler-based context extraction")
    @IntValue(min = 1)
    public static int LLM_DECOMPILER_TIMEOUT_SECONDS = 10;

    @Parameter(key = "llm_seed_initial_population", group = "LLM",
            description = "Seed the initial population with LLM-generated tests")
    public static boolean LLM_SEED_INITIAL_POPULATION = false;

    @Parameter(key = "llm_diagnostic_extractor_trace_targets", group = "LLM",
            description = "Comma-separated target classes for verbose blocker-signal extraction tracing in "
                    + "ProblemCardExtractor debug logs; use '*' or 'all' to trace every target class")
    public static String LLM_DIAGNOSTIC_EXTRACTOR_TRACE_TARGETS = "";

    /**
     * Preset bundle for LLM seeding features. When non-{@code OFF}, the chosen
     * profile force-overrides the individual {@code LLM_SEED_*}/{@code LLM_ENRICH_*}
     * flags via {@link #applyLlmSeedingProfile()}. Users who want fine-grained
     * control should leave this at {@code OFF} and toggle individual flags.
     */
    public enum LlmSeedingProfile {
        /** No bundle — individual flags are honoured as-is. */
        OFF,
        /** Cast-class enrichment only (structural gate, single LLM call). */
        MIN,
        /** Constant pool + object pool + cast classes — no test factory, no initial seed. */
        STANDARD,
        /** Everything: structural + data enrichment + initial seed + test factory + non-SUT constants. */
        FULL
    }

    @Parameter(key = "llm_seeding_profile", group = "LLM",
            description = "Preset bundle of LLM seeding flags. When set to MIN, "
                    + "STANDARD, or FULL, force-overrides the individual "
                    + "llm_enrich_* / llm_seed_initial_population / llm_test_factory "
                    + "flags at orchestrator startup. Set to OFF to retain "
                    + "fine-grained control over individual flags.")
    public static LlmSeedingProfile LLM_SEEDING_PROFILE = LlmSeedingProfile.OFF;

    /**
     * Force-applies the LLM seeding profile bundle to the corresponding
     * individual flags. Idempotent. Should be called once during EvoSuite
     * setup — see {@link org.evosuite.TestSuiteGenerator}.
     */
    public static void applyLlmSeedingProfile() {
        switch (LLM_SEEDING_PROFILE) {
            case OFF:
                return;
            case MIN:
                LLM_ENRICH_CAST_CLASSES = true;
                LLM_ENRICH_CONSTANT_POOL = false;
                LLM_ENRICH_NON_SUT_CONSTANT_POOL = false;
                LLM_ENRICH_OBJECT_POOL = false;
                LLM_SEED_INITIAL_POPULATION = false;
                LLM_TEST_FACTORY = false;
                return;
            case STANDARD:
                LLM_ENRICH_CAST_CLASSES = true;
                LLM_ENRICH_CONSTANT_POOL = true;
                LLM_ENRICH_NON_SUT_CONSTANT_POOL = false;
                LLM_ENRICH_OBJECT_POOL = true;
                LLM_SEED_INITIAL_POPULATION = false;
                LLM_TEST_FACTORY = false;
                return;
            case FULL:
                LLM_ENRICH_CAST_CLASSES = true;
                LLM_ENRICH_CONSTANT_POOL = true;
                LLM_ENRICH_NON_SUT_CONSTANT_POOL = true;
                LLM_ENRICH_OBJECT_POOL = true;
                LLM_SEED_INITIAL_POPULATION = true;
                LLM_TEST_FACTORY = true;
        }
    }

    /** Controls whether LLM-generated assertions are retained in parsed tests. */
    public enum LlmGeneratedAssertionsPolicy {
        /** Keep assertions for LLMSTRATEGY, drop for search/enrichment integrations. */
        AUTO,
        /** Always keep LLM-generated assertions in parsed tests. */
        KEEP,
        /** Always drop LLM-generated assertions from parsed tests. */
        DROP
    }

    @Parameter(key = "llm_generated_assertions_policy", group = "LLM",
            description = "Retention policy for LLM-generated assertions in parsed tests")
    public static LlmGeneratedAssertionsPolicy LLM_GENERATED_ASSERTIONS_POLICY =
            LlmGeneratedAssertionsPolicy.AUTO;

    // --- LLM Strategy Mode ---
    public enum LlmStrategyMode {
        /** One-shot baseline: generate once and stop. */
        SINGLE_PROMPT,
        /** Iterative baseline: query, run, re-query for uncovered goals until budget exhausted. */
        ITERATIVE_BUDGETED
    }

    @Parameter(key = "llm_strategy_mode", group = "LLM",
            description = "Mode for LLMSTRATEGY: one-shot baseline or iterative budgeted querying")
    public static LlmStrategyMode LLM_STRATEGY_MODE = LlmStrategyMode.SINGLE_PROMPT;

    @Parameter(key = "llm_strategy_max_iterations", group = "LLM",
            description = "Hard cap on follow-up iterations in ITERATIVE_BUDGETED mode "
                    + "(0 means no cap; the stopping condition / LLM budget still apply)")
    @IntValue(min = 0)
    public static int LLM_STRATEGY_MAX_ITERATIONS = 20;

    @Parameter(key = "llm_strategy_no_progress_limit", group = "LLM",
            description = "Stop iterating in ITERATIVE_BUDGETED mode after this many consecutive "
                    + "iterations without covering any new goal (0 disables the guard)")
    @IntValue(min = 0)
    public static int LLM_STRATEGY_NO_PROGRESS_LIMIT = 3;

    @Parameter(key = "llm_strategy_parse_fail_limit", group = "LLM",
            description = "Stop iterating in ITERATIVE_BUDGETED mode after this many consecutive "
                    + "iterations that produced no parsed tests at all (0 disables the guard)")
    @IntValue(min = 0)
    public static int LLM_STRATEGY_PARSE_FAIL_LIMIT = 2;

    @Parameter(key = "llm_strategy_initial_target_tests", group = "LLM",
            description = "Soft target for the number of tests requested in the initial "
                    + "ITERATIVE_BUDGETED prompt. This is prompt guidance, not an admission cap; "
                    + "all valid returned tests are retained (0 omits numeric guidance)")
    @IntValue(min = 0)
    public static int LLM_STRATEGY_INITIAL_TARGET_TESTS = 0;

    @Parameter(key = "llm_strategy_followup_target_tests", group = "LLM",
            description = "Soft target for the number of focused tests requested in each "
                    + "ITERATIVE_BUDGETED follow-up prompt. This is prompt guidance, not an "
                    + "admission cap; all valid returned tests are retained (0 omits guidance)")
    @IntValue(min = 0)
    public static int LLM_STRATEGY_FOLLOWUP_TARGET_TESTS = 0;

    @Parameter(key = "llm_iterative_timeline_enabled", group = "LLM",
            description = "Write iterative_llm_timeline_<TARGET_CLASS>.csv under REPORT_DIR "
                    + "with one row for the initial request and each follow-up round")
    public static boolean LLM_ITERATIVE_TIMELINE_ENABLED = true;

    @Parameter(key = "llm_test_factory", group = "LLM",
            description = "Enable LLM test-factory wrapper; fallback factory remains active")
    public static boolean LLM_TEST_FACTORY = false;

    @Parameter(key = "llm_test_factory_probability", group = "LLM",
            description = "Probability that LLM wrapper is selected before falling back to the wrapped factory")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LLM_TEST_FACTORY_PROBABILITY = 0.1;

    @Parameter(key = "llm_async_producer", group = "LLM",
            description = "Enable asynchronous LLM test production")
    public static boolean LLM_ASYNC_PRODUCER = false;

    @Parameter(key = "llm_async_producer_queue_size", group = "LLM",
            description = "Maximum number of buffered tests in async LLM producer")
    @IntValue(min = 1)
    public static int LLM_ASYNC_PRODUCER_QUEUE_SIZE = 2;

    @Parameter(key = "llm_async_producer_delay_ms", group = "LLM",
            description = "Minimum delay between async LLM calls in milliseconds")
    @IntValue(min = 0)
    public static int LLM_ASYNC_PRODUCER_DELAY_MS = 0;

    @Parameter(key = "llm_async_producer_refresh_interval", group = "LLM",
            description = "How often async producer refreshes prompt context")
    @IntValue(min = 1)
    public static int LLM_ASYNC_PRODUCER_REFRESH_INTERVAL = 5;

    /** Prompt-content strategy for the asynchronous LLM producer. */
    public enum LlmAsyncProducerPromptMode {
        /**
         * Goal-driven prompt: picks a few uncovered goals each iteration and
         * asks the LLM for a JUnit test targeting them. This is the historical
         * behaviour and stays the default while we A/B the diagnostic path.
         */
        POOL,
        /**
         * Diagnostic prompt: when ranked problem cards can be extracted from the
         * cached population snapshot, the producer sends a card-driven prompt
         * (mirroring the stagnation diagnostic mode). Falls back to POOL on any
         * iteration where no cards are available.
         */
        DIAGNOSTIC
    }

    @Parameter(key = "llm_async_producer_prompt", group = "LLM",
            description = "Prompt-content strategy for the async producer: POOL builds a goal-driven "
                    + "prompt; DIAGNOSTIC builds a card-driven prompt from the cached population "
                    + "snapshot and falls back to POOL when no cards can be extracted.")
    public static LlmAsyncProducerPromptMode LLM_ASYNC_PRODUCER_PROMPT = LlmAsyncProducerPromptMode.POOL;

    @Parameter(key = "llm_on_stagnation", group = "LLM",
            description = "Trigger LLM generation when search stagnates")
    public static boolean LLM_ON_STAGNATION = false;

    @Parameter(key = "llm_stagnation_timeout_seconds", group = "LLM",
            description = "Wall-clock seconds without fitness/coverage improvement before a "
                    + "stagnation intervention fires. The window resets on improvement and "
                    + "on each stagnation LLM call: in SYNC mode (llm_stagnation_mode) when "
                    + "the call returns, since submission and completion are effectively "
                    + "simultaneous; in ASYNC mode when the call completes and its results "
                    + "are published, not when it is submitted -- so a long-running ASYNC "
                    + "call still requires a fresh stagnation period afterwards before the "
                    + "next one fires.")
    @IntValue(min = 1)
    public static int LLM_STAGNATION_TIMEOUT_SECONDS = 30;

    @Parameter(key = "llm_stagnation_tests", group = "LLM",
            description = "Number of tests requested per stagnation intervention")
    @IntValue(min = 1)
    public static int LLM_STAGNATION_TESTS = 3;

    /** Threading mode for stagnation-triggered LLM calls. */
    public enum LlmStagnationMode {
        /** Block the GA evolve loop until the LLM call (and any repair) returns. */
        SYNC,
        /** Submit to a background worker; the GA continues evolving. */
        ASYNC
    }

    @Parameter(key = "llm_stagnation_mode", group = "LLM",
            description = "How stagnation LLM calls interact with the search: "
                    + "SYNC blocks the evolve loop until the call returns; "
                    + "ASYNC submits to a background worker and the search continues. "
                    + "Scope: this setting (and llm_stagnation_budget_guard_seconds, "
                    + "and the LLM_Stagnation* runtime variables) only applies to "
                    + "MOSA-family algorithms (MOSA/DynaMOSA) which route stagnation "
                    + "through StagnationLlmHelper. Whole-suite GAs (StandardGA, "
                    + "MonotonicGA) trigger stagnation via the fitness-based legacy "
                    + "path and remain synchronous regardless of this flag.")
    public static LlmStagnationMode LLM_STAGNATION_MODE = LlmStagnationMode.ASYNC;

    @Parameter(key = "llm_stagnation_budget_guard_seconds", group = "LLM",
            description = "Skip new stagnation LLM submissions when the remaining "
                    + "search budget (seconds) is below this threshold. Use 0 to "
                    + "disable the guard entirely. When -1 (default), the helper "
                    + "uses a conservative minimum (~45s) to avoid late-run calls "
                    + "that are likely to be interrupted at search shutdown. SYNC mode additionally caps "
                    + "the prompt+repair wait at min(llm_timeout_seconds * "
                    + "(1 + llm_repair_attempts), remaining budget) so a "
                    + "late-firing call cannot block past the search deadline. "
                    + "Set explicitly to require a larger remaining budget before "
                    + "stagnation fires.")
    @IntValue(min = -1)
    public static int LLM_STAGNATION_BUDGET_GUARD_SECONDS = -1;

    /** Prompt-content strategy for stagnation-triggered LLM calls. */
    public enum LlmStagnationPromptMode {
        /**
         * Pool-level prompt: includes the full uncovered-goal set (with
         * fitness-distance annotations) and the top-K most relevant tests
         * from the current population as existing-test context. Asks for
         * {@code llm_stagnation_tests} fresh tests targeting any uncovered
         * goal.
         */
        POOL,
        /**
         * Diagnostic prompt: builds a ranked set of "problem cards" from the
         * current population snapshot (e.g., unreached methods, consistent
         * exception barriers) and asks the LLM to prioritize and address the
         * most pressing blockers in a single call.
         *
         * <p>If no reliable cards can be extracted, the detector falls back to
         * {@link #POOL} for that call.
         */
        DIAGNOSTIC
    }

    @Parameter(key = "llm_stagnation_prompt", group = "LLM",
            description = "Prompt-content strategy for stagnation LLM calls: "
                    + "POOL builds a population-level prompt with the full uncovered "
                    + "goal set and top-K relevant tests; DIAGNOSTIC builds one "
                    + "unified prompt around ranked problem cards inferred from the "
                    + "current population, falling back to POOL when no cards are "
                    + "available.")
    public static LlmStagnationPromptMode LLM_STAGNATION_PROMPT = LlmStagnationPromptMode.DIAGNOSTIC;

    // ---- LLM injection blending (brood recombination + lineage elitism) ----

    @Parameter(key = "llm_blend_enabled", group = "LLM",
            description = "Blend LLM-injected candidates into the population by breeding a brood "
                    + "of mutation-burst and crossover variants per candidate; all variants are "
                    + "evaluated (archive capture) but only the best few are admitted to the union")
    public static boolean LLM_BLEND_ENABLED = false;

    @Parameter(key = "llm_blend_mutants", group = "LLM",
            description = "Mutation-burst variants bred per injected candidate")
    @IntValue(min = 0)
    public static int LLM_BLEND_MUTANTS = 2;

    @Parameter(key = "llm_blend_goal_crossovers", group = "LLM",
            description = "Goal-directed crossover variants per injected candidate (the partner is "
                    + "the population's best individual for the candidate's target goal; one "
                    + "crossover call yields both directions)")
    @IntValue(min = 0)
    public static int LLM_BLEND_GOAL_CROSSOVERS = 2;

    @Parameter(key = "llm_blend_tournament_crossovers", group = "LLM",
            description = "Tournament-partner crossover variants per injected candidate")
    @IntValue(min = 0)
    public static int LLM_BLEND_TOURNAMENT_CROSSOVERS = 1;

    @Parameter(key = "llm_blend_max_admitted_variants", group = "LLM",
            description = "Maximum brood variants admitted to the union per injected candidate; "
                    + "variants are admitted only when they cover a new goal or strictly improve "
                    + "on the raw candidate's target-goal fitness")
    @IntValue(min = 0)
    public static int LLM_BLEND_MAX_ADMITTED_VARIANTS = 2;

    @Parameter(key = "llm_blend_max_evals_per_gen", group = "LLM",
            description = "Generation-wide budget of brood-variant fitness evaluations")
    @IntValue(min = 0)
    public static int LLM_BLEND_MAX_EVALS_PER_GEN = 20;

    @Parameter(key = "llm_blend_keep_raw", group = "LLM",
            description = "Admit the unmodified injected candidate alongside its blends "
                    + "(false = blend-only ablation)")
    public static boolean LLM_BLEND_KEEP_RAW = true;

    @Parameter(key = "llm_lineage_elitism_generations", group = "LLM",
            description = "Speciation-free incubation: for this many generations after injection, "
                    + "each injected lineage keeps its best member in the population "
                    + "(0 disables lineage elitism)")
    @IntValue(min = 0)
    public static int LLM_LINEAGE_ELITISM_GENERATIONS = 0;

    @Parameter(key = "llm_lineage_elitism_max_fraction", group = "LLM",
            description = "Share cap: lineage-elitism-protected members may occupy at most this "
                    + "fraction of the population; oldest lineages lose protection first")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LLM_LINEAGE_ELITISM_MAX_FRACTION = 0.2;

    @Parameter(key = "llm_enrich_constant_pool", group = "LLM",
            description = "Enable LLM enrichment of constant pools")
    public static boolean LLM_ENRICH_CONSTANT_POOL = false;

    @Parameter(key = "llm_enrich_non_sut_constant_pool", group = "LLM",
            description = "Enable LLM enrichment of non-SUT/dependency constant pool")
    public static boolean LLM_ENRICH_NON_SUT_CONSTANT_POOL = false;

    @Parameter(key = "llm_non_sut_constant_classes_max", group = "LLM",
            description = "Maximum dependency classes to query for non-SUT constant enrichment")
    @IntValue(min = 1)
    public static int LLM_NON_SUT_CONSTANT_CLASSES_MAX = 10;

    @Parameter(key = "llm_non_sut_constants_per_class_max", group = "LLM",
            description = "Maximum constants accepted per dependency class in non-SUT enrichment")
    @IntValue(min = 1)
    public static int LLM_NON_SUT_CONSTANTS_PER_CLASS_MAX = 10;

    @Parameter(key = "llm_constant_vocabulary_max_per_type", group = "LLM",
            description = "Maximum number of existing SUT constants of each type "
                    + "(string/int/long/float/double) shown to the LLM in the constants-enrichment "
                    + "prompt as a 'domain vocabulary' hint. 0 disables the hint.")
    @IntValue(min = 0)
    public static int LLM_CONSTANT_VOCABULARY_MAX_PER_TYPE = 12;

    @Parameter(key = "llm_enrich_object_pool", group = "LLM",
            description = "Enable LLM enrichment of object pools")
    public static boolean LLM_ENRICH_OBJECT_POOL = false;

    @Parameter(key = "llm_object_pool_max_keys_per_sequence", group = "LLM",
            description = "Maximum number of distinct produced-type keys under "
                    + "which a single LLM-generated construction sequence is "
                    + "inserted into the object pool. Bounds pool pollution when "
                    + "a sequence happens to produce many side-effect types.")
    @IntValue(min = 1)
    public static int LLM_OBJECT_POOL_MAX_KEYS_PER_SEQUENCE = 5;

    @Parameter(key = "llm_fair_budget_accounting", group = "LLM",
            description = "If true, block on ALL pool enrichments (cast classes, "
                    + "constants, objects) before search starts and deduct the "
                    + "elapsed wall-clock time from the search budget so the four "
                    + "seeding strategies can be compared on equal footing. If "
                    + "false, only cast classes block (data enrichments run "
                    + "asynchronously during search) and no deduction is applied.")
    public static boolean LLM_FAIR_BUDGET_ACCOUNTING = true;

    @Parameter(key = "llm_enrich_cast_classes", group = "LLM",
            description = "Use LLM to propose additional cast-relevant classes for CastClassManager")
    public static boolean LLM_ENRICH_CAST_CLASSES = false;

    @Parameter(key = "llm_cast_class_max_suggestions", group = "LLM",
            description = "Maximum number of validated LLM cast-class suggestions to add")
    @IntValue(min = 1)
    public static int LLM_CAST_CLASS_MAX_SUGGESTIONS = 8;

    @Parameter(key = "llm_postprocessing_enabled", group = "LLM",
            description = "Run unified LLM post-processing after the minimization phase")
    public static boolean LLM_POSTPROCESSING_ENABLED = false;

    @Parameter(key = "llm_postprocessing_assertions", group = "LLM",
            description = "Allow unified LLM post-processing to propose assertions")
    public static boolean LLM_POSTPROCESSING_ASSERTIONS = true;

    @Parameter(key = "llm_postprocessing_test_names", group = "LLM",
            description = "Allow unified LLM post-processing to propose test names")
    public static boolean LLM_POSTPROCESSING_TEST_NAMES = true;

    @Parameter(key = "llm_postprocessing_variable_names", group = "LLM",
            description = "Allow unified LLM post-processing to propose variable names")
    public static boolean LLM_POSTPROCESSING_VARIABLE_NAMES = true;

    @Parameter(key = "llm_postprocessing_comments", group = "LLM",
            description = "Allow unified LLM post-processing to propose comments")
    public static boolean LLM_POSTPROCESSING_COMMENTS = true;

    @Parameter(key = "llm_postprocessing_section_breaks", group = "LLM",
            description = "Allow unified LLM post-processing to propose section boundaries")
    public static boolean LLM_POSTPROCESSING_SECTION_BREAKS = true;

    public enum LlmPostProcessingPromptVariant {
        P0_CURRENT,
        P1_GROUNDED_PRODUCTIVE,
        P2_CANDIDATE_SELECTION,
        P3_TYPED_TEMPLATES,
        P4_CANONICAL_CANDIDATES,
        P5_ACTION_RANKED_CANDIDATES,
        P6_RELATIONAL_OPPORTUNITIES,
        P7_STABILITY_LABELS,
        P8_COMPACT_OBSERVED_CALLS,
        P9_LITERAL_DISCIPLINE,
        P10_ASSERTABLE_TYPES_ONLY,
        P11_EXCEPTION_ADJACENT_ASSERTIONS,
        P12_ORACLE_CONTEXT_V2
    }

    /*
     * Historical replay/provenance value.  It is intentionally not a
     * @Parameter: fresh requests are always the evaluated P2 treatment.
     */
    public static LlmPostProcessingPromptVariant LLM_POSTPROCESSING_PROMPT_VARIANT =
            LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;

    @Parameter(key = "llm_postprocessing_max_assertions_per_test", group = "LLM",
            description = "Maximum unified LLM assertion proposals accepted per test")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_ASSERTIONS_PER_TEST = 5;

    @Parameter(key = "llm_postprocessing_max_comments_per_test", group = "LLM",
            description = "Maximum unified LLM comment proposals accepted per test")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_COMMENTS_PER_TEST = 3;

    @Parameter(key = "llm_postprocessing_max_comment_chars", group = "LLM",
            description = "Maximum characters per unified LLM comment")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_COMMENT_CHARS = 160;

    @Parameter(key = "llm_postprocessing_max_observation_chars", group = "LLM",
            description = "Maximum characters of observation context per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_OBSERVATION_CHARS = 12000;

    @Parameter(key = "llm_postprocessing_max_candidate_facts", group = "LLM",
            description = "Maximum ranked selectable candidate facts per unified LLM request (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CANDIDATE_FACTS = 40;

    @Parameter(key = "llm_postprocessing_max_candidate_chars", group = "LLM",
            description = "Maximum characters of ranked candidate-fact context per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CANDIDATE_CHARS = 6000;

    @Parameter(key = "llm_postprocessing_max_callable_chars", group = "LLM",
            description = "Maximum characters of callable-member context per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CALLABLE_CHARS = 6000;

    @Parameter(key = "llm_postprocessing_max_observed_expression_chars", group = "LLM",
            description = "Maximum characters of observed safe-expression context per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_OBSERVED_EXPRESSION_CHARS = 3000;

    @Parameter(key = "llm_postprocessing_max_relational_opportunities", group = "LLM",
            description = "Maximum grounded relational opportunities per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_RELATIONAL_OPPORTUNITIES = 12;

    @Parameter(key = "llm_postprocessing_max_relational_chars", group = "LLM",
            description = "Maximum characters of grounded relational-opportunity context per unified LLM request")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_RELATIONAL_CHARS = 3000;

    @Parameter(key = "llm_postprocessing_max_collection_elements", group = "LLM",
            description = "Maximum collection or array elements summarized in unified LLM observations")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_COLLECTION_ELEMENTS = 10;

    @Parameter(key = "llm_postprocessing_assertion_repair_attempts", group = "LLM",
            description = "Maximum targeted assertion repair requests per test")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_ASSERTION_REPAIR_ATTEMPTS = 1;

    public enum LlmPostProcessingRepairPolicy {
        NONE,
        TARGETED_ONE,
        BATCHED
    }

    @Parameter(key = "llm_postprocessing_repair_policy", group = "LLM",
            description = "Assertion repair policy: none, one targeted rejection, or the current batch")
    public static LlmPostProcessingRepairPolicy LLM_POSTPROCESSING_REPAIR_POLICY =
            LlmPostProcessingRepairPolicy.TARGETED_ONE;

    @Parameter(key = "llm_postprocessing_max_tests", group = "LLM",
            description = "Maximum tests processed by unified LLM post-processing (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_TESTS = 0;

    @Parameter(key = "llm_postprocessing_max_total_statements", group = "LLM",
            description = "Maximum total statements processed by unified LLM post-processing (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_TOTAL_STATEMENTS = 0;

    @Parameter(key = "llm_postprocessing_max_calls", group = "LLM",
            description = "Maximum LLM calls issued by unified LLM post-processing (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CALLS = 40;

    @Parameter(key = "llm_postprocessing_timeout", group = "LLM",
            description = "Maximum seconds spent in unified LLM post-processing (0 = unlimited)")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_TIMEOUT = 120;

    @Parameter(key = "llm_postprocessing_max_chain_depth", group = "LLM",
            description = "Maximum expression member-chain depth accepted from unified LLM post-processing")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CHAIN_DEPTH = 4;

    public enum LlmPostProcessingCallablePolicy {
        CURATED_ONLY, INSPECTORS_ONLY, PURE_BOUNDED
    }

    @Parameter(key = "llm_postprocessing_callable_policy", group = "LLM",
            description = "Callable-member policy for unified LLM assertion expressions")
    public static LlmPostProcessingCallablePolicy LLM_POSTPROCESSING_CALLABLE_POLICY =
            LlmPostProcessingCallablePolicy.PURE_BOUNDED;

    @Parameter(key = "llm_postprocessing_allow_chained_calls", group = "LLM",
            description = "Allow unified LLM assertion expressions to call explicitly allowlisted method chains")
    public static boolean LLM_POSTPROCESSING_ALLOW_CHAINED_CALLS = true;

    @Parameter(key = "llm_postprocessing_max_callable_args", group = "LLM",
            description = "Maximum arity for pure bounded callable methods in unified LLM assertion expressions")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CALLABLE_ARGS = 2;

    @Parameter(key = "llm_postprocessing_max_callable_members_per_type", group = "LLM",
            description = "Maximum pure bounded callable methods advertised per receiver/type")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CALLABLE_MEMBERS_PER_TYPE = 20;

    @Parameter(key = "llm_postprocessing_max_callable_types_per_test", group = "LLM",
            description = "Maximum chain-return receiver types advertised per unified LLM post-processing test")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CALLABLE_TYPES_PER_TEST = 30;

    @Parameter(key = "llm_postprocessing_limited_max_tests", group = "LLM",
            description = "Maximum tests processed when incomplete minimization policy is LIMITED")
    @IntValue(min = 1)
    public static int LLM_POSTPROCESSING_LIMITED_MAX_TESTS = 20;

    @Parameter(key = "llm_postprocessing_limited_max_total_statements", group = "LLM",
            description = "Maximum total statements processed when incomplete minimization policy is LIMITED")
    @IntValue(min = 1)
    public static int LLM_POSTPROCESSING_LIMITED_MAX_TOTAL_STATEMENTS = 400;

    @Parameter(key = "llm_postprocessing_limited_max_calls", group = "LLM",
            description = "Maximum LLM calls when incomplete minimization policy is LIMITED")
    @IntValue(min = 1)
    public static int LLM_POSTPROCESSING_LIMITED_MAX_CALLS = 40;

    @Parameter(key = "llm_postprocessing_pure_static_allowlist", group = "LLM",
            description = "Comma-separated pure static methods with full JVM descriptors; wildcards are rejected")
    public static String LLM_POSTPROCESSING_PURE_STATIC_ALLOWLIST = "";

    @Parameter(key = "llm_postprocessing_allow_immutable_constructors", group = "LLM",
            description = "Allow constructors of purity-proven or allowlisted immutable types in assertion expressions")
    public static boolean LLM_POSTPROCESSING_ALLOW_IMMUTABLE_CONSTRUCTORS = true;

    @Parameter(key = "llm_postprocessing_immutable_types", group = "LLM",
            description = "Comma-separated immutable types appended to the built-in unified LLM immutable type list")
    public static String LLM_POSTPROCESSING_IMMUTABLE_TYPES = "";

    @Parameter(key = "llm_postprocessing_max_expression_chars", group = "LLM",
            description = "Maximum characters per unified LLM assertion expression")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_EXPRESSION_CHARS = 500;

    @Parameter(key = "llm_postprocessing_max_expression_nodes", group = "LLM",
            description = "Maximum AST nodes per unified LLM assertion expression")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_EXPRESSION_NODES = 64;

    @Parameter(key = "llm_postprocessing_max_constructed_array_elements", group = "LLM",
            description = "Maximum elements in constructed array expressions proposed by unified LLM post-processing")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_CONSTRUCTED_ARRAY_ELEMENTS = 16;

    @Parameter(key = "llm_postprocessing_max_literal_chars", group = "LLM",
            description = "Maximum literal characters in unified LLM assertion expressions")
    @IntValue(min = 0)
    public static int LLM_POSTPROCESSING_MAX_LITERAL_CHARS = 200;

    @Parameter(key = "llm_postprocessing_assertion_eval_timeout_ms", group = "LLM",
            description = "Per-candidate timeout for evaluating unified LLM assertions")
    @IntValue(min = 1)
    public static int LLM_POSTPROCESSING_ASSERTION_EVAL_TIMEOUT_MS = 2000;

    @Parameter(key = "llm_postprocessing_assertion_compile_timeout_ms", group = "LLM",
            description = "Per-candidate timeout for compiling unified LLM assertions")
    @IntValue(min = 1)
    public static int LLM_POSTPROCESSING_ASSERTION_COMPILE_TIMEOUT_MS = 10000;

    public enum LlmPostProcessingOnIncompleteMinimization {
        SKIP, LIMITED, FULL
    }

    @Parameter(key = "llm_postprocessing_on_incomplete_minimization", group = "LLM",
            description = "Unified LLM post-processing policy when minimization is incomplete")
    public static LlmPostProcessingOnIncompleteMinimization LLM_POSTPROCESSING_ON_INCOMPLETE_MINIMIZATION =
            LlmPostProcessingOnIncompleteMinimization.SKIP;

    public enum LlmPostProcessingAssertionFallback {
        NONE, ON_INFRASTRUCTURE_FAILURE, ON_NO_ACCEPTED_ASSERTIONS
    }

    @Parameter(key = "llm_postprocessing_assertion_fallback", group = "LLM",
            description = "When unified LLM assertion post-processing falls back to trace-based assertions")
    public static LlmPostProcessingAssertionFallback LLM_POSTPROCESSING_ASSERTION_FALLBACK =
            LlmPostProcessingAssertionFallback.NONE;

    public enum LlmPostProcessingAssertionFallbackStrategy {
        ALL, MUTATION
    }

    @Parameter(key = "llm_postprocessing_assertion_fallback_strategy", group = "LLM",
            description = "Trace-based assertion generator used for unified LLM assertion fallback")
    public static LlmPostProcessingAssertionFallbackStrategy LLM_POSTPROCESSING_ASSERTION_FALLBACK_STRATEGY =
            LlmPostProcessingAssertionFallbackStrategy.ALL;

    public enum LlmPostProcessingScope {
        ALL_TESTS, ASSERTION_ELIGIBLE_TESTS
    }

    @Parameter(key = "llm_postprocessing_scope", group = "LLM",
            description = "Which tests receive unified LLM post-processing requests")
    public static LlmPostProcessingScope LLM_POSTPROCESSING_SCOPE = LlmPostProcessingScope.ALL_TESTS;

    @Parameter(key = "llm_repair_attempts", group = "LLM",
            description = "Maximum repair attempts for malformed LLM output")
    @IntValue(min = 0)
    public static int LLM_REPAIR_ATTEMPTS = 4;

    @Parameter(key = "llm_include_dependency_code_on_repair", group = "LLM",
            description = "Inline decompiled/bytecode excerpts of non-CUT classes whose code triggered a repair-blocking exception")
    public static boolean LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = true;

    @Parameter(key = "llm_repair_hints_enabled", group = "LLM",
            description = "Enable generic, error-triggered repair hints in addition to SUT-specific diagnostics")
    public static boolean LLM_REPAIR_HINTS_ENABLED = true;

    @Parameter(key = "llm_repair_hints_always_on", group = "LLM",
            description = "Include a small always-on baseline of generic repair hints in every repair prompt")
    public static boolean LLM_REPAIR_HINTS_ALWAYS_ON = true;

    @Parameter(key = "llm_repair_hints_max_per_attempt", group = "LLM",
            description = "Maximum number of generic repair hints injected per repair attempt")
    @IntValue(min = 0)
    public static int LLM_REPAIR_HINTS_MAX_PER_ATTEMPT = 6;

    @Parameter(key = "llm_repair_hints_cooldown_attempts", group = "LLM",
            description = "Minimum number of repair attempts before repeating the same generic hint")
    @IntValue(min = 0)
    public static int LLM_REPAIR_HINTS_COOLDOWN_ATTEMPTS = 1;

    @Parameter(key = "llm_dependency_code_max_chars", group = "LLM",
            description = "Maximum characters of dependency code to inline per repair turn")
    @IntValue(min = 0)
    public static int LLM_DEPENDENCY_CODE_MAX_CHARS = 4000;

    @Parameter(key = "llm_dependency_code_max_classes", group = "LLM",
            description = "Hard cap on distinct dependency classes whose code may be inlined per repair turn")
    @IntValue(min = 0)
    public static int LLM_DEPENDENCY_CODE_MAX_CLASSES = 1;

    @Parameter(key = "llm_enable_truncation_recovery", group = "LLM",
            description = "Attempt to salvage truncated Java test output by trimming incomplete members")
    public static boolean LLM_ENABLE_TRUNCATION_RECOVERY = true;

    @Parameter(key = "llm_expand_cluster_on_demand", group = "LLM",
            description = "Expand test cluster for resolvable classpath symbols referenced by LLM output")
    public static boolean LLM_EXPAND_CLUSTER_ON_DEMAND = true;

    @Parameter(key = "llm_max_calls", group = "LLM",
            description = "Maximum number of LLM calls per run (0 means unlimited)")
    @IntValue(min = 0)
    public static int LLM_MAX_CALLS = 0;

    @Parameter(key = "llm_trace_enabled", group = "LLM",
            description = "Enable reproducibility traces for LLM interactions")
    public static boolean LLM_TRACE_ENABLED = false;

    @Parameter(key = "llm_trace_dir", group = "LLM",
            description = "Directory for LLM trace artifacts")
    public static String LLM_TRACE_DIR = "";

    public enum LlmSuiteInjectionPolicy {
        /** Build a new TestSuiteChromosome from LLM-generated tests and compete it. */
        NEW_SUITE,
        /** Merge LLM-generated tests into existing (e.g., worst-ranked) suites. */
        MERGE_INTO_EXISTING
    }

    @Parameter(key = "llm_suite_injection_policy", group = "LLM",
            description = "How LLM-generated tests are injected into WholeSuite populations: "
                    + "NEW_SUITE creates a new suite from LLM tests; "
                    + "MERGE_INTO_EXISTING merges them into the worst existing suite")
    public static LlmSuiteInjectionPolicy LLM_SUITE_INJECTION_POLICY =
            LlmSuiteInjectionPolicy.NEW_SUITE;

    @Parameter(key = "llm_operator_enabled", group = "LLM",
            description = "Enable LLM-based semantic mutation and crossover operators in MOSA")
    public static boolean LLM_OPERATOR_ENABLED = false;

    @Parameter(key = "llm_mutation_probability", group = "LLM",
            description = "Probability of applying LLM semantic mutation instead of standard mutation (0.0-1.0)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LLM_MUTATION_PROBABILITY = 0.1;

    @Parameter(key = "llm_crossover_probability", group = "LLM",
            description = "Probability of applying LLM semantic crossover instead of standard crossover (0.0-1.0)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double LLM_CROSSOVER_PROBABILITY = 0.1;

    @Parameter(key = "llm_operator_max_attempts", group = "LLM",
            description = "Maximum LLM query attempts per operator invocation before falling back to standard operator")
    @IntValue(min = 1)
    public static int LLM_OPERATOR_MAX_ATTEMPTS = 2;

    // ---------------------------------------------------------------
    // Speciation / Diversity (LLM-independent)

    public enum SpeciationMetric {
        TRACE_BRANCH_JACCARD,
        TRACE_LINE_JACCARD,
        GOAL_JACCARD,
        METHOD_CALL_JACCARD,
        HYBRID
    }

    @Parameter(key = "speciation_enabled", group = "Speciation",
            description = "Enable species-based diversity control in MOSA survival selection")
    public static boolean SPECIATION_ENABLED = false;

    @Parameter(key = "speciation_metric", group = "Speciation",
            description = "Distance metric for speciation: TRACE_BRANCH_JACCARD (default), TRACE_LINE_JACCARD, "
                    + "GOAL_JACCARD, METHOD_CALL_JACCARD, HYBRID")
    public static SpeciationMetric SPECIATION_METRIC = SpeciationMetric.TRACE_BRANCH_JACCARD;

    @Parameter(key = "speciation_threshold", group = "Speciation",
            description = "Jaccard distance threshold for species membership (0.0-1.0); "
                    + "individuals within this distance of a species leader belong to that species")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SPECIATION_THRESHOLD = 0.3;

    @Parameter(key = "species_survival_cap", group = "Speciation",
            description = "Maximum fraction of survivor slots any single species may occupy (0.0-1.0; 1.0 = no cap)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SPECIES_SURVIVAL_CAP = 0.5;

    @Parameter(key = "species_min_survivors_per_species", group = "Speciation",
            description = "Minimum non-front0 survivors reserved per species when feasible")
    @IntValue(min = 0)
    public static int SPECIES_MIN_SURVIVORS_PER_SPECIES = 1;

    @Parameter(key = "species_newborn_protection_generations", group = "Speciation",
            description = "Number of generations a newly created species receives extra survival protection")
    @IntValue(min = 0)
    public static int SPECIES_NEWBORN_PROTECTION_GENERATIONS = 5;

    @Parameter(key = "species_incubator_enabled", group = "Speciation",
            description = "If true, route fresh injected individuals through temporary incubator species")
    public static boolean SPECIES_INCUBATOR_ENABLED = false;

    @Parameter(key = "species_incubator_generations", group = "Speciation",
            description = "Number of generations injected individuals remain eligible for incubator protection")
    @IntValue(min = 0)
    public static int SPECIES_INCUBATOR_GENERATIONS = 5;

    @Parameter(key = "species_incubator_quota_initial", group = "Speciation",
            description = "Initial minimum survivors reserved for an incubator species (before decay)")
    @IntValue(min = 0)
    public static int SPECIES_INCUBATOR_QUOTA_INITIAL = 2;

    @Parameter(key = "species_incubator_quota_min", group = "Speciation",
            description = "Minimum survivors reserved for an incubator species after decay")
    @IntValue(min = 0)
    public static int SPECIES_INCUBATOR_QUOTA_MIN = 1;

    @Parameter(key = "species_incubator_quota_decay", group = "Speciation",
            description = "Per-generation decay factor for incubator quota (0.0-1.0)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SPECIES_INCUBATOR_QUOTA_DECAY = 0.6;

    @Parameter(key = "species_incubator_only_llm_stagnation", group = "Speciation",
            description = "If true, incubator protection applies only to LLM stagnation injections")
    public static boolean SPECIES_INCUBATOR_ONLY_LLM_STAGNATION = true;

    @Parameter(key = "species_fitness_sharing_enabled", group = "Speciation",
            description = "If true, apply species-density fitness sharing in crowding tie-breaking")
    public static boolean SPECIES_FITNESS_SHARING_ENABLED = true;

    @Parameter(key = "species_balance_parent_selection", group = "Speciation",
            description = "If true, balance parent selection across species to promote diversity")
    public static boolean SPECIES_BALANCE_PARENT_SELECTION = false;

    @Parameter(key = "species_restrict_mating", group = "Speciation",
            description = "If true, restrict crossover pairing so the second parent is selected "
                    + "from the same species as the first parent")
    public static boolean SPECIES_RESTRICT_MATING = false;

    @Parameter(key = "species_timeline_enabled", group = "Speciation",
            description = "If true, emit per-generation species count as a timeline runtime variable")
    public static boolean SPECIES_TIMELINE_ENABLED = true;

    @Parameter(key = "species_track_when_speciation_disabled", group = "Speciation",
            description = "If true, compute species assignment and emit species timelines even when "
                    + "speciation_enabled is false (tracking only; does not affect survival or mating)")
    public static boolean SPECIES_TRACK_WHEN_SPECIATION_DISABLED = false;

    @Parameter(key = "species_largest_share_timeline_enabled", group = "Speciation",
            description = "If true, emit per-generation largest species share as a timeline runtime variable")
    public static boolean SPECIES_LARGEST_SHARE_TIMELINE_ENABLED = false;

    @Parameter(key = "species_stable_ids", group = "Speciation",
            description = "If true, use leader-carry-over assignment so species IDs persist across generations. "
                    + "Required for the population species timeline; otherwise IDs are reallocated per generation.")
    public static boolean SPECIES_STABLE_IDS = false;

    @Parameter(key = "species_dormant_generations", group = "Speciation",
            description = "When SPECIES_STABLE_IDS is enabled, a species ID whose membership drops to zero is "
                    + "retained for this many generations so a re-emerging cluster keeps the same ID. "
                    + "Set to 0 to retire IDs immediately on emptying.")
    @IntValue(min = 0)
    public static int SPECIES_DORMANT_GENERATIONS = 5;

    @Parameter(key = "species_population_timeline_enabled", group = "Speciation",
            description = "If true, write per-generation, per-individual species and rank assignments to "
                    + "population_species_timeline_<TARGET_CLASS>.csv under REPORT_DIR. Implies SPECIES_STABLE_IDS.")
    public static boolean SPECIES_POPULATION_TIMELINE_ENABLED = false;

    @Parameter(key = "speciation_hybrid_phenotypic_weight", group = "Speciation",
            description = "Weight for phenotypic component in HYBRID speciation metric (0.0-1.0)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SPECIATION_HYBRID_PHENOTYPIC_WEIGHT = 0.7;

    @Parameter(key = "speciation_empty_profile_distance", group = "Speciation",
            description = "Distance to use when both compared speciation profiles are empty "
                    + "(0.0 groups empty profiles together, 1.0 forces them apart)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SPECIATION_EMPTY_PROFILE_DISTANCE = 0.0;

    // ---- Diversity tracking (Phase 8) ----

    @Parameter(key = "diversity_sample_size", group = "Speciation",
            description = "Maximum number of pairs to sample for diversity computation (0 = all pairs)")
    @IntValue(min = 0)
    public static int DIVERSITY_SAMPLE_SIZE = 0;

    @Parameter(key = "stf_enabled", group = "Speciation",
            description = "Enable State Transition Frequency (STF) distance for diversity tracking "
                    + "(optional, not default)")
    public static boolean STF_ENABLED = false;

    @Parameter(key = "stf_jaccard_weight", group = "Speciation",
            description = "Weight of Jaccard component when STF is enabled in hybrid mode "
                    + "(0.0 = pure STF, 1.0 = pure Jaccard)")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double STF_JACCARD_WEIGHT = 0.0;

    // ---- Search Process Visualization (Phase 9) ----

    @Parameter(key = "objective_coverage_timeline_enabled", group = "Visualization",
            description = "If true, write per-generation best (minimum) fitness per coverage "
                    + "goal to objective_coverage_timeline_<TARGET_CLASS>.csv under REPORT_DIR, "
                    + "plus a one-time objective_index_<TARGET_CLASS>.csv mapping goal IDs to "
                    + "class/method/description. Used to render per-goal/per-method coverage "
                    + "heatmaps.")
    public static boolean OBJECTIVE_COVERAGE_TIMELINE_ENABLED = false;

    @Parameter(key = "objective_coverage_timeline_sample_interval", group = "Visualization",
            description = "Record an objective coverage timeline row only every N generations "
                    + "(1 = every generation). Generation 0 is always recorded. Increase for "
                    + "classes with very many goals to keep the CSV small.")
    @IntValue(min = 1)
    public static int OBJECTIVE_COVERAGE_TIMELINE_SAMPLE_INTERVAL = 1;

    @Parameter(key = "fitness_space_snapshot_enabled", group = "Visualization",
            description = "If true, periodically write the fitness vector (one value per coverage "
                    + "goal, over the full original goal set) of each population individual, with "
                    + "its Pareto rank, to fitness_space_snapshots_<TARGET_CLASS>.csv under "
                    + "REPORT_DIR. Goals already covered (in the archive) score 0.0 and unreached "
                    + "goals score 1.0, so the coordinate system is stationary across generations. "
                    + "Used to render PCA trajectory plots of the population in fitness space.")
    public static boolean FITNESS_SPACE_SNAPSHOT_ENABLED = false;

    @Parameter(key = "fitness_space_snapshot_interval", group = "Visualization",
            description = "Record a fitness space snapshot only every N generations (1 = every "
                    + "generation). Generation 0 is always recorded.")
    @IntValue(min = 1)
    public static int FITNESS_SPACE_SNAPSHOT_INTERVAL = 10;

    @Parameter(key = "fitness_space_snapshot_max_individuals", group = "Visualization",
            description = "Maximum number of population individuals to record per snapshot "
                    + "generation. If the population is larger, only the first N (in population "
                    + "order) are recorded.")
    @IntValue(min = 1)
    public static int FITNESS_SPACE_SNAPSHOT_MAX_INDIVIDUALS = 50;

    @Parameter(key = "population_shape_snapshot_enabled", group = "Visualization",
            description = "If true, periodically write each population individual's covered-branch "
                    + "set plus species/rank/fitness metadata to "
                    + "population_shape_<TARGET_CLASS>.csv under REPORT_DIR. Used to render "
                    + "population-shape (joint Jaccard/MDS) small-multiple grids.")
    public static boolean POPULATION_SHAPE_SNAPSHOT_ENABLED = false;

    @Parameter(key = "population_shape_snapshot_interval", group = "Visualization",
            description = "Record a population shape snapshot only every N generations (1 = every "
                    + "generation). Generation 0 is always recorded.")
    @IntValue(min = 1)
    public static int POPULATION_SHAPE_SNAPSHOT_INTERVAL = 5;

    @Parameter(key = "population_shape_max_individuals", group = "Visualization",
            description = "Maximum number of individuals recorded per population shape snapshot. "
                    + "Safety bound only; keep above the population size so the whole population "
                    + "is recorded.")
    @IntValue(min = 1)
    public static int POPULATION_SHAPE_MAX_INDIVIDUALS = 100;

    @Parameter(key = "problem_card_timeline_interval", group = "Visualization",
            description = "Extract problem cards every N generations and record their "
                    + "per-type distribution in the population species timeline sidecar "
                    + "(0 = disabled). Requires species_population_timeline_enabled. "
                    + "Extraction is skipped when coverage and population are unchanged "
                    + "since the last sample; a typical enabled value is 5.")
    @IntValue(min = 0)
    public static int PROBLEM_CARD_TIMELINE_INTERVAL = 0;

    @Parameter(key = "problem_card_log_enabled", group = "Visualization",
            description = "Log selected problem-card instances (with their diagnosed goals) "
                    + "and the goals covered by card-informed LLM injections to "
                    + "problem_card_log_<TARGET_CLASS>.csv under REPORT_DIR, for offline "
                    + "card-resolution analysis (scripts/analyze_card_resolution.py).")
    public static boolean PROBLEM_CARD_LOG_ENABLED = false;

    // ---- Operator Disruption Analysis (Phase 8b) ----

    @Parameter(key = "llm_operator_disruption_analysis_enabled", group = "LLM",
            description = "Enable per-operator disruption event recording for standard vs semantic operators")
    public static boolean LLM_OPERATOR_DISRUPTION_ANALYSIS_ENABLED = false;

    @Parameter(key = "llm_operator_disruption_evaluate_isolated", group = "LLM",
            description = "When disruption analysis is enabled, run isolated intermediate fitness probes "
                    + "post-crossover (pre-mutation) and post-mutation in MOSA/DynaMOSA")
    public static boolean LLM_OPERATOR_DISRUPTION_EVALUATE_ISOLATED = false;

    @Parameter(key = "llm_operator_disruption_output_dir", group = "LLM",
            description = "Directory for disruption sidecar artifacts; empty resolves under REPORT_DIR")
    public static String LLM_OPERATOR_DISRUPTION_OUTPUT_DIR = "";

    // ---------------------------------------------------------------
    // Sandbox
    @Parameter(key = "sandbox", group = "Sandbox", description = "Execute tests in a sandbox environment")
    public static boolean SANDBOX = true;

    @Parameter(key = "sandbox_mode", group = "Sandbox", description = "Mode in which the sandbox is applied")
    public static Sandbox.SandboxMode SANDBOX_MODE = Sandbox.SandboxMode.RECOMMENDED;

    @Parameter(key = "filter_sandbox_tests", group = "Sandbox", description = "Drop tests that require the sandbox")
    public static boolean FILTER_SANDBOX_TESTS = false;

    @Parameter(key = "virtual_fs", group = "Sandbox",
            description = "Usa a virtual file system for all File I/O operations")
    public static boolean VIRTUAL_FS = true;


    @Parameter(key = "virtual_net", group = "Sandbox",
            description = "Usa a virtual network for all TCP/UDP communications")
    public static boolean VIRTUAL_NET = true;

    @Parameter(key = "use_separate_classloader", group = "Sandbox",
            description = "Usa a separate classloader in the final test cases")
    public static boolean USE_SEPARATE_CLASSLOADER = false;


    // ---------------------------------------------------------------
    // Experimental


    @Deprecated
    @Parameter(key = "jee", description = "Support for JEE")
    public static boolean JEE = false;

    @Deprecated
    @Parameter(key = "handle_servlets", description = "Special treatment of JEE Servlets")
    public static boolean HANDLE_SERVLETS = false;

    @Parameter(key = "cluster_recursion",
            description = "The maximum level of recursion when calculating the dependencies in the test cluster")
    public static int CLUSTER_RECURSION = 10;

    @Parameter(key = "sort_calls",
            description = "Sort SUT methods by remaining coverage to bias search towards uncovered parts")
    public static boolean SORT_CALLS = false;

    @Parameter(key = "sort_objects",
            description = "Sort objects in a test to make calls on objects closer to SUT more likely")
    public static boolean SORT_OBJECTS = false;

    @Parameter(key = "inheritance_file", description = "Cached version of inheritance tree")
    public static String INHERITANCE_FILE = "";

    @Parameter(key = "branch_eval", description = "Jeremy's branch evaluation")
    public static boolean BRANCH_EVAL = false;

    @Parameter(key = "branch_statement", description = "Require statement coverage for branch coverage")
    public static boolean BRANCH_STATEMENT = false;

    @Parameter(key = "remote_testing", description = "Include remote calls")
    public static boolean REMOTE_TESTING = false;

    @Parameter(key = "cpu_timeout", description = "Measure timeouts on CPU time, not global time")
    public static boolean CPU_TIMEOUT = false;

    @Parameter(key = "log_timeout", description = "Produce output each time a test times out")
    public static boolean LOG_TIMEOUT = false;

    @Parameter(key = "call_probability",
            description = "Probability to reuse an existing test case, if it produces a required object")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double CALL_PROBABILITY = 0.0;

    @Parameter(key = "usage_models", description = "Names of usage model files")
    public static String USAGE_MODELS = "";

    @Parameter(key = "usage_rate", description = "Probability with which to use transitions out of the OUM")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double USAGE_RATE = 0.5;

    @Parameter(key = "instrumentation_skip_debug",
            description = "Skip debug information in bytecode instrumentation (needed for "
                    + "compatibility with classes transformed by Emma code instrumentation due to "
                    + "an ASM bug)")
    public static boolean INSTRUMENTATION_SKIP_DEBUG = false;

    @Parameter(key = "instrument_parent", description = "Also count coverage goals in superclasses")
    public static boolean INSTRUMENT_PARENT = false;

    @Parameter(key = "instrument_context", description = "Also instrument methods called from the SUT")
    public static boolean INSTRUMENT_CONTEXT = false;

    @Parameter(key = "instrument_method_calls", description = "Instrument methods calls")
    public static boolean INSTRUMENT_METHOD_CALLS = false;

    @Parameter(key = "instrument_libraries", description = "Instrument the libraries used by the project under test")
    public static boolean INSTRUMENT_LIBRARIES = false;

    @Parameter(key = "break_on_exception", description = "Stop test execution if exception occurrs")
    public static boolean BREAK_ON_EXCEPTION = true;

    @Parameter(key = "handle_static_fields", description = "Include methods that update required static fields")
    public static boolean HANDLE_STATIC_FIELDS = true;

    public enum TestFactory {
        RANDOM, ALLMETHODS, TOURNAMENT, JUNIT, PARSED_JUNIT, ARCHIVE, SERIALIZATION,
        SEED_BEST_INDIVIDUAL, SEED_RANDOM_INDIVIDUAL,
        SEED_BEST_AND_RANDOM_INDIVIDUAL, SEED_BEST_INDIVIDUAL_METHOD,
        SEED_RANDOM_INDIVIDUAL_METHOD, SEED_MUTATED_BEST_INDIVIDUAL, LLM
    }

    @Parameter(key = "test_archive", description = "Use an archive of covered goals during test generation")
    public static boolean TEST_ARCHIVE = true;

    @Parameter(key = "test_factory", description = "Which factory creates tests "
            + "(LLM wraps the configured fallback factory)")
    public static TestFactory TEST_FACTORY = TestFactory.ARCHIVE;

    public enum ArchiveType {
        COVERAGE, MIO
    }

    /**
     * Constant <code>ARCHIVE_TYPE=COVERAGE</code>.
     */
    @Parameter(key = "archive_type", description = "Which type of archive to keep track of covered goals during search")
    public static ArchiveType ARCHIVE_TYPE = ArchiveType.COVERAGE;

    @Parameter(key = "seed_file", description = "File storing TestGenerationResult or GeneticAlgorithm")
    public static String SEED_FILE = "";

    @Parameter(key = "seed_probability", description = "Probability to seed on methods with randomness involved")
    public static double SEED_PROBABILITY = 0.1;

    @Parameter(key = "selected_junit",
            description = "List of fully qualified class names (separated by ':') indicating which "
                    + "JUnit test suites the user has selected (e.g., for seeding)")
    public static String SELECTED_JUNIT = null;

    @Parameter(key = "junit_strict", description = "Only include test files containing the target classname")
    public static boolean JUNIT_STRICT = false;

    @Parameter(key = "seed_clone", description = "Probability with which existing individuals are cloned")
    @DoubleValue(min = 0.0, max = 1.0)
    public static double SEED_CLONE = 0.2;

    @Parameter(key = "seed_mutations", description = "Number of mutations applied to a cloned individual")
    public static int SEED_MUTATIONS = 3;

    @Parameter(key = "seed_test_source_dir",
            description = "Root directory to find test source files for PARSED_JUNIT seeding")
    public static String SEED_TEST_SOURCE_DIR = null;

    @Parameter(key = "seed_dir", group = "Output", description = "Directory name where the best chromosomes are saved")
    public static String SEED_DIR = "evosuite-seeds";

    @Parameter(key = "concolic_mutation", description = "Deprecated. Probability of using concolic mutation operator")
    @DoubleValue(min = 0.0, max = 1.0)
    @Deprecated
    public static double CONCOLIC_MUTATION = 0.0;

    @Parameter(key = "constraint_solution_attempts",
            description = "Number of attempts to solve constraints related to one code branch")
    public static int CONSTRAINT_SOLUTION_ATTEMPTS = 3;

    @Parameter(key = "testability_transformation", description = "Apply testability transformation (Yanchuan)")
    public static boolean TESTABILITY_TRANSFORMATION = false;

    @Parameter(key = "TT_stack", description = "Maximum stack depth for testability transformation")
    public static int TT_stack = 10;

    @Parameter(key = "TT", description = "Testability transformation")
    public static boolean TT = false;

    public enum TransformationScope {
        TARGET, PREFIX, ALL
    }

    @Parameter(key = "tt_scope", description = "Testability transformation")
    public static TransformationScope TT_SCOPE = TransformationScope.ALL;

    // ---------------------------------------------------------------
    // Contracts / Asserts:
    @Parameter(key = "check_contracts", description = "Check contracts during test execution")
    public static boolean CHECK_CONTRACTS = false;

    @Parameter(key = "check_contracts_end", description = "Check contracts only once per test")
    public static boolean CHECK_CONTRACTS_END = false;

    @Parameter(key = "catch_undeclared_exceptions", description = "Use try/catch block for undeclared exceptions")
    public static boolean CATCH_UNDECLARED_EXCEPTIONS = true;

    @Parameter(key = "junit_theories", description = "Check JUnit theories as contracts")
    public static String JUNIT_THEORIES = "";


    @Parameter(key = "exception_branches",
            description = "Instrument code with explicit branches for exceptional control flow")
    public static boolean EXCEPTION_BRANCHES = false;

    @Parameter(key = "error_branches", description = "Instrument code with error checking branches")
    public static boolean ERROR_BRANCHES = false;

    public enum ErrorInstrumentation {
        ARRAY, CAST, DEQUE, DIVISIONBYZERO, LINKEDHASHSET, NPE, OVERFLOW, QUEUE, STACK, VECTOR, LIST,
        COLLECTIONCAPACITY
    }

    @Parameter(key = "error_instrumentation", description = "Which instrumentation to use for error checks")
    public static ErrorInstrumentation[] ERROR_INSTRUMENTATION = new ErrorInstrumentation[]{
            ErrorInstrumentation.ARRAY, ErrorInstrumentation.CAST, ErrorInstrumentation.DEQUE,
            ErrorInstrumentation.DIVISIONBYZERO, ErrorInstrumentation.LINKEDHASHSET,
            ErrorInstrumentation.NPE, ErrorInstrumentation.OVERFLOW, ErrorInstrumentation.QUEUE,
            ErrorInstrumentation.STACK, ErrorInstrumentation.VECTOR, ErrorInstrumentation.COLLECTIONCAPACITY};

    @Parameter(key = "enable_asserts_for_evosuite",
            description = "When running EvoSuite clients, for debugging purposes check its assserts")
    public static boolean ENABLE_ASSERTS_FOR_EVOSUITE = false;

    @Parameter(key = "enable_asserts_for_sut", description = "Check asserts in the SUT")
    public static boolean ENABLE_ASSERTS_FOR_SUT = true;

    // ---------------------------------------------------------------
    // Test Execution
    @Parameter(key = "timeout", group = "Test Execution",
            description = "Milliseconds allowed to execute the body of a test")
    public static int TIMEOUT = 3000;

    @Parameter(key = "timeout_reset", group = "Test Execution",
            description = "Milliseconds allowed to execute the static reset of a test")
    public static int TIMEOUT_RESET = 2000;


    @Parameter(key = "concolic_timeout", group = "Test Execution",
            description = "Milliseconds allowed per test during concolic execution")
    public static int CONCOLIC_TIMEOUT = 15000;

    @Parameter(key = "shutdown_timeout", group = "Test Execution",
            description = "Milliseconds grace time to shut down test cleanly")
    public static int SHUTDOWN_TIMEOUT = 1000;

    @Parameter(key = "mutation_timeouts", group = "Test Execution",
            description = "Number of timeouts before we consider a mutant killed")
    public static int MUTATION_TIMEOUTS = 3;

    @Parameter(key = "array_limit", group = "Test Execution",
            description = "Hard limit on array allocation in the code")
    public static int ARRAY_LIMIT = 1000000;

    @Parameter(key = "collection_capacity_limit", group = "Test Execution",
            description = "Hard limit on collection constructor capacity in the code")
    public static int COLLECTION_CAPACITY_LIMIT = 1000000;

    @Parameter(key = "map_capacity_limit", group = "Test Execution",
            description = "Hard limit on map constructor capacity in the code")
    public static int MAP_CAPACITY_LIMIT = 1000000;

    @Parameter(key = "allocation_sensitive_byte_input_limit", group = "Test Execution",
            description = "Maximum length of byte[]/InputStream inputs to constructors of"
                    + " classes that have been registered as allocation-sensitive at runtime")
    public static int ALLOCATION_SENSITIVE_BYTE_INPUT_LIMIT = 4096;

    @Parameter(key = "allocation_sensitive_string_input_limit", group = "Test Execution",
            description = "Maximum length of String inputs to constructors of classes that have"
                    + " been registered as allocation-sensitive at runtime")
    public static int ALLOCATION_SENSITIVE_STRING_INPUT_LIMIT = 256;

    @Parameter(key = "max_mutants", group = "Test Execution",
            description = "Maximum number of mutants to target at the same time")
    public static int MAX_MUTANTS = 100;

    @Parameter(key = "mutation_generations", group = "Test Execution",
            description = "Number of generations before changing the currently targeted mutants")
    public static int MUTATION_GENERATIONS = 10;

    @Parameter(key = "replace_calls", group = "Test Execution",
            description = "Replace nondeterministic calls and System.exit")
    public static boolean REPLACE_CALLS = true;

    @Parameter(key = "replace_system_in", group = "Test Execution",
            description = "Replace System.in with a smart stub/mock")
    public static boolean REPLACE_SYSTEM_IN = true;

    @Parameter(key = "replace_gui", group = "Test Execution",
            description = "Replace javax.swing with a smart stub/mock")
    public static boolean REPLACE_GUI = true;


    @Parameter(key = "max_started_threads", group = "Test Execution",
            description = "Max number of threads allowed to be started in each test")
    public static int MAX_STARTED_THREADS = RuntimeSettings.maxNumberOfThreads;

    @Parameter(key = "max_loop_iterations", group = "Test Execution",
            description = "Max number of iterations allowed per loop. A negative value means no check is done.")
    public static long MAX_LOOP_ITERATIONS = RuntimeSettings.maxNumberOfIterationsPerLoop;

    @Parameter(key = "max_mock_invocations_per_test", group = "Test Execution",
            description = "Maximum total invocations observed by functional mocks per test execution "
                    + "before aborting with TooManyResourcesException")
    @IntValue(min = 1)
    public static int MAX_MOCK_INVOCATIONS_PER_TEST = 10_000;

    // ---------------------------------------------------------------
    // Debugging

    @Parameter(key = "debug", group = "Debugging",
            description = "Enables debugging support in the client VM")
    public static boolean DEBUG = false;

    @Parameter(key = "profile", group = "Debugging",
            description = "Enables profiler support in the client VM")
    public static String PROFILE = "";

    @Parameter(key = "port", group = "Debugging",
            description = "Port on localhost, to which the client VM will listen for a remote "
                    + "debugger; defaults to 1044")
    @IntValue(min = 1024, max = 65535)
    public static int PORT = 1044;

    @Parameter(key = "jmc", group = "Debugging",
            description = "Experimental: activate Flight Recorder in spawn client process for "
                    + "Java Mission Control")
    public static boolean JMC = false;

    // ---------------------------------------------------------------
    // TODO: Fix description
    public enum AlternativeFitnessCalculationMode {
        SUM, MIN, MAX, AVG, SINGLE
    }

    /**
     * Constant <code>ALTERNATIVE_FITNESS_CALCULATION_MODE</code>.
     */
    @Parameter(key = "alternative_fitness_calculation_mode", description = "")
    public static AlternativeFitnessCalculationMode ALTERNATIVE_FITNESS_CALCULATION_MODE =
            AlternativeFitnessCalculationMode.SUM;

    @Parameter(key = "starve_by_fitness", description = "")
    public static boolean STARVE_BY_FITNESS = true;

    @Parameter(key = "enable_alternative_fitness_calculation", description = "")
    public static boolean ENABLE_ALTERNATIVE_FITNESS_CALCULATION = false;

    @Parameter(key = "enable_alternative_suite_fitness", description = "")
    public static boolean ENABLE_ALTERNATIVE_SUITE_FITNESS = false;

    @Parameter(key = "defuse_debug_mode", description = "")
    public static boolean DEFUSE_DEBUG_MODE = false;

    @Parameter(key = "defuse_aliases", description = "")
    public static boolean DEFUSE_ALIASES = true;

    @Parameter(key = "randomize_difficulty", description = "")
    public static boolean RANDOMIZE_DIFFICULTY = true;

    // ---------------------------------------------------------------
    // UI Test generation parameters
    @Parameter(key = "UI_BACKGROUND_COVERAGE_DELAY", group = "EXSYST",
            description = "How often to write out coverage information in the background (in ms). "
                    + "-1 to disable.")
    public static int UI_BACKGROUND_COVERAGE_DELAY = -1;

    // ---------------------------------------------------------------
    // Runtime parameters

    public enum Criterion {
        EXCEPTION, DEFUSE, ALLDEFS, BRANCH, CBRANCH, STRONGMUTATION, WEAKMUTATION,
        MUTATION, STATEMENT, RHO, AMBIGUITY, IBRANCH, READABILITY,
        ONLYBRANCH, ONLYMUTATION, METHODTRACE, METHOD, METHODNOEXCEPTION, LINE, ONLYLINE, OUTPUT, INPUT,
        TRYCATCH
    }

    @Parameter(key = "criterion", group = "Runtime",
            description = "Coverage criterion. Can define more than one criterion by using a ':' "
                    + "separated list")
    public static Criterion[] CRITERION = new Criterion[]{
            //these are basic criteria that should be always on by default
            Criterion.LINE, Criterion.BRANCH, Criterion.EXCEPTION, Criterion.WEAKMUTATION,
            Criterion.OUTPUT, Criterion.METHOD, Criterion.METHODNOEXCEPTION, Criterion.CBRANCH};


    /**
     * Cache target class.
     */
    private static Class<?> TARGET_CLASS_INSTANCE = null;

    @Parameter(key = "CP", group = "Runtime", description = "The classpath of the target classes")
    public static String CP = "";

    @Parameter(key = "CP_file_path", group = "Runtime",
            description = "Location of file where classpath is specified (in its first line). "
                    + "This is needed for operating systems like Windows where cannot have too "
                    + "long input parameters")
    public static String CP_FILE_PATH = null;


    @Parameter(key = "PROJECT_PREFIX", group = "Runtime", description = "Package name of target package")
    public static String PROJECT_PREFIX = "";

    @Parameter(key = "PROJECT_DIR", group = "Runtime", description = "Directory name of target package")
    public static String PROJECT_DIR = null;

    /**
     * Package name of target class (might be a subpackage).
     */
    public static String CLASS_PREFIX = "";

    /**
     * Sub-package name of target class.
     */
    public static String SUB_PREFIX = "";

    @Parameter(key = "TARGET_CLASS_PREFIX", group = "Runtime", description = "Prefix of classes we are trying to cover")
    public static String TARGET_CLASS_PREFIX = "";

    /**
     * Class under test.
     */
    @Parameter(key = "TARGET_CLASS", group = "Runtime", description = "Class under test")
    public static String TARGET_CLASS = "";

    /**
     * Method under test.
     */
    @Parameter(key = "target_method", group = "Runtime", description = "Method for which to generate tests")
    public static String TARGET_METHOD = "";

    /**
     * Method under test.
     */
    @Parameter(key = "target_method_prefix", group = "Runtime",
            description = "All methods matching prefix will be used for generating tests")
    public static String TARGET_METHOD_PREFIX = "";

    /**
     * Method under test.
     */
    @Parameter(key = "target_method_list", group = "Runtime",
            description = "A colon(:) separated list of methods for which to generate tests")
    public static String TARGET_METHOD_LIST = "";

    @Parameter(key = "hierarchy_data", group = "Runtime", description = "File in which hierarchy data is stored")
    public static String HIERARCHY_DATA = "hierarchy.xml";

    @Parameter(key = "connection_data", group = "Runtime", description = "File in which connection data is stored")
    public static String CONNECTION_DATA = "connection.xml";

    @Parameter(key = "exclude_ibranches_cut", group = "Runtime",
            description = "Exclude ibranches in the cut, to speed up ibranch as secondary criterion")
    public static boolean EXCLUDE_IBRANCHES_CUT = false;

    public enum Strategy {
        ONEBRANCH, EVOSUITE, RANDOM, RANDOM_FIXED, ENTBUG, MOSUITE, DSE, NOVELTY, MAP_ELITES, LLMSTRATEGY
    }

    @Parameter(key = "strategy", group = "Runtime", description = "Which mode to use")
    public static Strategy STRATEGY = Strategy.MOSUITE;

    @Parameter(key = "process_communication_port", group = "Runtime",
            description = "Port at which the communication with the external process is done")
    public static int PROCESS_COMMUNICATION_PORT = -1;

    @Parameter(key = "spawn_process_manager_port", group = "Runtime",
            description = "Port at which the spawn process manager (if any) is listening")
    public static Integer SPAWN_PROCESS_MANAGER_PORT = null;

    @Parameter(key = "stopping_port", group = "Runtime",
            description = "Port at which a stopping condition waits for interruption")
    public static int STOPPING_PORT = -1;

    @Parameter(key = "max_stalled_threads", group = "Runtime", description = "Number of stalled threads")
    public static int MAX_STALLED_THREADS = 10;

    @Parameter(key = "max_total_rotations", group = "Runtime",
            description = "Cumulative executor rotation limit before stopping search. "
                    + "Catches rapid timeout cycling where threads die quickly and evade the stalled thread check.")
    public static int MAX_TOTAL_ROTATIONS = 50;

    @Parameter(key = "rotation_cooldown_ms", group = "Runtime",
            description = "Minimum interval in milliseconds between executor rotations. "
                    + "Prevents GC thrashing from rapid timeout cycling.")
    public static long ROTATION_COOLDOWN_MS = 1000;

    @Parameter(key = "ignore_threads", group = "Runtime",
            description = "Do not attempt to kill threads matching this prefix")
    public static String[] IGNORE_THREADS = new String[]{};

    @Parameter(key = "min_free_mem", group = "Runtime", description = "Minimum amount of available memory")
    public static int MIN_FREE_MEM = 50 * 1000 * 1000;


    @Parameter(key = "client_on_thread", group = "Runtime",
            description = "Run client process on same JVM of master in separate thread. "
                    + "To be used only for debugging purposes")
    public static volatile boolean CLIENT_ON_THREAD = false;


    @Parameter(key = "is_running_a_system_test", group = "Runtime",
            description = "Specify that a system test is running. To be used only for debugging purposes")
    public static volatile boolean IS_RUNNING_A_SYSTEM_TEST = false;


    // ---------------------------------------------------------------
    // Seeding test cases

    @Parameter(key = "classpath", group = "Test Seeding",
            description = "The classpath needed to compile the seeding test case.")
    public static String[] CLASSPATH = new String[]{""};

    @Parameter(key = "sourcepath", group = "Test Seeding", description = "The path to the test case source.")
    public static String[] SOURCEPATH = new String[]{""};

    // ---------------------------------------------------------------
    // Eclipse Plug-in flag

    @Parameter(key = "eclipse_plugin", group = "Plugin",
            description = "Running plugin for experiments. Use EvoSuiteTest annotation and "
                    + "decorate generated tests with (checked = false).")
    public static boolean ECLIPSE_PLUGIN = false;

    // Added - fix for @NotNull annotations issue on evo mailing list

    @Parameter(key = "honour_data_annotations", group = "Runtime",
            description = "Allows EvoSuite to generate tests with or without honouring the "
                    + "parameter data annotations")
    public static boolean HONOUR_DATA_ANNOTATIONS = true;

    /**
     * Get all parameters that are available.
     *
     * @return a {@link java.util.Set} object.
     */
    public static Set<String> getParameters() {
        return parameterMap.keySet();
    }

    /**
     * Determine fields that are declared as parameters.
     */
    private static void reflectMap() {
        for (Field f : Properties.class.getFields()) {
            if (f.isAnnotationPresent(Parameter.class)) {
                Parameter p = f.getAnnotation(Parameter.class);
                parameterMap.put(p.key(), f);
                try {
                    defaultMap.put(f, f.get(null));
                } catch (Exception e) {
                    logger.error("Exception: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Initialize properties from property file or command line parameters.
     */
    private void initializeProperties() throws IllegalStateException {
        for (String parameter : parameterMap.keySet()) {
            try {
                String property = System.getProperty(parameter);
                if (property == null) {
                    property = properties.getProperty(parameter);
                }
                if (property != null) {
                    setValue(parameter, property);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Wrong parameter settings for '" + parameter + "': " + e.getMessage());
            }
        }
        if (POPULATION_LIMIT == PopulationLimit.STATEMENTS) {
            if (MAX_LENGTH < POPULATION) {
                MAX_LENGTH = POPULATION;
            }
        }
    }

    /**
     * Load and initialize a properties file from the default path.
     *
     * @param silent whether to suppress output
     */
    public void loadProperties(boolean silent) {
        loadPropertiesFile(System.getProperty(PROPERTIES_FILE,
                "evosuite-files/evosuite.properties"), silent);
        initializeProperties();
    }

    /**
     * Load and initialize a properties file from a given path.
     *
     * @param propertiesPath a {@link java.lang.String} object.
     * @param silent         whether to suppress output
     */
    public void loadProperties(String propertiesPath, boolean silent) {
        loadPropertiesFile(propertiesPath, silent);
        initializeProperties();
    }

    /**
     * Load a properties file.
     *
     * @param propertiesPath a {@link java.lang.String} object.
     * @param silent         whether to suppress output
     */
    public void loadPropertiesFile(String propertiesPath, boolean silent) {
        properties = new java.util.Properties();
        try {
            InputStream in = null;
            File propertiesFile = new File(propertiesPath);
            if (propertiesFile.exists()) {
                in = new FileInputStream(propertiesPath);
                properties.load(in);

                if (!silent) {
                    LoggingUtils.getEvoLogger().info(
                            "* Properties loaded from "
                                    + propertiesFile.getAbsolutePath());
                }
            } else {
                propertiesPath = "evosuite.properties";
                in = this.getClass().getClassLoader()
                        .getResourceAsStream(propertiesPath);
                if (in != null) {
                    properties.load(in);
                    if (!silent) {
                        LoggingUtils.getEvoLogger().info(
                                "* Properties loaded from "
                                        + this.getClass().getClassLoader()
                                        .getResource(propertiesPath)
                                        .getPath());
                    }
                }
                // logger.info("* Properties loaded from default configuration file.");
            }
        } catch (FileNotFoundException e) {
            logger.warn("- Error: Could not find configuration file "
                    + propertiesPath);
        } catch (IOException e) {
            logger.warn("- Error: Could not find configuration file "
                    + propertiesPath);
        } catch (Exception e) {
            logger.warn("- Error: Could not find configuration file "
                    + propertiesPath);
        }
    }

    /**
     * All fields representing values, inserted via reflection.
     */
    private static final Map<String, Field> parameterMap = new HashMap<>();

    /**
     * All fields representing values, inserted via reflection.
     */
    private static final Map<Field, Object> defaultMap = new HashMap<>();

    static {
        // need to do it once, to capture all the default values
        reflectMap();
    }

    /**
     * Keep track of which fields have been changed from their defaults during
     * loading.
     */
    private static final Set<String> changedFields = new HashSet<>();

    /**
     * Get class of parameter.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link java.lang.Class} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static Class<?> getType(String key) throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        return f.getType();
    }

    /**
     * Get description string of parameter.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static String getDescription(String key)
            throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        Parameter p = f.getAnnotation(Parameter.class);
        return p.description();
    }

    /**
     * Get group name of parameter.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static String getGroup(String key) throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        Parameter p = f.getAnnotation(Parameter.class);
        return p.group();
    }

    /**
     * Get integer boundaries.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link org.evosuite.Properties.IntValue} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static IntValue getIntLimits(String key)
            throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        return f.getAnnotation(IntValue.class);
    }

    /**
     * Get long boundaries.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link org.evosuite.Properties.LongValue} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static LongValue getLongLimits(String key)
            throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        return f.getAnnotation(LongValue.class);
    }

    /**
     * Get double boundaries.
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link org.evosuite.Properties.DoubleValue} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     */
    public static DoubleValue getDoubleLimits(String key)
            throws NoSuchParameterException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        return f.getAnnotation(DoubleValue.class);
    }

    /**
     * Get an integer parameter value.
     *
     * @param key a {@link java.lang.String} object.
     * @return a int.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public static int getIntegerValue(String key)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        return parameterMap.get(key).getInt(null);
    }

    /**
     * Get an integer parameter value.
     *
     * @param key a {@link java.lang.String} object.
     * @return a long.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public static long getLongValue(String key)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        return parameterMap.get(key).getLong(null);
    }

    /**
     * Get a boolean parameter value.
     *
     * @param key a {@link java.lang.String} object.
     * @return a boolean.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public static boolean getBooleanValue(String key)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        return parameterMap.get(key).getBoolean(null);
    }

    /**
     * Get a double parameter value.
     *
     * @param key a {@link java.lang.String} object.
     * @return a double.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public static double getDoubleValue(String key)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        return parameterMap.get(key).getDouble(null);
    }

    /**
     * Get parameter value as string (works for all types).
     *
     * @param key a {@link java.lang.String} object.
     * @return a {@link java.lang.String} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public static String getStringValue(String key)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        StringBuffer sb = new StringBuffer();
        Object val = parameterMap.get(key).get(null);
        if (val != null && val.getClass().isArray()) {
            int len = Array.getLength(val);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(";");
                }

                sb.append(Array.get(val, i));
            }
        } else {
            sb.append(val);
        }
        return sb.toString();
    }

    /**
     * Check if there exist any parameter with given name.
     *
     * @param parameterName the name of the parameter to check
     * @return true if the parameter exists, false otherwise
     */
    public static boolean hasParameter(String parameterName) {
        return parameterMap.containsKey(parameterName);
    }

    /**
     * Set parameter to new integer value.
     *
     * @param key   a {@link java.lang.String} object.
     * @param value a int.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalAccessException                 if any.
     * @throws java.lang.IllegalArgumentException               if any.
     */
    public void setValue(String key, int value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);

        if (f.isAnnotationPresent(IntValue.class)) {
            IntValue i = f.getAnnotation(IntValue.class);
            if (value < i.min() || value > i.max()) {
                throw new IllegalArgumentException();
            }
        }

        f.setInt(this, value);
    }

    /**
     * Set parameter to new long value.
     *
     * @param key   a {@link java.lang.String} object.
     * @param value a long.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalAccessException                 if any.
     * @throws java.lang.IllegalArgumentException               if any.
     */
    public void setValue(String key, long value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);

        if (f.isAnnotationPresent(LongValue.class)) {
            LongValue i = f.getAnnotation(LongValue.class);
            if (value < i.min() || value > i.max()) {
                throw new IllegalArgumentException();
            }
        }

        f.setLong(this, value);
    }

    /**
     * Set parameter to new boolean value.
     *
     * @param key   a {@link java.lang.String} object.
     * @param value a boolean.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalAccessException                 if any.
     * @throws java.lang.IllegalArgumentException               if any.
     */
    public void setValue(String key, boolean value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        f.setBoolean(this, value);
    }

    /**
     * Set parameter to new double value.
     *
     * @param key   a {@link java.lang.String} object.
     * @param value a double.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    public void setValue(String key, double value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        if (f.isAnnotationPresent(DoubleValue.class)) {
            DoubleValue i = f.getAnnotation(DoubleValue.class);
            if (value < i.min() || value > i.max()) {
                throw new IllegalArgumentException();
            }
        }
        f.setDouble(this, value);
    }

    /**
     * Set parameter to new value from String.
     *
     * @param key   a {@link java.lang.String} object.
     * @param value a {@link java.lang.String} object.
     * @throws org.evosuite.Properties.NoSuchParameterException if any.
     * @throws java.lang.IllegalArgumentException               if any.
     * @throws java.lang.IllegalAccessException                 if any.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setValue(String key, String value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);
        changedFields.add(key);

        //Enum
        if (f.getType().isEnum()) {
            f.set(null, Enum.valueOf((Class<Enum>) f.getType(),
                    value.toUpperCase()));
        } else if (f.getType().equals(int.class)) {
            //Integers
            setValue(key, Integer.parseInt(value));
        } else if (f.getType().equals(Integer.class)) {
            setValue(key, (Integer) Integer.parseInt(value));
        } else if (f.getType().equals(long.class)) {
            //Long
            setValue(key, Long.parseLong(value));
        } else if (f.getType().equals(Long.class)) {
            setValue(key, (Long) Long.parseLong(value));
        } else if (f.getType().equals(boolean.class)) {
            //Boolean
            setValue(key, strictParseBoolean(value));
        } else if (f.getType().equals(Boolean.class)) {
            setValue(key, (Boolean) strictParseBoolean(value));
        } else if (f.getType().equals(double.class)) {
            //Double
            setValue(key, Double.parseDouble(value));
        } else if (f.getType().equals(Double.class)) {
            setValue(key, (Double) Double.parseDouble(value));
        } else if (f.getType().isArray()) {
            //Array
            if (f.getType().isAssignableFrom(String[].class)) {
                setValue(key, value.split(":"));
            } else if (f.getType().getComponentType().equals(Criterion.class)) {
                String[] values = value.split(":");
                Criterion[] criteria = new Criterion[values.length];

                int pos = 0;
                for (String stringValue : values) {
                    criteria[pos++] = Enum.valueOf(Criterion.class,
                            stringValue.toUpperCase());
                }

                f.set(this, criteria);
            }
        } else {
            f.set(null, value);
        }
    }

    /**
     * Strict function to parse boolean values because Boolean.parseBoolean silently
     * ignores malformed strings.
     *
     * @param s the string to parse
     * @return the boolean value
     */
    protected boolean strictParseBoolean(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(
                    "empty string does not represent a valid boolean");
        }

        if (s.equalsIgnoreCase("true")) {
            return true;
        }

        if (s.equalsIgnoreCase("false")) {
            return false;
        }

        throw new IllegalArgumentException(
                "Invalid string representing a boolean: " + s);
    }

    /**
     * Set parameter to new value from array of Strings.
     *
     * @param key   a {@link java.lang.String} object identifying the parameter.
     * @param value an array of {@link java.lang.String} objects representing the new values.
     * @throws org.evosuite.Properties.NoSuchParameterException if the parameter key is not found.
     * @throws java.lang.IllegalArgumentException               if the value is invalid.
     * @throws java.lang.IllegalAccessException                 if the field cannot be accessed.
     */
    public void setValue(String key, String[] value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);

        f.set(this, value);
    }

    /**
     * Set the given <code>key</code> variable to the given input Object
     * <code>value</code>.
     *
     * @param key   a {@link java.lang.String} object identifying the parameter.
     * @param value the new value for the parameter.
     * @throws NoSuchParameterException if the parameter key is not found.
     * @throws IllegalArgumentException if the value is invalid.
     * @throws IllegalAccessException   if the field cannot be accessed.
     */
    public void setValue(String key, Object value)
            throws NoSuchParameterException, IllegalArgumentException,
            IllegalAccessException {
        if (!parameterMap.containsKey(key)) {
            throw new NoSuchParameterException(key);
        }

        Field f = parameterMap.get(key);

        f.set(this, value);
    }

    /**
     * Singleton instance.
     */
    private static Properties instance = null; // new Properties(true, true);

    /**
     * Internal properties hashmap.
     */
    private java.util.Properties properties;

    /**
     * Constructor.
     */
    private Properties(boolean loadProperties, boolean silent) {
        if (loadProperties) {
            loadProperties(silent);
        }
        setClassPrefix();
    }

    /**
     * Singleton accessor.
     *
     * @return a {@link org.evosuite.Properties} object.
     */
    public static Properties getInstance() {
        if (instance == null) {
            instance = new Properties(true, false);
        }
        return instance;
    }

    /**
     * Singleton accessor.
     *
     * @return a {@link org.evosuite.Properties} object.
     */
    public static Properties getInstanceSilent() {
        if (instance == null) {
            instance = new Properties(true, true);
        }
        return instance;
    }

    /**
     * This exception is used when a non-existent parameter is accessed.
     */
    public static class NoSuchParameterException extends Exception {

        private static final long serialVersionUID = 9074828392047742535L;

        public NoSuchParameterException(String key) {
            super("No such property defined: " + key);
        }
    }

    private static void setClassPrefix() {
        if (TARGET_CLASS != null && !TARGET_CLASS.equals("")) {
            if (TARGET_CLASS.contains(".")) {
                CLASS_PREFIX = TARGET_CLASS.substring(0,
                        TARGET_CLASS.lastIndexOf('.'));
                SUB_PREFIX = CLASS_PREFIX.replace(PROJECT_PREFIX + ".", "");
            }
            if (PROJECT_PREFIX == null || PROJECT_PREFIX.equals("")) {
                if (CLASS_PREFIX.contains(".")) {
                    PROJECT_PREFIX = CLASS_PREFIX.substring(0,
                            CLASS_PREFIX.indexOf("."));
                } else {
                    PROJECT_PREFIX = CLASS_PREFIX;
                }
                // LoggingUtils.getEvoLogger().info("* Using project prefix: "
                // + PROJECT_PREFIX);
            }
        }
    }

    /**
     * Returns the target class. It required, it also executes the
     * {@code <clinit>} class initialiser of the target class.
     *
     * @return the initialised target class
     */
    public static Class<?> getInitializedTargetClass() {
        return getTargetClass(true);
    }

    /**
     * Returns the target class. If the class is not yet initialised,
     * this method *does not* execute the {@code <clinit>} class initialiser of the target class.
     * This method explicitly states that the {@code <clinit>} method is not executed
     * because of this method.
     *
     * @return the target class. The target class could be uninitialised.
     */
    public static Class<?> getTargetClassAndDontInitialise() {
        return getTargetClass(false);
    }


    /**
     * Returns true if there is a loaded target class object.
     * Warning: resetTargetClass() does not load the class, only
     * discards the previous target class object.
     *
     * @return true if the target class has been loaded
     */
    public static boolean hasTargetClassBeenLoaded() {
        return TARGET_CLASS_INSTANCE != null;
    }

    /**
     * Get class object of class under test.
     *
     * @param initialise whether to initialise the class
     * @return a {@link java.lang.Class} object.
     */
    private static Class<?> getTargetClass(boolean initialise) {

        if (TARGET_CLASS_INSTANCE != null
                && TARGET_CLASS_INSTANCE.getCanonicalName()
                .equals(TARGET_CLASS)) {
            return TARGET_CLASS_INSTANCE;
        }

        if (TARGET_CLASS_INSTANCE != null) {
            TARGET_CLASS_INSTANCE = null;
        }

        boolean wasLoopCheckOn = LoopCounter.getInstance().isActivated();

        try {
            /*
             * TODO: loading the SUT will execute its static initializer.
             * This might interact with the environment (eg, read a file, access static
             * variables of other classes), and even fails if an exception is thrown.
             * Those cases should be handled here before starting the search.
             */

            Runtime.getInstance().resetRuntime(); //it is important to initialize the VFS


            LoopCounter.getInstance().setActive(false);
            TARGET_CLASS_INSTANCE = Class.forName(TARGET_CLASS, initialise,
                    TestGenerationContext.getInstance().getClassLoaderForSUT());

            setClassPrefix();

        } catch (ClassNotFoundException e) {
            LoggingUtils.getEvoLogger().warn(
                    "* Could not find class under test " + Properties.TARGET_CLASS + ": " + e);
        } finally {
            LoopCounter.getInstance().setActive(wasLoopCheckOn);
        }

        return TARGET_CLASS_INSTANCE;
    }

    /**
     * Reset the class under test instance.
     */
    public static void resetTargetClass() {
        TARGET_CLASS_INSTANCE = null;
    }

    /**
     * Update the evosuite.properties file with the current setting.
     */
    public void writeConfiguration() {
        URL fileURL = this.getClass().getClassLoader()
                .getResource("evosuite.properties");
        String name = fileURL.getFile();
        writeConfiguration(name);
    }

    /**
     * Update the evosuite.properties file with the current setting.
     *
     * @param fileName a {@link java.lang.String} object.
     */
    public void writeConfiguration(String fileName) {
        String classpathForConfig = ClassPathHandler.getInstance().getTargetProjectClasspath();
        if (classpathForConfig == null || classpathForConfig.trim().isEmpty()) {
            classpathForConfig = Properties.CP;
        }
        if (classpathForConfig == null) {
            classpathForConfig = "";
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("CP=");
        // Replace backslashes with forwardslashes, as backslashes are dropped during reading
        // TODO: What if there are weird characters in the code? Need regex
        buffer.append(classpathForConfig.replace("\\", "/"));
        buffer.append("\nPROJECT_PREFIX=");
        if (Properties.PROJECT_PREFIX != null) {
            buffer.append(Properties.PROJECT_PREFIX);
        }
        buffer.append("\n");

        Map<String, Set<Parameter>> fieldMap = new HashMap<>();
        for (Field f : Properties.class.getFields()) {
            if (f.isAnnotationPresent(Parameter.class)) {
                Parameter p = f.getAnnotation(Parameter.class);
                if (!fieldMap.containsKey(p.group())) {
                    fieldMap.put(p.group(), new HashSet<>());
                }

                fieldMap.get(p.group()).add(p);
            }
        }

        for (String group : fieldMap.keySet()) {
            if (group.equals("Runtime")) {
                continue;
            }

            buffer.append("#--------------------------------------\n");
            buffer.append("# ");
            buffer.append(group);
            buffer.append("\n#--------------------------------------\n\n");
            for (Parameter p : fieldMap.get(group)) {
                buffer.append("# ");
                buffer.append(p.description());
                buffer.append("\n");
                if (!changedFields.contains(p.key())) {
                    buffer.append("#");
                }
                buffer.append(p.key());
                buffer.append("=");
                try {
                    buffer.append(getStringValue(p.key()));
                } catch (Exception e) {
                    logger.error("Exception " + e.getMessage(), e);
                }
                buffer.append("\n\n");
            }
        }
        FileIOUtils.writeFile(buffer.toString(), fileName);
    }

    /**
     * Reset all properties to defaults.
     */
    public void resetToDefaults() {
        Properties.instance = new Properties(false, true);
        for (Field f : Properties.class.getFields()) {
            if (f.isAnnotationPresent(Parameter.class)) {
                if (defaultMap.containsKey(f)) {
                    try {
                        f.set(null, defaultMap.get(f));
                    } catch (Exception e) {
                        logger.error("Failed to init property field " + f
                                + " , " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Checks whether the current generation strategy is DSE.
     *
     * @return true if the strategy is DSE, false otherwise
     */
    public static boolean isDSEStrategySelected() {
        return STRATEGY == Strategy.DSE;
    }

    /**
     * Checks whether DSE is enabled in Local Search.
     *
     * @return true if DSE is enabled in local search, false otherwise
     */
    public static boolean isDSEEnabledInLocalSearch() {
        return DSE_PROBABILITY > 0.0
                && LOCAL_SEARCH_RATE > 0
                && LOCAL_SEARCH_PROBABILITY > 0.0;
    }

    /**
     * Checks whether the selected arrays implementation for DSE is arrays theory.
     *
     * @return true if arrays theory is selected, false otherwise
     */
    public static boolean isArraysTheoryImplementationSelected() {
        return SELECTED_DseArraysMemoryModelVersion == DseArraysMemoryModelVersion.SELECT_STORE_EXPRESSIONS;
    }

    /**
     * Checks whether the selected arrays implementation for DSE is lazy arrays.
     *
     * @return true if lazy arrays implementation is selected, false otherwise
     */
    public static boolean isLazyArraysImplementationSelected() {
        return SELECTED_DseArraysMemoryModelVersion == DseArraysMemoryModelVersion.LAZY_VARIABLES;
    }
}
