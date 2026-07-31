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
        return new ExprType(Kind.ARRAY, componentType + "[]", componentType, arrayDepth);
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
        return reference(trimmed);
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
        return "boolean".equals(typeName) || "java.lang.Boolean".equals(canonicalWrapper(typeName))
                || "Boolean".equals(typeName);
    }

    boolean isBooleanLike() {
        return isBoolean();
    }

    boolean isCharLike() {
        return "char".equals(typeName)
                || "java.lang.Character".equals(canonicalWrapper(typeName))
                || "Character".equals(typeName);
    }

    boolean isNumeric() {
        return isNumericPrimitive(typeName) || isNumericWrapper(typeName);
    }

    boolean isNumericLike() {
        return isNumeric() || isCharLike();
    }

    boolean isFloatingPoint() {
        return isFloatLike() || isDoubleLike();
    }

    boolean isFloatLike() {
        return "float".equals(typeName)
                || "java.lang.Float".equals(canonicalWrapper(typeName))
                || "Float".equals(typeName);
    }

    boolean isDoubleLike() {
        return "double".equals(typeName)
                || "java.lang.Double".equals(canonicalWrapper(typeName))
                || "Double".equals(typeName);
    }

    boolean isLongLike() {
        return "long".equals(typeName)
                || "java.lang.Long".equals(canonicalWrapper(typeName))
                || "Long".equals(typeName);
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
        String canonical = canonicalWrapper(typeName);
        return "java.lang.Byte".equals(canonical)
                || "java.lang.Short".equals(canonical)
                || "java.lang.Integer".equals(canonical)
                || "java.lang.Long".equals(canonical)
                || "java.lang.Float".equals(canonical)
                || "java.lang.Double".equals(canonical);
    }

    private static String canonicalWrapper(String typeName) {
        if ("Byte".equals(typeName)) {
            return "java.lang.Byte";
        }
        if ("Short".equals(typeName)) {
            return "java.lang.Short";
        }
        if ("Character".equals(typeName)) {
            return "java.lang.Character";
        }
        if ("Integer".equals(typeName)) {
            return "java.lang.Integer";
        }
        if ("Long".equals(typeName)) {
            return "java.lang.Long";
        }
        if ("Float".equals(typeName)) {
            return "java.lang.Float";
        }
        if ("Double".equals(typeName)) {
            return "java.lang.Double";
        }
        if ("Boolean".equals(typeName)) {
            return "java.lang.Boolean";
        }
        return typeName;
    }
}
