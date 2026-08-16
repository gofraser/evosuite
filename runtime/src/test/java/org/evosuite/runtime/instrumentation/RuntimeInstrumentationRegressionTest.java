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
package org.evosuite.runtime.instrumentation;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.evosuite.PackageInfo;
import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.LoggerFactory;

public class RuntimeInstrumentationRegressionTest {

    private Logger runtimeInstrumentationLogger;

    private ListAppender<ILoggingEvent> instrumentationErrorAppender;

    private boolean originalAdditive;

    @BeforeEach
    public void captureInstrumentationErrors() {
        runtimeInstrumentationLogger =
                (Logger) LoggerFactory.getLogger(RuntimeInstrumentation.class);
        originalAdditive = runtimeInstrumentationLogger.isAdditive();
        instrumentationErrorAppender = new ListAppender<>();
        instrumentationErrorAppender.start();
        runtimeInstrumentationLogger.addAppender(instrumentationErrorAppender);
        runtimeInstrumentationLogger.setAdditive(false);
    }

    @AfterEach
    public void resetMockingFlags() {
        runtimeInstrumentationLogger.detachAppender(instrumentationErrorAppender);
        instrumentationErrorAppender.stop();
        runtimeInstrumentationLogger.setAdditive(originalAdditive);
        RuntimeSettings.mockJVMNonDeterminism = false;
        RuntimeSettings.mockGUI = false;
        RuntimeSettings.useVFS = false;
        RuntimeSettings.useVNET = false;
        RuntimeInstrumentation.setAvoidInstrumentingShadedClasses(false);
        RuntimeInstrumentation.setTargetClassPrefix(null);
    }

    @Test
    public void testInstrumentationFailureThrowsInsteadOfReturningPartialBytecode() {
        RuntimeInstrumentation instrumentation = new ThrowingRuntimeInstrumentation();
        ClassReader reader = new ClassReader(createSimpleClassBytes("sample/Bug8"));

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> instrumentation.transformBytes(getClass().getClassLoader(), "sample/Bug8", reader, false));
        Assertions.assertEquals("Failed to instrument class sample/Bug8", ex.getMessage());
        Assertions.assertInstanceOf(RuntimeException.class, ex.getCause());
        Assertions.assertEquals("forced instrumentation failure", ex.getCause().getMessage());
        Assertions.assertEquals(1, instrumentationErrorAppender.list.size());
        ILoggingEvent event = instrumentationErrorAppender.list.get(0);
        Assertions.assertTrue(event.getFormattedMessage()
                .contains("Error while instrumenting class sample/Bug8: forced instrumentation failure"));
        Assertions.assertNotNull(event.getThrowableProxy());
        Assertions.assertEquals(RuntimeException.class.getName(),
                event.getThrowableProxy().getClassName());
    }

    @Test
    public void testConstructorReplacementDoesNotApplyToPendingSuperCall() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.mockGUI = true;
        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();
        ClassReader reader = new ClassReader(createSubclassCallingPopupMenuSuper("sample/PopupSubclass"));

        Assertions.assertDoesNotThrow(
                () -> instrumentation.transformBytes(getClass().getClassLoader(), "sample/PopupSubclass", reader, false));
    }

    /**
     * EvoSuite's own mocking stack must never be instrumented, and it is already covered
     * by the EvoSuite package rule rather than by the library-prefix list - which matters,
     * because in the shaded jar the "org.mockito." literal is relocated and no longer
     * matches anything the subject could load.
     */
    @Test
    public void testEvoSuiteOwnPackagesAreNeverInstrumented() {
        RuntimeInstrumentation.setTargetClassPrefix("com.acme.");

        Assertions.assertFalse(RuntimeInstrumentation.checkIfCanInstrument(
                PackageInfo.getEvoSuitePackage() + ".runtime.mock.java.lang.MockThrowable"));
        Assertions.assertFalse(RuntimeInstrumentation.checkIfCanInstrument(
                PackageInfo.getEvoSuitePackage() + ".shaded.org.mockito.Mockito"));
    }

    /**
     * A subject library must stay instrumentable during test generation. Excluding it
     * would leave its loops without a loop counter, so a thread spinning inside it could
     * no longer be terminated by the kill switch - which is what starved whole runs when
     * the library list was activated for every target.
     */
    @Test
    public void testSubjectLibrariesStayInstrumentableDuringGeneration() {
        RuntimeInstrumentation.setAvoidInstrumentingShadedClasses(false);
        RuntimeInstrumentation.setTargetClassPrefix("com.acme.");

        Assertions.assertTrue(RuntimeInstrumentation.checkIfCanInstrument("org.hsqldb.Log"));
        Assertions.assertTrue(RuntimeInstrumentation.checkIfCanInstrument("org.hibernate.Session"));
    }

    /**
     * The standalone shaded runtime still applies the library list, and still exempts the
     * package the target itself lives in.
     */
    @Test
    public void testTargetPackageIsExemptFromLibraryExclusions() {
        RuntimeInstrumentation.setAvoidInstrumentingShadedClasses(true);
        RuntimeInstrumentation.setTargetClassPrefix("org.hsqldb.");

        Assertions.assertTrue(RuntimeInstrumentation.checkIfCanInstrument("org.hsqldb.Log"));
        Assertions.assertFalse(RuntimeInstrumentation.checkIfCanInstrument("org.hibernate.Session"));
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

    private static byte[] createSubclassCallingPopupMenuSuper(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/awt/PopupMenu", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/PopupMenu", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class ThrowingRuntimeInstrumentation extends RuntimeInstrumentation {
        @Override
        protected void applyClassVisitor(ClassNode classNode, ClassVisitor classVisitor) {
            throw new RuntimeException("forced instrumentation failure");
        }
    }
}
