/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.llm.postprocess;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable expression type classification used by assertion validation. */
final class ExprType {

    private enum Kind {
        UNKNOWN,
        NULL,
        PRIMITIVE,
        REFERENCE,
        ARRAY
    }

    private final Kind kind;
    final String typeName;
    final String componentType;
    final int arrayDepth;
    /**
     * {@link #canonicalName(String)} of {@link #typeName}, which the type predicates read.
     */
    private final String canonicalTypeName;

    private ExprType(Kind kind, String typeName, String componentType, int arrayDepth) {
        this.kind = kind;
        this.typeName = typeName;
        this.canonicalTypeName = canonicalName(typeName);
        this.componentType = componentType;
        this.arrayDepth = arrayDepth;
    }

    static ExprType unknown() {
        return new ExprType(Kind.UNKNOWN, null, null, 0);
    }

    static ExprType nullType() {
        return new ExprType(Kind.NULL, "null", null, 0);
    }

    static ExprType primitive(String typeName) {
        return new ExprType(Kind.PRIMITIVE, typeName, null, 0);
    }

    static ExprType reference(String typeName) {
        return new ExprType(Kind.REFERENCE, typeName, null, 0);
    }

    static ExprType array(String componentType, int arrayDepth) {
        String canonicalComponent = canonicalName(componentType);
        int depth = Math.max(1, arrayDepth);
        StringBuilder name = new StringBuilder(canonicalComponent);
        for (int i = 0; i < depth; i++) {
            name.append("[]");
        }
        return new ExprType(Kind.ARRAY, name.toString(), canonicalComponent, depth);
    }

    static ExprType fromTypeName(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) {
            return unknown();
        }
        String trimmed = typeName.trim();
        int depth = 0;
        while (trimmed.endsWith("[]")) {
            depth++;
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        if (depth > 0) {
            return array(trimmed, depth);
        }
        if (isPrimitiveName(trimmed)) {
            return primitive(trimmed);
        }
        return reference(canonicalName(trimmed));
    }

    /** Canonical name of a type with any generic arguments dropped, or null for null. */
    static String canonicalRawName(String typeName) {
        if (typeName == null) {
            return null;
        }
        String trimmed = typeName.trim();
        int genericStart = trimmed.indexOf('<');
        return canonicalName(genericStart < 0 ? trimmed : trimmed.substring(0, genericStart));
    }

    /** Simple names of well-known types, mapped to their fully qualified names. */
    private static final Map<String, String> QUALIFIED_NAMES = qualifiedNames();

    private static Map<String, String> qualifiedNames() {
        Map<String, String> names = new HashMap<>();
        names.put("String", "java.lang.String");
        names.put("Boolean", "java.lang.Boolean");
        names.put("Byte", "java.lang.Byte");
        names.put("Short", "java.lang.Short");
        names.put("Character", "java.lang.Character");
        names.put("Integer", "java.lang.Integer");
        names.put("Long", "java.lang.Long");
        names.put("Float", "java.lang.Float");
        names.put("Double", "java.lang.Double");
        names.put("Object", "java.lang.Object");
        names.put("BigInteger", "java.math.BigInteger");
        names.put("BigDecimal", "java.math.BigDecimal");
        names.put("Optional", "java.util.Optional");
        names.put("UUID", "java.util.UUID");
        names.put("LocalDate", "java.time.LocalDate");
        names.put("LocalTime", "java.time.LocalTime");
        names.put("LocalDateTime", "java.time.LocalDateTime");
        names.put("Instant", "java.time.Instant");
        names.put("Duration", "java.time.Duration");
        names.put("Period", "java.time.Period");
        return Collections.unmodifiableMap(names);
    }

    static String canonicalName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String trimmed = typeName.trim();
        return QUALIFIED_NAMES.getOrDefault(trimmed, trimmed);
    }

    private static boolean isPrimitiveName(String typeName) {
        return "boolean".equals(typeName)
                || "byte".equals(typeName)
                || "short".equals(typeName)
                || "char".equals(typeName)
                || "int".equals(typeName)
                || "long".equals(typeName)
                || "float".equals(typeName)
                || "double".equals(typeName);
    }

    boolean isKnown() {
        return kind != Kind.UNKNOWN;
    }

    boolean isNull() {
        return kind == Kind.NULL;
    }

    boolean isArray() {
        return kind == Kind.ARRAY;
    }

    boolean isReferenceLike() {
        return kind == Kind.REFERENCE || kind == Kind.ARRAY || kind == Kind.NULL;
    }

    boolean isBoolean() {
        return isBooleanLike();
    }

    boolean isBooleanLike() {
        String canonical = canonicalTypeName;
        return "boolean".equals(canonical) || "java.lang.Boolean".equals(canonical);
    }

    boolean isCharLike() {
        String canonical = canonicalTypeName;
        return "char".equals(canonical) || "java.lang.Character".equals(canonical);
    }

    boolean isNumeric() {
        String canonical = canonicalTypeName;
        return isNumericPrimitive(canonical) || isNumericWrapper(canonical);
    }

    boolean isNumericLike() {
        return isNumeric() || isCharLike();
    }

    boolean isFloatingPoint() {
        return isFloatLike() || isDoubleLike();
    }

    boolean isFloatLike() {
        String canonical = canonicalTypeName;
        return "float".equals(canonical) || "java.lang.Float".equals(canonical);
    }

    boolean isDoubleLike() {
        String canonical = canonicalTypeName;
        return "double".equals(canonical) || "java.lang.Double".equals(canonical);
    }

    boolean isLongLike() {
        String canonical = canonicalTypeName;
        return "long".equals(canonical) || "java.lang.Long".equals(canonical);
    }

    private static boolean isNumericPrimitive(String typeName) {
        return "byte".equals(typeName)
                || "short".equals(typeName)
                || "int".equals(typeName)
                || "long".equals(typeName)
                || "float".equals(typeName)
                || "double".equals(typeName);
    }

    private static boolean isNumericWrapper(String typeName) {
        return "java.lang.Byte".equals(typeName)
                || "java.lang.Short".equals(typeName)
                || "java.lang.Integer".equals(typeName)
                || "java.lang.Long".equals(typeName)
                || "java.lang.Float".equals(typeName)
                || "java.lang.Double".equals(typeName);
    }
}
