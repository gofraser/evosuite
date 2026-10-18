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
package org.evosuite.llm;

import org.evosuite.Properties;

/**
 * Answers "is the LLM on?" for code outside the LLM packages, from the current
 * {@link Properties} and without touching any LLM class that has side effects
 * (services, thread pools, workers). Code on the default path must ask here
 * before using such a class.
 *
 * <p>Each question reads plain fields, so it is cheap enough for hot paths such
 * as {@code TimeController}. Feature-internal gates (for example the per-feature
 * seeding or stagnation switches) stay with their features.
 */
public final class LlmRunFeatures {

    private LlmRunFeatures() {
    }

    /** Returns whether an LLM provider is configured; without one no LLM request is ever made. */
    public static boolean providerConfigured() {
        return Properties.LLM_PROVIDER != Properties.LlmProvider.NONE;
    }

    /**
     * Returns whether an LLM response can be parsed and repaired in this run, which is when the
     * parse-phase compilation worker is needed.
     */
    public static boolean parsePathPossible() {
        return providerConfigured();
    }

    /**
     * Returns whether unified post-processing is enabled, a provider is configured and at least
     * one response feature is selected.
     */
    public static boolean postProcessing() {
        return Properties.LLM_POSTPROCESSING_ENABLED
                && providerConfigured()
                && (Properties.LLM_POSTPROCESSING_ASSERTIONS
                || Properties.LLM_POSTPROCESSING_TEST_NAMES
                || Properties.LLM_POSTPROCESSING_VARIABLE_NAMES
                || Properties.LLM_POSTPROCESSING_COMMENTS
                || Properties.LLM_POSTPROCESSING_SECTION_BREAKS);
    }

    /** Returns whether any pre-search enrichment is selected and a provider is configured. */
    public static boolean enrichment() {
        return providerConfigured()
                && (Properties.LLM_ENRICH_CONSTANT_POOL
                || Properties.LLM_ENRICH_OBJECT_POOL
                || Properties.LLM_ENRICH_CAST_CLASSES);
    }
}
