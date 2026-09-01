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

import com.examples.with.different.packagename.classhandling.ResourceScanNoRegistry;
import com.examples.with.different.packagename.classhandling.RiskyRegistryClinit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;

public class ClinitResetSafetyAnalyzerTest {

    @Test
    public void testFirebirdLikePatternIsUnsafe() throws IOException {
        // Same shape as org.firebirdsql.gds.impl.GDSFactory: static Map
        // registry field, <clinit> reaches Thread.getContextClassLoader()
        // and ClassLoader.getResources(...) through a private static helper.
        ClassReader reader = new ClassReader(RiskyRegistryClinit.class.getName());
        Assertions.assertTrue(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    @Test
    public void testGDSFactoryIsExcludedByExactNameDespiteMatchingTheRule() {
        // Same risky shape as RiskyRegistryClinit (resource-scan call reachable
        // from <clinit> + a static Map registry field), but built under the
        // exact internal name org/firebirdsql/gds/impl/GDSFactory. Verified by
        // paired before/after EvoSuite runs that permanently skipping this
        // class's reset regresses org.firebirdsql.management.FBNBackupManager
        // on every tested seed - see the class javadoc for the mechanism. Must
        // never be flagged, regardless of what the structural rule would say.
        byte[] bytes = buildRiskyRegistryShapedClass("org/firebirdsql/gds/impl/GDSFactory");
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(new ClassReader(bytes)));
    }

    @Test
    public void testGDSTypeIsExcludedByExactNameEvenIfFlaggedTransitively() {
        byte[] bytes = buildRiskyRegistryShapedClass("org/firebirdsql/gds/impl/GDSType");
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(new ClassReader(bytes)));
    }

    @Test
    public void testUnrelatedClassWithTheSameShapeIsStillFlagged() {
        // Regression guard: the exclusion is scoped to the two exact class
        // names, not to the shape in general - a different class with the
        // identical risky pattern must still be flagged.
        byte[] bytes = buildRiskyRegistryShapedClass("org/firebirdsql/gds/impl/NotGDSFactory");
        Assertions.assertTrue(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(new ClassReader(bytes)));
    }

    @Test
    public void testResourceScanWithoutRegistryFieldIsSafe() throws IOException {
        // Reaches the same classpath-scan calls, but builds no Map/Collection
        // or self-singleton field. Must not be flagged - otherwise the rule
        // would fire on any class that merely reads a resource in <clinit>.
        ClassReader reader = new ClassReader(ResourceScanNoRegistry.class.getName());
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    @Test
    public void testClassForNameAloneIsNotEnoughEvenWithCollectionField() {
        // Class.forName is deliberately not a trigger on its own: it is far
        // too common to be a reliable signal, and a static Map field alone
        // (no resource-scan call reachable from <clinit>) must not flag.
        byte[] bytes = buildClassForNameWithMapField("sample/ClassForNameWithMap");
        ClassReader reader = new ClassReader(bytes);
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    @Test
    public void testSelfSingletonWithoutCollectionInstanceFieldIsSafe() {
        // "private static X instance = new X()" plus a resource scan in the
        // constructor, but no Map/Collection instance field on X - the
        // second half of the self-singleton signal is missing, so this must
        // not flag.
        byte[] bytes = buildSelfSingletonWithoutCollectionField("sample/PlainSingleton");
        ClassReader reader = new ClassReader(bytes);
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    @Test
    public void testSelfSingletonWithCollectionInstanceFieldAndScanInConstructorIsUnsafe() {
        // Same shape as com.liferay.portal.kernel.util.SystemProperties:
        // <clinit> only does "_instance = new SystemProperties()"; the
        // resource scan and the Map field both live one hop away, inside the
        // constructor invoked from <clinit>.
        byte[] bytes = buildSystemPropertiesLikeClass("sample/SystemPropertiesLike");
        ClassReader reader = new ClassReader(bytes);
        Assertions.assertTrue(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    @Test
    public void testClassWithNoClinitIsSafe() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "sample/NoClinit", null, "java/lang/Object", null);
        writer.visitEnd();
        ClassReader reader = new ClassReader(writer.toByteArray());
        Assertions.assertFalse(ClinitResetSafetyAnalyzer.isUnsafeToReplayClinit(reader));
    }

    /**
     * {@code <clinit>}: {@code registry.put(Class.forName("java.lang.String"), "x")}.
     * Static field {@code registry} is a {@code Map}, but the only call
     * reachable from {@code <clinit>} is {@code Class.forName} - not a
     * resource-scan signal.
     */
    private static byte[] buildClassForNameWithMapField(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registry", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "registry", "Ljava/util/Map;");

        clinit.visitLdcInsn("java.lang.String");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        writeDefaultConstructor(cw, internalName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code <clinit>}: {@code instance = new PlainSingleton()}. Constructor
     * calls {@code Thread.currentThread().getContextClassLoader()} but the
     * class declares no {@code Map}/{@code Collection} instance field.
     */
    private static byte[] buildSelfSingletonWithoutCollectionField(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String selfDesc = "L" + internalName + ";";
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "instance", selfDesc, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, internalName);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "instance", selfDesc);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Mirrors {@code com.liferay.portal.kernel.util.SystemProperties}:
     * {@code <clinit>} just does {@code instance = new SystemPropertiesLike()};
     * the constructor reads {@code Thread.currentThread().getContextClassLoader()
     * .getResource(...)} into an instance {@code Map} field.
     */
    private static byte[] buildSystemPropertiesLikeClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String selfDesc = "L" + internalName + ";";
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "instance", selfDesc, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "properties", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "properties", "Ljava/util/Map;");

        init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        init.visitLdcInsn("system.properties");
        init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "getResource",
                "(Ljava/lang/String;)Ljava/net/URL;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, internalName);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "instance", selfDesc);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Builds a class with the same risky shape as {@link RiskyRegistryClinit}
     * (static Map registry field, {@code <clinit>} reaching {@code
     * Thread.getContextClassLoader()} + {@code ClassLoader.getResources(...)}
     * through a private static helper), but under a caller-supplied internal
     * name - used to test the exact-name exclusion independently of shape.
     */
    private static byte[] buildRiskyRegistryShapedClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "registry", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "scan", "()V", null, null);
        helper.visitCode();
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        helper.visitLdcInsn("META-INF/services/example");
        helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "getResources",
                "(Ljava/lang/String;)Ljava/util/Enumeration;", false);
        helper.visitInsn(Opcodes.POP);
        helper.visitInsn(Opcodes.RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "registry", "Ljava/util/Map;");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, "scan", "()V", false);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        writeDefaultConstructor(cw, internalName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeDefaultConstructor(ClassWriter cw, String internalName) {
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
