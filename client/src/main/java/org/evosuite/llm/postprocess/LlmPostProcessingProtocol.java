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
package org.evosuite.llm.postprocess;

/** Stable protocol identifiers recorded by traces and experiment manifests. */
public final class LlmPostProcessingProtocol {

    /** Schema emitted by every new production request. */
    public static final int RESPONSE_SCHEMA_VERSION = 2;
    public static final String PARSER_VERSION = "postprocessing-parser-v4";
    public static final String PRODUCTION_PROMPT_VERSION = "postprocessing-production-v2";

    /** Prompt markers the renderer emits when it truncates internal context. */
    public static final String OBSERVATIONS_TRUNCATED_MARKER = "truncated=true";
    public static final String CALLABLE_TYPES_TRUNCATED_MARKER = "truncatedCallableTypes=";
    public static final String CANDIDATES_TRUNCATED_MARKER = "truncatedCandidates=";

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
