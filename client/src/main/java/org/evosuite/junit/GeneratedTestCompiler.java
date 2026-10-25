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
package org.evosuite.junit;

import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.runtime.util.JarPathing;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Compiles generated test sources with the system Java compiler: the classpath javac compiles
 * them against, one javac invocation, and the classification of what javac reports when it
 * fails without judging the source (a crash, running out of heap, a broken classpath).
 * What to do with an outcome is up to the caller.
 */
public final class GeneratedTestCompiler {

    /** What javac prints when it catches an error of its own and asks for a bug report. */
    public static final String JAVAC_INTERNAL_ERROR_MARKER = "An exception has occurred in the compiler";
    public static final String JAVAC_STACK_OVERFLOW_MARKER = "java.lang.StackOverflowError";
    public static final String JAVAC_OUT_OF_MEMORY_MARKER = "java.lang.OutOfMemoryError";

    private static String cachedCompilerClasspath = null;
    private static String cachedCompilerClasspathEvoSuiteInput = null;
    private static String cachedCompilerClasspathTargetInput = null;
    static final int MAX_STORED_COMPILER_DIAGNOSTICS = 100;

    private GeneratedTestCompiler() {
    }

    /**
     * The classpath javac compiles the generated tests against: the project classpath followed by
     * EvoSuite's own, with pathing jars expanded and malformed jars sanitized. It is computed
     * again only when the classpaths it is built from change.
     */
    public static synchronized String compilerClasspathForGeneratedTests() {
        String evosuiteCP = ClassPathHandler.getInstance().getEvoSuiteClassPath();
        String targetProjectCP = ClassPathHandler.getInstance().getTargetProjectClasspath();
        if (cachedCompilerClasspath != null
                && Objects.equals(evosuiteCP, cachedCompilerClasspathEvoSuiteInput)
                && Objects.equals(targetProjectCP, cachedCompilerClasspathTargetInput)) {
            return cachedCompilerClasspath;
        }
        String expandedEvoSuiteCP = evosuiteCP;
        if (JarPathing.containsAPathingJar(expandedEvoSuiteCP)) {
            expandedEvoSuiteCP = JarPathing.expandPathingJars(expandedEvoSuiteCP);
        }
        String expandedTargetProjectCP = targetProjectCP;
        if (JarPathing.containsAPathingJar(expandedTargetProjectCP)) {
            expandedTargetProjectCP = JarPathing.expandPathingJars(expandedTargetProjectCP);
        }
        String classpath = CompilerClasspath.sanitize(
                expandedTargetProjectCP + File.pathSeparator + expandedEvoSuiteCP,
                CompilerClasspath.Policy.GENERATED_TESTS);
        cachedCompilerClasspathEvoSuiteInput = evosuiteCP;
        cachedCompilerClasspathTargetInput = targetProjectCP;
        cachedCompilerClasspath = classpath;
        return classpath;
    }

    static CompilerAttemptResult runCompilerAttempt(JavaCompiler compiler,
                                                    List<File> generated,
                                                    String classpath,
                                                    Locale locale,
                                                    Charset charset) throws IOException {
        return runCompilerAttempt(compiler, generated, classpath, locale, charset,
                new BoundedDiagnosticListener(MAX_STORED_COMPILER_DIAGNOSTICS));
    }

    /**
     * Runs javac once on {@code sources} without a diagnostic listener, so javac prints its
     * diagnostics to the compiler output exactly as the command-line compiler does, with the
     * source line and a caret. The sources are read as UTF-8, as the JUnit check reads them and as
     * the repair loop writes them. Infrastructure
     * failures are returned rather than thrown, as for the JUnit check.
     */
    public static CompilerAttemptResult compileWithPrintedDiagnostics(JavaCompiler compiler,
                                                                      List<File> sources,
                                                                      String classpath) throws IOException {
        return runCompilerAttempt(compiler, sources, classpath, null, StandardCharsets.UTF_8, null);
    }

