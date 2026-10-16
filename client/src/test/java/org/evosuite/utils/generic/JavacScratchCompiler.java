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
package org.evosuite.utils.generic;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared, in-memory {@code javac} compile for the handful of tests in this project that ground a
 * behavioural verdict in an actual compile of a throwaway probe class, rather than this test's own
 * reasoning about the JLS (see {@link GenericAssignabilityMatrixTest}, {@link ClassLiteralVarianceTest}
 * and {@code ClassLiteralRawPathCompileTest}).
 *
 * <p>No source or class output file is ever created: the probe source is handed to {@code javac}
 * as an in-memory {@link JavaFileObject}, and compiled class output is discarded as it is produced
 * by a {@link JavaFileObject} whose output stream keeps nothing -- none of this project's
 * javac-grounded tests load the compiled classes back, they only inspect the exit code and stderr.
 * The {@link JavaCompiler} and the {@link StandardJavaFileManager} that resolves {@code
 * java.class.path} are each built exactly once, for the lifetime of the JVM, rather than once per
 * call; the resolver itself still reads the classpath's actual jars/directories from disk, since
 * that is the compile's real input. The file manager is shared across every caller in the JVM,
 * which may span several independent JUnit test classes with no single shared {@code @AfterAll},
 * so it is closed by a shutdown hook rather than per call.
 */
public final class JavacScratchCompiler {

    private JavacScratchCompiler() {
    }

    /** The outcome of one scratch compile: whether it succeeded, and anything javac wrote to stderr. */
    public static final class Result {
        public final boolean success;
        public final String stderr;

        private Result(boolean success, String stderr) {
            this.success = success;
            this.stderr = stderr;
        }
    }

    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    /**
     * Wraps the compiler's own {@link StandardJavaFileManager} so that class output is discarded
     * instead of written to disk. The underlying {@code StandardJavaFileManager} is what actually
     * resolves {@code java.class.path} (set once, below, rather than re-parsed per call), and is
     * closed by a shutdown hook rather than per call, since this is shared across every caller in
     * the JVM, which may span several independent JUnit test classes with no single shared
     * {@code @AfterAll}.
     */
    private static final DiscardingOutputFileManager FILE_MANAGER = buildFileManager();

    private static DiscardingOutputFileManager buildFileManager() {
        if (COMPILER == null) {
            return null;
        }
        StandardJavaFileManager standard = COMPILER.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try {
            String classpath = System.getProperty("java.class.path");
            List<File> entries = new ArrayList<>();
            for (String entry : classpath.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    entries.add(new File(entry));
                }
            }
            standard.setLocation(StandardLocation.CLASS_PATH, entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        DiscardingOutputFileManager wrapped = new DiscardingOutputFileManager(standard);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                wrapped.close();
            } catch (IOException ignored) {
                // Best-effort cleanup on JVM exit: nothing downstream can act on this any more.
            }
        }, "JavacScratchCompiler-shutdown"));
        return wrapped;
    }

    /**
     * Compiles {@code source} (which must declare a single top-level class named
     * {@code className}) against the current test classpath, entirely in memory.
     *
     * @param scratchPrefix a label identifying the caller, folded into the in-memory source's URI
     *                       purely so that any javac diagnostic naming it is easy to trace back to
     *                       its source (no directory is created under it any more)
     * @param className      the probe class's name, matching what {@code source} declares
     * @param source         the complete, self-contained Java source of the probe class
     * @return whether the compile succeeded, and its stderr output
     */
    public static Result compile(String scratchPrefix, String className, String source) {
        if (COMPILER == null) {
            throw new IllegalStateException("a system Java compiler is required to run this test");
        }

        JavaFileObject sourceFile = new InMemorySource(scratchPrefix, className, source);
        StringWriter extraOutput = new StringWriter();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        JavaCompiler.CompilationTask task = COMPILER.getTask(
                extraOutput,
                FILE_MANAGER,
                diagnostics,
                null,
                null,
                Collections.singletonList(sourceFile));

        boolean success = task.call();

        StringBuilder err = new StringBuilder(extraOutput.toString());
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            err.append(diagnostic).append('\n');
        }
        return new Result(success, err.toString());
    }

    /** An in-memory probe source, so nothing is ever written to disk. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String source;

        InMemorySource(String scratchPrefix, String className, String source) {
            super(URI.create("string:///" + scratchPrefix + "/" + className + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /**
     * Forwards everything except class output (classpath/platform resolution, in particular) to
     * the real {@link StandardJavaFileManager}, but hands back a {@link DiscardedOutput} for every
     * compiled class instead of a real file -- so output is never materialised on disk and there is
     * no per-call scratch directory to clear or delete.
     */
    private static final class DiscardingOutputFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        DiscardingOutputFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                FileObject sibling) {
            return new DiscardedOutput(className, kind);
        }
    }

    /** A compiled-class sink that keeps nothing: see {@link DiscardingOutputFileManager}. */
    private static final class DiscardedOutput extends SimpleJavaFileObject {
        DiscardedOutput(String className, Kind kind) {
            super(URI.create("string:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    // Discarded: nothing downstream reads compiled output back.
                }
            };
        }
    }
}
