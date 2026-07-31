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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 */
package org.evosuite.llm.postprocess;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import javax.lang.model.SourceVersion;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Safety and type-matching policy for callable expressions in post-processing
 * assertions.
 */
final class PostProcessingCallablePolicy {

    private static final Set<String> BUILT_IN_PURE_STATIC_METHODS = allowedFields(
            "java.lang.Math#*",
            "java.util.Arrays#asList",
            "java.lang.Boolean#valueOf",
            "java.lang.Byte#valueOf",
            "java.lang.Byte#parseByte",
            "java.lang.Short#valueOf",
            "java.lang.Short#parseShort",
            "java.lang.Integer#valueOf",
            "java.lang.Integer#parseInt",
            "java.lang.Long#valueOf",
            "java.lang.Long#parseLong",
            "java.lang.Float#valueOf",
            "java.lang.Float#parseFloat",
            "java.lang.Double#valueOf",
            "java.lang.Double#parseDouble",
            "java.math.BigInteger#valueOf",
            "java.math.BigDecimal#valueOf",
            "java.util.Optional#empty",
            "java.util.Optional#of",
            "java.util.Optional#ofNullable");
    private static final Set<String> BUILT_IN_IMMUTABLE_TYPES = allowedFields(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.math.BigInteger",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.time.LocalDate",
            "java.time.LocalTime",
            "java.time.LocalDateTime",
            "java.time.Instant",
            "java.time.Duration",
            "java.time.Period");
    private static final Set<String> DENIED_STATIC_OWNERS = allowedFields(
            "java.lang.System",
            "java.lang.Runtime",
            "java.lang.Thread",
            "java.lang.ProcessBuilder",
            "java.io.File",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.nio.file.FileSystems",
            "java.lang.Class");
    private static final Set<String> DENIED_STATIC_METHODS = allowedFields(
            "java.lang.Math#random");
    private static final Set<String> DENIED_INSTANCE_METHODS = allowedFields(
            "wait",
            "notify",
            "notifyAll",
            "getClass",
            "hashCode",
            "finalize");

    interface ExpressionTypeResolver {
        ExprType resolve(Expression expression);
    }

    private final LlmPostProcessingResponseParser.ParseContext context;
    private final PostProcessingOptions options;
    private final ExpressionTypeResolver typeResolver;
    private final Set<StaticAllowlistEntry> staticAllowlistEntries;
    private final Set<String> immutableTypes;

    PostProcessingCallablePolicy(LlmPostProcessingResponseParser.ParseContext context,
                                 PostProcessingOptions options,
                                 ExpressionTypeResolver typeResolver) {
        this.context = context;
        this.options = options;
        this.typeResolver = typeResolver;
        this.staticAllowlistEntries = buildStaticAllowlist(options);
        this.immutableTypes = buildImmutableTypes(options);
    }

