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

import org.evosuite.PackageInfo;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.classpath.ResourceList;
import org.evosuite.graphs.cfg.CFGClassAdapter;
import org.evosuite.instrumentation.error.ErrorConditionClassAdapter;
import org.evosuite.instrumentation.testability.BooleanTestabilityTransformation;
import org.evosuite.instrumentation.testability.ComparisonTransformation;
import org.evosuite.instrumentation.testability.ContainerTransformation;
import org.evosuite.instrumentation.testability.StringTransformation;
import org.evosuite.junit.writer.TestSuiteWriterUtils;
import org.evosuite.runtime.instrumentation.*;
import org.evosuite.runtime.util.ComputeClassWriter;
import org.evosuite.seeding.PrimitiveClassAdapter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcarver.instrument.Instrumenter;
import org.evosuite.testcarver.instrument.TransformerUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.SerialVersionUIDAdder;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The bytecode transformer - transforms bytecode depending on package and
 * whether it is the class under test.
 *
 * @author Gordon Fraser
 */
public class BytecodeInstrumentation {

    private static final Logger logger = LoggerFactory.getLogger(BytecodeInstrumentation.class);

    private static final String[] EVOSUITE_PACKAGES = new String[]{
            PackageInfo.getEvoSuitePackage(),
            "org.exsyst",
            "de.unisb.cs.st.testcarver",
            "de.unisb.cs.st.evosuite",
            "testing.generation.evosuite",
            "de.unisl.cs.st.bugex"
    };

    private final Instrumenter testCarvingInstrumenter;
    private final InstrumentationConfig config;

    /**
     * <p>
     * Constructor for BytecodeInstrumentation.
     * </p>
     */
    public BytecodeInstrumentation() {
        this(InstrumentationConfig.fromProperties());
    }

    /**
     * <p>
     * Constructor for BytecodeInstrumentation.
     * </p>
     *
     * @param config The configuration to use for instrumentation.
     */
    public BytecodeInstrumentation(InstrumentationConfig config) {
        this.testCarvingInstrumenter = new Instrumenter();
        this.config = config;
    }

    private static String[] getEvoSuitePackages() {
        return EVOSUITE_PACKAGES;
    }

    /**
     * Check if we can instrument the given class.
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public static boolean checkIfCanInstrument(String className) {
        return RuntimeInstrumentation.checkIfCanInstrument(className);
    }

    /**
     * Check if the class belongs to an EvoSuite package.
     * This includes the current EvoSuite package and legacy packages from previous versions.
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public static boolean checkIfEvoSuitePackage(String className) {
        for (String s : BytecodeInstrumentation.getEvoSuitePackages()) {
            if (className.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>
     * shouldTransform.
     * </p>
     *
     * @param className a {@link java.lang.String} object.
     * @return a boolean.
     */
    public boolean shouldTransform(String className) {
        if (!config.ttEnabled()) {
            return false;
        }
        switch (config.ttScope()) {
            case ALL:
                logger.info("Allowing transformation of " + className);
                return true;
            case TARGET:
                if (className.equals(config.targetClass()) || className.startsWith(config.targetClass() + "$")) {
                    return true;
                }
                break;
            case PREFIX:
                if (className.startsWith(config.projectPrefix())) {
                    return true;
                }
                break;
            default:
                break;

        }
        logger.info("Preventing transformation of " + className);
        return false;
    }

    private boolean isTargetClassName(String className) {
        // TODO: Need to replace this in the long term
        return TestCluster.isTargetClassName(className);
    }

    /**
     * Check whether the given class is the class under test (CUT).
     *
     * <p>This uses the narrow CUT definition that the coverage goal factories use (see
     * {@code AbstractFitnessFactory.isCUT} and {@code InstrumentingClassLoader.shouldWarnForMissingClass}):
     * the target class itself plus its nested classes ({@code Foo$Bar}).  Nested classes
     * count as CUT because their branches and lines are part of the target's coverage
     * goals, so losing their probes degrades the reported coverage just as silently as
     * losing the probes of the outer class.
     *
     * <p>The broader {@link org.evosuite.setup.TestCluster#isTargetClassName(String)} is
     * deliberately NOT used here: it also matches every class below
     * {@code TARGET_CLASS_PREFIX} and, with {@code INSTRUMENT_PARENT}, subclasses of the
     * target.  Those are exactly the dependency classes that the instrumentation fallback
     * ladder exists to rescue, and their behaviour must not change.
     *
     * @param classNameWithDots the class name in binary (dotted) form.
     * @return true if the class is the class under test or one of its nested classes.
     */
    private boolean isClassUnderTest(String classNameWithDots) {
        String targetClass = config.targetClass();
        if (targetClass == null || targetClass.isEmpty()) {
            return false;
        }
        return classNameWithDots.equals(targetClass)
                || classNameWithDots.startsWith(targetClass + "$");
    }

