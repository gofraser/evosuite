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

import org.evosuite.Properties;
import org.evosuite.seeding.ConstantPool;
import org.evosuite.seeding.ConstantPoolManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Renders a compact "domain vocabulary" hint from the SUT's static constant
 * pool — the literals that EvoSuite has already harvested from the SUT
 * bytecode. Used to anchor LLM constant suggestions to the SUT's own
 * vocabulary, instead of letting the model invent unrelated tokens.
 *
 * <p>The hint includes both strings and numeric literals (int / long / float /
 * double). Each type is independently capped via
 * {@link Properties#LLM_CONSTANT_VOCABULARY_MAX_PER_TYPE} so the prompt stays
 * bounded even for SUTs with hundreds of literals.
 */
final class SutConstantVocabulary {

    /** Strings shown here are also truncated individually to avoid a single 65K literal blowing up the prompt. */
    private static final int MAX_STRING_CHARS_RENDERED = 80;

    private SutConstantVocabulary() {
    }

    /**
     * Returns a multi-line hint that can be appended to a constants prompt. Empty
     * string when the cap is zero or the SUT pool is empty.
     */
    static String buildVocabularyHint() {
        int cap = Math.max(0, Properties.LLM_CONSTANT_VOCABULARY_MAX_PER_TYPE);
        if (cap == 0) {
            return "";
        }
        ConstantPool pool;
        try {
            pool = ConstantPoolManager.getInstance().getSUTConstantPool();
        } catch (Throwable t) {
            return "";
        }
        if (pool == null) {
            return "";
        }
        return buildVocabularyHint(pool, cap);
    }

    /** Visible for testing — same contract as {@link #buildVocabularyHint()} but with explicit dependencies. */
    static String buildVocabularyHint(ConstantPool pool, int cap) {
        if (pool == null || cap <= 0) {
            return "";
        }

        List<String> stringSample = sampleStrings(pool.getStrings(), cap);
        List<Integer> intSample = sample(pool.getInts(), cap);
        List<Long> longSample = sample(pool.getLongs(), cap);
        List<Float> floatSample = sample(pool.getFloats(), cap);
        List<Double> doubleSample = sample(pool.getDoubles(), cap);

        if (stringSample.isEmpty() && intSample.isEmpty() && longSample.isEmpty()
                && floatSample.isEmpty() && doubleSample.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nDomain vocabulary already used by the SUT (suggest more in this style; "
                + "showing up to ").append(cap).append(" per type):\n");
        if (!stringSample.isEmpty()) {
            sb.append("- Strings: ");
            appendStringLiterals(sb, stringSample);
            sb.append('\n');
        }
        if (!intSample.isEmpty()) {
            sb.append("- Ints: ").append(joinAsLiterals(intSample, "")).append('\n');
        }
        if (!longSample.isEmpty()) {
            sb.append("- Longs: ").append(joinAsLiterals(longSample, "L")).append('\n');
        }
        if (!floatSample.isEmpty()) {
            sb.append("- Floats: ").append(joinAsLiterals(floatSample, "f")).append('\n');
        }
        if (!doubleSample.isEmpty()) {
            sb.append("- Doubles: ").append(joinAsLiterals(doubleSample, "")).append('\n');
        }
        return sb.toString();
    }

    private static <T> List<T> sample(Collection<T> source, int cap) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<T> out = new ArrayList<>(Math.min(cap, source.size()));
        for (T value : source) {
            if (value == null) {
                continue;
            }
            out.add(value);
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static List<String> sampleStrings(Collection<String> source, int cap) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(Math.min(cap, source.size()));
        for (String value : source) {
            if (value == null) {
                continue;
            }
            out.add(value);
            if (out.size() >= cap) {
                break;
            }
        }
        return out;
    }

    private static <T> String joinAsLiterals(List<T> values, String suffix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i)).append(suffix);
        }
        return sb.toString();
    }

    private static void appendStringLiterals(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(escapeStringForPrompt(values.get(i)));
        }
    }

    private static String escapeStringForPrompt(String raw) {
        String value = raw;
        boolean truncated = false;
        if (value.length() > MAX_STRING_CHARS_RENDERED) {
            value = value.substring(0, MAX_STRING_CHARS_RENDERED);
            truncated = true;
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('"');
        if (truncated) {
            sb.append("...");
        }
        return sb.toString();
    }
}
