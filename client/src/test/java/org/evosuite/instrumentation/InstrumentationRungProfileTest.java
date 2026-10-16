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

import com.examples.with.different.packagename.StaticCounterHolder;
import org.evosuite.Properties;
import org.evosuite.instrumentation.BytecodeInstrumentation.TargetClassInstrumentationException;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodTooLargeException;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Characterisation of the instrumentation fallback ladder in {@link BytecodeInstrumentation}
 * before it is rewritten as a table of instrumentation profiles.
 *
 * <p>Two things are pinned, and both must survive the refactoring unless a change is made on
 * purpose:
 * <ol>
 *   <li><b>Transitions</b>: which rung is tried after which failure, with which loop-counter
 *       and writer flags, for the class under test and for a dependency class. The rungs are
 *       replaced by recording stubs, so this is the decision function only.</li>
 *   <li><b>Rung content</b>: which instrumentation features each rung really applies to a
 *       small fixture class. A feature is the presence of a family of calls in the output (for
 *       example the loop counter, the coverage probes, the static-access probes).</li>
 * </ol>
 *
 * <p>Where the current behaviour looks accidental (the review calls these I2, I3 and I4) the
 * test says so. It documents what happens now; it does not endorse it.
 */
public class InstrumentationRungProfileTest {

    private static final String CUT = "sample.RungTarget";
    private static final String CUT_RESOURCE = "sample/RungTarget";
    private static final byte[] SENTINEL = new byte[]{42};

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        Properties.MAX_LOOP_ITERATIONS = 1;
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    // ===================================================================== transitions

    private enum Failure {FRAME, TOO_LARGE, JSR, OTHER}

    private static final String FULL = "internal(loop=true,FRAMES)";
    private static final String FULL_MAXS = "internal(loop=true,MAXS)";
    private static final String NO_LOOP = "internal(loop=false,FRAMES)";
    private static final String SKIP_TT = "skipTestability";
    private static final String MINIMAL = "minimal";