    /**
     * Abort instead of silently returning bytecode without coverage probes for the class
     * under test.
     *
     * <p>The fallback profiles that stop emitting probes ({@link Profile#MINIMAL},
     * {@link Profile#BARE} and {@link Profile#RAW}) are the right answer for dependency
     * classes: the run continues and only loses instrumentation we do not measure anyway.
     * For the CUT they are the wrong answer: no probes means no branches and no lines are
     * registered, so the run completes "successfully" while reporting exactly 0 coverage with
     * nothing in the log marking the result as invalid.  We log at ERROR and fail loudly
     * instead.
     *
     * @param classNameWithDots the class name in binary (dotted) form.
     * @param rung              description of the coverage-free fallback we were about to take.
     * @param cause             the underlying instrumentation failure, may be null.
     */
    private void failIfClassUnderTest(String classNameWithDots, String rung, Throwable cause) {
        if (!isClassUnderTest(classNameWithDots)) {
            return;
        }
        String message = "Cannot instrument class under test " + classNameWithDots
                + ": the remaining fallback (" + rung + ") emits no coverage probes, so the run"
                + " would report 0 coverage without any coverage goals. Underlying failure: "
                + (cause == null ? "unknown" : cause.toString());
        logger.error(message, cause);
        throw new TargetClassInstrumentationException(message, cause);
    }

    /**
     * The instrumentation configurations that {@link #transformBytes} tries, from the full
     * instrumentation down to an unchanged copy of the class.  {@link #next} decides which one
     * follows a failure.
     *
     * <p>Every profile with an adapter chain also collects constants for the value pool and adds
     * the mocking adapters when the agent is needed.  The other adapters are switched by the
     * flags, each of which additionally depends on its configuration switch:
     * <ul>
     *   <li>{@code writerFlags}: the {@code ClassWriter} flags.  Class files from Java 7 on need
     *       their StackMapTable frames, so only {@link #FULL_MAXS} uses {@code COMPUTE_MAXS}, and
     *       only for legacy class files.</li>
     *   <li>{@code loopCounter}: the loop counter ({@code max_loop_iterations >= 0}).</li>
     *   <li>{@code testability}: the ClassNode roundtrip with the String, Comparison, Container
     *       and Boolean testability transformations, for the classes that get them.</li>
     *   <li>{@code targetChain}: the coverage adapters for the classes that are analysed, the
     *       non-target adapters otherwise.  Without it every class gets the non-target adapters,
     *       so the class under test loses its coverage probes.</li>
     *   <li>{@code timeoutRethrow}: the timeout rethrow in the catch handlers of analysed
     *       classes; the non-target adapters add their own.</li>
     *   <li>{@code staticAccess}: the probes on GETSTATIC/PUTSTATIC ({@code reset_static_fields}).</li>
     *   <li>{@code purity}: the cheap purity analysis ({@code pure_inspectors}).</li>
     *   <li>{@code classReset}: {@code __STATIC_RESET} and the class initialisation callbacks
     *       ({@code reset_static_fields}).</li>
     * </ul>
     */
    enum Profile {
        //                                          loop   testab target rethrow static purity reset
        FULL(ClassWriter.COMPUTE_FRAMES,            true,  true,  true,  true,   true,  true,  true),
        FULL_MAXS(ClassWriter.COMPUTE_MAXS,         true,  true,  true,  true,   true,  true,  true),
        NO_LOOP_COUNTER(ClassWriter.COMPUTE_FRAMES, false, true,  true,  true,   true,  true,  true),
        NO_TESTABILITY(ClassWriter.COMPUTE_FRAMES,  false, false, true,  true,   true,  true,  true),
        MINIMAL(ClassWriter.COMPUTE_FRAMES,         false, false, false, false,  false, false, true),
        /**
         * Only the class-level changes (marker interface, final removed); the method bytecode
         * and its frames are copied as read.
         */
        BARE(0,                                     false, false, false, false,  false, false, false),
        /** The original bytes, unchanged. */
        RAW(0,                                      false, false, false, false,  false, false, false);

