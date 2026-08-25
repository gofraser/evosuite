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

import com.examples.with.different.packagename.EnumWithUserMethodsFixture;
import com.examples.with.different.packagename.PureEnumFixture;
import org.evosuite.Properties;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.testdata.EvoSuiteFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntSupplier;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.List;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import javax.swing.JPanel;

class TestUsageCheckerTest {

    private final boolean defaultUseVFS = RuntimeSettings.useVFS;
    private final boolean defaultUseVNET = RuntimeSettings.useVNET;
    private final String defaultClassPrefix = Properties.CLASS_PREFIX;
    private final String defaultTargetClass = Properties.TARGET_CLASS;

    private static class CompilerAccessorNameFallbackFixture {
        static void access$600() {
            // simulate an accessor whose synthetic bit was stripped by instrumentation
        }

        public static void userVisibleMethod() {
            // no-op
        }
    }

    @SuppressWarnings("serial")
    private static class GuiSubclassFixture extends JPanel {
        public int getMarker() {
            return 7;
        }
    }

    static class ShadowParent {
        protected Object value;
        protected Object inheritedOnly;
    }

    static class ShadowChild extends ShadowParent {
        private Object value;
    }

    @AfterEach
    void restoreRuntimeSettings() {
        RuntimeSettings.useVFS = defaultUseVFS;
        RuntimeSettings.useVNET = defaultUseVNET;
        Properties.CLASS_PREFIX = defaultClassPrefix;
        Properties.TARGET_CLASS = defaultTargetClass;
    }

    @Test
    void testEnvironmentDataClassUsableWhenVfsEnabled() {
        RuntimeSettings.useVFS = true;
        RuntimeSettings.useVNET = false;

        Assertions.assertTrue(TestUsageChecker.canUse(EvoSuiteFile.class));
    }

    @Test
    void testEnvironmentDataClassNotUsableWhenVfsDisabled() {
        RuntimeSettings.useVFS = false;
        RuntimeSettings.useVNET = false;

        Assertions.assertFalse(TestUsageChecker.canUse(EvoSuiteFile.class));
    }

