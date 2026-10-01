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
package org.evosuite.runtime;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code runtime/src/main/java} has a Java 8 floor that the build does not check.
 *
 * <p>The runtime jar is what executes generated tests on a user's machine, and EvoSuite supports
 * running them on Java 8. This module therefore compiles its main source root at
 * {@code -source 8 -target 8} (see {@code runtime/pom.xml}, which overrides {@code default-compile}
 * because the module also carries {@code src/main/java11}, {@code java17} and {@code java25} source
 * sets that must be compiled at higher levels). But {@code -source}/{@code -target} only constrain
 * the <em>language level</em>: they happily resolve any API the compiling JDK offers. So a call to a
 * method added in Java 9 or later compiles cleanly on JDK 17, ships, and then fails on a user's
 * Java 8 JVM.
 *
 * <p>Two severities, both observed in practice. A post-8 <em>type</em> in a field, parameter or
 * local breaks the whole class at load time ({@code NoClassDefFoundError}). A post-8 <em>method
 * call</em> throws only when that line executes ({@code NoSuchMethodError}), which is worse,
 * because it survives every smoke test. Five real instances of this were found and fixed by hand
 * in October 2026 -- a {@code PrintStream(OutputStream, boolean, Charset)} constructor that is
 * Java 10+, a {@code PrintWriter} equivalent, a {@code SocketImpl} constructor that is package
 * private before JDK 12, {@code Component.setMixingCutoutShape}, and a whole interface's worth of
 * {@code sun.awt.ComponentFactory} methods that are abstract on Java 8 and only gained defaults in
 * JDK 9. Every one of them was found by reading code, not by the build.
 *
 * <p>{@code javac --release 8} is the fix: unlike {@code -source}/{@code -target} it links against
 * Java 8's actual API signatures, so it rejects a post-8 API outright. This runs as a test rather
 * than as a second {@code maven-compiler-plugin} execution on purpose. That was tried first and
 * reverted: {@code maven-compiler-plugin}'s {@code outputDirectory} is bound to the read-write
 * parameter {@code ${project.build.outputDirectory}}, so configuring it on a second execution
 * redirects the module's output for the rest of the reactor, and downstream modules then compiled
 * against the release-8 scratch directory. As a test there is no build state to corrupt, and it
 * still runs on every {@code mvn test} -- which an opt-in profile would not.
 *
 * @see #theCheckActuallyRejectsAPostJava8Api() the canary, which proves this test can fail
 */
public class Java8ApiFloorTest {

    /**
     * Paths (relative to the source root) that {@code --release} cannot check, with the reason.
     *
     * <p>{@code --release} refuses to resolve JDK-internal packages at all, on principle, even
     * though real {@code javac 8} compiles against them happily and this class has to implement a
     * {@code sun.awt} interface to do its job. Excluding it costs nothing here, because
     * {@link PeerCreationGuardToolkitTest} and a manual Corretto 8 run cover it instead.
     *
     * <p>{@link #everyExcludedPathStillExists()} fails if an entry here goes stale, so a rename
     * cannot silently widen the hole.
     */
    private static final Set<String> EXCLUDED_FROM_RELEASE_CHECK = Collections.unmodifiableSet(
            new LinkedHashSet<>(Collections.singletonList(
                    // implements sun.awt.ComponentFactory; --release rejects sun.* packages
                    "org/evosuite/runtime/PeerCreationGuardToolkit.java")));

