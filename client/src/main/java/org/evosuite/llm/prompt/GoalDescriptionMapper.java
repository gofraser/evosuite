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
package org.evosuite.llm.prompt;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.coverage.cbranch.CBranchTestFitness;
import org.evosuite.coverage.dataflow.AllDefsCoverageTestFitness;
import org.evosuite.coverage.dataflow.DefUseCoverageTestFitness;
import org.evosuite.coverage.exception.ExceptionCoverageTestFitness;
import org.evosuite.coverage.ibranch.IBranchTestFitness;
import org.evosuite.coverage.io.input.InputCoverageTestFitness;
import org.evosuite.coverage.io.output.OutputCoverageTestFitness;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.method.MethodCoverageTestFitness;
import org.evosuite.coverage.method.MethodNoExceptionCoverageTestFitness;
import org.evosuite.coverage.mutation.MutationTestFitness;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts {@link TestFitnessFunction} instances into human-readable
 * descriptions suitable for LLM prompts.
 *
 * <p>Each concrete fitness function subtype is mapped to a concise,
 * LLM-friendly string that avoids internal identifiers (instruction IDs,
 * bytecode opcodes, branch IDs) and instead uses method names, line numbers,
 * and natural-language descriptions.
 */
public class GoalDescriptionMapper {

    private static final Pattern METHOD_DESC_PATTERN =
            Pattern.compile("^([^(]+)(\\(.*\\).*)$");

    private static final Map<String, String> VALUE_DESCRIPTOR_MAP = new LinkedHashMap<>();

    static {
        VALUE_DESCRIPTOR_MAP.put("NUM_NEGATIVE", "negative number");
        VALUE_DESCRIPTOR_MAP.put("NUM_ZERO", "zero");
        VALUE_DESCRIPTOR_MAP.put("NUM_POSITIVE", "positive number");
        VALUE_DESCRIPTOR_MAP.put("CHAR_ALPHA", "alphabetic char");
        VALUE_DESCRIPTOR_MAP.put("CHAR_DIGIT", "digit char");
        VALUE_DESCRIPTOR_MAP.put("CHAR_OTHER", "other char");
        VALUE_DESCRIPTOR_MAP.put("BOOL_TRUE", "true");
        VALUE_DESCRIPTOR_MAP.put("BOOL_FALSE", "false");
        VALUE_DESCRIPTOR_MAP.put("REF_NULL", "null");
        VALUE_DESCRIPTOR_MAP.put("REF_NONNULL", "non-null");
        VALUE_DESCRIPTOR_MAP.put("STRING_EMPTY", "empty string");
        VALUE_DESCRIPTOR_MAP.put("STRING_NONEMPTY", "non-empty string");
        VALUE_DESCRIPTOR_MAP.put("ARRAY_EMPTY", "empty array");
        VALUE_DESCRIPTOR_MAP.put("ARRAY_NONEMPTY", "non-empty array");
    }

    /**
     * Returns a human-readable description of the given coverage goal.
     *
     * @param goal the fitness function to describe
     * @return a concise, LLM-friendly description
     */
    public String describe(TestFitnessFunction goal) {
        if (goal instanceof BranchCoverageTestFitness) {
            return describeBranch((BranchCoverageTestFitness) goal);
        }
        if (goal instanceof OnlyBranchCoverageTestFitness) {
            return describeBranch((OnlyBranchCoverageTestFitness) goal);
        }
        if (goal instanceof CBranchTestFitness) {
            return describeCBranch((CBranchTestFitness) goal);
        }
        if (goal instanceof IBranchTestFitness) {
            return describeIBranch((IBranchTestFitness) goal);
        }
        if (goal instanceof LineCoverageTestFitness) {
            return describeLine((LineCoverageTestFitness) goal);
        }
        if (goal instanceof MethodCoverageTestFitness) {
            return describeMethod((MethodCoverageTestFitness) goal);
        }
        if (goal instanceof MethodNoExceptionCoverageTestFitness) {
            return describeMethodNoEx((MethodNoExceptionCoverageTestFitness) goal);
        }
        if (goal instanceof ExceptionCoverageTestFitness) {
            return describeException((ExceptionCoverageTestFitness) goal);
        }
        if (goal instanceof InputCoverageTestFitness) {
            return describeInput((InputCoverageTestFitness) goal);
        }
        if (goal instanceof OutputCoverageTestFitness) {
            return describeOutput((OutputCoverageTestFitness) goal);
        }
        if (goal instanceof MutationTestFitness) {
            return describeMutation((MutationTestFitness) goal);
        }
        if (goal instanceof StatementCoverageTestFitness) {
            return describeStatement((StatementCoverageTestFitness) goal);
        }
        if (goal instanceof DefUseCoverageTestFitness) {
            return describeDefUse((DefUseCoverageTestFitness) goal);
        }
        if (goal instanceof AllDefsCoverageTestFitness) {
            return describeAllDefs((AllDefsCoverageTestFitness) goal);
        }
        return goal.toString();
    }

