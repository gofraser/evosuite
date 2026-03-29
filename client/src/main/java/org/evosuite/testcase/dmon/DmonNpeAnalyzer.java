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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Post-mortem analyzer for constructor NPE failures.
 * V1 focuses on extracting stable metadata for later promotion/injection phases.
 */
public class DmonNpeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(DmonNpeAnalyzer.class);

    private final NpeMessageParser messageParser = new NpeMessageParser();

    /**
     * Analyzes a constructor NullPointerException to determine if it can be promoted.
     *
     * @param statement the constructor statement
     * @param throwable the NullPointerException that occurred
     * @return an optional promotion plan
     */
    public Optional<DmonPromotionPlan> analyzeConstructorNpe(ConstructorStatement statement, Throwable throwable) {
        if (statement == null || throwable == null) {
            logger.debug("DMoN analyzer: skip [reason=NULL_INPUT]");
            return Optional.empty();
        }

        if (!(throwable instanceof NullPointerException)) {
            logger.debug("DMoN analyzer: skip [reason=NOT_NPE, type={}]", throwable.getClass().getName());
            return Optional.empty();
        }

        if (Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR
                && !Properties.TARGET_CLASS.equals(statement.getDeclaringClassName())) {
            logger.debug("DMoN analyzer: skip [reason=NOT_TARGET_CTOR, targetClass={}, statementClass={}]",
                    Properties.TARGET_CLASS, statement.getDeclaringClassName());
            return Optional.empty();
        }

        NpeMessageParser.ParseResult parseResult = Properties.DMON_HELPFUL_NPE_PARSE
                ? messageParser.parse(throwable.getMessage())
                : new NpeMessageParser.ParseResult(Optional.empty(), Optional.empty(), Optional.empty(),
                NpeMessageParser.ParseStrength.NO_MATCH);

        StackTraceElement site = pickFailureSite(throwable, statement.getDeclaringClassName());
        DmonFailureSite failureSite = new DmonFailureSite(
                site == null ? statement.getDeclaringClassName() : site.getClassName(),
                site == null ? "<init>" : site.getMethodName(),
                site == null ? -1 : site.getLineNumber(),
                DmonFailureSiteKind.CONSTRUCTOR_INIT
        );

        Optional<String> ownerToken = parseResult.getOwnerToken();
        Optional<String> memberToken = parseResult.getMemberToken();
        Optional<String> inferredType = Optional.empty();
        if (memberToken.isPresent()) {
            inferredType = inferMissingTypeFromMemberToken(memberToken.get());
        }

        if (Properties.DMON_ASM_FALLBACK
                && parseResult.getStrength() == NpeMessageParser.ParseStrength.NO_MATCH
                && site != null) {
            Optional<AsmHint> asmHint = analyzeWithAsmFallback(statement, site);
            if (asmHint.isPresent()) {
                AsmHint hint = asmHint.get();
                if (!ownerToken.isPresent()) {
                    ownerToken = hint.ownerToken;
                }
                if (!memberToken.isPresent()) {
                    memberToken = hint.memberToken;
                }
                if (!inferredType.isPresent()) {
                    inferredType = hint.inferredMissingTypeName;
                }
            }
        }

        DmonPromotionPlan plan = new DmonPromotionPlan(
                failureSite,
                parseResult.getNullExpression(),
                ownerToken,
                memberToken,
                inferredType
        );
        logger.debug("DMoN analyzer: plan created "
                        + "[owner={}, method={}, line={}, nullExpr={}, member={}, inferredType={}]",
                plan.getFailureSite().getOwnerClass(),
                plan.getFailureSite().getMethodName(),
                plan.getFailureSite().getLineNumber(),
                plan.getNullExpression().orElse(""),
                plan.getMemberToken().orElse(""),
                plan.getInferredMissingTypeName().orElse(""));
        return Optional.of(plan);
    }

    private static StackTraceElement pickFailureSite(Throwable throwable, String declaringClass) {
        if (throwable.getStackTrace() == null || throwable.getStackTrace().length == 0) {
            return null;
        }
        for (StackTraceElement element : throwable.getStackTrace()) {
            String cls = element.getClassName();
            if (cls == null) {
                continue;
            }
            // Always accept frames from the declaring class — it IS the CUT.
            if (matchesDeclaringClass(cls, declaringClass)) {
                return element;
            }
            // Skip reflection/EvoSuite infrastructure frames.
            if (cls.startsWith("org.evosuite.")
                    || cls.startsWith("java.lang.reflect.")
                    || cls.startsWith("jdk.internal.reflect.")
                    || cls.startsWith("sun.reflect.")
                    || cls.startsWith("org.junit.")
                    || cls.startsWith("junit.")) {
                continue;
            }
            return element;
        }
        return throwable.getStackTrace()[0];
    }

    /**
     * Checks whether a JVM class name matches the declaring class name,
     * accounting for canonical-vs-binary naming of inner classes.
     */
    private static boolean matchesDeclaringClass(String jvmClassName, String declaringClass) {
        if (declaringClass == null || jvmClassName == null) {
            return false;
        }
        if (jvmClassName.equals(declaringClass)) {
            return true;
        }
        // Canonical name uses '.' for inner classes, JVM/binary name uses '$'.
        // Try matching by converting inner-class dots to '$' from the end.
        return jvmClassName.equals(declaringClass.replace('.', '$'))
                || declaringClass.equals(jvmClassName.replace('$', '.'));
    }

    private Optional<AsmHint> analyzeWithAsmFallback(ConstructorStatement statement, StackTraceElement site) {
        if (statement == null || site == null || site.getLineNumber() <= 0) {
            return Optional.empty();
        }

        String ownerClass = site.getClassName();
        String methodName = site.getMethodName();
        String expectedDesc = null;
        if ("<init>".equals(methodName)) {
            Constructor<?> ctor = statement.getConstructor().getConstructor();
            expectedDesc = Type.getConstructorDescriptor(ctor);
        }

        Class<?> statementOwner = statement.getConstructor().getConstructor().getDeclaringClass();
        ClassLoader loader = statementOwner == null
                ? Thread.currentThread().getContextClassLoader()
                : statementOwner.getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }

        String resourceName = ownerClass.replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(resourceName)) {
            if (in == null) {
                return Optional.empty();
            }
            ClassReader reader = new ClassReader(in);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_FRAMES);
            MethodNode targetMethod = findTargetMethod(node.methods, methodName, expectedDesc);
            if (targetMethod == null) {
                return Optional.empty();
            }
            Optional<AbstractInsnNode> maybeInsn = findBestDereferenceInstruction(targetMethod, site.getLineNumber());
            if (!maybeInsn.isPresent()) {
                return Optional.empty();
            }
            return toAsmHint(maybeInsn.get());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static MethodNode findTargetMethod(List<MethodNode> methods, String methodName, String expectedDesc) {
        if (methods == null || methodName == null) {
            return null;
        }
        MethodNode byName = null;
        for (MethodNode method : methods) {
            if (!methodName.equals(method.name)) {
                continue;
            }
            if (expectedDesc != null && expectedDesc.equals(method.desc)) {
                return method;
            }
            if (byName == null) {
                byName = method;
            }
        }
        return byName;
    }

    private static Optional<AbstractInsnNode> findBestDereferenceInstruction(MethodNode method, int targetLine) {
        if (method == null || method.instructions == null) {
            return Optional.empty();
        }

        List<LineInstructionCandidate> candidates = new ArrayList<>();
        int currentLine = -1;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode) {
                currentLine = ((LineNumberNode) insn).line;
                continue;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC
                    || opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                    || opcode == Opcodes.INVOKESPECIAL) {
                candidates.add(new LineInstructionCandidate(currentLine, insn));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        LineInstructionCandidate exact = null;
        LineInstructionCandidate next = null;
        LineInstructionCandidate prev = null;
        for (LineInstructionCandidate c : candidates) {
            if (c.line == targetLine && exact == null) {
                exact = c;
            } else if (c.line > targetLine && next == null) {
                next = c;
            } else if (c.line <= targetLine) {
                prev = c;
            }
        }
        if (exact != null) {
            return Optional.of(exact.insn);
        }
        if (next != null) {
            return Optional.of(next.insn);
        }
        return Optional.of(prev.insn);
    }

    private static Optional<AsmHint> toAsmHint(AbstractInsnNode insn) {
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode fieldInsn = (FieldInsnNode) insn;
            Optional<String> inferredType = descriptorToClassName(fieldInsn.desc);
            return Optional.of(new AsmHint(
                    Optional.of(internalNameToClassName(fieldInsn.owner)),
                    Optional.of(fieldInsn.name),
                    inferredType));
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode methodInsn = (MethodInsnNode) insn;
            String ownerClassName = internalNameToClassName(methodInsn.owner);
            return Optional.of(new AsmHint(
                    Optional.of(ownerClassName),
                    Optional.of(methodInsn.name),
                    Optional.of(ownerClassName)));
        }
        return Optional.empty();
    }

    private static Optional<String> descriptorToClassName(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return Optional.empty();
        }
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.OBJECT) {
            return Optional.of(type.getClassName());
        }
        return Optional.empty();
    }

    private static String internalNameToClassName(String internalName) {
        return internalName == null ? "" : internalName.replace('/', '.');
    }

    private static Optional<String> inferMissingTypeFromMemberToken(String memberToken) {
        if (memberToken == null) {
            return Optional.empty();
        }
        String token = memberToken.trim();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        int lastDot = token.lastIndexOf('.');
        if (lastDot <= 0) {
            return Optional.empty();
        }
        if (token.contains("(")) {
            // "a.b.Type.method()" -> "a.b.Type"
            return Optional.of(token.substring(0, lastDot));
        }
        return Optional.of(token);
    }

    private static final class LineInstructionCandidate {
        private final int line;
        private final AbstractInsnNode insn;

        private LineInstructionCandidate(int line, AbstractInsnNode insn) {
            this.line = line;
            this.insn = insn;
        }
    }

    private static final class AsmHint {
        private final Optional<String> ownerToken;
        private final Optional<String> memberToken;
        private final Optional<String> inferredMissingTypeName;

        private AsmHint(Optional<String> ownerToken,
                        Optional<String> memberToken,
                        Optional<String> inferredMissingTypeName) {
            this.ownerToken = ownerToken == null ? Optional.empty() : ownerToken;
            this.memberToken = memberToken == null ? Optional.empty() : memberToken;
            this.inferredMissingTypeName = inferredMissingTypeName == null ? Optional.empty() : inferredMissingTypeName;
        }
    }
}