    @Test
    void testCompilerGeneratedEnumValuesExcluded() throws NoSuchMethodException {
        Method values = PureEnumFixture.class.getDeclaredMethod("values");

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedEnumMethod(values));
        Assertions.assertFalse(TestUsageChecker.canUse(values, PureEnumFixture.class));
    }

    @Test
    void testCompilerGeneratedEnumValueOfExcluded() throws NoSuchMethodException {
        Method valueOf = PureEnumFixture.class.getDeclaredMethod("valueOf", String.class);

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedEnumMethod(valueOf));
        Assertions.assertFalse(TestUsageChecker.canUse(valueOf, PureEnumFixture.class));
    }

    @Test
    void testCustomEnumMethodsRemainUsable() throws NoSuchMethodException {
        Method customFactory = EnumWithUserMethodsFixture.class.getDeclaredMethod("value", int.class);
        Method customValue = EnumWithUserMethodsFixture.class.getDeclaredMethod("customValue");

        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedEnumMethod(customFactory));
        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedEnumMethod(customValue));
        Assertions.assertTrue(TestUsageChecker.canUse(customFactory, EnumWithUserMethodsFixture.class));
        Assertions.assertTrue(TestUsageChecker.canUse(customValue, EnumWithUserMethodsFixture.class));
    }

    @Test
    void testCompilerAccessorNameFallbackExcludedWhenSyntheticBitMissing() throws NoSuchMethodException {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = CompilerAccessorNameFallbackFixture.class.getName();

        Method accessor = CompilerAccessorNameFallbackFixture.class.getDeclaredMethod("access$600");
        Method userVisibleMethod = CompilerAccessorNameFallbackFixture.class.getDeclaredMethod("userVisibleMethod");

        Assertions.assertTrue(TestUsageChecker.isCompilerGeneratedAccessorMethod(accessor));
        Assertions.assertFalse(TestUsageChecker.canUse(accessor, CompilerAccessorNameFallbackFixture.class));
        Assertions.assertFalse(TestUsageChecker.isCompilerGeneratedAccessorMethod(userVisibleMethod));
        Assertions.assertTrue(TestUsageChecker.canUse(userVisibleMethod, CompilerAccessorNameFallbackFixture.class));
    }

    @Test
    void testUnstableGuiAccessorsAreExcludedFromMethodCalls() throws NoSuchMethodException {
        Method getBackground = java.awt.Component.class.getMethod("getBackground");
        Method getBounds = java.awt.Component.class.getMethod("getBounds");
        Method getComponentOrientation = java.awt.Component.class.getMethod("getComponentOrientation");

        Assertions.assertTrue(TestUsageChecker.isUnstableGuiAccessor(getBackground));
        Assertions.assertTrue(TestUsageChecker.isUnstableGuiAccessor(getBounds));
        Assertions.assertTrue(TestUsageChecker.isUnstableGuiAccessor(getComponentOrientation));
        Assertions.assertFalse(TestUsageChecker.canUse(getBackground, GuiSubclassFixture.class));
        Assertions.assertFalse(TestUsageChecker.canUse(getBounds, GuiSubclassFixture.class));
        Assertions.assertFalse(TestUsageChecker.canUse(getComponentOrientation, GuiSubclassFixture.class));
    }

    @Test
    void testGuiSubclassMethodsRemainUsableWhenNotInheritedGuiAccessors() throws NoSuchMethodException {
        Method marker = GuiSubclassFixture.class.getMethod("getMarker");

        Assertions.assertFalse(TestUsageChecker.isUnstableGuiAccessor(marker));
        Assertions.assertTrue(TestUsageChecker.canUse(marker, GuiSubclassFixture.class));
    }

    @Test
    void testInheritedFieldShadowedByOwnerIsRejected() throws NoSuchFieldException {
        Properties.CLASS_PREFIX = "org.evosuite.setup";
        Properties.TARGET_CLASS = ShadowChild.class.getName();

        Field parentValue = ShadowParent.class.getDeclaredField("value");

        // The parent's protected `value` is accessible by itself in the same
        // package, but ShadowChild redeclares `value` as private. Direct
        // access through a ShadowChild-typed variable would resolve to the
        // shadow and fail to compile.
        Assertions.assertFalse(TestUsageChecker.canUse(parentValue, ShadowChild.class));
        // Without shadowing, the inherited field remains usable from the owner.
        Field inherited = ShadowParent.class.getDeclaredField("inheritedOnly");
        Assertions.assertTrue(TestUsageChecker.canUse(inherited, ShadowChild.class));
        // The parent's own field is still usable when accessed via the parent.
        Assertions.assertTrue(TestUsageChecker.canUse(parentValue, ShadowParent.class));
    }

    @Test
    void testUnboundedStreamFactoriesAreRejected() throws NoSuchMethodException {
        Method streamIterate = Stream.class.getMethod("iterate", Object.class, UnaryOperator.class);
        Method streamGenerate = Stream.class.getMethod("generate", Supplier.class);
        Method intStreamIterate = IntStream.class.getMethod("iterate", int.class, IntUnaryOperator.class);
        Method intStreamGenerate = IntStream.class.getMethod("generate", IntSupplier.class);
        Method longStreamIterate = LongStream.class.getMethod("iterate", long.class, LongUnaryOperator.class);
        Method longStreamGenerate = LongStream.class.getMethod("generate", LongSupplier.class);
        Method doubleStreamIterate = DoubleStream.class.getMethod("iterate", double.class,
                DoubleUnaryOperator.class);
        Method doubleStreamGenerate = DoubleStream.class.getMethod("generate", DoubleSupplier.class);

        Assertions.assertFalse(TestUsageChecker.canUse(streamIterate));
        Assertions.assertFalse(TestUsageChecker.canUse(streamGenerate));
        Assertions.assertFalse(TestUsageChecker.canUse(intStreamIterate));
        Assertions.assertFalse(TestUsageChecker.canUse(intStreamGenerate));
        Assertions.assertFalse(TestUsageChecker.canUse(longStreamIterate));
        Assertions.assertFalse(TestUsageChecker.canUse(longStreamGenerate));
        Assertions.assertFalse(TestUsageChecker.canUse(doubleStreamIterate));
        Assertions.assertFalse(TestUsageChecker.canUse(doubleStreamGenerate));
    }

    @Test
    void testFiniteStreamFactoriesRemainUsable() throws NoSuchMethodException {
        Method streamOf = Stream.class.getMethod("of", Object[].class);

        Assertions.assertTrue(TestUsageChecker.canUse(streamOf));
    }

    @Test
    void testPreviewListOfLazyIsRejected() throws NoSuchMethodException {
        Method ofLazy = List.class.getMethod("ofLazy", int.class, IntFunction.class);

        Assertions.assertFalse(TestUsageChecker.canUse(ofLazy));
    }

    @Test
    void testUnsafeDependencyImplementationClassesAreRejectedByName() {
        ByteArrayClassLoader loader = new ByteArrayClassLoader();
        Class<?> mockitoInternal = loader.define("org.mockito.internal.creation.bytebuddy.GeneratedCandidate",
                createSimpleClassBytes("org.mockito.internal.creation.bytebuddy.GeneratedCandidate"));
        Class<?> byteBuddyInternal = loader.define("net.bytebuddy.GeneratedCandidate",
                createSimpleClassBytes("net.bytebuddy.GeneratedCandidate"));
        Class<?> objenesisInternal = loader.define("org.objenesis.GeneratedCandidate",
                createSimpleClassBytes("org.objenesis.GeneratedCandidate"));
        Class<?> scalaInternal = loader.define("scala.collection.GeneratedCandidate",
                createSimpleClassBytes("scala.collection.GeneratedCandidate"));

        Assertions.assertFalse(TestUsageChecker.canUse(mockitoInternal));
        Assertions.assertFalse(TestUsageChecker.canUse(byteBuddyInternal));
        Assertions.assertFalse(TestUsageChecker.canUse(objenesisInternal));
        Assertions.assertFalse(TestUsageChecker.canUse(scalaInternal));
    }

    @Test
    void testJdkCollectionStaticFactoryMethodsAreRejected() throws NoSuchMethodException {
        // List/Set/Map .of(...) are static *interface* methods (Java 9+); a generated
        // test that calls one needs -source 8+ and fails against many Defects4J targets
        // built at -source 6/7 (e.g. Closure). They must be excluded from the cluster.
        Assertions.assertFalse(TestUsageChecker.canUse(
                java.util.List.class.getMethod("of", Object[].class)),
                "List.of(...) must not be usable");
        Assertions.assertFalse(TestUsageChecker.canUse(
                java.util.Set.class.getMethod("of", Object[].class)),
                "Set.of(...) must not be usable");
        Assertions.assertFalse(TestUsageChecker.canUse(
                java.util.Map.class.getMethod("of")),
                "Map.of() must not be usable");
        Assertions.assertFalse(TestUsageChecker.canUse(
                java.util.List.class.getMethod("copyOf", java.util.Collection.class)),
                "List.copyOf(...) must not be usable");
    }

    @Test
    void testUnsafeDependencyPrefixIsAllowedForMatchingTargetPackage() {
        Properties.TARGET_CLASS = "scala.collection.GeneratedCandidate";
        ByteArrayClassLoader loader = new ByteArrayClassLoader();
        Class<?> scalaTarget = loader.define("scala.collection.GeneratedCandidate",
                createSimpleClassBytes("scala.collection.GeneratedCandidate"));

        Assertions.assertTrue(TestUsageChecker.canUse(scalaTarget));
    }

    @Test
    void testClassWithMissingDeclaringClassMetadataIsRejectedWithoutThrowing() {
        ByteArrayClassLoader loader = new ByteArrayClassLoader();
        Class<?> candidate = loader.define("missing.Owner$Candidate",
                createClassBytesWithMissingDeclaringClass("missing.Owner$Candidate", "missing.Owner"));

        Assertions.assertFalse(Assertions.assertDoesNotThrow(() -> TestUsageChecker.canUse(candidate)));
    }

    private static byte[] createSimpleClassBytes(String binaryName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                binaryName.replace('.', '/'), null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createClassBytesWithMissingDeclaringClass(String binaryName, String declaringClassName) {
        ClassWriter writer = new ClassWriter(0);
        String internalName = binaryName.replace('.', '/');
        String declaringInternalName = declaringClassName.replace('.', '/');
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        writer.visitInnerClass(internalName, declaringInternalName, "Candidate",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