    boolean isInstanceCallScope(Expression scope) {
        if (scope instanceof NameExpr) {
            return context.hasVariableId(((NameExpr) scope).getNameAsString());
        }
        if (scope instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) scope;
            return methodCall.getScope().isPresent() && isInstanceCallScope(methodCall.getScope().get());
        }
        if (scope instanceof FieldAccessExpr) {
            return isInstanceCallScope(((FieldAccessExpr) scope).getScope());
        }
        return false;
    }

    boolean isCallableMethod(String receiverId, String ownerType, MethodCallExpr call) {
        for (LlmPostProcessingResponseParser.CallableMethod method : context.callableMethods()) {
            if (!java.util.Objects.equals(receiverId, method.receiverId())
                    || (ownerType != null && !java.util.Objects.equals(
                    canonicalOwnerType(ownerType), canonicalOwnerType(method.ownerType())))
                    || !java.util.Objects.equals(call.getNameAsString(), method.methodName())
                    || call.getArguments().size() != method.argumentCount()) {
                continue;
            }
            if (argumentsMatchDescriptor(call, method.signatureDescriptor())) {
                return true;
            }
        }
        return false;
    }

    String callableReturnType(String receiverId, String ownerType, MethodCallExpr call) {
        String selectedReturnType = null;
        int selectedScore = Integer.MAX_VALUE;
        for (LlmPostProcessingResponseParser.CallableMethod method : context.callableMethods()) {
            if (!java.util.Objects.equals(receiverId, method.receiverId())
                    || (ownerType != null && !java.util.Objects.equals(
                    canonicalOwnerType(ownerType), canonicalOwnerType(method.ownerType())))
                    || !java.util.Objects.equals(call.getNameAsString(), method.methodName())
                    || call.getArguments().size() != method.argumentCount()) {
                continue;
            }
            int score = descriptorMatchScore(call, method.signatureDescriptor());
            if (score >= 0 && score < selectedScore) {
                selectedScore = score;
                selectedReturnType = method.returnType();
            }
        }
        return selectedReturnType;
    }

    ExprType staticMethodReturnType(String owner, MethodCallExpr call) {
        String methodName = call.getNameAsString();
        String canonicalOwner = canonicalType(owner);
        if ("java.lang.Boolean".equals(canonicalOwner) && "valueOf".equals(methodName)) {
            return ExprType.reference("java.lang.Boolean");
        }
        if ("java.lang.Byte".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseByte".equals(methodName))) {
            return "parseByte".equals(methodName)
                    ? ExprType.primitive("byte") : ExprType.reference("java.lang.Byte");
        }
        if ("java.lang.Short".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseShort".equals(methodName))) {
            return "parseShort".equals(methodName)
                    ? ExprType.primitive("short") : ExprType.reference("java.lang.Short");
        }
        if ("java.lang.Integer".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseInt".equals(methodName))) {
            return "parseInt".equals(methodName)
                    ? ExprType.primitive("int") : ExprType.reference("java.lang.Integer");
        }
        if ("java.lang.Long".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseLong".equals(methodName))) {
            return "parseLong".equals(methodName)
                    ? ExprType.primitive("long") : ExprType.reference("java.lang.Long");
        }
        if ("java.lang.Float".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseFloat".equals(methodName))) {
            return "parseFloat".equals(methodName)
                    ? ExprType.primitive("float") : ExprType.reference("java.lang.Float");
        }
        if ("java.lang.Double".equals(canonicalOwner)
                && ("valueOf".equals(methodName) || "parseDouble".equals(methodName))) {
            return "parseDouble".equals(methodName)
                    ? ExprType.primitive("double") : ExprType.reference("java.lang.Double");
        }
        if ("java.math.BigInteger".equals(canonicalOwner) && "valueOf".equals(methodName)) {
            return ExprType.reference("java.math.BigInteger");
        }
        if ("java.math.BigDecimal".equals(canonicalOwner) && "valueOf".equals(methodName)) {
            return ExprType.reference("java.math.BigDecimal");
        }
        if ("java.util.Optional".equals(canonicalOwner)) {
            return ExprType.reference("java.util.Optional");
        }
        for (StaticAllowlistEntry entry : staticAllowlistEntries) {
            if (entry.builtIn || entry.signature == null
                    || !matchesStaticAllowlistEntry(owner, call, entry)) {
                continue;
            }
            JvmMethodDescriptor signature = entry.signature;
            if (signature != null && signature.isValid()) {
                return signature.returnType();
            }
        }
        return ExprType.unknown();
    }

    boolean containsDisallowedObjectConstruction(Expression expression) {
        for (ObjectCreationExpr objectCreation : expression.findAll(ObjectCreationExpr.class)) {
            if (!isAllowedImmutableConstructorType(objectCreation.getTypeAsString())) {
                return true;
            }
        }
        return false;
    }

    boolean containsDisallowedStaticMethodCall(Expression expression) {
        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            if (!methodCall.getScope().isPresent()) {
                return true;
            }
            Expression scope = methodCall.getScope().get();
            if (isInstanceCallScope(scope)) {
                continue;
            }
            if (isDeniedStaticCall(scope.toString(), methodCall.getNameAsString())
                    || !isAllowedStaticCall(scope.toString(), methodCall)) {
                return true;
            }
        }
        return false;
    }

    boolean containsDisallowedInstanceMethodCall(Expression expression) {
        for (MethodCallExpr methodCall : expression.findAll(MethodCallExpr.class)) {
            if (!methodCall.getScope().isPresent()) {
                continue;
            }
            Expression scope = methodCall.getScope().get();
            if (!isInstanceCallScope(scope)) {
                continue;
            }
            if (isDeniedInstanceCall(methodCall.getNameAsString())) {
                return true;
            }
            if (scope instanceof NameExpr) {
                String receiverId = ((NameExpr) scope).getNameAsString();
                if (!isCallableMethod(receiverId, null, methodCall)) {
                    return true;
                }
                continue;
            }
            if (!options.assertionPolicy().allowChainedCalls()) {
                return true;
            }
            ExprType receiverType = typeResolver.resolve(scope);
            if (!receiverType.isKnown() || !isCallableMethod(null, receiverType.typeName, methodCall)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDeniedStaticCall(String owner, String methodName) {
        boolean deniedOwner = false;
        for (String denied : DENIED_STATIC_OWNERS) {
            if (denied.equals(owner) || simpleName(denied).equals(owner)) {
                deniedOwner = true;
                break;
            }
        }
        return deniedOwner
                || DENIED_STATIC_METHODS.contains(owner + "#" + methodName)
                || DENIED_STATIC_METHODS.contains(simpleName(owner) + "#" + methodName);
    }

    private boolean isDeniedInstanceCall(String methodName) {
        return DENIED_INSTANCE_METHODS.contains(methodName);
    }

    private boolean isAllowedStaticCall(String owner, MethodCallExpr call) {
        for (StaticAllowlistEntry allowlistEntry : staticAllowlistEntries) {
            if (matchesStaticAllowlistEntry(owner, call, allowlistEntry)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesStaticAllowlistEntry(String owner, MethodCallExpr call,
                                                StaticAllowlistEntry entry) {
        if (!owner.equals(entry.owner) && !owner.equals(simpleName(entry.owner))) {
            return false;
        }
        if (entry.wildcard) {
            return entry.builtIn && !"random".equals(call.getNameAsString());
        }
        if (!call.getNameAsString().equals(entry.method)) {
            return false;
        }
        if (entry.signature == null) {
            return entry.builtIn;
        }
        return argumentsMatchDescriptor(call, entry.signature);
    }

    private static boolean isValidConfiguredStaticAllowlistEntry(String entry) {
        if (entry == null || entry.isEmpty()) {
            return false;
        }
        int separator = entry.indexOf('#');
        if (separator <= 0 || separator == entry.length() - 1) {
            return false;
        }
        String owner = entry.substring(0, separator);
        String member = entry.substring(separator + 1);
        if (!isDottedTypeName(owner) || "*".equals(member)) {
            return false;
        }
        int descriptorStart = member.indexOf('(');
        int descriptorEnd = member.lastIndexOf(')');
        if (descriptorStart <= 0 || descriptorEnd <= descriptorStart || descriptorEnd == member.length() - 1) {
            return false;
        }
        String methodName = member.substring(0, descriptorStart);
        JvmMethodDescriptor signature = JvmMethodDescriptor.parse(member.substring(descriptorStart));
        return SourceVersion.isIdentifier(methodName) && signature != null && signature.isValid();
    }

    private boolean argumentsMatchDescriptor(MethodCallExpr call, JvmMethodDescriptor signature) {
        return descriptorMatchScore(call, signature) >= 0;
    }

    private int descriptorMatchScore(MethodCallExpr call, JvmMethodDescriptor signature) {
        if (signature == null) {
            return call.getArguments().isEmpty() ? 0 : -1;
        }
        if (!signature.isValid() || signature.parameterDescriptors().size() != call.getArguments().size()) {
            return -1;
        }
        int score = 0;
        for (int i = 0; i < signature.parameterDescriptors().size(); i++) {
            int argumentScore = argumentCompatibilityScore(
                    typeResolver.resolve(call.getArgument(i)), signature.parameterTypes().get(i));
            if (argumentScore < 0) {
                return -1;
            }
            score += argumentScore;
        }
        return score;
    }

    private int argumentCompatibilityScore(ExprType actual, ExprType expectedType) {
        if (actual == null || !actual.isKnown()) {
            return -1;
        }
        if (actual.isNull()) {
            return expectedType.isReferenceLike() ? 5 : -1;
        }
        String expectedCanonical = ExprType.canonicalName(expectedType.typeName);
        if ("java.lang.Object".equals(expectedCanonical)) {
            return 10;
        }
        if (actual.isNumericLike()) {
            return numericInvocationConversionScore(actual.typeName, expectedType.typeName);
        }
        if (actual.isBooleanLike()) {
            return expectedType.isBooleanLike() ? 0 : -1;
        }
        if (actual.isCharLike()) {
            return expectedType.isCharLike() ? 0 : -1;
        }
        if (actual.isArray()) {
            return expectedType.isArray()
                    && expectedType.typeName.equals(ExprType.canonicalName(actual.typeName)) ? 0 : -1;
        }
        String actualType = ExprType.canonicalName(actual.typeName);
        if (actualType.equals(expectedCanonical)) {
            return 0;
        }
        try {
            ClassLoader loader = org.evosuite.TestGenerationContext.getInstance().getClassLoaderForSUT();
            Class<?> expectedClass = Class.forName(expectedCanonical, false, loader);
            Class<?> actualClass = Class.forName(actualType, false, loader);
            return expectedClass.isAssignableFrom(actualClass) ? 5 : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int numericInvocationConversionScore(String actualType, String expectedType) {
        String actualPrimitive = unboxedNumericType(actualType);
        String expectedPrimitive = unboxedNumericType(expectedType);
        if (actualPrimitive == null || expectedPrimitive == null) {
            return -1;
        }
        if (actualPrimitive.equals(expectedPrimitive)) {
            return 0;
        }
        boolean actualIsPrimitive = isNumericPrimitiveOrChar(actualType);
        boolean expectedIsPrimitive = isNumericPrimitiveOrChar(expectedType);
        if (actualIsPrimitive && !expectedIsPrimitive) {
            return -1;
        }
        if (!actualIsPrimitive && !expectedIsPrimitive) {
            return -1;
        }
        if ("char".equals(actualPrimitive)) {
            int expectedRank = numericRank(expectedPrimitive);
            return expectedRank >= numericRank("int") ? expectedRank - numericRank("int") + 1 : -1;
        }
        int actualRank = numericRank(actualPrimitive);
        int expectedRank = numericRank(expectedPrimitive);
        return actualRank >= 0 && expectedRank >= actualRank ? expectedRank - actualRank + 1 : -1;
    }

    private String unboxedNumericType(String typeName) {
        String canonical = canonicalType(typeName);
        if ("java.lang.Byte".equals(canonical)) return "byte";
        if ("java.lang.Short".equals(canonical)) return "short";
        if ("java.lang.Character".equals(canonical)) return "char";
        if ("java.lang.Integer".equals(canonical)) return "int";
        if ("java.lang.Long".equals(canonical)) return "long";
        if ("java.lang.Float".equals(canonical)) return "float";
        if ("java.lang.Double".equals(canonical)) return "double";
        return isNumericPrimitiveOrChar(canonical) ? canonical : null;
    }

    private boolean isNumericPrimitiveOrChar(String typeName) {
        String canonical = canonicalType(typeName);
        return "byte".equals(canonical) || "short".equals(canonical) || "char".equals(canonical)
                || "int".equals(canonical) || "long".equals(canonical) || "float".equals(canonical)
                || "double".equals(canonical);
    }

    private int numericRank(String primitive) {
        if ("byte".equals(primitive)) return 0;
        if ("short".equals(primitive)) return 1;
        if ("int".equals(primitive)) return 2;
        if ("long".equals(primitive)) return 3;
        if ("float".equals(primitive)) return 4;
        if ("double".equals(primitive)) return 5;
        return -1;
    }

    private static boolean isDottedTypeName(String owner) {
        String[] parts = owner.split("\\.");
        if (parts.length == 0) {
            return false;
        }
        for (String part : parts) {
            if (!SourceVersion.isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAllowedImmutableConstructorType(String typeName) {
        if (!options.assertionPolicy().allowImmutableConstructors()) {
            return false;
        }
        if (immutableTypes.contains(ExprType.canonicalName(typeName))) {
            return true;
        }
        return false;
    }

    private static Set<StaticAllowlistEntry> buildStaticAllowlist(PostProcessingOptions options) {
        Set<StaticAllowlistEntry> entries = new LinkedHashSet<>();
        for (String entry : BUILT_IN_PURE_STATIC_METHODS) {
            entries.add(StaticAllowlistEntry.parse(entry, true));
        }
        for (String entry : options.assertionPolicy().pureStaticAllowlist()) {
            if (isValidConfiguredStaticAllowlistEntry(entry)) {
                entries.add(StaticAllowlistEntry.parse(entry, false));
            }
        }
        return Collections.unmodifiableSet(entries);
    }

    private static Set<String> buildImmutableTypes(PostProcessingOptions options) {
        Set<String> types = new LinkedHashSet<>(BUILT_IN_IMMUTABLE_TYPES);
        for (String type : options.assertionPolicy().immutableTypes()) {
            String canonical = ExprType.canonicalName(type);
            types.add(canonical);
            types.add(simpleName(canonical));
        }
        return Collections.unmodifiableSet(types);
    }

    private static final class StaticAllowlistEntry {
        private final String owner;
        private final String method;
        private final JvmMethodDescriptor signature;
        private final boolean wildcard;
        private final boolean builtIn;

        private StaticAllowlistEntry(String owner, String method, JvmMethodDescriptor signature,
                                     boolean wildcard, boolean builtIn) {
            this.owner = owner;
            this.method = method;
            this.signature = signature;
            this.wildcard = wildcard;
            this.builtIn = builtIn;
        }

        private static StaticAllowlistEntry parse(String entry, boolean builtIn) {
            int separator = entry.indexOf('#');
            String owner = entry.substring(0, separator);
            String member = entry.substring(separator + 1);
            if ("*".equals(member)) {
                return new StaticAllowlistEntry(owner, null, null, true, builtIn);
            }
            int descriptorStart = member.indexOf('(');
            String method = descriptorStart < 0 ? member : member.substring(0, descriptorStart);
            JvmMethodDescriptor signature = descriptorStart < 0
                    ? null : JvmMethodDescriptor.parse(member.substring(descriptorStart));
            return new StaticAllowlistEntry(owner, method, signature, false, builtIn);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof StaticAllowlistEntry)) {
                return false;
            }
            StaticAllowlistEntry entry = (StaticAllowlistEntry) other;
            return owner.equals(entry.owner)
                    && java.util.Objects.equals(method, entry.method)
                    && java.util.Objects.equals(signature == null ? null : signature.descriptor(),
                    entry.signature == null ? null : entry.signature.descriptor());
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(owner, method,
                    signature == null ? null : signature.descriptor());
        }
    }

    private static Set<String> allowedFields(String... fields) {
        Set<String> result = new LinkedHashSet<>();
        Collections.addAll(result, fields);
        return Collections.unmodifiableSet(result);
    }

    private static String canonicalOwnerType(String typeName) {
        if (typeName == null) {
            return null;
        }
        String trimmed = typeName.trim();
        int genericStart = trimmed.indexOf('<');
        return genericStart < 0 ? trimmed : trimmed.substring(0, genericStart);
    }

    private static String canonicalType(String typeName) {
        return ExprType.canonicalName(typeName);
    }

    private static String simpleName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }
}
