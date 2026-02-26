package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator for all Phase 4 post-processing features. Each feature is independently
 * toggleable via its respective property. The orchestrator runs after test execution
 * but before test writing, applying LLM-based improvements where enabled.
 *
 * <p>All features degrade gracefully on LLM failure—no feature failure blocks
 * test output generation.
 */
public class LlmPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessor.class);

    /**
     * Check if any Phase 4 post-processing feature is enabled and the LLM provider
     * is configured. Use as a guard to skip orchestrator creation entirely when not needed.
     *
     * <p>Note: LLM assertion generation is activated via {@code ASSERTION_STRATEGY=LLM}
     * in the standard assertion pipeline ({@link org.evosuite.TestSuiteGeneratorHelper#addAssertions}),
     * not through this orchestrator.
     */
    public static boolean isAnyFeatureEnabled() {
        if (Properties.LLM_PROVIDER == Properties.LlmProvider.NONE) {
            return false;
        }
        return Properties.LLM_RENAME_TESTS
                || Properties.LLM_RENAME_VARIABLES
                || Properties.LLM_NICEIFY_LITERALS;
    }

    /**
     * Run literal niceification on a test suite.
     *
     * @param suite the test suite to niceify
     */
    public void runLiteralNiceification(TestSuiteChromosome suite) {
        if (!Properties.LLM_NICEIFY_LITERALS) {
            return;
        }
        logger.info("Running LLM literal niceification");
        try {
            LlmLiteralNiceifier niceifier = new LlmLiteralNiceifier();
            niceifier.niceify(suite);
            int count = niceifier.getLiteralsNiceified();
            logger.info("LLM literal niceification complete: {} literals replaced", count);
            ClientServices.track(RuntimeVariable.LLM_Literals_Niceified, count);
        } catch (Exception e) {
            logger.warn("LLM literal niceification failed; continuing without changes", e);
            ClientServices.track(RuntimeVariable.LLM_Literals_Niceified, 0);
        }
    }

    /**
     * Publish Phase 4 naming metrics to runtime variables.
     * Called after test naming has been applied.
     */
    public void publishNamingMetrics(LlmTestNameGenerator nameGenerator) {
        if (nameGenerator != null) {
            ClientServices.track(RuntimeVariable.LLM_Tests_Renamed, nameGenerator.getTestsRenamed());
            ClientServices.track(RuntimeVariable.LLM_Test_Naming_Fallbacks, nameGenerator.getFallbacks());
        }
    }

    /**
     * Publish Phase 4 variable naming metrics to runtime variables.
     * Called after variable naming strategy has been used.
     */
    public void publishVariableNamingMetrics(LlmVariableNameStrategy variableStrategy) {
        if (variableStrategy != null) {
            ClientServices.track(RuntimeVariable.LLM_Variables_Renamed, variableStrategy.getVariablesRenamed());
            ClientServices.track(RuntimeVariable.LLM_Variable_Naming_Fallbacks, variableStrategy.getFallbackCount());
        }
    }
}