        final int writerFlags;
        final boolean loopCounter;
        final boolean testability;
        final boolean targetChain;
        final boolean timeoutRethrow;
        final boolean staticAccess;
        final boolean purity;
        final boolean classReset;

        Profile(int writerFlags, boolean loopCounter, boolean testability, boolean targetChain,
                boolean timeoutRethrow, boolean staticAccess, boolean purity, boolean classReset) {
            this.writerFlags = writerFlags;
            this.loopCounter = loopCounter;
            this.testability = testability;
            this.targetChain = targetChain;
            this.timeoutRethrow = timeoutRethrow;
            this.staticAccess = staticAccess;
            this.purity = purity;
            this.classReset = classReset;
        }
    }

    /** What went wrong in an attempt, as far as the choice of the next profile is concerned. */
    enum FailureKind {
        /** ASM could not compute the frames or maxima of the instrumented code. */
        FRAME,
        /** The instrumented class or one of its methods exceeds a JVM limit. */
        TOO_LARGE,
        /** The class uses JSR/RET, which COMPUTE_FRAMES cannot handle. */
        JSR,
        /** Anything else; never retried. */
        OTHER
    }

    /**
     * The two fallback ladders.  Once a class has been found too large, it stays on the size
     * ladder, which only moves on after another "too large".
     */
    enum Ladder {
        FRAME, SIZE
    }

    /** The profile to try next, and the ladder it belongs to. */
    static final class Step {
        final Profile profile;
        final Ladder ladder;

        Step(Profile profile, Ladder ladder) {
            this.profile = profile;
            this.ladder = ladder;
        }
    }

    static FailureKind classify(Throwable throwable) {
        if (isUnsupportedJsrRetFailure(throwable)) {
            return FailureKind.JSR;
        }
        if (isClassOrMethodTooLarge(throwable)) {
            return FailureKind.TOO_LARGE;
        }
        if (isAsmFrameMergeFailure(throwable)) {
            return FailureKind.FRAME;
        }
        return FailureKind.OTHER;
    }

    /**
     * The fallback after {@code failed} failed with {@code failure} on {@code ladder}, or null if
     * the failure is to be rethrown.  Profiles that do not apply to the class are skipped
     * afterwards (see {@link #transformBytes}).
     */
    static Step next(Profile failed, FailureKind failure, Ladder ladder) {
        if (ladder == Ladder.SIZE) {
            if (failure != FailureKind.TOO_LARGE) {
                return null;
            }
            switch (failed) {
                case NO_TESTABILITY:
                    return new Step(Profile.MINIMAL, Ladder.SIZE);
                case MINIMAL:
                    return new Step(Profile.BARE, Ladder.SIZE);
                default:
                    return null;
            }
        }
        // The testability transformation is the main source of constant pool inflation
        // (ClassNode roundtrip + Comparison/String/Container transformations), so a class
        // that is too large starts the size ladder by dropping it.
        Step sizeLadder = new Step(Profile.NO_TESTABILITY, Ladder.SIZE);
        switch (failed) {
            case FULL:
                if (failure == FailureKind.JSR) {
                    return new Step(Profile.RAW, Ladder.FRAME);
                }
                return failure == FailureKind.FRAME ? new Step(Profile.FULL_MAXS, Ladder.FRAME)
                        : failure == FailureKind.TOO_LARGE ? sizeLadder : null;
            case FULL_MAXS:
                // The loop counter adds branches that can confuse ASM frame merge.
                return failure == FailureKind.FRAME ? new Step(Profile.NO_LOOP_COUNTER, Ladder.FRAME)
                        : failure == FailureKind.TOO_LARGE ? sizeLadder : null;
            case NO_LOOP_COUNTER:
                // The ClassNode roundtrip and the testability transformations can produce
                // bytecode that confuses ASM's frame computation.
                return failure == FailureKind.FRAME ? new Step(Profile.NO_TESTABILITY, Ladder.FRAME)
                        : failure == FailureKind.TOO_LARGE ? sizeLadder : null;
            case NO_TESTABILITY:
                return failure == FailureKind.FRAME ? new Step(Profile.MINIMAL, Ladder.FRAME)
                        : failure == FailureKind.TOO_LARGE ? sizeLadder : null;
            case MINIMAL:
                return failure == FailureKind.FRAME || failure == FailureKind.TOO_LARGE
                        ? new Step(Profile.BARE, Ladder.FRAME) : null;
            case BARE:
                return failure == FailureKind.JSR ? new Step(Profile.RAW, Ladder.FRAME) : null;
            default:
                return null;
        }
    }