    /**
     * Extracts a human-readable method name from a goal (without class prefix).
     * Used for grouping goals by method.
     *
     * @param goal the fitness function
     * @return the clean method name, or empty string if not extractable
     */
    public String extractMethodName(TestFitnessFunction goal) {
        return describeTarget(goal).getMethodName();
    }

    /**
     * Extracts a class-qualified method label from a goal when available.
     *
     * @param goal the fitness function
     * @return class-qualified method label, method name, or empty string
     */
    public String extractQualifiedMethodLabel(TestFitnessFunction goal) {
        return describeTarget(goal).getQualifiedMethodName();
    }

    /**
     * Builds structured prompt-facing target metadata for a coverage goal.
     *
     * @param goal the fitness function
     * @return immutable target metadata
     */
    public GoalTarget describeTarget(TestFitnessFunction goal) {
        if (goal == null) {
            return GoalTarget.empty();
        }
        return describeTarget(goal.getTargetClass(), goal.getTargetMethod());
    }

    /**
     * Builds structured prompt-facing target metadata from raw class and method identifiers.
     *
     * @param className target class name
     * @param rawMethodName target method identifier
     * @return immutable target metadata
     */
    public GoalTarget describeTarget(String className, String rawMethodName) {
        return new GoalTarget(safeTrim(className),
                cleanMethodName(rawMethodName),
                baseMethodName(rawMethodName));
    }

    /**
     * Builds structured prompt-facing metadata for a method-like operation identified by a goal.
     *
     * @param goal the goal
     * @return immutable operation metadata
     */
    public OperationTarget describeMethodOperation(TestFitnessFunction goal) {
        GoalTarget target = describeTarget(goal);
        return describeMethodOperation(target.getClassName(), target.getMethodName(), target.getExecutionKey(),
                target.getBaseMethodName(),
                qualifiedSignatureKey(target.getClassName(), target.getMethodName()));
    }

