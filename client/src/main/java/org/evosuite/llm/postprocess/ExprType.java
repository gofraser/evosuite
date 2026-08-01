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

    private ExprType(Kind kind, String typeName, String componentType, int arrayDepth) {
        this.kind = kind;
        this.typeName = typeName;
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

    static String canonicalName(String typeName) {
        if (typeName == null) {
            return "";
        }
        String trimmed = typeName.trim();
        if ("String".equals(trimmed)) return "java.lang.String";
        if ("Boolean".equals(trimmed)) return "java.lang.Boolean";
        if ("Byte".equals(trimmed)) return "java.lang.Byte";
        if ("Short".equals(trimmed)) return "java.lang.Short";
        if ("Character".equals(trimmed)) return "java.lang.Character";
        if ("Integer".equals(trimmed)) return "java.lang.Integer";
        if ("Long".equals(trimmed)) return "java.lang.Long";
        if ("Float".equals(trimmed)) return "java.lang.Float";
        if ("Double".equals(trimmed)) return "java.lang.Double";
        if ("Object".equals(trimmed)) return "java.lang.Object";
        if ("BigInteger".equals(trimmed)) return "java.math.BigInteger";
        if ("BigDecimal".equals(trimmed)) return "java.math.BigDecimal";
        if ("Optional".equals(trimmed)) return "java.util.Optional";
        if ("UUID".equals(trimmed)) return "java.util.UUID";
        if ("LocalDate".equals(trimmed)) return "java.time.LocalDate";
        if ("LocalTime".equals(trimmed)) return "java.time.LocalTime";
        if ("LocalDateTime".equals(trimmed)) return "java.time.LocalDateTime";
        if ("Instant".equals(trimmed)) return "java.time.Instant";
        if ("Duration".equals(trimmed)) return "java.time.Duration";
        if ("Period".equals(trimmed)) return "java.time.Period";
        return trimmed;
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
        String canonical = canonicalName(typeName);
        return "boolean".equals(canonical) || "java.lang.Boolean".equals(canonical);
    }

    boolean isCharLike() {
        String canonical = canonicalName(typeName);
        return "char".equals(canonical) || "java.lang.Character".equals(canonical);
    }

    boolean isNumeric() {
        String canonical = canonicalName(typeName);
        return isNumericPrimitive(canonical) || isNumericWrapper(canonical);
    }

    boolean isNumericLike() {
        return isNumeric() || isCharLike();
    }

    boolean isFloatingPoint() {
        return isFloatLike() || isDoubleLike();
    }

    boolean isFloatLike() {
        String canonical = canonicalName(typeName);
        return "float".equals(canonical) || "java.lang.Float".equals(canonical);
    }

    boolean isDoubleLike() {
        String canonical = canonicalName(typeName);
        return "double".equals(canonical) || "java.lang.Double".equals(canonical);
    }

    boolean isLongLike() {
        String canonical = canonicalName(typeName);
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