    /**
     * <p>
     * transformBytes.
     * </p>
     *
     * <p>Starts with {@link Profile#FULL} and, as long as the failures are ones a smaller
     * instrumentation can avoid, falls back along the ladder given by {@link #next}.
     *
     * @param classLoader a {@link java.lang.ClassLoader} object.
     * @param className a {@link java.lang.String} object.
     * @param reader    a {@link org.objectweb.asm.ClassReader} object.
     * @return an array of byte.
     */
    public byte[] transformBytes(ClassLoader classLoader, String className, ClassReader reader) {

        int readFlags = ClassReader.SKIP_FRAMES;

        if (config.skipDebug()) {
            readFlags |= ClassReader.SKIP_DEBUG;
        }

        String classNameWithDots = ResourceList.getClassNameFromResourcePath(className);

        if (!checkIfCanInstrument(classNameWithDots)) {
            throw new RuntimeException("Should not transform a shared class (" + classNameWithDots
                    + ")! Load by parent (JVM) classloader.");
        }

        TransformationStatistics.reset();

        Step step = new Step(Profile.FULL, Ladder.FRAME);
        Throwable firstFailure = null;
        Throwable sizeFailure = null;
        while (true) {
            Profile profile = step.profile;
            try {
                return transform(profile, classLoader, className, classNameWithDots, reader, readFlags);
            } catch (RuntimeException | Error t) {
                FailureKind failure = classify(t);
                if (firstFailure == null) {
                    firstFailure = t;
                }
                Step next = skipInapplicable(next(profile, failure, step.ladder), failure, classNameWithDots,
                        reader);
                if (next == null) {
                    throw t;
                }
                if (next.ladder == Ladder.SIZE && step.ladder == Ladder.FRAME) {
                    sizeFailure = t;
                }
                announce(next, profile, classNameWithDots, t, firstFailure, sizeFailure);
                step = next;
            }
        }
    }

    /**
     * Skip the profiles that cannot help this class: COMPUTE_MAXS for a class file that needs
     * StackMapTable frames, and dropping a loop counter that is not there.
     */
    private Step skipInapplicable(Step step, FailureKind failure, String classNameWithDots, ClassReader reader) {
        while (step != null && !applies(step.profile, classNameWithDots, reader)) {
            step = next(step.profile, failure, step.ladder);
        }
        return step;
    }

    private boolean applies(Profile profile, String classNameWithDots, ClassReader reader) {
        switch (profile) {
            case FULL_MAXS:
                if (isLegacyClassFile(reader)) {
                    return true;
                }
                // Modern class files require StackMapTable frames, and COMPUTE_MAXS can
                // produce verifier-invalid bytecode (VerifyError: expecting stackmap frame).
                logger.warn("Skipping COMPUTE_MAXS fallback for {} (class file major version {}) "
                                + "because StackMapTable frames are required",
                        classNameWithDots, getClassFileMajorVersion(reader));
                return false;
            case NO_LOOP_COUNTER:
                return config.maxLoopIterations() >= 0;
            default:
                return true;
        }
    }

