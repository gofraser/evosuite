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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Shared scratch-directory lifecycle for the handful of tests in this project that ground a
 * behavioural verdict in an actual {@code javac} compile of a throwaway probe class, rather than
 * this test's own reasoning about the JLS (see {@link GenericAssignabilityMatrixTest},
 * {@link ClassLiteralVarianceTest} and {@code ClassLiteralRawPathCompileTest}). Those tests used
 * to each inline the same "write a temp source file, run the system compiler against it,
 * check the exit code" boilerplate, and -- since none of them deleted the directory -- each
 * compile left its scratch directory behind; {@code GenericAssignabilityMatrixTest} alone runs
 * this twice per case across 163 cases, so that is hundreds of orphaned directories per run.
 *
 * <p>This class owns that directory for the caller: it creates a fresh one per call, uses it for
 * both the probe source and the compiled output (there is nothing else to clean up), and removes
 * it again in a {@code finally} block before returning -- compile success and compile failure are
 * both ordinary, expected outcomes here, not exceptional ones that would excuse skipping cleanup.
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

    /**
     * Compiles {@code source} (which must declare a single top-level class named
     * {@code className}) against the current test classpath, in a private temporary directory
     * created under {@code scratchPrefix} and deleted again before this method returns.
     *
     * @param scratchPrefix a {@link Files#createTempDirectory} prefix identifying the caller, so
     *                       that any directory which does somehow survive (eg the JVM being
     *                       killed mid-test) is still easy to trace back to its source
     * @param className      the probe class's name, matching what {@code source} declares
     * @param source         the complete, self-contained Java source of the probe class
     * @return whether the compile succeeded, and its stderr output
     */
    public static Result compile(String scratchPrefix, String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("a system Java compiler is required to run this test");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory(scratchPrefix);
            Path sourceFile = tempDir.resolve(className + ".java");
            Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            String classpath = System.getProperty("java.class.path");
            int result = compiler.run(null, null, new PrintStream(err, true, StandardCharsets.UTF_8),
                    "-classpath", classpath,
                    "-d", tempDir.toString(),
                    sourceFile.toString());

            return new Result(result == 0, err.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup: a leftover file here is orphaned scratch noise,
                    // not a reason to fail the test that actually exercised the compiler.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; see above.
        }
    }
}
