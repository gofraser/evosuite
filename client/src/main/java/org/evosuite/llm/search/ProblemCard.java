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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestFitnessFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A compact diagnostic signal describing one likely blocker to further coverage.
 */
public final class ProblemCard {

    private final ProblemCardType type;
    private final String title;
    private final List<String> evidence;
    private final List<TestFitnessFunction> relatedGoals;
    private final double impact;
    private final double blockage;
    private final double confidence;
    private final double priority;
    private final ProblemCardFamily family;
    private final String rootCauseKey;
    private final String scopeKey;
    private final String selectionFingerprint;

    private ProblemCard(ProblemCardType type,
                        String title,
                        List<String> evidence,
                        List<TestFitnessFunction> relatedGoals,
                        double impact,
                        double blockage,
                        double confidence,
                        ProblemCardFamily family,
                        String rootCauseKey,
                        String scopeKey,
                        String selectionFingerprint) {
        this.type = Objects.requireNonNull(type, "type");
        this.title = title == null ? "" : title.trim();
        this.evidence = evidence == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(evidence));
        this.relatedGoals = relatedGoals == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(relatedGoals));
        this.impact = sanitize01(impact);
        this.blockage = sanitize01(blockage);
        this.confidence = sanitize01(confidence);
        // Soft combination so a single zero sub-score doesn't veto the card. A card with no
        // observed blockage (e.g. "never attempted" — by definition unobservable) still scores up
        // to 40% of its impact; confidence floors at 50% so a one-observation card isn't trivialised.
        this.priority = this.impact
                * (0.4 + 0.6 * this.blockage)
                * (0.5 + 0.5 * this.confidence);
        this.family = family == null ? type.getDefaultFamily() : family;
        this.rootCauseKey = sanitizeKey(rootCauseKey);
        this.scopeKey = sanitizeKey(scopeKey);
        this.selectionFingerprint = sanitizeKey(selectionFingerprint);
    }

    public static Builder builder(ProblemCardType type) {
        return new Builder(type);
    }

    /**
     * Fluent builder for {@link ProblemCard}. Adding a new optional axis only
     * requires one builder method instead of a new constructor overload.
     */
    public static final class Builder {
        private final ProblemCardType type;
        private String title = "";
        private List<String> evidence = Collections.emptyList();
        private List<TestFitnessFunction> relatedGoals = Collections.emptyList();
        private double impact;
        private double blockage;
        private double confidence;
        private ProblemCardFamily family;
        private String rootCauseKey;
        private String scopeKey;
        private String selectionFingerprint;

        private Builder(ProblemCardType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder evidence(List<String> evidence) {
            this.evidence = evidence;
            return this;
        }

        public Builder relatedGoals(List<TestFitnessFunction> relatedGoals) {
            this.relatedGoals = relatedGoals;
            return this;
        }

        public Builder impact(double impact) {
            this.impact = impact;
            return this;
        }

        public Builder blockage(double blockage) {
            this.blockage = blockage;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder family(ProblemCardFamily family) {
            this.family = family;
            return this;
        }

        public Builder rootCauseKey(String rootCauseKey) {
            this.rootCauseKey = rootCauseKey;
            return this;
        }

        public Builder scopeKey(String scopeKey) {
            this.scopeKey = scopeKey;
            return this;
        }

        public Builder selectionFingerprint(String selectionFingerprint) {
            this.selectionFingerprint = selectionFingerprint;
            return this;
        }

        public ProblemCard build() {
            if ((title == null || title.trim().isEmpty())
                    && (evidence == null || evidence.isEmpty())
                    && (relatedGoals == null || relatedGoals.isEmpty())) {
                throw new IllegalStateException(
                        "ProblemCard must carry at least a title, evidence, or related goals");
            }
            return new ProblemCard(type, title, evidence, relatedGoals,
                    impact, blockage, confidence,
                    family, rootCauseKey, scopeKey, selectionFingerprint);
        }
    }

    public ProblemCardType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public List<TestFitnessFunction> getRelatedGoals() {
        return relatedGoals;
    }

    public double getImpact() {
        return impact;
    }

    public double getBlockage() {
        return blockage;
    }

    public double getConfidence() {
        return confidence;
    }

    public double getPriority() {
        return priority;
    }

    public ProblemCardFamily getFamily() {
        return family;
    }

    public String getRootCauseKey() {
        return rootCauseKey;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public String getSelectionFingerprint() {
        return selectionFingerprint;
    }

    public RepeatedInjectionTarget toRepeatedInjectionTarget() {
        String key = !scopeKey.isEmpty() ? scopeKey : rootCauseKey;
        if (key.isEmpty()) {
            key = type.name();
        }
        String fingerprint = selectionFingerprint.isEmpty() ? key : selectionFingerprint;
        return new RepeatedInjectionTarget(key, fingerprint, type);
    }

    private static double sanitize01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static String sanitizeKey(String value) {
        return value == null ? "" : value.trim();
    }
}
