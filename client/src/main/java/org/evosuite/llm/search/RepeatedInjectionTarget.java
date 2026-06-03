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

import java.util.Objects;

/**
 * Stable prompt-target identity for repeated-injection selection memory.
 */
public final class RepeatedInjectionTarget {

    private final String key;
    private final String fingerprint;
    private final ProblemCardType diagnosticCardType;

    public RepeatedInjectionTarget(String key, String fingerprint) {
        this(key, fingerprint, null);
    }

    public RepeatedInjectionTarget(String key, String fingerprint, ProblemCardType diagnosticCardType) {
        this.key = sanitize(key);
        this.fingerprint = sanitize(fingerprint);
        this.diagnosticCardType = diagnosticCardType;
    }

    public String getKey() {
        return key;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public ProblemCardType getDiagnosticCardType() {
        return diagnosticCardType;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepeatedInjectionTarget)) {
            return false;
        }
        RepeatedInjectionTarget that = (RepeatedInjectionTarget) other;
        return key.equals(that.key)
                && fingerprint.equals(that.fingerprint)
                && diagnosticCardType == that.diagnosticCardType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, fingerprint, diagnosticCardType);
    }
}