    /**
     * Log the fallback we are about to take, and refuse the coverage-free ones for the class
     * under test.
     */
    private void announce(Step step, Profile failed, String classNameWithDots, Throwable failure,
                          Throwable firstFailure, Throwable sizeFailure) {
        boolean size = step.ladder == Ladder.SIZE;
        switch (step.profile) {
            case FULL_MAXS:
                logger.warn("Retrying instrumentation of {} with COMPUTE_MAXS "
                                + "due to ASM frame merge failure: {}",
                        classNameWithDots, firstFailure.getMessage());
                break;
            case NO_LOOP_COUNTER:
                logger.warn("Retrying instrumentation of {} without loop counter "
                                + "due to ASM frame merge failure: {}",
                        classNameWithDots, firstFailure.getMessage());
                break;
            case NO_TESTABILITY:
                if (size) {
                    logger.warn("Class {} is too large ({}); retrying without testability transformation",
                            classNameWithDots, sizeFailure.getMessage());
                } else {
                    logger.warn("Retrying instrumentation of {} without testability transformation "
                            + "due to persistent ASM frame merge failure", classNameWithDots);
                }
                break;
            case MINIMAL:
                if (size) {
                    // A class we cannot fit inside the JVM limits is still a class we cannot
                    // measure, and a silent 0 coverage result is worse than an explicit failure.
                    failIfClassUnderTest(classNameWithDots,
                            "minimal (non-target) instrumentation for an over-sized class", sizeFailure);
                    logger.warn("Class {} is still too large; retrying with minimal (non-target) instrumentation",
                            classNameWithDots);
                } else {
                    failIfClassUnderTest(classNameWithDots, "minimal (non-target) instrumentation", firstFailure);
                    logger.warn("Retrying instrumentation of {} with minimal (non-target) instrumentation "
                                    + "due to persistent ASM frame merge failure",
                            classNameWithDots);
                }
                break;
            case BARE:
                if (size) {
                    failIfClassUnderTest(classNameWithDots,
                            "bare passthrough (marker interface only) for an over-sized class", sizeFailure);
                    logger.warn("Class {} is still too large; retrying with bare passthrough", classNameWithDots);
                } else {
                    failIfClassUnderTest(classNameWithDots, "bare passthrough (marker interface only)",
                            firstFailure);
                    logger.warn("Retrying instrumentation of {} with bare passthrough (no method-level changes) "
                            + "due to persistent ASM failure", classNameWithDots);
                }
                break;
            case RAW:
                failIfClassUnderTest(classNameWithDots, "raw bytecode passthrough", failure);
                if (failed == Profile.BARE) {
                    logger.warn("Falling back to raw bytecode passthrough for {} "
                                    + "due to unsupported JSR/RET in bare passthrough: {}",
                            classNameWithDots, failure.getMessage());
                } else {
                    logger.warn("Retrying instrumentation of {} with raw bytecode passthrough "
                                    + "due to unsupported JSR/RET: {}",
                            classNameWithDots, failure.getMessage());
                }
                break;
            default:
                break;
        }
    }

