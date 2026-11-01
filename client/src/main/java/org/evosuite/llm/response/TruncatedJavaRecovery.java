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
package org.evosuite.llm.response;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Best-effort recovery for truncated Java class source.
 * It trims to the last safe top-level member boundary and restores class-closing braces.
 */
public final class TruncatedJavaRecovery {

    /**
     * Recovery result containing source and metadata.
     */
    public static final class RecoveryResult {
        private final String source;
        private final boolean recovered;
        private final String reason;
        private final CompilationUnit parsedUnit;

        RecoveryResult(String source, boolean recovered, String reason, CompilationUnit parsedUnit) {
            this.source = source;
            this.recovered = recovered;
            this.reason = reason;
            this.parsedUnit = parsedUnit;
        }

        /**
         * The syntax tree {@link #getSource()} parsed to while recovering, or null if it does not parse.
         */
        CompilationUnit getParsedUnit() {
            return parsedUnit;
        }

        public String getSource() {
            return source;
        }

        public boolean isRecovered() {
            return recovered;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Attempts to recover truncated Java class source. If recovery is not possible,
     * returns the original source and {@code recovered=false}.
     */
    public RecoveryResult recover(String source) {
        if (source == null || source.trim().isEmpty()) {
            return new RecoveryResult(source, false, "empty source", null);
        }
        CompilationUnit original = parse(source);
        if (original != null) {
            return new RecoveryResult(source, false, "already parseable", original);
        }

        int classKeyword = source.indexOf("class ");
        if (classKeyword < 0) {
            return new RecoveryResult(source, false, "no class declaration", null);
        }
        int classOpenBrace = source.indexOf('{', classKeyword);
        if (classOpenBrace < 0) {
            return new RecoveryResult(source, false, "class body start not found", null);
        }

        List<Integer> boundaries = findTopLevelMemberBoundaries(source, classOpenBrace);
        if (boundaries.isEmpty()) {
            boundaries = Collections.singletonList(classOpenBrace + 1);
        }

        for (int i = boundaries.size() - 1; i >= 0; i--) {
            int cut = boundaries.get(i);
            String candidate = closeOutstandingBraces(source.substring(0, cut));
            if (candidate.equals(source)) {
                continue;
            }
            CompilationUnit trimmed = parse(candidate);
            if (trimmed != null) {
                return new RecoveryResult(candidate, true, "trimmed trailing incomplete member", trimmed);
            }
        }

        String minimal = closeOutstandingBraces(source.substring(0, classOpenBrace + 1));
        CompilationUnit header = parse(minimal);
        if (header != null && !minimal.equals(source)) {
            return new RecoveryResult(minimal, true, "trimmed to class header", header);
        }

        return new RecoveryResult(source, false, "recovery did not produce parseable source", null);
    }

    private CompilationUnit parse(String source) {
        try {
            return StaticJavaParser.parse(source);
        } catch (RuntimeException | StackOverflowError ignored) {
            return null;
        }
    }

    private List<Integer> findTopLevelMemberBoundaries(String source, int classOpenBrace) {
        LexState state = new LexState();
        int curlyDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        List<Integer> boundaries = new ArrayList<>();

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            int consumed = lexNonCode(state, c, next);
            if (consumed >= 0) {
                i += consumed;
                continue;
            }

            if (c == '{') {
                curlyDepth++;
                continue;
            }
            if (c == '}') {
                if (curlyDepth > 0) {
                    curlyDepth--;
                }
                if (curlyDepth == 1 && parenDepth == 0 && bracketDepth == 0 && i > classOpenBrace) {
                    boundaries.add(i + 1);
                }
                continue;
            }
            if (c == '(') {
                parenDepth++;
                continue;
            }
            if (c == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (c == '[') {
                bracketDepth++;
                continue;
            }
            if (c == ']') {
                if (bracketDepth > 0) {
                    bracketDepth--;
                }
                continue;
            }
            if (c == ';' && curlyDepth == 1 && parenDepth == 0 && bracketDepth == 0 && i > classOpenBrace) {
                boundaries.add(i + 1);
            }
        }
        return boundaries;
    }

    /**
     * Advances the lexical state over comments and string or character literals.
     *
     * @return the number of extra characters consumed after {@code c}, or -1 if {@code c} is code
     */
    private static int lexNonCode(LexState state, char c, char next) {
        if (state.lineComment) {
            if (c == '\n' || c == '\r') {
                state.lineComment = false;
            }
            return 0;
        }
        if (state.blockComment) {
            if (c == '*' && next == '/') {
                state.blockComment = false;
                return 1;
            }
            return 0;
        }
        if (state.stringLiteral) {
            if (state.escape) {
                state.escape = false;
            } else if (c == '\\') {
                state.escape = true;
            } else if (c == '"') {
                state.stringLiteral = false;
            }
            return 0;
        }
        if (state.charLiteral) {
            if (state.escape) {
                state.escape = false;
            } else if (c == '\\') {
                state.escape = true;
            } else if (c == '\'') {
                state.charLiteral = false;
            }
            return 0;
        }
        if (c == '/' && next == '/') {
            state.lineComment = true;
            return 1;
        }
        if (c == '/' && next == '*') {
            state.blockComment = true;
            return 1;
        }
        if (c == '"') {
            state.stringLiteral = true;
            state.escape = false;
            return 0;
        }
        if (c == '\'') {
            state.charLiteral = true;
            state.escape = false;
            return 0;
        }
        return -1;
    }

    private String closeOutstandingBraces(String source) {
        LexState state = new LexState();
        int curlyDepth = 0;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            int consumed = lexNonCode(state, c, next);
            if (consumed >= 0) {
                i += consumed;
                continue;
            }

            if (c == '{') {
                curlyDepth++;
            } else if (c == '}' && curlyDepth > 0) {
                curlyDepth--;
            }
        }

        if (curlyDepth <= 0) {
            return source;
        }

        StringBuilder builder = new StringBuilder(source.trim());
        builder.append(System.lineSeparator());
        for (int i = 0; i < curlyDepth; i++) {
            builder.append('}').append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private static final class LexState {
        private boolean lineComment;
        private boolean blockComment;
        private boolean stringLiteral;
        private boolean charLiteral;
        private boolean escape;
    }
}