    @Test
    public void mainSourcesCompileAgainstTheJava8Api() throws IOException {
        Path sourceRoot = mainSourceRoot();
        List<File> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isExcluded(sourceRoot, p))
                    .forEach(p -> sources.add(p.toFile()));
        }
        assertTrue(sources.size() > 100,
                "Expected the runtime main source root to hold a few hundred files, found "
                        + sources.size() + " under " + sourceRoot
                        + " -- the source root lookup is probably wrong");

        List<Diagnostic<? extends JavaFileObject>> errors = compileAtRelease8(sources);
        if (!errors.isEmpty()) {
            fail(describe(errors, sourceRoot));
        }
    }

    /**
     * The canary. A test that silently stops checking anything is worse than no test, and this one
     * would pass trivially if the {@code --release 8} argument were dropped, misspelled, or ignored
     * by a future compiler. So: compile a snippet that uses {@code List.of}, added in Java 9, and
     * require that it is rejected at release 8 and accepted at release 11. Both halves matter --
     * the first proves the check bites, the second proves the snippet is otherwise valid and the
     * rejection is really about the API level.
     */
    @Test
    public void theCheckActuallyRejectsAPostJava8Api() {
        String source = "package probe;\n"
                + "import java.util.List;\n"
                + "public class Probe {\n"
                + "    public static List<String> get() { return List.of(\"a\"); }\n"
                + "}\n";

        List<Diagnostic<? extends JavaFileObject>> atEight =
                compileSnippet(source, "8");
        assertFalse(atEight.isEmpty(),
                "java.util.List.of was added in Java 9, so --release 8 must reject it. "
                        + "It compiled cleanly instead, which means this test is no longer "
                        + "checking anything.");
        assertTrue(atEight.stream().anyMatch(d -> String.valueOf(
                        d.getMessage(Locale.ENGLISH)).contains("of")),
                "Expected the rejection to name the unavailable method, got: "
                        + atEight.stream().map(d -> d.getMessage(Locale.ENGLISH))
                        .collect(Collectors.joining("; ")));

        List<Diagnostic<? extends JavaFileObject>> atEleven =
                compileSnippet(source, "11");
        assertTrue(atEleven.isEmpty(),
                "The probe is valid Java 11 and must compile at --release 11; if it does not, "
                        + "the harness is broken rather than the API level: "
                        + atEleven.stream().map(d -> d.getMessage(Locale.ENGLISH))
                        .collect(Collectors.joining("; ")));
    }

    @Test
    public void everyExcludedPathStillExists() {
        Path sourceRoot = mainSourceRoot();
        for (String excluded : EXCLUDED_FROM_RELEASE_CHECK) {
            assertTrue(Files.isRegularFile(sourceRoot.resolve(excluded)),
                    "Excluded path no longer exists: " + excluded + ". It was skipped because "
                            + "--release cannot resolve JDK-internal packages; if the file moved, "
                            + "update EXCLUDED_FROM_RELEASE_CHECK, and if it is gone, drop the "
                            + "entry -- leaving it stale silently excludes nothing while looking "
                            + "like it still guards something.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isExcluded(Path sourceRoot, Path file) {
        String relative = sourceRoot.relativize(file).toString().replace(File.separatorChar, '/');
        return EXCLUDED_FROM_RELEASE_CHECK.contains(relative);
    }

    /**
     * Surefire runs with the module directory as the working directory, and Maven also sets
     * {@code basedir}. Both are checked, and a miss is a failure rather than a skip: a skipped
     * guard is indistinguishable from a passing one in the build output.
     */
    private static Path mainSourceRoot() {
        List<Path> candidates = Arrays.asList(
                Paths.get(java.lang.System.getProperty("basedir", ".")).resolve("src/main/java"),
                Paths.get("src/main/java"),
                Paths.get("runtime/src/main/java"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return fail("Could not locate runtime/src/main/java from working directory "
                + Paths.get("").toAbsolutePath() + "; tried " + candidates);
    }

    private static List<Diagnostic<? extends JavaFileObject>> compileAtRelease8(List<File> sources)
            throws IOException {
        JavaCompiler compiler = systemCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path output = Files.createTempDirectory("evosuite-java8-api-check");
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT,
                    Collections.singletonList(output.toFile()));
            compiler.getTask(null, fileManager, diagnostics,
                            Arrays.asList("--release", "8", "-nowarn", "-proc:none",
                                    "-classpath", java.lang.System.getProperty("java.class.path")),
                            null, fileManager.getJavaFileObjectsFromFiles(sources))
                    .call();
        } finally {
            deleteRecursively(output);
        }
        return errorsOf(diagnostics);
    }

    private static List<Diagnostic<? extends JavaFileObject>> compileSnippet(String source,
                                                                             String release) {
        JavaCompiler compiler = systemCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///probe/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        // -d must name a real, writable directory for this probe to succeed at all -- but the
        // class file it produces is throwaway, so it gets its own scratch directory (like
        // compileAtRelease8's) rather than the shared system temp directory, and that directory
        // is removed again below regardless of the compile's outcome.
        Path output;
        try {
            output = Files.createTempDirectory("evosuite-java8-api-floor-canary");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            compiler.getTask(null, null, diagnostics,
                    Arrays.asList("--release", release, "-nowarn", "-proc:none",
                            "-d", output.toString()),
                    null, Collections.singletonList(unit)).call();
            return errorsOf(diagnostics);
        } finally {
            try {
                deleteRecursively(output);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static JavaCompiler systemCompiler() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "No system Java compiler available; this test needs a JDK, not a "
                + "JRE. The build already requires a JDK to compile, so this should not happen.");
        return compiler;
    }

    private static List<Diagnostic<? extends JavaFileObject>> errorsOf(
            DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
    }

    private static String describe(List<Diagnostic<? extends JavaFileObject>> errors,
                                   Path sourceRoot) {
        StringBuilder message = new StringBuilder();
        message.append(errors.size())
                .append(" source(s) in runtime/src/main/java use an API that does not exist on ")
                .append("Java 8. Generated tests run on a user's JVM, which may be Java 8, so ")
                .append("this would compile here and fail there -- at class load time for a ")
                .append("post-8 type, or on first execution for a post-8 call.\n")
                .append("Use a Java 8 equivalent, or call the newer API reflectively and fall ")
                .append("back (see MockHeadlessSwing's invokeComponentSetMixingCutoutShape")
                .append("Reflective for the established pattern).\n\n");
        for (Diagnostic<? extends JavaFileObject> error : errors) {
            JavaFileObject file = error.getSource();
            String name = file == null ? "<unknown>"
                    : Paths.get(file.getName()).toAbsolutePath().toString();
            message.append("  ").append(name).append(':').append(error.getLineNumber())
                    .append("\n      ").append(error.getMessage(Locale.ENGLISH).replace("\n",
                            "\n      "))
                    .append('\n');
        }
        message.append("\nSource root: ").append(sourceRoot.toAbsolutePath());
        return message.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
