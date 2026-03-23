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
import org.objectweb.asm.Type;

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
        String raw = goal.getTargetMethod();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return cleanMethodName(raw);
    }

    private String describeBranch(BranchCoverageTestFitness goal) {
        return describeBranchGoal(goal.getBranchGoal());
    }

    private String describeBranch(OnlyBranchCoverageTestFitness goal) {
        return describeBranchGoal(goal.getBranchGoal());
    }

    private String describeBranchGoal(BranchCoverageGoal goal) {
        String method = cleanMethodName(goal.getMethodName());
        Branch branch = goal.getBranch();
        if (branch == null) {
            return "Branch in " + method + ": method entry (root branch)";
        }
        int line = goal.getLineNumber();
        boolean value = goal.getValue();
        String direction = value ? "TRUE" : "FALSE";

        if (branch.isSwitchCaseBranch()) {
            Integer caseValue = branch.getTargetCaseValue();
            if (caseValue != null) {
                return "Branch in " + method + " at line " + line
                        + ": switch case " + caseValue;
            } else {
                return "Branch in " + method + " at line " + line
                        + ": switch default case";
            }
        }
        return "Branch in " + method + " at line " + line
                + " — " + direction + " path";
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
        if (key.endsWith("_EXPLICIT")) return "explicit";
        if (key.endsWith("_IMPLICIT")) return "implicit";
        if (key.endsWith("_DECLARED")) return "declared";
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
        // Strip leading class name (e.g., "com.example.Foo.bar(I)V" → "bar(I)V")
        int lastDot = rawMethodName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < rawMethodName.length() - 1) {
            char afterDot = rawMethodName.charAt(lastDot + 1);
            if (Character.isLowerCase(afterDot) || afterDot == '<') {
                rawMethodName = rawMethodName.substring(lastDot + 1);
            }
        }

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
                if (i > 0) sb.append(", ");
                sb.append(humanType(argTypes[i]));
            }
            sb.append(')');
            return sb.toString();
        } catch (Exception e) {
            return rawMethodName;
        }
    }

    /** Converts an ASM {@link Type} to a human-readable string. */
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
        if (fqcn == null) return "";
        int dot = fqcn.lastIndexOf('.');
        return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
    }

    static String humanizeValueDescriptor(String descriptor) {
        if (descriptor == null) return "unknown";
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
        if (name == null) return "mutation";
        String human = MUTATION_NAME_MAP.get(name);
        return human != null ? human : name;
    }
}