    /**
     * Builds structured prompt-facing metadata for a class-qualified method identifier.
     *
     * @param qualifiedMethodName class-qualified method identifier
     * @return immutable operation metadata
     */
    public OperationTarget describeQualifiedMethodOperation(String qualifiedMethodName) {
        String raw = safeTrim(qualifiedMethodName);
        if (raw.isEmpty()) {
            return OperationTarget.empty();
        }
        int lastDot = raw.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= raw.length() - 1) {
            return describeMethodOperation("", raw);
        }
        return describeMethodOperation(raw.substring(0, lastDot), raw.substring(lastDot + 1));
    }

    /**
     * Builds structured prompt-facing metadata for a method-like operation.
     *
     * @param className declaring class
     * @param rawMethodName raw method identifier, optionally with descriptor
     * @return immutable operation metadata
     */
    public OperationTarget describeMethodOperation(String className, String rawMethodName) {
        return describeMethodOperation(className, cleanMethodName(rawMethodName),
                qualifiedExecutionKey(className, baseMethodName(rawMethodName)),
                baseMethodName(rawMethodName),
                qualifiedSignatureKey(className, rawMethodName));
    }

    /**
     * Builds structured prompt-facing metadata for an observed direct method invocation.
     *
     * @param statement the method statement
     * @return immutable operation metadata
     */
    public OperationTarget describeMethodOperation(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return OperationTarget.empty();
        }
        Method method = statement.getMethod().getMethod();
        String className = observedMethodOwner(statement, method);
        String rawMethodName = statement.getMethod().getNameWithDescriptor();
        if (rawMethodName == null || rawMethodName.isEmpty()) {
            rawMethodName = method.getName() + Type.getMethodDescriptor(method);
        }
        return describeMethodOperation(className, rawMethodName);
    }

    private String observedMethodOwner(MethodStatement statement, Method method) {
        if (statement != null && statement.getMethod() != null && !statement.getMethod().isStatic()
                && statement.getCallee() != null && statement.getCallee().getVariableClass() != null) {
            return statement.getCallee().getVariableClass().getName();
        }
        Class<?> declaringClass = method == null ? null : method.getDeclaringClass();
        return declaringClass == null ? "" : declaringClass.getName();
    }

    /**
     * Builds structured prompt-facing metadata for an observed constructor acquisition step.
     *
     * @param statement the constructor statement
     * @return immutable operation metadata
     */
    public OperationTarget describeConstructorOperation(ConstructorStatement statement) {
        if (statement == null || statement.getConstructor() == null
                || statement.getConstructor().getDeclaringClass() == null) {
            return OperationTarget.empty();
        }
        String className = statement.getConstructor().getDeclaringClass().getName();
        String rawConstructor = statement.getConstructor().getNameWithDescriptor();
        if (rawConstructor == null || rawConstructor.isEmpty()) {
            rawConstructor = statement.getConstructor().getDescriptor();
        }
        return describeConstructorOperation(className, rawConstructor);
    }

    /**
     * Builds structured prompt-facing metadata for a constructor acquisition step.
     *
     * @param className declaring class
     * @param rawConstructorName raw constructor identifier or descriptor
     * @return immutable operation metadata
     */
    public OperationTarget describeConstructorOperation(String className, String rawConstructorName) {
        String owner = safeTrim(className);
        if (owner.isEmpty()) {
            return OperationTarget.empty();
        }
        return new OperationTarget(owner,
                cleanConstructorLabel(owner, rawConstructorName),
                owner + ".<init>",
                "<init>",
                qualifiedSignatureKey(owner, "<init>" + safeTrim(extractDescriptor(rawConstructorName))));
    }

    private static String extractDescriptor(String raw) {
        String stripped = stripOwningClass(raw);
        if (stripped.isEmpty()) {
            return "";
        }
        int start = stripped.indexOf('(');
        if (start < 0) {
            return "";
        }
        return stripped.substring(start);
    }

    /**
     * Builds structured prompt-facing metadata for a branch goal.
     *
     * @param goal the branch goal
     * @return immutable branch metadata
     */
    public BranchTarget describeBranchTarget(BranchCoverageGoal goal) {
        if (goal == null) {
            return BranchTarget.empty();
        }
        Branch branch = goal.getBranch();
        GoalTarget target = branch == null
                ? describeTarget(null, goal.getMethodName())
                : describeTarget(branch.getClassName(), branch.getMethodName());
        int lineNumber = goal.getLineNumber();
        if (branch == null) {
            return BranchTarget.root(target, lineNumber);
        }
        boolean switchCaseBranch = branch.isSwitchCaseBranch();
        return BranchTarget.regular(target,
                lineNumber,
                goal.getValue(),
                switchCaseBranch,
                switchCaseBranch && branch.isDefaultCase(),
                switchCaseBranch ? branch.getTargetCaseValue() : null);
    }

    /**
     * Builds structured prompt-facing metadata for a branch when only the branch instance is available.
     *
     * @param branch the branch
     * @param desiredValue desired branch outcome
     * @return immutable branch metadata
     */
    public BranchTarget describeBranchTarget(Branch branch, boolean desiredValue) {
        if (branch == null) {
            return BranchTarget.empty();
        }
        GoalTarget target = describeTarget(branch.getClassName(), branch.getMethodName());
        int lineNumber = branch.getInstruction() == null ? -1 : branch.getInstruction().getLineNumber();
        boolean switchCaseBranch = branch.isSwitchCaseBranch();
        return BranchTarget.regular(target,
                lineNumber,
                desiredValue,
                switchCaseBranch,
                switchCaseBranch && branch.isDefaultCase(),
                switchCaseBranch ? branch.getTargetCaseValue() : null);
    }

    private String describeBranch(BranchCoverageTestFitness goal) {
        return describeBranchGoal(goal.getBranchGoal());
    }

    private String describeBranch(OnlyBranchCoverageTestFitness goal) {
        return describeBranchGoal(goal.getBranchGoal());
    }

    private String describeBranchGoal(BranchCoverageGoal goal) {
        return describeBranchTarget(goal).asGoalDescription();
    }

    private String describeCBranch(CBranchTestFitness goal) {
        String base = describeBranchGoal(goal.getBranchGoal());
        return base + " (context-sensitive)";
    }

    private String describeIBranch(IBranchTestFitness goal) {
        String base = describeBranchGoal(goal.getBranchGoal());
        return base + " (interprocedural)";
    }

    private String describeLine(LineCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        if (method.isEmpty()) {
            return "Line " + goal.getLine();
        }
        return "Line " + goal.getLine() + " in " + method;
    }

    private String describeMethod(MethodCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        return "Method " + method;
    }

    private String describeMethodNoEx(MethodNoExceptionCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        return "Method " + method + " (no exception)";
    }

    private String describeException(ExceptionCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        Class<?> exClass = goal.getExceptionClass();
        String exName = exClass != null ? simpleName(exClass.getName()) : "Exception";
        String type = describeExceptionType(goal);
        return method + " throws " + exName + " (" + type + ")";
    }

    private String describeExceptionType(ExceptionCoverageTestFitness goal) {
        String key = goal.getKey();
        if (key.endsWith("_EXPLICIT")) {
            return "explicit";
        }
        if (key.endsWith("_IMPLICIT")) {
            return "implicit";
        }
        if (key.endsWith("_DECLARED")) {
            return "declared";
        }
        return "unknown";
    }

    private String describeInput(InputCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        String desc = humanizeValueDescriptor(goal.getValueDescriptor());
        // InputCoverageTestFitness doesn't expose getGoal(); use toString() to extract arg index
        String raw = goal.toString();
        // Format: [Input]: className.method[argIndex]:descriptor
        int bracketOpen = raw.indexOf('[', raw.indexOf(']') + 1);
        int bracketClose = raw.indexOf(']', bracketOpen + 1);
        String argIdx = "?";
        if (bracketOpen >= 0 && bracketClose > bracketOpen) {
            argIdx = raw.substring(bracketOpen + 1, bracketClose);
        }
        return "Input " + method + " arg[" + argIdx + "]: " + desc;
    }

    private String describeOutput(OutputCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getMethod());
        String desc = humanizeValueDescriptor(goal.getValueDescriptor());
        return "Output " + method + ": " + desc;
    }

    private String describeMutation(MutationTestFitness goal) {
        String className = goal.getTargetClass();
        String method = cleanMethodName(goal.getTargetMethod());
        String name = "";
        int line = 0;
        if (goal.getMutation() != null) {
            name = goal.getMutation().getMutationName();
            line = goal.getMutation().getLineNumber();
        }
        if (name == null || name.isEmpty()) {
            name = "mutation";
        }
        String humanName = humanizeMutationName(name);
        if (line > 0) {
            return "Mutation in " + method + " at line " + line + ": " + humanName;
        }
        return "Mutation in " + method + ": " + humanName;
    }

    private String describeStatement(StatementCoverageTestFitness goal) {
        String method = cleanMethodName(goal.getTargetMethod());
        return "Statement in " + method;
    }

    private String describeDefUse(DefUseCoverageTestFitness goal) {
        return "Def-use pair in " + cleanMethodName(goal.getTargetMethod());
    }

    private String describeAllDefs(AllDefsCoverageTestFitness goal) {
        return "All-defs in " + cleanMethodName(goal.getTargetMethod());
    }

    /**
     * Converts a JVM method descriptor like {@code foo(I)V} or
     * {@code bar(Ljava/lang/String;Z)I} into a human-readable form
     * like {@code foo(int)} or {@code bar(String, boolean)}.
     *
     * <p>If the input does not contain a JVM descriptor, it is returned
     * as-is (with the fully-qualified class prefix stripped if present).
     */
    static String cleanMethodName(String rawMethodName) {
        if (rawMethodName == null || rawMethodName.isEmpty()) {
            return "";
        }
        rawMethodName = stripOwningClass(rawMethodName);

        Matcher m = METHOD_DESC_PATTERN.matcher(rawMethodName);
        if (!m.matches()) {
            return rawMethodName;
        }

        String name = m.group(1);
        String descriptor = m.group(2);

        // Handle <init> and <clinit>
        if ("<init>".equals(name)) {
            name = "<init>";
        } else if ("<clinit>".equals(name)) {
            return "<clinit>";
        }

        try {
            Type[] argTypes = Type.getArgumentTypes(descriptor);
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < argTypes.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(humanType(argTypes[i]));
            }
            sb.append(')');
            return sb.toString();
        } catch (Exception e) {
            return rawMethodName;
        }
    }

    static String baseMethodName(String rawMethodName) {
        String stripped = stripOwningClass(rawMethodName);
        if (stripped.isEmpty()) {
            return "";
        }
        int descriptorStart = stripped.indexOf('(');
        if (descriptorStart > 0) {
            return stripped.substring(0, descriptorStart);
        }
        return stripped;
    }

    /**
     * Converts an ASM {@link Type} to a human-readable string.
     */
    static String humanType(Type t) {
        switch (t.getSort()) {
            case Type.VOID:    return "void";
            case Type.BOOLEAN: return "boolean";
            case Type.CHAR:    return "char";
            case Type.BYTE:    return "byte";
            case Type.SHORT:   return "short";
            case Type.INT:     return "int";
            case Type.FLOAT:   return "float";
            case Type.LONG:    return "long";
            case Type.DOUBLE:  return "double";
            case Type.ARRAY:
                return humanType(t.getElementType()) + "[]";
            case Type.OBJECT:
                return simpleName(t.getClassName());
            default:
                return t.getClassName();
        }
    }

    static String simpleName(String fqcn) {
        if (fqcn == null) {
            return "";
        }
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    static String humanizeValueDescriptor(String descriptor) {
        if (descriptor == null) {
            return "unknown";
        }
        String human = VALUE_DESCRIPTOR_MAP.get(descriptor);
        return human != null ? human : descriptor.toLowerCase().replace('_', ' ');
    }

    private static final Map<String, String> MUTATION_NAME_MAP = new LinkedHashMap<>();

    static {
        MUTATION_NAME_MAP.put("AOR", "arithmetic operator replacement");
        MUTATION_NAME_MAP.put("ROR", "relational operator replacement");
        MUTATION_NAME_MAP.put("COR", "conditional operator replacement");
        MUTATION_NAME_MAP.put("SOR", "shift operator replacement");
        MUTATION_NAME_MAP.put("LOR", "logical operator replacement");
        MUTATION_NAME_MAP.put("AORB", "arithmetic operator replacement (binary)");
        MUTATION_NAME_MAP.put("AORS", "arithmetic operator replacement (short-cut)");
        MUTATION_NAME_MAP.put("AORU", "arithmetic operator replacement (unary)");
        MUTATION_NAME_MAP.put("SDL", "statement deletion");
        MUTATION_NAME_MAP.put("VDL", "variable deletion");
        MUTATION_NAME_MAP.put("CDL", "constant deletion");
        MUTATION_NAME_MAP.put("ODL", "operator deletion");
    }

    static String humanizeMutationName(String name) {
        if (name == null) {
            return "mutation";
        }
        String human = MUTATION_NAME_MAP.get(name);
        return human != null ? human : name;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripOwningClass(String rawMethodName) {
        String trimmed = safeTrim(rawMethodName);
        if (trimmed.isEmpty()) {
            return "";
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < trimmed.length() - 1) {
            char afterDot = trimmed.charAt(lastDot + 1);
            if (Character.isLowerCase(afterDot) || afterDot == '<') {
                return trimmed.substring(lastDot + 1);
            }
        }
        return trimmed;
    }

    private static String qualifiedExecutionKey(String className, String baseMethodName) {
        String owner = safeTrim(className);
        String method = safeTrim(baseMethodName);
        if (!owner.isEmpty() && !method.isEmpty()) {
            return owner + "." + method;
        }
        return method.isEmpty() ? owner : method;
    }

    /**
     * Builds an overload-disambiguating identifier of the form
     * {@code owner.name(descriptor)} when the raw method name carries a JVM
     * descriptor, e.g. {@code com.example.Foo.bar(Ljava/lang/String;)V}. Falls
     * back to {@link #qualifiedExecutionKey(String, String)} when no descriptor
     * is available so the signature key never differs from the execution key
     * for sources that don't expose one.
     */
    private static String qualifiedSignatureKey(String className, String rawMethodName) {
        String stripped = stripOwningClass(rawMethodName);
        String base = baseMethodName(stripped);
        String owner = safeTrim(className);
        if (base.isEmpty()) {
            return qualifiedExecutionKey(owner, base);
        }
        int descriptorStart = stripped.indexOf('(');
        if (descriptorStart <= 0 || descriptorStart >= stripped.length()) {
            return qualifiedExecutionKey(owner, base);
        }
        String descriptor = stripped.substring(descriptorStart);
        if (owner.isEmpty()) {
            return base + descriptor;
        }
        return owner + "." + base + descriptor;
    }

    private static String qualifyDisplayLabel(String className, String displayName) {
        String owner = safeTrim(className);
        String name = safeTrim(displayName);
        if (!owner.isEmpty() && !name.isEmpty()) {
            return owner + "." + name;
        }
        return name.isEmpty() ? owner : name;
    }

    private static String cleanConstructorLabel(String className, String rawConstructorName) {
        String owner = safeTrim(className);
        if (owner.isEmpty()) {
            return "";
        }
        String raw = safeTrim(rawConstructorName);
        if (raw.isEmpty()) {
            return owner + " constructor";
        }
        if (raw.startsWith("(")) {
            raw = "<init>" + raw;
        }
        String cleaned = cleanMethodName(raw);
        if (cleaned.startsWith("<init>(")) {
            return owner + cleaned.substring("<init>".length());
        }
        return owner + " constructor";
    }

    public static final class GoalTarget {
        private static final GoalTarget EMPTY = new GoalTarget("", "", "");

        private final String className;
        private final String methodName;
        private final String baseMethodName;

        private GoalTarget(String className, String methodName, String baseMethodName) {
            this.className = safeTrim(className);
            this.methodName = safeTrim(methodName);
            this.baseMethodName = safeTrim(baseMethodName);
        }

        static GoalTarget empty() {
            return EMPTY;
        }

        public String getClassName() {
            return className;
        }

        public String getMethodName() {
            return methodName;
        }

        public String getBaseMethodName() {
            return baseMethodName;
        }

        public String getExecutionKey() {
            return qualifiedExecutionKey(className, baseMethodName);
        }

        public String getQualifiedMethodName() {
            return qualifyDisplayLabel(className, methodName);
        }
    }

    public static final class OperationTarget {
        private static final OperationTarget EMPTY = new OperationTarget("", "", "", "", "");

        private final String className;
        private final String displayLabel;
        private final String executionKey;
        private final String baseName;
        /**
         * Parameter-descriptor-disambiguated identifier, so overloaded methods
         * can be bucketed apart. Falls back to {@link #executionKey} when the
         * descriptor is unknown.
         */
        private final String signatureKey;

        private OperationTarget(String className, String displayLabel, String executionKey, String baseName) {
            this(className, displayLabel, executionKey, baseName, executionKey);
        }

        private OperationTarget(String className, String displayLabel, String executionKey,
                                String baseName, String signatureKey) {
            this.className = safeTrim(className);
            this.displayLabel = safeTrim(displayLabel);
            this.executionKey = safeTrim(executionKey);
            this.baseName = safeTrim(baseName);
            String trimmedSignature = safeTrim(signatureKey);
            this.signatureKey = trimmedSignature.isEmpty() ? this.executionKey : trimmedSignature;
        }

        static OperationTarget empty() {
            return EMPTY;
        }

        public String getClassName() {
            return className;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }

        public String getExecutionKey() {
            return executionKey;
        }

        public String getBaseName() {
            return baseName;
        }

        public String getSignatureKey() {
            return signatureKey;
        }
    }

    private OperationTarget describeMethodOperation(String className,
                                                    String displayMethodName,
                                                    String executionKey,
                                                    String baseMethodName) {
        return describeMethodOperation(className, displayMethodName, executionKey,
                baseMethodName, executionKey);
    }

    private OperationTarget describeMethodOperation(String className,
                                                    String displayMethodName,
                                                    String executionKey,
                                                    String baseMethodName,
                                                    String signatureKey) {
        return new OperationTarget(safeTrim(className),
                qualifyDisplayLabel(className, displayMethodName),
                executionKey,
                baseMethodName,
                signatureKey);
    }

    public static final class BranchTarget {
        private static final BranchTarget EMPTY = new BranchTarget(GoalTarget.empty(), -1, false, false, false, null, false);

        private final GoalTarget target;
        private final int lineNumber;
        private final boolean desiredValue;
        private final boolean switchCaseBranch;
        private final boolean defaultCase;
        private final Integer caseValue;
        private final boolean rootBranch;

        private BranchTarget(GoalTarget target,
                             int lineNumber,
                             boolean desiredValue,
                             boolean switchCaseBranch,
                             boolean defaultCase,
                             Integer caseValue,
                             boolean rootBranch) {
            this.target = target == null ? GoalTarget.empty() : target;
            this.lineNumber = lineNumber;
            this.desiredValue = desiredValue;
            this.switchCaseBranch = switchCaseBranch;
            this.defaultCase = defaultCase;
            this.caseValue = caseValue;
            this.rootBranch = rootBranch;
        }

        static BranchTarget empty() {
            return EMPTY;
        }

        static BranchTarget root(GoalTarget target, int lineNumber) {
            return new BranchTarget(target, lineNumber, false, false, false, null, true);
        }

        static BranchTarget regular(GoalTarget target,
                                    int lineNumber,
                                    boolean desiredValue,
                                    boolean switchCaseBranch,
                                    boolean defaultCase,
                                    Integer caseValue) {
            return new BranchTarget(target, lineNumber, desiredValue, switchCaseBranch, defaultCase, caseValue, false);
        }

        public GoalTarget getTarget() {
            return target;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public String getLocationLabel() {
            String qualifiedMethod = target.getQualifiedMethodName();
            if (qualifiedMethod.isEmpty()) {
                return lineNumber > 0 ? "line " + lineNumber : "unknown location";
            }
            if (lineNumber > 0) {
                return qualifiedMethod + " at line " + lineNumber;
            }
            return qualifiedMethod;
        }

        public String getOutcomeLabel() {
            if (switchCaseBranch) {
                return defaultCase ? "switch default case" : "switch case " + caseValue;
            }
            return desiredValue ? "TRUE path" : "FALSE path";
        }

        public boolean isSwitchCaseBranch() {
            return switchCaseBranch;
        }

        public boolean isDefaultCase() {
            return defaultCase;
        }

        public String getNeedLabel() {
            return "need " + getOutcomeLabel();
        }

        public String asGoalDescription() {
            String methodName = target.getMethodName();
            if (methodName.isEmpty()) {
                methodName = target.getQualifiedMethodName();
            }
            if (methodName.isEmpty()) {
                methodName = "unknown method";
            }
            String lineSuffix = lineNumber > 0 ? " at line " + lineNumber : "";
            if (rootBranch) {
                return "Branch in " + methodName + ": method entry (root branch)";
            }
            if (switchCaseBranch) {
                return "Branch in " + methodName + lineSuffix + ": " + getOutcomeLabel();
            }
            return "Branch in " + methodName + lineSuffix + " — " + getOutcomeLabel();
        }
    }
}
