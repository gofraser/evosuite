/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;

/** Stable protocol identifiers recorded by traces and experiment manifests. */
public final class LlmPostProcessingProtocol {

    /** Schema emitted by every new production request. */
    public static final int RESPONSE_SCHEMA_VERSION = 2;
    public static final int MAX_RESPONSE_SCHEMA_VERSION = 3;
    public static final int MIN_RESPONSE_SCHEMA_VERSION = 1;
    public static final String PARSER_VERSION = "postprocessing-parser-v4";
    public static final String PRODUCTION_PROMPT_VERSION = "postprocessing-p2-v2";

    private LlmPostProcessingProtocol() {
        // Utility class.
    }

    public static String promptVersion() {
        return PRODUCTION_PROMPT_VERSION;
    }

    /**
     * Resolve a historical prompt identifier for trace/replay metadata.  This
     * method is deliberately not used by the fresh-request renderer.
     */
    public static String historicalPromptVersion(Properties.LlmPostProcessingPromptVariant variant) {
        if (variant == null) {
            variant = Properties.LlmPostProcessingPromptVariant.P2_CANDIDATE_SELECTION;
        }
        switch (variant) {
            case P0_CURRENT:
                return "postprocessing-p0-v2";
            case P1_GROUNDED_PRODUCTIVE:
                return "postprocessing-p1-v2";
            case P3_TYPED_TEMPLATES:
                return "postprocessing-p3-v2";
            case P4_CANONICAL_CANDIDATES:
                return "postprocessing-p4-v3";
            case P5_ACTION_RANKED_CANDIDATES:
                return "postprocessing-p5-v3";
            case P6_RELATIONAL_OPPORTUNITIES:
                return "postprocessing-p6-v3";
            case P7_STABILITY_LABELS:
                return "postprocessing-p7-v5";
            case P8_COMPACT_OBSERVED_CALLS:
                return "postprocessing-p8-v3";
            case P9_LITERAL_DISCIPLINE:
                return "postprocessing-p9-v3";
            case P10_ASSERTABLE_TYPES_ONLY:
                return "postprocessing-p10-v4";
            case P11_EXCEPTION_ADJACENT_ASSERTIONS:
                return "postprocessing-p11-v3";
            case P12_ORACLE_CONTEXT_V2:
                return "postprocessing-p12-v6";
            case P2_CANDIDATE_SELECTION:
            default:
                return "postprocessing-p2-v2";
        }
    }

    public static int responseSchemaVersion() {
        return RESPONSE_SCHEMA_VERSION;
    }
}