    /**
     * Instrument the class with the given profile.
     *
     * @param profile           which instrumentation to apply.
     * @param classLoader       the class loader the class is instrumented for.
     * @param className         the class name in internal (slashed) form.
     * @param classNameWithDots the class name in binary (dotted) form.
     * @param reader            the original bytecode.
     * @param readFlags         the {@code ClassReader} flags for the profiles with an adapter chain.
     * @return the instrumented bytecode.
     */
    protected byte[] transform(Profile profile, ClassLoader classLoader, String className, String classNameWithDots,
                               ClassReader reader, int readFlags) {
        if (profile == Profile.RAW) {
            return copyOriginalBytecode(reader);
        }
        if (profile == Profile.BARE) {
            return transformBytesBarePassthrough(className, reader);
        }
        /*
         * To use COMPUTE_FRAMES we need to remove JSR commands. Therefore, we
         * have a JSRInlinerAdapter in NonTargetClassAdapter as well as
         * CFGAdapter.
         */
        ClassWriter writer = new ComputeClassWriter(profile.writerFlags);

        ClassVisitor cv = writer;
        if (logger.isDebugEnabled()) {
            // TraceClassVisitor prints to a PrintWriter. Bridging to SLF4J is non-trivial without buffering.
            // Using System.err for debug trace as is common for TraceClassVisitor.
            cv = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        cv = createAdapterChain(profile, cv, className, classNameWithDots, classLoader, reader);

        if (profile.testability && shouldApplyTestabilityTransformation(classNameWithDots)) {
            return applyTestabilityTransformation(reader, cv, writer, className, classNameWithDots,
                    classLoader, readFlags);
        }

        reader.accept(cv, readFlags);
        return writer.toByteArray();
    }

    private ClassVisitor createAdapterChain(Profile profile, ClassVisitor cv, String className,
                                            String classNameWithDots, ClassLoader classLoader,
                                            ClassReader reader) {
        if (profile.timeoutRethrow && DependencyAnalysis.shouldAnalyze(classNameWithDots)) {
            // Innermost, so the injected rethrow is written straight to the writer and
            // never enters the MethodNode buffers of the CFG/testability adapters.
            cv = new TimeoutExceededRethrowClassAdapter(cv);
        }

        if (profile.staticAccess && config.resetStaticFields()) {
            cv = new StaticAccessClassAdapter(cv, className);
        }

        if (profile.purity && config.pureInspectors()) {
            CheapPurityAnalyzer purityAnalyzer = CheapPurityAnalyzer.getInstance();
            cv = new PurityAnalysisClassVisitor(cv, className, purityAnalyzer);
        }

        if (profile.loopCounter && config.maxLoopIterations() >= 0) {
            cv = new LoopCounterClassAdapter(cv);
        }

        // Apply transformations to class under test and its owned classes
        if (!profile.targetChain) {
            cv = addNonTargetTransformationAdapters(cv, className, classNameWithDots, classLoader);
        } else if (DependencyAnalysis.shouldAnalyze(classNameWithDots)) {
            logger.debug("Applying target transformation to class " + classNameWithDots);
            cv = addTargetTransformationAdapters(cv, className, classLoader);
        } else {
            logger.debug("Not applying target transformation");
            cv = addNonTargetTransformationAdapters(cv, className, classNameWithDots, classLoader);
        }

        // Collect constant values for the value pool
        cv = new PrimitiveClassAdapter(cv, className);

        if (profile.classReset && config.resetStaticFields()) {
            cv = handleStaticReset(className, cv, reader);
        }

        // Mock instrumentation (eg File and TCP).
        if (TestSuiteWriterUtils.needToUseAgent()) {
            cv = addMockingAdapters(cv, className);
        }

        return cv;
    }
    private static boolean isAsmFrameMergeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ArrayIndexOutOfBoundsException
                    || current instanceof IndexOutOfBoundsException
                    || current instanceof NegativeArraySizeException) {
                for (StackTraceElement element : current.getStackTrace()) {
                    String className = element.getClassName();
                    String methodName = element.getMethodName();
                    if (className.endsWith(".Frame") && "merge".equals(methodName)) {
                        return true;
                    }
                    if (className.endsWith(".MethodWriter")
                            && ("computeAllFrames".equals(methodName)
                                || "visitMaxs".equals(methodName))) {
                        return true;
                    }
                    // AnalyzerAdapter.pop() underflow: the testability transformation
                    // produced bytecode whose operand stack is inconsistent with what
                    // the AnalyzerAdapter expects.
                    if (className.endsWith(".AnalyzerAdapter") && "pop".equals(methodName)) {
                        return true;
                    }
                    // Data-flow Analyzer.analyze covers BooleanTestabilityTransformation,
                    // StringTransformation, MutationInstrumentation, PurityAnalysisMethodVisitor,
                    // and any other ASM Analyzer<V> user — these can throw AIOOBE on bytecode
                    // shapes the interpreter wasn't designed for.
                    if (className.endsWith(".Analyzer") && "analyze".equals(methodName)) {
                        return true;
                    }
                    // JSRInlinerAdapter buffers the method and replays at visitEnd; failures
                    // inside that replay (or its own JSR-handling) are recoverable by stripping
                    // instrumentation.
                    if (className.endsWith(".JSRInlinerAdapter")) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Check if the throwable is a ClassTooLargeException or MethodTooLargeException from ASM.
     * These extend IndexOutOfBoundsException, so they match the instanceof check in
     * isAsmFrameMergeFailure, but their stack traces don't match the expected patterns,
     * meaning the standard retry logic won't handle them.
     */
    private static boolean isClassOrMethodTooLarge(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.endsWith(".ClassTooLargeException") || name.endsWith(".MethodTooLargeException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isUnsupportedJsrRetFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("JSR/RET are not supported")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static byte[] copyOriginalBytecode(ClassReader reader) {
        ClassWriter writer = new ClassWriter(0);
        reader.accept(writer, 0);
        return writer.toByteArray();
    }

    private static boolean isLegacyClassFile(ClassReader reader) {
        return getClassFileMajorVersion(reader) < Opcodes.V1_7;
    }

    private static int getClassFileMajorVersion(ClassReader reader) {
        // ClassFile header layout: magic (4), minor (2), major (2)
        return reader.readShort(6);
    }

    /**
     * Bare passthrough: reads the class with EXPAND_FRAMES to preserve the original
     * StackMapTable, adds only the InstrumentedClass marker interface and removes
     * final modifiers, but does NOT modify any method bytecode.  This avoids all
     * frame computation issues at the cost of losing all method-level instrumentation.
     * The writer flag is 0 (copy frames verbatim from the reader).
     */
    private byte[] transformBytesBarePassthrough(String className, ClassReader reader) {
        ClassWriter writer = new ComputeClassWriter(0);
        ClassVisitor cv = writer;

        // Only class-level changes: remove final, add InstrumentedClass interface
        cv = new ClassVisitor(Opcodes.ASM9, cv) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                // Remove final
                access &= ~Opcodes.ACC_FINAL;
                // Add InstrumentedClass interface
                String instrumentedInterface = InstrumentedClass.class.getCanonicalName().replace('.', '/');
                boolean found = false;
                for (String iface : interfaces) {
                    if (iface.equals(instrumentedInterface)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    String[] newInterfaces = new String[interfaces.length + 1];
                    System.arraycopy(interfaces, 0, newInterfaces, 0, interfaces.length);
                    newInterfaces[interfaces.length] = instrumentedInterface;
                    interfaces = newInterfaces;
                }
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                super.visitInnerClass(name, outerName, innerName, access & ~Opcodes.ACC_FINAL);
            }
        };

        // Read with EXPAND_FRAMES to preserve existing StackMapTable entries
        reader.accept(cv, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private ClassVisitor addTargetTransformationAdapters(ClassVisitor cv, String className, ClassLoader classLoader) {
        if (!config.testCarving() && config.makeAccessible()) {
            cv = new AccessibleClassAdapter(cv, className, classLoader);
        }

        cv = new RemoveFinalClassAdapter(cv);

        cv = new ExecutionPathClassAdapter(cv, className);

        cv = new CFGClassAdapter(classLoader, cv, className);

        if (config.exceptionBranches()) {
            cv = new ExceptionTransformationClassAdapter(cv, className);
        }

        if (config.errorBranches()) {
            cv = new ErrorConditionClassAdapter(cv, className);
        }
        return cv;
    }

    private ClassVisitor addNonTargetTransformationAdapters(ClassVisitor cv, String className,
                                                            String classNameWithDots, ClassLoader classLoader) {
        cv = new NonTargetClassAdapter(cv, className);

        if (config.makeAccessible()) {
            cv = new AccessibleClassAdapter(cv, className, classLoader);
        }

        // If we are doing testability transformation on all classes we need
        // to create the CFG first
        if (config.ttEnabled() && classNameWithDots.startsWith(config.classPrefix())) {
            cv = new CFGClassAdapter(classLoader, cv, className);
        }
        return cv;
    }

    private ClassVisitor addMockingAdapters(ClassVisitor cv, String className) {
        cv = new MethodCallReplacementClassAdapter(cv, className);

        /*
         * If the class is serializable, then doing any change (adding hashCode, static reset, etc)
         * will change the serialVersionUID if it is not defined in the class.
         * Hence, if it is not defined, we have to define it to
         * avoid problems in serialising the class, as reading Master will not do instrumentation.
         * The serialVersionUID HAS to be the same as the un-instrumented class
         */
        if (config.applyUidTransformation()) {
            cv = new SerialVersionUIDAdder(cv);
        }
        return cv;
    }

    private boolean shouldApplyTestabilityTransformation(String classNameWithDots) {
        if (shouldTransform(classNameWithDots)) {
            return true;
        }
        if (classNameWithDots.startsWith(config.projectPrefix())) {
            return true;
        }
        if (!config.targetClassPrefix().isEmpty()
                && classNameWithDots.startsWith(config.targetClassPrefix())) {
            return true;
        }
        return false;
    }

    private byte[] applyTestabilityTransformation(ClassReader reader, ClassVisitor cv, ClassWriter writer,
                                                  String className, String classNameWithDots,
                                                  ClassLoader classLoader, int readFlags) {
        ClassNode cn = new AnnotatedClassNode();
        reader.accept(cn, readFlags);
        logger.info("Starting transformation of " + className);

        if (config.stringReplacement()) {
            StringTransformation st = new StringTransformation(cn);
            if (isTargetClassName(classNameWithDots) || shouldTransform(classNameWithDots)) {
                cn = st.transform();
            }
        }

        ComparisonTransformation cmp = new ComparisonTransformation(cn);
        if (isTargetClassName(classNameWithDots) || shouldTransform(classNameWithDots)) {
            cn = cmp.transform();
            ContainerTransformation ct = new ContainerTransformation(cn);
            cn = ct.transform();
        }

        if (shouldTransform(classNameWithDots)) {
            logger.info("Testability Transforming " + className);

            BooleanTestabilityTransformation tt = new BooleanTestabilityTransformation(cn, classLoader);
            try {
                cn = tt.transform();
            } catch (Throwable t) {
                // Wrap Throwable in RuntimeException to avoid declaring it or throwing Error
                throw new RuntimeException("Error during boolean testability transformation of " + className, t);
            }
            logger.info("Testability Transformation done: " + className);
        }

        // -----
        cn.accept(cv);

        if (config.testCarving() && TransformerUtil.isClassConsideredForInstrumentation(className)) {
            return handleCarving(className, writer);
        }

        return writer.toByteArray();
    }

    private byte[] handleCarving(String className, ClassWriter writer) {
        ClassReader cr = new ClassReader(writer.toByteArray());
        ClassNode cn2 = new ClassNode();
        cr.accept(cn2, ClassReader.EXPAND_FRAMES);

        this.testCarvingInstrumenter.transformClassNode(cn2, className);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn2.accept(cw);

        if (logger.isDebugEnabled()) {
            final StringWriter sw = new StringWriter();
            cn2.accept(new TraceClassVisitor(new PrintWriter(sw)));
            logger.debug("test carving instrumentation result:\n{}", sw);
        }

        return cw.toByteArray();
    }

    /**
     * Adds the instrumentation to deal with re-iniatilizing classes: adding
     * __STATIC_RESET() methods, inserting callbacks for PUTSTATIC and GETSTATIC
     * instructions.
     *
     * @param className a {@link java.lang.String} object.
     * @param cv a {@link org.objectweb.asm.ClassVisitor} object.
     * @param reader a fresh {@link org.objectweb.asm.ClassReader} over the original,
     *               uninstrumented bytecode, used to decide whether {@code <clinit>} is safe to replay.
     * @return a {@link org.objectweb.asm.ClassVisitor} object.
     */
    private ClassVisitor handleStaticReset(String className, ClassVisitor cv, ClassReader reader) {
        // Create a __STATIC_RESET() cloning the original <clinit> method or
        // create one by default
        cv = new CreateClassResetClassAdapter(cv, className, config.resetStaticFinalFields(), reader);

        // Adds a callback before leaving the <clinit> method
        EndOfClassInitializerVisitor exitClassInitAdapter = new EndOfClassInitializerVisitor(cv, className);
        cv = exitClassInitAdapter;
        return cv;
    }

    /**
     * Thrown when the class under test could only be instrumented by a fallback that emits
     * no coverage probes.  Such a run would report exactly 0 coverage and still look like a
     * success, so we abort instead.  Dependency classes are unaffected: for them the
     * fallbacks are taken as before.
     */
    public static class TargetClassInstrumentationException extends RuntimeException {

        private static final long serialVersionUID = 6017281404946521264L;

        public TargetClassInstrumentationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
