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
package org.evosuite.llm.seeding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses typed Java literals (strings, ints, longs, doubles, floats, NaN/Infinity)
 * out of a free-form LLM response. The parser is permissive — it accepts mixed
 * prose and code, and silently skips tokens that don't match any pattern. Reuse
 * across enrichment paths and stagnation interventions is intentional.
 *
 * <p>Order of pattern application matters: strings → floats → longs → doubles →
 * special doubles (NaN/Infinity) → ints. Each pass works on the original
 * response string, and lookaheads on the numeric patterns prevent overlap
 * (e.g. {@code 1.5f} matches FLOAT but not DOUBLE).
 */
public final class ConstantResponseParser {

    /** Matches a JSON-style double-quoted string with standard Java escapes. */
    static final Pattern STRING_PATTERN = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Matches integer literals up to 10 digits (no L/d/f suffix). */
    static final Pattern INT_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d{1,10})(?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);

    /** Matches long literals with L or l suffix. */
    static final Pattern LONG_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+)[Ll](?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);

    /** Matches double literals (digit.digit, optional exponent, optional D/d suffix). */
    static final Pattern DOUBLE_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+\\.\\d+(?:[eE][+-]?\\d+)?)[dD]?(?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);

    /** Matches float literals (digit.digit, optional exponent, required F/f suffix). */
    static final Pattern FLOAT_PATTERN = Pattern.compile(
            "(?:^|[\\s,;:=\\[({])(-?\\d+\\.\\d+(?:[eE][+-]?\\d+)?)[fF](?=[\\s,;:=\\])}]|$)", Pattern.MULTILINE);

    /** Matches symbolic NaN (case-insensitive, optionally prefixed by Double.). */
    static final Pattern NAN_PATTERN = Pattern.compile("(?i)\\b(?:double\\.)?nan\\b");

    /** Matches symbolic Infinity with optional sign/qualifier (case-insensitive). */
    static final Pattern INFINITY_PATTERN =
            Pattern.compile("(?i)(?:double\\.)?(positive_|negative_)?([+-])?infinity\\b");

    private ConstantResponseParser() {
    }

    /**
     * Parses typed constants from an LLM response. Handles strings, ints, longs,
     * doubles, floats, and special doubles (NaN/Infinity). Duplicates are removed
     * while preserving first-seen order.
     *
     * @param response free-form text containing Java literals
     * @return parsed constants, autoboxed and ordered by first occurrence
     */
    public static List<Object> parseConstants(String response) {
        List<Object> constants = new ArrayList<>();
        if (response == null || response.trim().isEmpty()) {
            return constants;
        }

        parseStrings(response, constants);
        parseFloats(response, constants);
        parseLongs(response, constants);
        parseDoubles(response, constants);
        parseSpecialDoubles(response, constants);
        parseInts(response, constants);

        // Deduplicate while preserving order
        return new ArrayList<>(new LinkedHashSet<>(constants));
    }

    private static void parseStrings(String response, List<Object> constants) {
        Matcher m = STRING_PATTERN.matcher(response);
        while (m.find()) {
            constants.add(unescapeJavaString(m.group(1)));
        }
    }

    private static void parseFloats(String response, List<Object> constants) {
        Matcher m = FLOAT_PATTERN.matcher(response);
        while (m.find()) {
            try {
                constants.add(Float.parseFloat(m.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }
    }

    private static void parseLongs(String response, List<Object> constants) {
        Matcher m = LONG_PATTERN.matcher(response);
        while (m.find()) {
            try {
                constants.add(Long.parseLong(m.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }
    }

    private static void parseDoubles(String response, List<Object> constants) {
        Matcher m = DOUBLE_PATTERN.matcher(response);
        while (m.find()) {
            try {
                constants.add(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }
    }

    private static void parseSpecialDoubles(String response, List<Object> constants) {
        if (NAN_PATTERN.matcher(response).find()) {
            constants.add(Double.NaN);
        }
        Matcher infinityMatcher = INFINITY_PATTERN.matcher(response);
        while (infinityMatcher.find()) {
            String qualifier = infinityMatcher.group(1);
            String sign = infinityMatcher.group(2);
            if ("negative_".equalsIgnoreCase(qualifier) || "-".equals(sign)) {
                constants.add(Double.NEGATIVE_INFINITY);
            } else {
                constants.add(Double.POSITIVE_INFINITY);
            }
        }
    }

    private static void parseInts(String response, List<Object> constants) {
        // Numbers without decimal or suffix. Values that overflow int range
        // fall through to Long so callers don't lose boundary literals when
        // the LLM omits an L suffix.
        Matcher m = INT_PATTERN.matcher(response);
        while (m.find()) {
            try {
                long val = Long.parseLong(m.group(1));
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    constants.add((int) val);
                } else {
                    constants.add(val);
                }
            } catch (NumberFormatException e) {
                // skip malformed
            }
        }
    }

    /**
     * Unescapes a string literal body (the content between the surrounding
     * double quotes). Handles standard Java escapes: \n, \t, \r, \\, \", \',
     * and \0. Unknown escapes are passed through as the literal backslash.
     */
    public static String unescapeJavaString(String escaped) {
        if (escaped == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == '\\' && i + 1 < escaped.length()) {
                char next = escaped.charAt(i + 1);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        i++;
                        break;
                    case 't':
                        sb.append('\t');
                        i++;
                        break;
                    case 'r':
                        sb.append('\r');
                        i++;
                        break;
                    case '\\':
                        sb.append('\\');
                        i++;
                        break;
                    case '"':
                        sb.append('"');
                        i++;
                        break;
                    case '\'':
                        sb.append('\'');
                        i++;
                        break;
                    case '0':
                        sb.append('\0');
                        i++;
                        break;
                    default:
                        sb.append(c);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
