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
package org.evosuite.instrumentation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.Properties;
import org.evosuite.instrumentation.BytecodeInstrumentation.TargetClassInstrumentationException;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The instrumentation fallback ladder in {@link BytecodeInstrumentation} degrades gracefully
 * for dependency classes, but its last rungs emit no coverage probes at all. Taking one of
 * those for the class under test produces a run that reports exactly 0 coverage and still
 * looks like a success, so the CUT must fail loudly instead.
 */
public class TargetClassInstrumentationEscalationTest {

    private static final String TARGET_CLASS = "sample.EscalationTarget";
    private static final String TARGET_RESOURCE = "sample/EscalationTarget";

    private Logger instrumentationLogger;

    private ListAppender<ILoggingEvent> logAppender;

    private boolean originalAdditive;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        instrumentationLogger = (Logger) LoggerFactory.getLogger(BytecodeInstrumentation.class);
        originalAdditive = instrumentationLogger.isAdditive();
        logAppender = new ListAppender<>();
        logAppender.start();
        instrumentationLogger.addAppender(logAppender);
        instrumentationLogger.setAdditive(false);
    }

    @AfterEach
    public void tearDown() {
        instrumentationLogger.detachAppender(logAppender);
        logAppender.stop();
        instrumentationLogger.setAdditive(originalAdditive);
        Properties.getInstance().resetToDefaults();
    }

    // ---------------------------------------------------------------- ASM frame merge ladder

    @Test
    public void testTargetClassEscalatesInsteadOfMinimalInstrumentation() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, true, false);

        TargetClassInstrumentationException ex = Assertions.assertThrows(
                TargetClassInstrumentationException.class,
                () -> transform(instrumentation, TARGET_RESOURCE));

        Assertions.assertTrue(ex.getMessage().contains(TARGET_CLASS), ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("minimal (non-target) instrumentation"),
                ex.getMessage());
        Assertions.assertEquals(0, instrumentation.minimalInvocations,
                "The coverage-free rung must not even be attempted for the CUT");
        Assertions.assertInstanceOf(ArrayIndexOutOfBoundsException.class, ex.getCause());

        List<String> errors = errorMessages();
        Assertions.assertEquals(1, errors.size(), "Expected exactly one ERROR naming the CUT");
        Assertions.assertTrue(errors.get(0).contains(TARGET_CLASS), errors.get(0));
        Assertions.assertTrue(errors.get(0).contains("0 coverage"), errors.get(0));
    }

    @Test
    public void testNestedClassOfTargetIsTreatedAsClassUnderTest() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, true, false);

        TargetClassInstrumentationException ex = Assertions.assertThrows(
                TargetClassInstrumentationException.class,
                () -> transform(instrumentation, TARGET_RESOURCE + "$Inner"));

        Assertions.assertTrue(ex.getMessage().contains(TARGET_CLASS + "$Inner"), ex.getMessage());
    }

    @Test
    public void testNonTargetClassStillFallsBackToMinimalInstrumentation() {
        Properties.TARGET_CLASS = "sample.SomethingElse";
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, true, false);

        byte[] transformed = transform(instrumentation, TARGET_RESOURCE);

        Assertions.assertArrayEquals(LadderProbeInstrumentation.MINIMAL_BYTES, transformed);
        Assertions.assertEquals(1, instrumentation.minimalInvocations);
        Assertions.assertTrue(errorMessages().isEmpty(), "Dependency classes must not log an ERROR");
    }

    @Test
    public void testNonTargetClassStillFallsBackToBarePassthrough() {
        Properties.TARGET_CLASS = "sample.SomethingElse";
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, true, true);

        byte[] transformed = transform(instrumentation, TARGET_RESOURCE);

        Assertions.assertNotNull(transformed);
        Assertions.assertEquals(1, instrumentation.minimalInvocations);
        Assertions.assertTrue(implementsInstrumentedClass(transformed),
                "Bare passthrough must still add the InstrumentedClass marker interface");
    }

    @Test
    public void testTargetClassStillUsesCoveragePreservingRetries() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        // Retry 3 (skip testability transformation) keeps the target adapter chain, hence the
        // coverage probes, so the CUT is allowed to take it.
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, false, false);

        byte[] transformed = transform(instrumentation, TARGET_RESOURCE);

        Assertions.assertArrayEquals(LadderProbeInstrumentation.SKIP_TESTABILITY_BYTES, transformed);
        Assertions.assertEquals(1, instrumentation.skipTestabilityInvocations);
        Assertions.assertTrue(errorMessages().isEmpty());
    }

    @Test
    public void testUnconfiguredTargetClassNeverEscalates() {
        // TARGET_CLASS defaults to the empty string; without a CUT there is nothing to protect.
        Assertions.assertEquals("", Properties.TARGET_CLASS);
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.FRAME_MERGE, true, false);

        byte[] transformed = transform(instrumentation, TARGET_RESOURCE);

        Assertions.assertArrayEquals(LadderProbeInstrumentation.MINIMAL_BYTES, transformed);
    }

    // ------------------------------------------------------------------------ JSR/RET ladder

    @Test
    public void testTargetClassEscalatesInsteadOfRawBytecodePassthrough() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.JSR_RET, true, false);

        TargetClassInstrumentationException ex = Assertions.assertThrows(
                TargetClassInstrumentationException.class,
                () -> transform(instrumentation, TARGET_RESOURCE));

        Assertions.assertTrue(ex.getMessage().contains("raw bytecode passthrough"), ex.getMessage());
        Assertions.assertEquals(1, errorMessages().size());
    }

    @Test
    public void testNonTargetClassStillFallsBackToRawBytecodePassthrough() {
        Properties.TARGET_CLASS = "sample.SomethingElse";
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.JSR_RET, true, false);
        byte[] original = createSimpleClassBytes(TARGET_RESOURCE);

        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(),
                TARGET_RESOURCE, new ClassReader(original));

        Assertions.assertArrayEquals(original, transformed);
    }

    // -------------------------------------------------------------------- class too large path

    @Test
    public void testTargetClassEscalatesWhenClassIsTooLarge() {
        Properties.TARGET_CLASS = TARGET_CLASS;
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.TOO_LARGE, true, false);

        TargetClassInstrumentationException ex = Assertions.assertThrows(
                TargetClassInstrumentationException.class,
                () -> transform(instrumentation, TARGET_RESOURCE));

        Assertions.assertTrue(ex.getMessage().contains("over-sized class"), ex.getMessage());
        Assertions.assertEquals(0, instrumentation.minimalInvocations);
        Assertions.assertEquals(1, errorMessages().size());
    }

    @Test
    public void testNonTargetClassStillDegradesWhenClassIsTooLarge() {
        Properties.TARGET_CLASS = "sample.SomethingElse";
        LadderProbeInstrumentation instrumentation =
                new LadderProbeInstrumentation(Failure.TOO_LARGE, true, false);

        byte[] transformed = transform(instrumentation, TARGET_RESOURCE);

        Assertions.assertArrayEquals(LadderProbeInstrumentation.MINIMAL_BYTES, transformed);
        Assertions.assertTrue(errorMessages().isEmpty());
    }

    // ---------------------------------------------------------------------------------- helpers

    private byte[] transform(BytecodeInstrumentation instrumentation, String internalName) {
        ClassReader reader = new ClassReader(createSimpleClassBytes(internalName));
        return instrumentation.transformBytes(getClass().getClassLoader(), internalName, reader);
    }

    private List<String> errorMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private static boolean implementsInstrumentedClass(byte[] bytes) {
        String marker = InstrumentedClass.class.getCanonicalName().replace('.', '/');
        return Arrays.asList(new ClassReader(bytes).getInterfaces()).contains(marker);
    }

    private static byte[] createSimpleClassBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private enum Failure {
        FRAME_MERGE, TOO_LARGE, JSR_RET
    }

    /**
     * Drives the fallback ladder by failing the rungs that still carry coverage probes, so that
     * the next rung to be taken is one of the coverage-free ones.
     */
    private static class LadderProbeInstrumentation extends BytecodeInstrumentation {

        static final byte[] SKIP_TESTABILITY_BYTES = new byte[]{4, 5, 6};
        static final byte[] MINIMAL_BYTES = new byte[]{7, 8, 9};

        private final Failure failure;
        private final boolean failSkipTestability;
        private final boolean failMinimal;

        int skipTestabilityInvocations = 0;
        int minimalInvocations = 0;

        LadderProbeInstrumentation(Failure failure, boolean failSkipTestability, boolean failMinimal) {
            this.failure = failure;
            this.failSkipTestability = failSkipTestability;
            this.failMinimal = failMinimal;
        }

        @Override
        protected byte[] transformBytesInternal(ClassLoader classLoader, String className,
                                                String classNameWithDots, ClassReader reader,
                                                int readFlags, boolean enableLoopCounter,
                                                int writerFlags) {
            throw createFailure();
        }

        @Override
        protected byte[] transformBytesSkipTestability(ClassLoader classLoader, String className,
                                                       String classNameWithDots, ClassReader reader,
                                                       int readFlags) {
            skipTestabilityInvocations++;
            if (failSkipTestability) {
                throw createFailure();
            }
            return SKIP_TESTABILITY_BYTES;
        }

        @Override
        protected byte[] transformBytesMinimal(ClassLoader classLoader, String className,
                                                String classNameWithDots, ClassReader reader,
                                                int readFlags) {
            minimalInvocations++;
            if (failMinimal) {
                throw createFailure();
            }
            return MINIMAL_BYTES;
        }

        private RuntimeException createFailure() {
            switch (failure) {
                case TOO_LARGE:
                    return new MethodTooLargeException("Sample", "someMethod", "()V", 70000);
                case JSR_RET:
                    return new RuntimeException("JSR/RET are not supported");
                default:
                    return createAsmLikeFrameMergeFailure();
            }
        }

        private static ArrayIndexOutOfBoundsException createAsmLikeFrameMergeFailure() {
            ArrayIndexOutOfBoundsException ex =
                    new ArrayIndexOutOfBoundsException("Index 0 out of bounds for length 0");
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("org.evosuite.shaded.org.objectweb.asm.Frame",
                            "merge", "Frame.java", 1280),
                    new StackTraceElement("org.evosuite.shaded.org.objectweb.asm.MethodWriter",
                            "computeAllFrames", "MethodWriter.java", 1612)
            });
            return ex;
        }
    }
}
