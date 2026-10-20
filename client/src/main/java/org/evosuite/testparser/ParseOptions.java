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
package org.evosuite.testparser;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable configuration of a {@link StatementParser}.
 *
 * <p>Strict parsing ({@link #strict()}) accepts only code that maps directly onto EvoSuite
 * statements. Best-effort parsing additionally salvages the idioms that LLM-written tests use
 * but that EvoSuite cannot represent directly, such as reflection helpers, local helper methods
 * and flattened try/if blocks. Provenance marking, which flags every created statement as
 * LLM-parsed, is a separate switch although both are enabled together for LLM output.
 */
public final class ParseOptions {

    private static final ParseOptions STRICT =
            new ParseOptions(false, false, Collections.<String, MethodDeclaration>emptyMap());

    private final boolean bestEffort;
    private final boolean markProvenance;
    private final Map<String, MethodDeclaration> inlineHelperMethods;

    private ParseOptions(boolean bestEffort, boolean markProvenance,
                         Map<String, MethodDeclaration> inlineHelperMethods) {
        this.bestEffort = bestEffort;
        this.markProvenance = markProvenance;
        this.inlineHelperMethods = inlineHelperMethods;
    }

    /** Strict parsing without provenance marking. */
    public static ParseOptions strict() {
        return STRICT;
    }

    /**
     * Best-effort parsing of LLM output: statements are marked as LLM-parsed and the given helper
     * methods (keyed as by {@link StatementParser}'s helper inlining) may be inlined at their call
     * sites.
     *
     * @param inlineHelperMethods inlineable helper methods; may be null or empty
     */
    public static ParseOptions llm(Map<String, MethodDeclaration> inlineHelperMethods) {
        Map<String, MethodDeclaration> helpers = inlineHelperMethods == null
                ? Collections.<String, MethodDeclaration>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inlineHelperMethods));
        return new ParseOptions(true, true, helpers);
    }

    /** Whether LLM-specific salvage heuristics are enabled. */
    public boolean isBestEffort() {
        return bestEffort;
    }

    /** Whether every created statement is marked as parsed from LLM output. */
    public boolean isMarkProvenance() {
        return markProvenance;
    }

    /** Helper methods (arity 0/1) that may be inlined as a plain return expression. */
    public Map<String, MethodDeclaration> getInlineHelperMethods() {
        return inlineHelperMethods;
    }
}
