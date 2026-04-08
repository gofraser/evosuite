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

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;

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

        RecoveryResult(String source, boolean recovered, String reason) {
            this.source = source;
            this.recovered = recovered;
            this.reason = reason;
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
            return new RecoveryResult(source, false, "empty source");
        }
        if (isParseable(source)) {
            return new RecoveryResult(source, false, "already parseable");
        }

        int classKeyword = source.indexOf("class ");
        if (classKeyword < 0) {
            return new RecoveryResult(source, false, "no class declaration");
        }
        int classOpenBrace = source.indexOf('{', classKeyword);
        if (classOpenBrace < 0) {
            return new RecoveryResult(source, false, "class body start not found");
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
            if (isParseable(candidate)) {
                return new RecoveryResult(candidate, true, "trimmed trailing incomplete member");
            }
        }

        String minimal = closeOutstandingBraces(source.substring(0, classOpenBrace + 1));
        if (isParseable(minimal) && !minimal.equals(source)) {
            return new RecoveryResult(minimal, true, "trimmed to class header");
        }

        return new RecoveryResult(source, false, "recovery did not produce parseable source");
    }

    private boolean isParseable(String source) {
        try {
            StaticJavaParser.parse(source);
            return true;
        } catch (ParseProblemException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
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

            if (state.lineComment) {
                if (c == '\n' || c == '\r') {
                    state.lineComment = false;
                }
                continue;
            }
            if (state.blockComment) {
                if (c == '*' && next == '/') {
                    state.blockComment = false;
                    i++;
                }
                continue;
            }
            if (state.stringLiteral) {
                if (state.escape) {
                    state.escape = false;
                } else if (c == '\\') {
                    state.escape = true;
                } else if (c == '"') {
                    state.stringLiteral = false;
                }
                continue;
            }
            if (state.charLiteral) {
                if (state.escape) {
                    state.escape = false;
                } else if (c == '\\') {
                    state.escape = true;
                } else if (c == '\'') {
                    state.charLiteral = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                state.lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                state.blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                state.stringLiteral = true;
                state.escape = false;
                continue;
            }
            if (c == '\'') {
                state.charLiteral = true;
                state.escape = false;
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

    private String closeOutstandingBraces(String source) {
        LexState state = new LexState();
        int curlyDepth = 0;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (state.lineComment) {
                if (c == '\n' || c == '\r') {
                    state.lineComment = false;
                }
                continue;
            }
            if (state.blockComment) {
                if (c == '*' && next == '/') {
                    state.blockComment = false;
                    i++;
                }
                continue;
            }
            if (state.stringLiteral) {
                if (state.escape) {
                    state.escape = false;
                } else if (c == '\\') {
                    state.escape = true;
                } else if (c == '"') {
                    state.stringLiteral = false;
                }
                continue;
            }
            if (state.charLiteral) {
                if (state.escape) {
                    state.escape = false;
                } else if (c == '\\') {
                    state.escape = true;
                } else if (c == '\'') {
                    state.charLiteral = false;
                }
                continue;
            }

            if (c == '/' && next == '/') {
                state.lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                state.blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                state.stringLiteral = true;
                state.escape = false;
                continue;
            }
            if (c == '\'') {
                state.charLiteral = true;
                state.escape = false;
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
