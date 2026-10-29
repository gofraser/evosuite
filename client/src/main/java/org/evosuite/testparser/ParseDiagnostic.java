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

public class ParseDiagnostic {

    public enum Severity { INFO, WARNING, ERROR }

    private final Severity severity;
    private final DiagnosticKind kind;
    private final String message;
    private final int lineNumber;
    private final String sourceSnippet;

    /**
     * Create a new ParseDiagnostic.
     *
     * @param severity the severity.
     * @param message the message.
     * @param lineNumber the line number.
     * @param sourceSnippet the source snippet.
     */
    public ParseDiagnostic(Severity severity, String message, int lineNumber, String sourceSnippet) {
        this(severity, null, message, lineNumber, sourceSnippet);
    }

    /**
     * Create a new ParseDiagnostic with a diagnostic kind.
     *
     * @param severity the severity.
     * @param kind the kind of diagnostic, or null if unclassified.
     * @param message the message.
     * @param lineNumber the line number.
     * @param sourceSnippet the source snippet.
     */
    public ParseDiagnostic(Severity severity,
                           DiagnosticKind kind,
                           String message,
                           int lineNumber,
                           String sourceSnippet) {
        this.severity = severity;
        this.kind = kind;
        this.message = message;
        this.lineNumber = lineNumber;
        this.sourceSnippet = sourceSnippet;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    /**
     * Returns the kind of this diagnostic, or null if the parser did not classify it.
     *
     * @return the kind, or null.
     */
    public DiagnosticKind getKind() {
        return kind;
    }

    /**
     * Returns the action the LLM is asked to take to fix this diagnostic, or null if the message
     * carries none.
     *
     * @return the action text, or null.
     */
    public String getRepairAction() {
        int marker = message == null ? -1 : message.indexOf(DiagnosticKind.ACTION_REQUIRED_PREFIX);
        return marker < 0
                ? null
                : message.substring(marker + DiagnosticKind.ACTION_REQUIRED_PREFIX.length()).trim();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getSourceSnippet() {
        return sourceSnippet;
    }

    @Override
    public String toString() {
        return severity + " (line " + lineNumber + "): " + message
                + (sourceSnippet != null ? " [" + sourceSnippet + "]" : "");
    }
}
