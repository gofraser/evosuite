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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kinds and concrete subtypes of the types listed in the SUT context summary
 * ({@code // Name [interface]} headers followed by {@code concrete subtypes:} lines).
 */
final class ContextTypeIndex {

    private static final Pattern CONTEXT_TYPE_HEADER_PATTERN = Pattern.compile(
            "^\\s*//\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*\\[(interface|abstract|class|enum)\\].*$");
    private static final Pattern CONTEXT_CONCRETE_SUBTYPES_PATTERN = Pattern.compile(
            "^\\s*concrete subtypes:\\s*(.*)$");

    private final Map<String, String> typeKind;
    private final Map<String, List<String>> concreteSubtypes;

    private ContextTypeIndex(Map<String, String> typeKind,
                             Map<String, List<String>> concreteSubtypes) {
        this.typeKind = typeKind;
        this.concreteSubtypes = concreteSubtypes;
    }

    static ContextTypeIndex fromSummary(String summary) {
        Map<String, String> typeKind = new LinkedHashMap<>();
        Map<String, List<String>> concreteSubtypes = new LinkedHashMap<>();
        if (summary == null || summary.trim().isEmpty()) {
            return new ContextTypeIndex(typeKind, concreteSubtypes);
        }

        String currentType = null;
        String[] lines = summary.split("\\r?\\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            java.util.regex.Matcher typeHeader = CONTEXT_TYPE_HEADER_PATTERN.matcher(line);
            if (typeHeader.matches()) {
                currentType = typeHeader.group(1);
                String kind = typeHeader.group(2);
                String simple = simpleOf(currentType);
                typeKind.put(simple, kind);
                continue;
            }
            if (currentType == null) {
                continue;
            }
            java.util.regex.Matcher concrete = CONTEXT_CONCRETE_SUBTYPES_PATTERN.matcher(line);
            if (!concrete.matches()) {
                continue;
            }
            String payload = concrete.group(1);
            List<String> values = parseSubtypeList(payload);
            if (!values.isEmpty()) {
                concreteSubtypes.put(simpleOf(currentType), values);
            }
        }
        return new ContextTypeIndex(typeKind, concreteSubtypes);
    }

    boolean isEmpty() {
        return typeKind.isEmpty();
    }

    boolean isKnownType(String simple) {
        return simple != null && typeKind.containsKey(simple);
    }

    boolean isNonInstantiable(String simple) {
        if (simple == null) {
            return false;
        }
        String kind = typeKind.get(simple);
        return "interface".equals(kind) || "abstract".equals(kind);
    }

    String getKind(String simple) {
        return typeKind.get(simple);
    }

    List<String> getConcreteSubtypes(String simple) {
        List<String> values = concreteSubtypes.get(simple);
        return values == null ? Collections.<String>emptyList() : values;
    }

    private static String simpleOf(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static List<String> parseSubtypeList(String payload) {
        List<String> out = new ArrayList<>();
        if (payload == null || payload.trim().isEmpty()) {
            return out;
        }
        String[] tokens = payload.split(",");
        for (String token : tokens) {
            String t = token == null ? "" : token.trim();
            if (t.isEmpty()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }
}