    @Test
    public void frameFailureEverywhereWalksTheWholeLadderForADependency() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.FRAME);

        byte[] out = ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        // full -> no loop counter -> no testability -> minimal -> bare passthrough (not stubbed)
        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, SKIP_TT, MINIMAL), ladder.calls);
        Assertions.assertTrue(implementsInstrumentedClass(out), "bare passthrough adds only the marker interface");
    }

    @Test
    public void legacyClassFilesGetAnExtraComputeMaxsRungAfterTheFirstFailure() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.FRAME);

        ladder.run(CUT_RESOURCE, Opcodes.V1_6);

        Assertions.assertEquals(Arrays.asList(FULL, FULL_MAXS, NO_LOOP, SKIP_TT, MINIMAL), ladder.calls);
    }

    @Test
    public void disabledLoopCounterSkipsTheNoLoopCounterRung() {
        Properties.MAX_LOOP_ITERATIONS = -1;
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.FRAME);

        ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        Assertions.assertEquals(Arrays.asList("internal(loop=true,FRAMES)", SKIP_TT, MINIMAL), ladder.calls);
    }

    @Test
    public void firstRungSuccessStopsTheLadder() {
        Ladder ladder = new Ladder(rung -> null);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL), ladder.calls);
    }

    @Test
    public void frameFailureStopsAtTheFirstRungThatSucceeds() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(SKIP_TT) || rung.equals(MINIMAL) ? null : Failure.FRAME);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, SKIP_TT), ladder.calls);
    }

    @Test
    public void classUnderTestNeverReachesTheCoverageFreeRungs() {
        Properties.TARGET_CLASS = CUT;
        Ladder ladder = new Ladder(rung -> Failure.FRAME);

        Assertions.assertThrows(TargetClassInstrumentationException.class,
                () -> ladder.run(CUT_RESOURCE, Opcodes.V1_8));

        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, SKIP_TT), ladder.calls);
    }

    @Test
    public void classUnderTestStillUsesTheRungsThatKeepCoverageProbes() {
        Properties.TARGET_CLASS = CUT;
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.FRAME : null);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP), ladder.calls);
    }

    @Test
    public void tooLargeSkipsTestabilityThenMinimalForADependency() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.TOO_LARGE);

        byte[] out = ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT, MINIMAL), ladder.calls);
        Assertions.assertTrue(implementsInstrumentedClass(out));
    }

    @Test
    public void tooLargeClassUnderTestStopsAfterSkipTestability() {
        Properties.TARGET_CLASS = CUT;
        Ladder ladder = new Ladder(rung -> Failure.TOO_LARGE);

        Assertions.assertThrows(TargetClassInstrumentationException.class,
                () -> ladder.run(CUT_RESOURCE, Opcodes.V1_8));

        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT), ladder.calls);
    }

    /**
     * I2 in the review: the too-large ladder handles nothing but "too large". A frame-merge
     * failure inside its first rung propagates and aborts the class, whereas the same failure
     * on the frame ladder moves on to the minimal rung. Pinned as it is today.
     */
    @Test
    public void frameFailureInsideTheTooLargeLadderIsADeadEnd() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.TOO_LARGE
                : rung.equals(SKIP_TT) ? Failure.FRAME : null);

        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> ladder.run(CUT_RESOURCE, Opcodes.V1_8));

        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT), ladder.calls,
                "minimal is not tried after a frame failure in the too-large ladder");
    }

    /** The desired behaviour once the transitions are one table (review I2). */
    @Test
    @Disabled("package 9: the too-large ladder does not yet move on after a frame failure")
    public void frameFailureInsideTheTooLargeLadderShouldContinueWithMinimal() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.TOO_LARGE
                : rung.equals(SKIP_TT) ? Failure.FRAME : null);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT, MINIMAL), ladder.calls);
    }

    @Test
    public void unsupportedJsrCopiesTheOriginalBytesAfterOneAttempt() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.JSR);
        byte[] original = classBytes(CUT_RESOURCE, Opcodes.V1_8);

        byte[] out = ladder.runBytes(CUT_RESOURCE, original);

        Assertions.assertEquals(Arrays.asList(FULL), ladder.calls);
        Assertions.assertArrayEquals(original, out);
    }

    @Test
    public void unrelatedFailuresAreRethrownWithoutRetry() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> Failure.OTHER);

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> ladder.run(CUT_RESOURCE, Opcodes.V1_8));

        Assertions.assertEquals("unrelated", ex.getMessage());
        Assertions.assertEquals(Arrays.asList(FULL), ladder.calls);
    }

    // ================================================================== rung content

    public static final class Fixture {
        static int counter;

        public int run(int n, String s) {
            int sum = 0;
            for (int i = 0; i < n; i++) {
                sum += i;
                counter++;
            }
            if (s.equals("x")) {
                sum++;
            }
            StaticCounterHolder.counter = sum;
            return sum + counter + StaticCounterHolder.counter;
        }
    }

    private static final String FIXTURE = Fixture.class.getName();

    private enum Rung {FULL_RUNG, NO_LOOP_RUNG, SKIP_TESTABILITY_RUNG, MINIMAL_RUNG}

    private static final String BASE = "marker staticReset classInitHooks identityHash";
    private static final String TARGET_PROBES = "branchProbes lineProbes methodProbes returnProbes";

    @Test
    public void classUnderTestProfiles() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess loopCounter stringTransform",
                features(bi, Rung.FULL_RUNG));
        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess stringTransform",
                features(bi, Rung.NO_LOOP_RUNG));
        // I3: the "full chain" skip-testability rung also drops the loop counter.
        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess",
                features(bi, Rung.SKIP_TESTABILITY_RUNG));
        // I3: minimal drops the coverage probes AND the static-access probes, but keeps __STATIC_RESET.
        Assertions.assertEquals(BASE + " killSwitch", features(bi, Rung.MINIMAL_RUNG));
    }

    @Test
    public void dependencyClassProfiles() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = "sample.Other";
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        Assertions.assertEquals(BASE + " killSwitch staticAccess loopCounter", features(bi, Rung.FULL_RUNG));
        Assertions.assertEquals(BASE + " killSwitch staticAccess", features(bi, Rung.NO_LOOP_RUNG));
        Assertions.assertEquals(BASE + " killSwitch staticAccess", features(bi, Rung.SKIP_TESTABILITY_RUNG));
        // Minimal loses the static-access probes of a dependency as well (I3).
        Assertions.assertEquals(BASE + " killSwitch", features(bi, Rung.MINIMAL_RUNG));
    }

    /**
     * The decision that separates retry 2 from retry 3: does a class get the testability
     * transformation at all? It is a private predicate, so it is read reflectively.
     */
    @Test
    public void testabilityTransformationDecisionPerClassShape() throws Exception {
        Properties.TT = false;
        Properties.TARGET_CLASS_PREFIX = "";
        Properties.PROJECT_PREFIX = "proj.pkg";
        Assertions.assertTrue(appliesTestability("proj.pkg.Foo"), "project prefix always transforms");
        Assertions.assertFalse(appliesTestability("other.Foo"), "TT off, no prefix match");

        Properties.TT = true;
        Properties.TT_SCOPE = Properties.TransformationScope.ALL;
        Assertions.assertTrue(appliesTestability("other.Foo"), "scope ALL transforms everything");

        Properties.TT_SCOPE = Properties.TransformationScope.TARGET;
        Properties.TARGET_CLASS = "other.Foo";
        Assertions.assertTrue(appliesTestability("other.Foo"));
        Assertions.assertTrue(appliesTestability("other.Foo$Inner"));
        Assertions.assertFalse(appliesTestability("other.Bar"));

        Properties.TT = false;
        Properties.TARGET_CLASS_PREFIX = "tgt.pkg";
        Assertions.assertTrue(appliesTestability("tgt.pkg.Baz"), "target class prefix transforms");
        Assertions.assertFalse(appliesTestability("tgt.zzz.Baz"));
    }

    private boolean appliesTestability(String className) throws Exception {
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
        java.lang.reflect.Method method = BytecodeInstrumentation.class
                .getDeclaredMethod("shouldApplyTestabilityTransformation", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(bi, className);
    }

    @Test
    public void staticResetAndStaticAccessFollowTheResetSwitch() throws IOException {
        Properties.RESET_STATIC_FIELDS = false;
        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        for (Rung rung : Rung.values()) {
            Assertions.assertFalse(features(bi, rung).contains("staticReset"), rung.name());
            Assertions.assertFalse(features(bi, rung).contains("staticAccess"), rung.name());
        }
    }

    /**
     * I4 in the review: for a dependency without testability transformation, retry 3 (skip
     * testability) repeats retry 2 (no loop counter) byte for byte. For the class under test
     * the two differ.
     */
    @Test
    public void skipTestabilityRepeatsTheNoLoopRungForAPlainDependency() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = "sample.Other";
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        Assertions.assertArrayEquals(rungBytes(bi, Rung.NO_LOOP_RUNG), rungBytes(bi, Rung.SKIP_TESTABILITY_RUNG));

        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation cutInstrumentation = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
        Assertions.assertFalse(Arrays.equals(rungBytes(cutInstrumentation, Rung.NO_LOOP_RUNG),
                rungBytes(cutInstrumentation, Rung.SKIP_TESTABILITY_RUNG)));
    }

    @Test
    public void finalModifierIsRemovedOnEveryRung() throws IOException {
        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
        Assertions.assertNotEquals(0, Fixture.class.getModifiers() & java.lang.reflect.Modifier.FINAL);

        for (Rung rung : Rung.values()) {
            int access = new ClassReader(rungBytes(bi, rung)).getAccess();
            Assertions.assertEquals(0, access & Opcodes.ACC_FINAL, rung + " must not leave the class final");
        }
    }

    // ===================================================================== helpers

    private byte[] rungBytes(BytecodeInstrumentation bi, Rung rung) throws IOException {
        String internal = FIXTURE.replace('.', '/');
        ClassReader reader = new ClassReader(readResource(internal));
        ClassLoader loader = getClass().getClassLoader();
        switch (rung) {
            case FULL_RUNG:
                return bi.transformBytesInternal(loader, internal, FIXTURE, reader, ClassReader.SKIP_FRAMES,
                        true, ClassWriter.COMPUTE_FRAMES);
            case NO_LOOP_RUNG:
                return bi.transformBytesInternal(loader, internal, FIXTURE, reader, ClassReader.SKIP_FRAMES,
                        false, ClassWriter.COMPUTE_FRAMES);
            case SKIP_TESTABILITY_RUNG:
                return bi.transformBytesSkipTestability(loader, internal, FIXTURE, reader, ClassReader.SKIP_FRAMES);
            default:
                return bi.transformBytesMinimal(loader, internal, FIXTURE, reader, ClassReader.SKIP_FRAMES);
        }
    }

    private String features(BytecodeInstrumentation bi, Rung rung) throws IOException {
        byte[] bytes = rungBytes(bi, rung);
        ClassReader reader = new ClassReader(bytes);
        TreeSet<String> calls = new TreeSet<>();
        TreeSet<String> methods = new TreeSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                methods.add(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + n);
                    }
                };
            }
        }, 0);

        List<String> out = new ArrayList<>();
        if (implementsInstrumentedClass(bytes)) {
            out.add("marker");
        }
        if (methods.contains("__STATIC_RESET")) {
            out.add("staticReset");
        }
        if (calls.contains("ExecutionTracer.enterClassInit")) {
            out.add("classInitHooks");
        }
        if (calls.contains("System.registerObjectForIdentityHashCode")) {
            out.add("identityHash");
        }
        if (calls.contains("ExecutionTracer.passedBranch")) {
            out.add("branchProbes");
        }
        if (calls.contains("ExecutionTracer.passedLine")) {
            out.add("lineProbes");
        }
        if (calls.contains("ExecutionTracer.enteredMethod")) {
            out.add("methodProbes");
        }
        if (calls.contains("ExecutionTracer.returnValue")) {
            out.add("returnProbes");
        }
        if (calls.contains("ExecutionTracer.checkTimeout")) {
            out.add("killSwitch");
        }
        if (calls.contains("ExecutionTracer.passedGetStatic") && calls.contains("ExecutionTracer.passedPutStatic")) {
            out.add("staticAccess");
        }
        if (calls.contains("LoopCounter.checkLoop")) {
            out.add("loopCounter");
        }
        if (calls.contains("StringHelper.StringEquals")) {
            out.add("stringTransform");
        }
        return String.join(" ", out);
    }

    private byte[] readResource(String internalName) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + internalName + ".class")) {
            Assertions.assertNotNull(in, internalName);
            return in.readAllBytes();
        }
    }

    private static boolean implementsInstrumentedClass(byte[] bytes) {
        String marker = InstrumentedClass.class.getCanonicalName().replace('.', '/');
        return Arrays.asList(new ClassReader(bytes).getInterfaces()).contains(marker);
    }

    private static byte[] classBytes(String internalName, int version) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Replaces the three overridable rungs by stubs that record the call and either return a
     * sentinel or fail as scripted. The bare passthrough rung is private and runs for real.
     */
    private static class Ladder extends BytecodeInstrumentation {
        final List<String> calls = new ArrayList<>();
        private final Function<String, Failure> script;

        Ladder(Function<String, Failure> script) {
            this.script = script;
        }

        byte[] run(String internalName, int version) {
            return runBytes(internalName, classBytes(internalName, version));
        }

        byte[] runBytes(String internalName, byte[] bytes) {
            return transformBytes(getClass().getClassLoader(), internalName, new ClassReader(bytes));
        }

        private byte[] step(String label) {
            calls.add(label);
            Failure failure = script.apply(label);
            if (failure == null) {
                return SENTINEL;
            }
            switch (failure) {
                case TOO_LARGE:
                    throw new MethodTooLargeException("Sample", "m", "()V", 70000);
                case JSR:
                    throw new RuntimeException("JSR/RET are not supported");
                case OTHER:
                    throw new IllegalStateException("unrelated");
                default:
                    ArrayIndexOutOfBoundsException ex = new ArrayIndexOutOfBoundsException("Index 0 out of bounds");
                    ex.setStackTrace(new StackTraceElement[]{
                            new StackTraceElement("org.objectweb.asm.Frame", "merge", "Frame.java", 1),
                            new StackTraceElement("org.objectweb.asm.MethodWriter", "computeAllFrames",
                                    "MethodWriter.java", 1)});
                    throw ex;
            }
        }

        @Override
        protected byte[] transformBytesInternal(ClassLoader classLoader, String className, String classNameWithDots,
                                                ClassReader reader, int readFlags, boolean enableLoopCounter,
                                                int writerFlags) {
            String flags = writerFlags == ClassWriter.COMPUTE_FRAMES ? "FRAMES"
                    : writerFlags == ClassWriter.COMPUTE_MAXS ? "MAXS" : "other" + writerFlags;
            return step("internal(loop=" + enableLoopCounter + "," + flags + ")");
        }

        @Override
        protected byte[] transformBytesSkipTestability(ClassLoader classLoader, String className,
                                                       String classNameWithDots, ClassReader reader, int readFlags) {
            return step(SKIP_TT);
        }

        @Override
        protected byte[] transformBytesMinimal(ClassLoader classLoader, String className,
                                               String classNameWithDots, ClassReader reader, int readFlags) {
            return step(MINIMAL);
        }
    }
}
