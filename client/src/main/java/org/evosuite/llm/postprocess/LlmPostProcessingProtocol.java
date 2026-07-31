/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.llm.postprocess;

/** Stable protocol identifiers recorded by traces and experiment manifests. */
public final class LlmPostProcessingProtocol {

    /** Schema emitted by every new production request. */
    public static final int RESPONSE_SCHEMA_VERSION = 2;
    public static final int MAX_RESPONSE_SCHEMA_VERSION = 3;
    public static final int MIN_RESPONSE_SCHEMA_VERSION = 1;
    public static final String PARSER_VERSION = "postprocessing-parser-v4";
    public static final String PRODUCTION_PROMPT_VERSION = "postprocessing-production-v2";

    private LlmPostProcessingProtocol() {
        // Utility class.
    }

    public static String promptVersion() {
        return PRODUCTION_PROMPT_VERSION;
    }

    public static int responseSchemaVersion() {
        return RESPONSE_SCHEMA_VERSION;
    }
}
