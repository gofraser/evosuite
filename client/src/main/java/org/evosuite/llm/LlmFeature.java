package org.evosuite.llm;

/**
 * Tags the integration point that triggered an LLM call.
 */
public enum LlmFeature {
    SEEDING,
    TEST_FACTORY,
    ASYNC_PRODUCER,
    STAGNATION,
    LOCAL_SEARCH,
    CONSTANT_POOL_ENRICHMENT,
    OBJECT_POOL_ENRICHMENT,
    TEST_REPAIR,
    TEST_NAMING,
    VARIABLE_NAMING,
    ASSERTION_GENERATION,
    LITERAL_NICEIFY
}
