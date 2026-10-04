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
package org.evosuite.setup;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Pins the fix for the fast path in {@link TestUsageChecker#canUse(Method, Class)}: a class
 * compiled against a leaner version of an interface than the one it is loaded against can have
 * {@code Class.getMethods()} surface the interface's own abstract declaration as if it were a
 * method of the concrete class, even though nothing in the hierarchy implements it. Calling such
 * a method throws {@link AbstractMethodError}, so {@code canUse} must reject it.
 *
 * <p>Ordinary Java source cannot express "compiled against a leaner interface", because javac
 * refuses to compile a concrete class that does not implement every abstract method of an
 * interface it declares. This class instead builds the shape directly with ASM: a concrete class
 * that {@code implements} an interface without ever emitting an override for one of its abstract
 * methods. The JVM verifier does not enforce interface-completeness on concrete classes (only
 * javac does), so the class loads fine; it is only {@code Class.getMethods()} resolution and
 * method invocation where the gap shows up.
 *
 * <p>Fixture classes (all generated at test-class setup time, in a private {@link ClassLoader}):
 * <ul>
 * <li>{@code SkewInterface} - an interface with an abstract method {@code value()} and a
 * {@code default} method {@code defaultValue()};</li>
 * <li>{@code SkewImpl} - a concrete class that implements {@code SkewInterface} and overrides
 * {@code value()} (does not override {@code defaultValue()});</li>
 * <li>{@code SkewGap} - a concrete class that {@code implements SkewInterface} but, unlike
 * anything javac could produce, never overrides {@code value()} - the version-skew shape;</li>
 * <li>{@code SkewAbstractOwner} - an {@code abstract} class that likewise never overrides
 * {@code value()}, standing in for the legitimate "abstract owner" case.</li>
 * </ul>
 */
class TestUsageCheckerAbstractMethodSkewTest {

    private static Class<?> skewInterface;
    private static Class<?> skewImpl;
    private static Class<?> skewGap;
    private static Class<?> skewAbstractOwner;

    @BeforeAll
    static void buildFixtureClasses() {
        FixtureClassLoader loader = new FixtureClassLoader();

        // Interface must be defined first: resolving the "implements" clause of the other
        // classes, while they are being defined in the same loader, requires it to already be
        // present under this loader's name table (see class javadoc).
        skewInterface = loader.define("org.evosuite.setup.vskew.SkewInterface",
                buildInterfaceBytes());
        skewImpl = loader.define("org.evosuite.setup.vskew.SkewImpl",
                buildImplBytes());
        skewGap = loader.define("org.evosuite.setup.vskew.SkewGap",
                buildGapBytes());
        skewAbstractOwner = loader.define("org.evosuite.setup.vskew.SkewAbstractOwner",
                buildAbstractOwnerBytes());
    }

    @AfterAll
    static void clearFixtureClasses() {
        skewInterface = null;
        skewImpl = null;
        skewGap = null;
        skewAbstractOwner = null;
    }

    // ------------------------------------------------------------------
    // (a) The getMethods() premise itself
    // ------------------------------------------------------------------

    @Test
    void implementedMethodIsReturnedAsTheImplementingNonAbstractMethod() throws Exception {
        Method m = skewImpl.getMethod("value");

        Assertions.assertEquals(skewImpl, m.getDeclaringClass());
        Assertions.assertFalse(Modifier.isAbstract(m.getModifiers()));
    }

    @Test
    void unimplementedMethodIsReturnedAsTheInterfacesAbstractMethod() throws Exception {
        Method m = skewGap.getMethod("value");

        Assertions.assertEquals(skewInterface, m.getDeclaringClass());
        Assertions.assertTrue(Modifier.isAbstract(m.getModifiers()));
    }

    @Test
    void invokingTheUnimplementedMethodThrowsAbstractMethodError() throws Throwable {
        Method m = skewGap.getMethod("value");
        Object instance = skewGap.getDeclaredConstructor().newInstance();

        // Reflective Method#invoke wraps whatever the dispatch throws in
        // InvocationTargetException, so unwrap one layer to assert on the real failure...
        InvocationTargetException wrapped = Assertions.assertThrows(InvocationTargetException.class,
                () -> m.invoke(instance));
        Assertions.assertInstanceOf(AbstractMethodError.class, wrapped.getCause());

        // ...and also demonstrate the unwrapped failure directly via a MethodHandle, which
        // propagates it as the bare AbstractMethodError the fix's rationale describes.
        MethodHandle handle = MethodHandles.publicLookup().unreflect(m);
        Assertions.assertThrows(AbstractMethodError.class, () -> handle.invoke(instance));
    }

    // ------------------------------------------------------------------
    // (b) canUse rejects the unimplemented abstract method on a concrete owner,
    //     and accepts it when the owner is the interface itself or an abstract class
    // ------------------------------------------------------------------

    @Test
    void canUseRejectsUnimplementedAbstractMethodOnConcreteOwner() throws Exception {
        Method m = skewGap.getMethod("value");

        Assertions.assertFalse(TestUsageChecker.canUse(m, skewGap));
    }

    @Test
    void canUseAcceptsAbstractMethodWhenOwnerIsTheInterfaceItself() throws Exception {
        Method m = skewInterface.getMethod("value");

        Assertions.assertTrue(TestUsageChecker.canUse(m, skewInterface));
    }

    @Test
    void canUseAcceptsAbstractMethodWhenOwnerIsAnAbstractClass() throws Exception {
        Method m = skewAbstractOwner.getMethod("value");

        Assertions.assertTrue(TestUsageChecker.canUse(m, skewAbstractOwner));
    }

    // ------------------------------------------------------------------
    // (c) canUse is not over-broad
    // ------------------------------------------------------------------

    @Test
    void canUseAcceptsANormalPublicConcreteMethod() throws Exception {
        Method m = skewImpl.getMethod("value");

        Assertions.assertTrue(TestUsageChecker.canUse(m, skewImpl));
    }

    @Test
    void canUseAcceptsADefaultInterfaceMethodOnAConcreteImplementor() throws Exception {
        Method m = skewImpl.getMethod("defaultValue");

        // The fix must key off Modifier.isAbstract on the METHOD, not isInterface on its
        // declaring class: a default method's declaring class is still an interface, but the
        // method itself is not ACC_ABSTRACT, and a concrete class that never overrides it still
        // inherits a perfectly callable implementation.
        Assertions.assertEquals(skewInterface, m.getDeclaringClass());
        Assertions.assertFalse(Modifier.isAbstract(m.getModifiers()));
        Assertions.assertTrue(TestUsageChecker.canUse(m, skewImpl));
    }

    @Test
    void canUseAcceptsAnAbstractMethodWhenOwnerClassIsAbstract() throws Exception {
        // Same scenario as canUseAcceptsAbstractMethodWhenOwnerIsAnAbstractClass above: restated
        // here because the task that motivated this fix calls it out explicitly as a
        // not-over-broad case in its own right (EvoSuite mocks it, or finds a concrete subtype).
        Method m = skewAbstractOwner.getMethod("value");

        Assertions.assertTrue(TestUsageChecker.canUse(m, skewAbstractOwner));
    }

    // ------------------------------------------------------------------
    // ASM fixture construction
    // ------------------------------------------------------------------

    private static final String IFACE_INTERNAL_NAME = "org/evosuite/setup/vskew/SkewInterface";
    private static final String IMPL_INTERNAL_NAME = "org/evosuite/setup/vskew/SkewImpl";
    private static final String GAP_INTERNAL_NAME = "org/evosuite/setup/vskew/SkewGap";
    private static final String ABSTRACT_OWNER_INTERNAL_NAME = "org/evosuite/setup/vskew/SkewAbstractOwner";

    // ------------------------------------------------------------------------------------
    // (d) an inherited implementation hidden by the cluster's merged method list
    // ------------------------------------------------------------------------------------

    public static class InheritedImplBase {
        public int value() {
            return 7;
        }
    }

    public interface InheritedImplInterface {
        int value();
    }

    /** Ordinary javac-compiled shape: the implementation comes from the superclass. */
    public static class InheritedImpl extends InheritedImplBase implements InheritedImplInterface {
    }

    @Test
    void canUseAcceptsAbstractDeclarationWhoseImplementationIsInherited() throws Exception {
        // TestClusterUtils.getMethods lets the interface's declaration replace the superclass
        // implementation, so the cluster hands canUse an abstract Method on a concrete owner.
        Method abstractEntry = null;
        for (Method m : TestClusterUtils.getMethods(InheritedImpl.class)) {
            if (m.getName().equals("value")) {
                abstractEntry = m;
            }
        }
        Assertions.assertNotNull(abstractEntry);
        Assertions.assertTrue(Modifier.isAbstract(abstractEntry.getModifiers()),
                "premise: the cluster method list holds the interface's abstract declaration");
        Assertions.assertEquals(7, abstractEntry.invoke(new InheritedImpl()),
                "premise: the abstract declaration is invocable on the concrete receiver");
        Assertions.assertTrue(TestUsageChecker.canUse(abstractEntry, InheritedImpl.class));
    }

    private static byte[] buildInterfaceBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                IFACE_INTERNAL_NAME, null, "java/lang/Object", null);

        // abstract int value();
        MethodVisitor value = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "value", "()I", null, null);
        value.visitEnd();

        // default int defaultValue() { return 7; }
        MethodVisitor defaultValue = cw.visitMethod(Opcodes.ACC_PUBLIC,
                "defaultValue", "()I", null, null);
        defaultValue.visitCode();
        defaultValue.visitIntInsn(Opcodes.BIPUSH, 7);
        defaultValue.visitInsn(Opcodes.IRETURN);
        defaultValue.visitMaxs(1, 1);
        defaultValue.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildImplBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                IMPL_INTERNAL_NAME, null, "java/lang/Object", new String[]{IFACE_INTERNAL_NAME});

        writeNoArgConstructor(cw);

        // public int value() { return 42; }  -- overrides the interface's abstract method
        MethodVisitor value = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(Opcodes.BIPUSH, 42);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();

        // defaultValue() is deliberately NOT overridden here.

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildGapBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                GAP_INTERNAL_NAME, null, "java/lang/Object", new String[]{IFACE_INTERNAL_NAME});

        writeNoArgConstructor(cw);
        // No "value" override: this is the version-skew shape javac could never produce for a
        // concrete class, built directly with ASM instead.

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildAbstractOwnerBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                ABSTRACT_OWNER_INTERNAL_NAME, null, "java/lang/Object",
                new String[]{IFACE_INTERNAL_NAME});

        writeNoArgConstructor(cw);
        // No "value" override: legitimate here, since the class is abstract.

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeNoArgConstructor(ClassWriter cw) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
    }

    private static final class FixtureClassLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