    private static CompilerAttemptResult runCompilerAttempt(JavaCompiler compiler,
                                                            List<File> generated,
                                                            String classpath,
                                                            Locale locale,
                                                            Charset charset,
                                                            BoundedDiagnosticListener diagnostics)
            throws IOException {
        // A null Writer here would make javac fall back to System.err for the compiler's own
        // output stream (e.g. usage/internal errors not routed through the DiagnosticListener
        // above). A leaked class-under-test thread that holds the System.err monitor then blocks
        // javac forever in Log.flush -> PrintStream.flush. Captured to a private buffer instead,
        // so compilation can never deadlock on a shared stream; its contents are logged below
        // alongside the diagnostics on failure so nothing is lost.
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, locale, charset)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(generated);
            List<String> optionList = new ArrayList<>(Arrays.asList(
                    "-classpath", classpath,
                    "-proc:none"));
            CompilationTask task = compiler.getTask(compilerOutput, fileManager, diagnostics,
                    optionList, null, compilationUnits);
            boolean compiled = task.call();
            return CompilerAttemptResult.success(compiled, diagnostics, compilerOutput.toString());
        } catch (RuntimeException e) {
            if (isJavacInfrastructureFailure(e)) {
                return CompilerAttemptResult.infrastructureFailure(e, diagnostics, compilerOutput.toString());
            }
            throw e;
        } catch (Error e) {
            if (isJavacInfrastructureFailure(e)) {
                return CompilerAttemptResult.infrastructureFailure(e, diagnostics, compilerOutput.toString());
            }
            throw e;
        }
    }

    /**
     * Whether javac reported running out of heap. It catches the error itself and prints it
     * instead of throwing it, e.g. when opening a jar of the classpath fails in
     * {@code ZipFileSystem.initCEN}.
     */
    static boolean reportsOutOfMemory(String compilerOutput) {
        return compilerOutput != null && compilerOutput.contains(JAVAC_OUT_OF_MEMORY_MARKER);
    }

    static boolean isOutOfMemory(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 20) {
            if (current instanceof OutOfMemoryError) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Whether javac reported a crash of its own rather than a verdict on the test. It catches
     * {@code OutOfMemoryError} and {@code StackOverflowError} internally and asks the user to file
     * a bug, leaving {@code compiled == false} with no useful diagnostic.
     *
     * @param compilerOutput what javac wrote to its own output stream
     * @return true if the compiler crashed
     */
    static boolean crashedInsteadOfRejecting(String compilerOutput) {
        if (compilerOutput == null || compilerOutput.isEmpty()) {
            return false;
        }
        // java.lang.OutOfMemoryError is not listed: the caller handles reportsOutOfMemory first.
        return compilerOutput.contains(JAVAC_INTERNAL_ERROR_MARKER)
                || compilerOutput.contains(JAVAC_STACK_OVERFLOW_MARKER);
    }

    static boolean isJavacInfrastructureFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof NoSuchElementException) {
                return true;
            }

            for (StackTraceElement element : current.getStackTrace()) {
                if (element == null) {
                    continue;
                }
                String className = element.getClassName();
                if (className == null) {
                    continue;
                }
                if (className.startsWith("java.util.ServiceLoader")
                        || className.startsWith("java.nio.file.spi.FileSystemProvider")
                        || className.startsWith("com.sun.tools.javac.file.FSInfo")
                        || className.startsWith("jdk.compiler/com.sun.tools.javac.file.FSInfo")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /** What one javac run reported. */
    public static final class CompilerAttemptResult {
        private final boolean compiled;
        private final BoundedDiagnosticListener diagnostics;
        private final Throwable infrastructureFailure;
        private final String compilerOutput;

        private CompilerAttemptResult(boolean compiled,
                                      BoundedDiagnosticListener diagnostics,
                                      Throwable infrastructureFailure,
                                      String compilerOutput) {
            this.compiled = compiled;
            this.diagnostics = diagnostics;
            this.infrastructureFailure = infrastructureFailure;
            this.compilerOutput = compilerOutput;
        }

        private static CompilerAttemptResult success(boolean compiled, BoundedDiagnosticListener diagnostics,
                                                      String compilerOutput) {
            return new CompilerAttemptResult(compiled, diagnostics, null, compilerOutput);
        }

        private static CompilerAttemptResult infrastructureFailure(Throwable failure,
                                                                   BoundedDiagnosticListener diagnostics,
                                                                   String compilerOutput) {
            return new CompilerAttemptResult(false, diagnostics, failure, compilerOutput);
        }

        public boolean isCompiled() {
            return compiled;
        }

        BoundedDiagnosticListener getDiagnostics() {
            return diagnostics;
        }

        public Throwable getInfrastructureFailure() {
            return infrastructureFailure;
        }

        public boolean hasInfrastructureFailure() {
            return infrastructureFailure != null;
        }

        public String getCompilerOutput() {
            return compilerOutput;
        }
    }

    static final class BoundedDiagnosticListener implements DiagnosticListener<JavaFileObject> {
        private static final String ERROR_WHILE_WRITING_PREFIX = "error while writing";
        private final int maxDiagnostics;
        // Errors (and OTHER, which can carry a fatal condition javac has no dedicated kind for)
        // are kept separate from everything else (warnings, mandatory warnings, notes) so that a
        // flood of warnings can never evict a real error from the bounded list, and so a caller
        // can log the two at different levels instead of dumping warnings next to errors as if
        // they had also kept the file from compiling.
        private final List<Diagnostic<? extends JavaFileObject>> errorDiagnostics = new ArrayList<>();
        private final List<Diagnostic<? extends JavaFileObject>> otherDiagnostics = new ArrayList<>();
        private boolean errorsTruncated = false;
        private boolean othersTruncated = false;
        private boolean sawErrorWhileWriting = false;

        private BoundedDiagnosticListener(int maxDiagnostics) {
            this.maxDiagnostics = Math.max(0, maxDiagnostics);
        }

        private static boolean isErrorLike(Diagnostic.Kind kind) {
            return kind == Diagnostic.Kind.ERROR || kind == Diagnostic.Kind.OTHER;
        }

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            String message = diagnostic.getMessage(null);
            if (message != null && message.startsWith(ERROR_WHILE_WRITING_PREFIX)) {
                sawErrorWhileWriting = true;
            }
            if (isErrorLike(diagnostic.getKind())) {
                if (errorDiagnostics.size() < maxDiagnostics) {
                    errorDiagnostics.add(diagnostic);
                } else {
                    errorsTruncated = true;
                }
            } else {
                if (otherDiagnostics.size() < maxDiagnostics) {
                    otherDiagnostics.add(diagnostic);
                } else {
                    othersTruncated = true;
                }
            }
        }

        List<Diagnostic<? extends JavaFileObject>> getErrorDiagnostics() {
            return errorDiagnostics;
        }

        List<Diagnostic<? extends JavaFileObject>> getOtherDiagnostics() {
            return otherDiagnostics;
        }

        boolean wasErrorsTruncated() {
            return errorsTruncated;
        }

        boolean wasOthersTruncated() {
            return othersTruncated;
        }

        boolean sawErrorWhileWriting() {
            return sawErrorWhileWriting;
        }
    }
}
