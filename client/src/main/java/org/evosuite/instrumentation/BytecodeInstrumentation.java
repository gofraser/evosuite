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
     * <p>
     * transformBytes.
     * </p>
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

        try {
            return transformBytesInternal(classLoader, className, classNameWithDots, reader, readFlags, true,
                    ClassWriter.COMPUTE_FRAMES);
        } catch (RuntimeException | Error t) {
            if (isUnsupportedJsrRetFailure(t)) {
                logger.warn("Retrying instrumentation of {} with raw bytecode passthrough "
                                + "due to unsupported JSR/RET: {}",
                        classNameWithDots, t.getMessage());
                return copyOriginalBytecode(reader);
            }
            if (isClassOrMethodTooLarge(t)) {
                return handleClassTooLarge(classLoader, className, classNameWithDots, reader, readFlags, t);
            }
            if (!isAsmFrameMergeFailure(t)) {
                throw t;
            }
            // Retry 1: retry with COMPUTE_MAXS only for legacy class files (< Java 7).
            // Modern class files require StackMapTable frames, and COMPUTE_MAXS can
            // produce verifier-invalid bytecode (VerifyError: expecting stackmap frame).
            if (isLegacyClassFile(reader)) {
                logger.warn("Retrying instrumentation of {} with COMPUTE_MAXS "
                                + "due to ASM frame merge failure: {}",
                        classNameWithDots, t.getMessage());
                try {
                    return transformBytesInternal(classLoader, className, classNameWithDots, reader, readFlags, true,
                            ClassWriter.COMPUTE_MAXS);
                } catch (RuntimeException | Error t2) {
                    if (isClassOrMethodTooLarge(t2)) {
                        return handleClassTooLarge(classLoader, className, classNameWithDots, reader, readFlags, t2);
                    }
                    if (!isAsmFrameMergeFailure(t2)) {
                        throw t2;
                    }
                }
            } else {
                logger.warn("Skipping COMPUTE_MAXS fallback for {} (class file major version {}) "
                                + "because StackMapTable frames are required",
                        classNameWithDots, getClassFileMajorVersion(reader));
            }

            // Retry 2: disable loop counter (if enabled) and retry COMPUTE_FRAMES.
            // The loop counter adds branches that can confuse ASM frame merge.
            if (config.maxLoopIterations() >= 0) {
                logger.warn("Retrying instrumentation of {} without loop counter "
                                + "due to ASM frame merge failure: {}",
                        classNameWithDots, t.getMessage());
                try {
                    return transformBytesInternal(classLoader, className, classNameWithDots, reader, readFlags, false,
                            ClassWriter.COMPUTE_FRAMES);
                } catch (RuntimeException | Error t3) {
                    if (isClassOrMethodTooLarge(t3)) {
                        return handleClassTooLarge(classLoader, className, classNameWithDots, reader, readFlags, t3);
                    }
                    if (!isAsmFrameMergeFailure(t3)) {
                        throw t3;
                    }
                    // Fall through — testability transformation may be causing the issue.
                }
            }
            // Retry 3: skip testability transformation — the ClassNode roundtrip and
            // Comparison/String/Container transformations can produce bytecode that
            // confuses ASM's frame computation.
            logger.warn("Retrying instrumentation of {} without testability transformation "
                    + "due to persistent ASM frame merge failure", classNameWithDots);
            try {
                return transformBytesSkipTestability(classLoader, className, classNameWithDots, reader, readFlags);
            } catch (RuntimeException | Error t4) {
                if (isClassOrMethodTooLarge(t4)) {
                    return handleClassTooLarge(classLoader, className, classNameWithDots, reader, readFlags, t4);
                }
                if (!isAsmFrameMergeFailure(t4)) {
                    throw t4;
                }
            }
            // Retry 4: minimal (non-target) instrumentation.
            logger.warn("Retrying instrumentation of {} with minimal (non-target) instrumentation "
                    + "due to persistent ASM frame merge failure",
                    classNameWithDots);
            try {
                return transformBytesMinimal(classLoader, className, classNameWithDots, reader, readFlags);
            } catch (RuntimeException | Error t5) {
                if (!isAsmFrameMergeFailure(t5) && !isClassOrMethodTooLarge(t5)) {
                    throw t5;
                }
            }
            // Retry 5: bare passthrough — only add the InstrumentedClass marker interface
            // without modifying any method bytecode.  We read with EXPAND_FRAMES to
            // preserve the original StackMapTable and avoid ASM frame recomputation.
            logger.warn("Retrying instrumentation of {} with bare passthrough (no method-level changes) "
                    + "due to persistent ASM failure", classNameWithDots);
            try {
                return transformBytesBarePassthrough(className, reader);
            } catch (RuntimeException | Error t6) {
                if (isUnsupportedJsrRetFailure(t6)) {
                    logger.warn("Falling back to raw bytecode passthrough for {} "
                                    + "due to unsupported JSR/RET in bare passthrough: {}",
                            classNameWithDots, t6.getMessage());
                    return copyOriginalBytecode(reader);
                }
                throw t6;
            }
        }
    }

    protected byte[] transformBytesInternal(ClassLoader classLoader, String className, String classNameWithDots,
                                            ClassReader reader, int readFlags, boolean enableLoopCounter,
                                            int writerFlags) {
        /*
         * To use COMPUTE_FRAMES we need to remove JSR commands. Therefore, we
         * have a JSRInlinerAdapter in NonTargetClassAdapter as well as
         * CFGAdapter.
         */
        ClassWriter writer = new ComputeClassWriter(writerFlags);

        ClassVisitor cv = writer;
        if (logger.isDebugEnabled()) {
            // TraceClassVisitor prints to a PrintWriter. Bridging to SLF4J is non-trivial without buffering.
            // Using System.err for debug trace as is common for TraceClassVisitor.
            cv = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        cv = createAdapterChain(cv, className, classNameWithDots, classLoader, enableLoopCounter);

        if (shouldApplyTestabilityTransformation(classNameWithDots)) {
            return applyTestabilityTransformation(reader, cv, writer, className, classNameWithDots,
                    classLoader, readFlags);
        }

        reader.accept(cv, readFlags);
        return writer.toByteArray();
    }

    private ClassVisitor createAdapterChain(ClassVisitor cv, String className, String classNameWithDots,
                                            ClassLoader classLoader, boolean enableLoopCounter) {
        if (config.resetStaticFields()) {
            cv = new StaticAccessClassAdapter(cv, className);
        }

        if (config.pureInspectors()) {
            CheapPurityAnalyzer purityAnalyzer = CheapPurityAnalyzer.getInstance();
            cv = new PurityAnalysisClassVisitor(cv, className, purityAnalyzer);
        }

        if (enableLoopCounter && config.maxLoopIterations() >= 0) {
            cv = new LoopCounterClassAdapter(cv);
        }

        // Apply transformations to class under test and its owned classes
        if (DependencyAnalysis.shouldAnalyze(classNameWithDots)) {
            logger.debug("Applying target transformation to class " + classNameWithDots);
            cv = addTargetTransformationAdapters(cv, className, classLoader);
        } else {
            logger.debug("Not applying target transformation");
            cv = addNonTargetTransformationAdapters(cv, className, classNameWithDots, classLoader);
        }

        // Collect constant values for the value pool
        cv = new PrimitiveClassAdapter(cv, className);

        if (config.resetStaticFields()) {
            cv = handleStaticReset(className, cv);
        }

        // Mock instrumentation (eg File and TCP).
        if (TestSuiteWriterUtils.needToUseAgent()) {
            cv = addMockingAdapters(cv, className);
        }

        return cv;
    }

    private boolean isAsmFrameMergeFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ArrayIndexOutOfBoundsException
                    || current instanceof IndexOutOfBoundsException
                    || current instanceof NegativeArraySizeException) {
                for (StackTraceElement element : current.getStackTrace()) {
                    String className = element.getClassName();
                    if (className.endsWith(".Frame") && "merge".equals(element.getMethodName())) {
                        return true;
                    }
                    if (className.endsWith(".MethodWriter") && "computeAllFrames".equals(element.getMethodName())) {
                        return true;
                    }
                    // AnalyzerAdapter.pop() underflow: the testability transformation
                    // produced bytecode whose operand stack is inconsistent with what
                    // the AnalyzerAdapter expects.
                    if (className.endsWith(".AnalyzerAdapter") && "pop".equals(element.getMethodName())) {
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
     * Handle a class/method too large error by skipping testability transformation, which is
     * the main source of constant pool inflation (ClassNode roundtrip + Comparison/String/Container
     * transformations). We always keep COMPUTE_FRAMES because modern class files (version >= 51)
     * require StackMapTable frames — COMPUTE_MAXS would produce bytecode the verifier rejects.
     */
    private byte[] handleClassTooLarge(ClassLoader classLoader, String className, String classNameWithDots,
                                       ClassReader reader, int readFlags, Throwable originalError) {
        // First try: skip testability transformation (the ClassNode roundtrip and
        // Comparison/String/Container transformations inflate the constant pool)
        // but keep the full adapter chain for target instrumentation.
        logger.warn("Class {} is too large ({}); retrying without testability transformation",
                classNameWithDots, originalError.getMessage());
        try {
            return transformBytesSkipTestability(classLoader, className, classNameWithDots, reader, readFlags);
        } catch (RuntimeException | Error t) {
            if (!isClassOrMethodTooLarge(t)) {
                throw t;
            }
        }

        // Retry 2: use only the non-target adapter chain (NonTargetClassAdapter + mocking),
        // which adds far fewer constant pool entries than the target chain.
        logger.warn("Class {} is still too large; retrying with minimal (non-target) instrumentation",
                classNameWithDots);
        try {
            return transformBytesMinimal(classLoader, className, classNameWithDots, reader, readFlags);
        } catch (RuntimeException | Error t2) {
            if (!isClassOrMethodTooLarge(t2)) {
                throw t2;
            }
        }

        // Last resort: bare passthrough with no method-level changes.
        logger.warn("Class {} is still too large; retrying with bare passthrough", classNameWithDots);
        return transformBytesBarePassthrough(className, reader);
    }

    /**
     * Transform bytes with the full adapter chain but skip the testability transformation entirely.
     * This avoids the ClassNode roundtrip and the Comparison/String/Container transformations
     * that can inflate the constant pool beyond the JVM limit for very large classes.
     */
    private byte[] transformBytesSkipTestability(ClassLoader classLoader, String className,
                                                  String classNameWithDots, ClassReader reader, int readFlags) {
        ClassWriter writer = new ComputeClassWriter(ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = writer;
        if (logger.isDebugEnabled()) {
            cv = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        cv = createAdapterChain(cv, className, classNameWithDots, classLoader, false);

        reader.accept(cv, readFlags);
        return writer.toByteArray();
    }

    /**
     * Transform bytes with only the non-target adapter chain (NonTargetClassAdapter + mocking).
     * This is the most minimal instrumentation that still allows the class to be loaded in
     * the EvoSuite runtime (InstrumentedClass interface, static reset, method call replacement).
     */
    private byte[] transformBytesMinimal(ClassLoader classLoader, String className,
                                          String classNameWithDots, ClassReader reader, int readFlags) {
        ClassWriter writer = new ComputeClassWriter(ClassWriter.COMPUTE_FRAMES);

        ClassVisitor cv = writer;
        if (logger.isDebugEnabled()) {
            cv = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        // Use the non-target chain regardless of DependencyAnalysis
        cv = addNonTargetTransformationAdapters(cv, className, classNameWithDots, classLoader);

        // Collect constant values for the value pool
        cv = new PrimitiveClassAdapter(cv, className);

        if (config.resetStaticFields()) {
            cv = handleStaticReset(className, cv);
        }

        if (TestSuiteWriterUtils.needToUseAgent()) {
            cv = addMockingAdapters(cv, className);
        }

        reader.accept(cv, readFlags);
        return writer.toByteArray();
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
            cv = new AccessibleClassAdapter(cv, className);
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
            cv = new AccessibleClassAdapter(cv, className);
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
     * @return a {@link org.objectweb.asm.ClassVisitor} object.
     */
    private ClassVisitor handleStaticReset(String className, ClassVisitor cv) {
        // Create a __STATIC_RESET() cloning the original <clinit> method or
        // create one by default
        final CreateClassResetClassAdapter resetClassAdapter;
        if (config.resetStaticFinalFields()) {
            resetClassAdapter = new CreateClassResetClassAdapter(cv, className, true);
        } else {
            resetClassAdapter = new CreateClassResetClassAdapter(cv, className, false);
        }
        cv = resetClassAdapter;

        // Adds a callback before leaving the <clinit> method
        EndOfClassInitializerVisitor exitClassInitAdapter = new EndOfClassInitializerVisitor(cv, className);
        cv = exitClassInitAdapter;
        return cv;
    }

}
