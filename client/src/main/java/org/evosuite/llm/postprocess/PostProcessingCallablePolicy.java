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
            "Math#*",
            "java.util.Arrays#asList",
            "Arrays#asList",
            "java.lang.Boolean#valueOf",
            "Boolean#valueOf",
            "java.lang.Byte#valueOf",
            "Byte#valueOf",
            "java.lang.Byte#parseByte",
            "Byte#parseByte",
            "java.lang.Short#valueOf",
            "Short#valueOf",
            "java.lang.Short#parseShort",
            "Short#parseShort",
            "java.lang.Integer#valueOf",
            "Integer#valueOf",
            "java.lang.Integer#parseInt",
            "Integer#parseInt",
            "java.lang.Long#valueOf",
            "Long#valueOf",
            "java.lang.Long#parseLong",
            "Long#parseLong",
            "java.lang.Float#valueOf",
            "Float#valueOf",
            "java.lang.Float#parseFloat",
            "Float#parseFloat",
            "java.lang.Double#valueOf",
            "Double#valueOf",
            "java.lang.Double#parseDouble",
            "Double#parseDouble",
            "java.math.BigInteger#valueOf",
            "BigInteger#valueOf",
            "java.math.BigDecimal#valueOf",
            "BigDecimal#valueOf",
            "java.util.Optional#empty",
            "Optional#empty",
            "java.util.Optional#of",
            "Optional#of",
            "java.util.Optional#ofNullable",
            "Optional#ofNullable");
    private static final Set<String> BUILT_IN_IMMUTABLE_TYPES = allowedFields(
            "java.lang.String", "String",
            "java.lang.Boolean", "Boolean",
            "java.lang.Byte", "Byte",
            "java.lang.Short", "Short",
            "java.lang.Character", "Character",
            "java.lang.Integer", "Integer",
            "java.lang.Long", "Long",
            "java.lang.Float", "Float",
            "java.lang.Double", "Double",
            "java.math.BigInteger", "BigInteger",
            "java.math.BigDecimal", "BigDecimal",
            "java.util.UUID", "UUID",
            "java.time.LocalDate", "LocalDate",
            "java.time.LocalTime", "LocalTime",
            "java.time.LocalDateTime", "LocalDateTime",
            "java.time.Instant", "Instant",
            "java.time.Duration", "Duration",
            "java.time.Period", "Period");
    private static final Set<String> DENIED_STATIC_OWNERS = allowedFields(
            "java.lang.System", "System",
            "java.lang.Runtime", "Runtime",
            "java.lang.Thread", "Thread",
            "java.lang.ProcessBuilder", "ProcessBuilder",
            "java.io.File", "File",
            "java.nio.file.Files", "Files",
            "java.nio.file.Paths", "Paths",
            "java.nio.file.FileSystems", "FileSystems",
            "java.lang.Class", "Class");
    private static final Set<String> DENIED_STATIC_METHODS = allowedFields(
            "java.lang.Math#random",
            "Math#random");
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

    PostProcessingCallablePolicy(LlmPostProcessingResponseParser.ParseContext context,
                                 PostProcessingOptions options,
                                 ExpressionTypeResolver typeResolver) {
        this.context = context;
        this.options = options;
        this.typeResolver = typeResolver;
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
        for (String entry : allStaticAllowlistEntries()) {
            if (!isBuiltInStaticEntry(entry) && matchesStaticAllowlistEntry(owner, call, entry)) {
                int descriptorStart = entry.indexOf('(');
                int close = entry.indexOf(')', descriptorStart + 1);
                if (descriptorStart > 0 && close > descriptorStart) {
                    JvmMethodDescriptor signature = JvmMethodDescriptor.parse(
                            entry.substring(descriptorStart));
                    String returnType = signature == null || !signature.isValid()
                            ? null : descriptorTypeName(signature.returnDescriptor());
                    if (returnType != null) {
                        return ExprType.fromTypeName(returnType);
                    }
                }
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
        return DENIED_STATIC_OWNERS.contains(owner)
                || DENIED_STATIC_METHODS.contains(owner + "#" + methodName)
                || DENIED_STATIC_METHODS.contains(simpleName(owner) + "#" + methodName);
    }

    private boolean isDeniedInstanceCall(String methodName) {
        return DENIED_INSTANCE_METHODS.contains(methodName);
    }

    private boolean isAllowedStaticCall(String owner, MethodCallExpr call) {
        for (String allowlistEntry : allStaticAllowlistEntries()) {
            if (matchesStaticAllowlistEntry(owner, call, allowlistEntry)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> allStaticAllowlistEntries() {
        Set<String> entries = new LinkedHashSet<>(BUILT_IN_PURE_STATIC_METHODS);
        if (!options.assertionPolicy().pureStaticAllowlist().isEmpty()) {
            for (String entry : options.assertionPolicy().pureStaticAllowlist()) {
                if (isValidConfiguredStaticAllowlistEntry(entry)) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private boolean matchesStaticAllowlistEntry(String owner, MethodCallExpr call, String entry) {
        int separator = entry.indexOf('#');
        if (separator <= 0 || separator == entry.length() - 1) {
            return false;
        }
        String allowedOwner = entry.substring(0, separator);
        String allowedMember = entry.substring(separator + 1);
        if (!owner.equals(allowedOwner) && !owner.equals(simpleName(allowedOwner))) {
            return false;
        }
        if ("*".equals(allowedMember)) {
            return isBuiltInStaticEntry(entry) && !"random".equals(call.getNameAsString());
        }
        int descriptorStart = allowedMember.indexOf('(');
        String allowedMethod = descriptorStart < 0 ? allowedMember : allowedMember.substring(0, descriptorStart);
        if (!call.getNameAsString().equals(allowedMethod)) {
            return false;
        }
        if (descriptorStart < 0) {
            return isBuiltInStaticEntry(entry);
        }
        return argumentsMatchDescriptor(call, allowedMember.substring(descriptorStart));
    }

    private boolean isBuiltInStaticEntry(String entry) {
        return BUILT_IN_PURE_STATIC_METHODS.contains(entry);
    }

    private boolean isValidConfiguredStaticAllowlistEntry(String entry) {
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

    private boolean argumentsMatchDescriptor(MethodCallExpr call, String descriptor) {
        return argumentsMatchDescriptor(call, JvmMethodDescriptor.parse(descriptor));
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
                    typeResolver.resolve(call.getArgument(i)), signature.parameterDescriptors().get(i));
            if (argumentScore < 0) {
                return -1;
            }
            score += argumentScore;
        }
        return score;
    }

    private int argumentCompatibilityScore(ExprType actual, String expectedDescriptor) {
        if (actual == null || !actual.isKnown()) {
            return -1;
        }
        if (actual.isNull()) {
            return expectedDescriptor.startsWith("L") || expectedDescriptor.startsWith("[") ? 5 : -1;
        }
        String expectedType = descriptorTypeName(expectedDescriptor);
        if (expectedType == null) {
            return -1;
        }
        String expectedCanonical = canonicalType(expectedType);
        if ("java.lang.Object".equals(expectedCanonical)) {
            return 10;
        }
        if (actual.isNumericLike()) {
            return numericInvocationConversionScore(actual.typeName, expectedType);
        }
        if (actual.isBooleanLike()) {
            return ExprType.fromTypeName(expectedType).isBooleanLike() ? 0 : -1;
        }
        if (actual.isCharLike()) {
            return ExprType.fromTypeName(expectedType).isCharLike() ? 0 : -1;
        }
        if (actual.isArray()) {
            return expectedDescriptor.startsWith("[")
                    && expectedType.equals(canonicalType(actual.typeName)) ? 0 : -1;
        }
        String actualType = canonicalType(actual.typeName);
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

    private String descriptorTypeName(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return null;
        }
        int arrays = 0;
        while (arrays < descriptor.length() && descriptor.charAt(arrays) == '[') {
            arrays++;
        }
        if (arrays > 0) {
            String component = descriptorTypeName(descriptor.substring(arrays));
            if (component == null) {
                return null;
            }
            StringBuilder result = new StringBuilder(component);
            for (int i = 0; i < arrays; i++) {
                result.append("[]");
            }
            return result.toString();
        }
        switch (descriptor.charAt(0)) {
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            case 'L':
                return descriptor.endsWith(";")
                        ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.') : null;
            default: return null;
        }
    }

    private boolean isDottedTypeName(String owner) {
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
        if (BUILT_IN_IMMUTABLE_TYPES.contains(typeName)) {
            return true;
        }
        for (String configuredType : options.assertionPolicy().immutableTypes()) {
            if (configuredType.equals(typeName) || simpleName(configuredType).equals(typeName)) {
                return true;
            }
        }
        return false;
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
        return trimmed;
    }

    private static String simpleName(String typeName) {
        int lastDot = typeName.lastIndexOf('.');
        return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    }
}
