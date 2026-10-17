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
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.instrumentation.BytecodeInstrumentation.TargetClassInstrumentationException;
import org.evosuite.runtime.instrumentation.InstrumentedClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>Where the current behaviour looks accidental the test says so. It documents what happens
 * now; it does not endorse it.
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
     * A frame-merge failure in the first rung of the too-large ladder continues as on the frame
     * ladder: with the minimal rung, and from there on the frame ladder.
     */
    @Test
    public void frameFailureInsideTheTooLargeLadderContinuesWithMinimal() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.TOO_LARGE
                : rung.equals(SKIP_TT) ? Failure.FRAME : null);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT, MINIMAL), ladder.calls);
    }

    @Test
    public void frameFailureInsideTheTooLargeLadderThenFollowsTheFrameLadder() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.TOO_LARGE : Failure.FRAME);

        byte[] out = ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT, MINIMAL), ladder.calls);
        Assertions.assertTrue(implementsInstrumentedClass(out), "bare passthrough after the minimal rung");
    }

    /** The class under test still gets the frame failure itself, as before. */
    @Test
    public void frameFailureInsideTheTooLargeLadderIsRethrownForTheClassUnderTest() {
        Properties.TARGET_CLASS = CUT;
        Ladder ladder = new Ladder(rung -> rung.equals(FULL) ? Failure.TOO_LARGE
                : rung.equals(SKIP_TT) ? Failure.FRAME : null);

        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> ladder.run(CUT_RESOURCE, Opcodes.V1_8));

        Assertions.assertEquals(Arrays.asList(FULL, SKIP_TT), ladder.calls);
    }

    /**
     * A class outside the project prefix gets no testability transformation, so dropping it
     * would repeat the attempt without the loop counter.
     */
    @Test
    public void frameLadderSkipsTheRepeatedAttemptForADependencyWithoutTestability() {
        Properties.TARGET_CLASS = "sample.Other";
        Properties.PROJECT_PREFIX = "proj";
        Ladder ladder = new Ladder(rung -> Failure.FRAME);

        ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, MINIMAL), ladder.calls);
    }

    @Test
    public void tooLargeLadderSkipsDroppingWhatTheFirstAttemptDidNotHave() {
        Properties.MAX_LOOP_ITERATIONS = -1;
        Properties.TARGET_CLASS = "sample.Other";
        Properties.PROJECT_PREFIX = "proj";
        Ladder ladder = new Ladder(rung -> Failure.TOO_LARGE);

        ladder.run(CUT_RESOURCE, Opcodes.V1_8);

        Assertions.assertEquals(Arrays.asList(FULL, MINIMAL), ladder.calls);
    }

    @Test
    public void tooLargeAfterDroppingTestabilityDoesNotRepeatThatAttempt() {
        Properties.TARGET_CLASS = "sample.Other";
        Ladder ladder = new Ladder(rung -> rung.equals(SKIP_TT) ? Failure.TOO_LARGE
                : rung.equals(MINIMAL) ? null : Failure.FRAME);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, SKIP_TT, MINIMAL), ladder.calls);
    }

    @Test
    public void classUnderTestWithTestabilityKeepsEveryAttempt() {
        Properties.TARGET_CLASS = CUT;
        Properties.PROJECT_PREFIX = "sample";
        Ladder ladder = new Ladder(rung -> rung.equals(SKIP_TT) ? null : Failure.FRAME);

        Assertions.assertArrayEquals(SENTINEL, ladder.run(CUT_RESOURCE, Opcodes.V1_8));
        Assertions.assertEquals(Arrays.asList(FULL, NO_LOOP, SKIP_TT), ladder.calls);
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

    private enum Rung {FULL_RUNG, FULL_MAXS_RUNG, NO_LOOP_RUNG, SKIP_TESTABILITY_RUNG, MINIMAL_RUNG, BARE_RUNG}

    private static final String BASE = "marker staticReset classInitHooks identityHash";
    private static final String TARGET_PROBES = "branchProbes lineProbes methodProbes returnProbes";

    @Test
    public void classUnderTestProfiles() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess loopCounter stringTransform",
                features(bi, Rung.FULL_RUNG));
        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess loopCounter stringTransform",
                features(bi, Rung.FULL_MAXS_RUNG));
        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess stringTransform",
                features(bi, Rung.NO_LOOP_RUNG));
        // The skip-testability rung also drops the loop counter.
        Assertions.assertEquals(BASE + " " + TARGET_PROBES + " staticAccess",
                features(bi, Rung.SKIP_TESTABILITY_RUNG));
        // Minimal drops the coverage probes AND the static-access probes, but keeps __STATIC_RESET.
        Assertions.assertEquals(BASE + " killSwitch", features(bi, Rung.MINIMAL_RUNG));
        Assertions.assertEquals("marker", features(bi, Rung.BARE_RUNG));
    }

    @Test
    public void dependencyClassProfiles() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        Properties.TARGET_CLASS = "sample.Other";
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());

        Assertions.assertEquals(BASE + " killSwitch staticAccess loopCounter", features(bi, Rung.FULL_RUNG));
        Assertions.assertEquals(BASE + " killSwitch staticAccess loopCounter", features(bi, Rung.FULL_MAXS_RUNG));
        Assertions.assertEquals(BASE + " killSwitch staticAccess", features(bi, Rung.NO_LOOP_RUNG));
        Assertions.assertEquals(BASE + " killSwitch staticAccess", features(bi, Rung.SKIP_TESTABILITY_RUNG));
        // Minimal loses the static-access probes of a dependency as well.
        Assertions.assertEquals(BASE + " killSwitch", features(bi, Rung.MINIMAL_RUNG));
        Assertions.assertEquals("marker", features(bi, Rung.BARE_RUNG));
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
     * For a dependency without testability transformation, retry 3 (skip
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

    @Test
    public void mockingAdaptersFollowTheAgentSwitch() throws IOException {
        Properties.TARGET_CLASS = FIXTURE;
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
        for (Rung rung : Rung.values()) {
            Assertions.assertEquals(rung != Rung.BARE_RUNG, features(bi, rung).contains("identityHash"), rung.name());
        }

        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.VIRTUAL_NET = false;
        Properties.REPLACE_GUI = false;
        Properties.RESET_STATIC_FIELDS = false;
        for (Rung rung : Rung.values()) {
            Assertions.assertFalse(features(bi, rung).contains("identityHash"), rung.name());
        }
    }

    /**
     * Writer flags: every rung recomputes the frames except the legacy COMPUTE_MAXS rung (no
     * StackMapTable at all) and the bare passthrough, which copies the original frames.
     */
    @Test
    public void writerFlagsPerRung() throws IOException {
        for (String target : Arrays.asList(FIXTURE, "sample.Other")) {
            Properties.TARGET_CLASS = target;
            BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
            for (Rung rung : Rung.values()) {
                byte[] bytes = rungBytes(bi, rung);
                Assertions.assertEquals(rung != Rung.FULL_MAXS_RUNG, hasFrames(bytes), target + " " + rung);
            }
        }
    }

    /**
     * The rungs differ in more than the probes: the timeout rethrow in catch handlers and the
     * purity analysis come from different adapters on different rungs.
     */
    @Test
    public void timeoutRethrowAndPurityPerRung() throws IOException {
        List<String> cut = new ArrayList<>();
        List<String> dependency = new ArrayList<>();
        for (Rung rung : Rung.values()) {
            cut.add(rung + ": " + catchingFeatures(rung, true));
            dependency.add(rung + ": " + catchingFeatures(rung, false));
        }
        // Minimal keeps the rethrow (the non-target chain has its own) but drops the purity analysis.
        Assertions.assertEquals(Arrays.asList(
                "FULL_RUNG: timeoutRethrow purity",
                "FULL_MAXS_RUNG: timeoutRethrow purity",
                "NO_LOOP_RUNG: timeoutRethrow purity",
                "SKIP_TESTABILITY_RUNG: timeoutRethrow purity",
                "MINIMAL_RUNG: timeoutRethrow",
                "BARE_RUNG: "), cut);
        Assertions.assertEquals(cut, dependency);
    }

    @Test
    public void purityAnalysisFollowsItsSwitch() throws IOException {
        Properties.PURE_INSPECTORS = false;
        for (Rung rung : Rung.values()) {
            Assertions.assertFalse(catchingFeatures(rung, true).contains("purity"), rung.name());
            Assertions.assertFalse(catchingFeatures(rung, false).contains("purity"), rung.name());
        }
    }

    /**
     * Instruments {@link CatchingFixture} under a fresh class name, because the purity
     * analyser is a singleton that never forgets a class.
     */
    private String catchingFeatures(Rung rung, boolean asClassUnderTest) throws IOException {
        String name = "rung.Catching" + (asClassUnderTest ? "Cut" : "Dependency") + rung + (++freshNames);
        Properties.TARGET_CLASS = asClassUnderTest ? name : "sample.Other";
        BytecodeInstrumentation bi = new BytecodeInstrumentation(InstrumentationConfig.fromProperties());
        byte[] bytes = rungBytes(bi, rung, CatchingFixture.class.getName(), name);

        List<String> out = new ArrayList<>();
        if (calls(bytes).contains("Runtime.rethrowIfTimeout")) {
            out.add("timeoutRethrow");
        }
        if (registeredWithPurityAnalyser(name)) {
            out.add("purity");
        }
        return String.join(" ", out);
    }

    private static boolean registeredWithPurityAnalyser(String className) {
        try {
            java.lang.reflect.Field field = CheapPurityAnalyzer.class.getDeclaredField("methodEntries");
            field.setAccessible(true);
            for (Object entry : (Iterable<?>) field.get(CheapPurityAnalyzer.getInstance())) {
                if (entry.toString().contains("className=" + className + ",")) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int freshNames;

    public static final class CatchingFixture {
        public int pureValue() {
            return 7;
        }

        public int swallow(Runnable r) {
            try {
                r.run();
                return 0;
            } catch (Throwable t) {
                return 1;
            }
        }
    }

    // ===================================================================== helpers

    private byte[] rungBytes(BytecodeInstrumentation bi, Rung rung) throws IOException {
        return rungBytes(bi, rung, FIXTURE, FIXTURE);
    }

    private byte[] rungBytes(BytecodeInstrumentation bi, Rung rung, String fixture, String instrumentedName)
            throws IOException {
        String internal = instrumentedName.replace('.', '/');
        ClassReader reader = new ClassReader(readResource(fixture.replace('.', '/')));
        ClassLoader loader = getClass().getClassLoader();
        return bi.transform(profile(rung), loader, internal, instrumentedName, reader, ClassReader.SKIP_FRAMES);
    }

    private static BytecodeInstrumentation.Profile profile(Rung rung) {
        switch (rung) {
            case FULL_RUNG:
                return BytecodeInstrumentation.Profile.FULL;
            case FULL_MAXS_RUNG:
                return BytecodeInstrumentation.Profile.FULL_MAXS;
            case NO_LOOP_RUNG:
                return BytecodeInstrumentation.Profile.NO_LOOP_COUNTER;
            case SKIP_TESTABILITY_RUNG:
                return BytecodeInstrumentation.Profile.NO_TESTABILITY;
            case MINIMAL_RUNG:
                return BytecodeInstrumentation.Profile.MINIMAL;
            default:
                return BytecodeInstrumentation.Profile.BARE;
        }
    }

    private static boolean hasFrames(byte[] bytes) {
        boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                        found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private static TreeSet<String> calls(byte[] bytes) {
        TreeSet<String> calls = new TreeSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + n);
                    }
                };
            }
        }, 0);
        return calls;
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
        protected byte[] transform(Profile profile, ClassLoader classLoader, String className,
                                   String classNameWithDots, ClassReader reader, int readFlags) {
            switch (profile) {
                case NO_TESTABILITY:
                    return step(SKIP_TT);
                case MINIMAL:
                    return step(MINIMAL);
                case BARE:
                case RAW:
                    return super.transform(profile, classLoader, className, classNameWithDots, reader, readFlags);
                default:
                    String flags = profile.writerFlags == ClassWriter.COMPUTE_FRAMES ? "FRAMES"
                            : profile.writerFlags == ClassWriter.COMPUTE_MAXS ? "MAXS" : "other" + profile.writerFlags;
                    return step("internal(loop=" + profile.loopCounter + "," + flags + ")");
            }
        }
    }
}
