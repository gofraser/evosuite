/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.dmon;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses "helpful NPE" messages (Java 14+) with regexes tolerant to small vendor/version wording changes.
 */
public final class NpeMessageParser {

    public enum ParseStrength {
        EXACT_MATCH,
        WEAK_MATCH,
        NO_MATCH
    }

    public static final class ParseResult {
        private final Optional<String> nullExpression;
        private final Optional<String> ownerToken;
        private final Optional<String> memberToken;
        private final ParseStrength strength;

        public ParseResult(Optional<String> nullExpression,
                           Optional<String> ownerToken,
                           Optional<String> memberToken,
                           ParseStrength strength) {
            this.nullExpression = nullExpression == null ? Optional.empty() : nullExpression;
            this.ownerToken = ownerToken == null ? Optional.empty() : ownerToken;
            this.memberToken = memberToken == null ? Optional.empty() : memberToken;
            this.strength = strength == null ? ParseStrength.NO_MATCH : strength;
        }

        public Optional<String> getNullExpression() {
            return nullExpression;
        }

        public Optional<String> getOwnerToken() {
            return ownerToken;
        }

        public Optional<String> getMemberToken() {
            return memberToken;
        }

        public ParseStrength getStrength() {
            return strength;
        }
    }

    // Examples:
    // "Cannot invoke \"x.y.Foo.bar()\" because \"this.service\" is null"
    // "Cannot read field \"a\" because \"obj\" is null"
    // "Cannot assign field \"a\" because \"obj\" is null"
    private static final Pattern BECAUSE_QUOTED_NULL_PATTERN = Pattern.compile(
            "(?:because|since)\\s+\"([^\"]+)\"\\s+is\\s+null",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern READ_FIELD_PATTERN = Pattern.compile(
            "Cannot\\s+read\\s+field\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ASSIGN_FIELD_PATTERN = Pattern.compile(
            "Cannot\\s+assign\\s+field\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INVOKE_PATTERN = Pattern.compile(
            "Cannot\\s+invoke\\s+\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    public ParseResult parse(String message) {
        if (message == null || message.isEmpty()) {
            return new ParseResult(Optional.empty(), Optional.empty(), Optional.empty(), ParseStrength.NO_MATCH);
        }

        Optional<String> nullExpression = extractFirst(BECAUSE_QUOTED_NULL_PATTERN, message, 1);
        Optional<String> member = extractFirst(READ_FIELD_PATTERN, message, 1);
        if (!member.isPresent()) {
            member = extractFirst(ASSIGN_FIELD_PATTERN, message, 1);
        }
        if (!member.isPresent()) {
            member = extractFirst(INVOKE_PATTERN, message, 1);
        }

        Optional<String> owner = inferOwnerToken(nullExpression);
        ParseStrength strength = nullExpression.isPresent() ? ParseStrength.EXACT_MATCH
                : member.isPresent() ? ParseStrength.WEAK_MATCH
                : ParseStrength.NO_MATCH;

        return new ParseResult(nullExpression, owner, member, strength);
    }

    private static Optional<String> extractFirst(Pattern pattern, String message, int group) {
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(group);
        return value == null || value.trim().isEmpty() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Optional<String> inferOwnerToken(Optional<String> nullExpression) {
        if (!nullExpression.isPresent()) {
            return Optional.empty();
        }
        String expr = nullExpression.get();
        int idx = expr.lastIndexOf('.');
        if (idx <= 0) {
            return Optional.of(expr);
        }
        return Optional.of(expr.substring(0, idx));
    }
}

