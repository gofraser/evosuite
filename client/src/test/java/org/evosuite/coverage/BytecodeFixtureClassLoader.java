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
package org.evosuite.coverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class BytecodeFixtureClassLoader extends ClassLoader {

    private static final Object COMPILATION_LOCK = new Object();
    private static final Map<String, Path> COMPILED_FIXTURES = new HashMap<>();
    private static final Path COMPILATION_ROOT = createCompilationRoot();

    private final String javaVersion;
    private final Path fixtureDirectory;

    public BytecodeFixtureClassLoader(String javaVersion) {
        // e.g., "8", "11", "17", "24"
        this.javaVersion = javaVersion;
        try {
            this.fixtureDirectory = compileFixtures(javaVersion);
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare Java " + javaVersion
                    + " bytecode fixtures", e);
        }
    }

    /**
     * Returns whether the current JDK can compile the requested fixture version.
     *
     * <p>The tests intentionally cover bytecode newer than the running JVM. Such bytecode can be
     * inspected by ASM, but it cannot be produced when the current JDK predates the requested
     * release. Those cases are skipped by the tests; supported releases are compiled on demand.
     */
    public static boolean isSupported(String javaVersion) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return false;
        }
        try {
            return Integer.parseInt(javaVersion) <= currentJavaFeatureVersion();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // We only intercept loading of our test fixtures
        if (name.startsWith("bytecode_tests.")) {
            try {
                byte[] classBytes = getClassBytes(name);
                return defineClass(name, classBytes, 0, classBytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException("Could not read Java " + javaVersion
                        + " fixture for " + name, e);
            }
        }
        return super.findClass(name);
    }
    
    public byte[] getClassBytes(String name) throws IOException {
        Path classFile = fixtureDirectory.resolve(name.replace('.', '/') + ".class");
        if (!Files.isRegularFile(classFile)) {
            throw new IOException("Could not find compiled Java " + javaVersion
                    + " fixture: " + classFile);
        }
        return Files.readAllBytes(classFile);
    }
    
    public Class<?> defineClassFromBytes(String name, byte[] b) {
        return defineClass(name, b, 0, b.length);
    }

    private static Path compileFixtures(String javaVersion) throws IOException {
        synchronized (COMPILATION_LOCK) {
            Path existing = COMPILED_FIXTURES.get(javaVersion);
            if (existing != null) {
                return existing;
            }

            if (!isSupported(javaVersion)) {
                throw new IOException("The current JDK cannot compile Java " + javaVersion
                        + " fixtures");
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Path sourceDirectory = Files.createTempDirectory(COMPILATION_ROOT, "sources-");
            Path outputDirectory = Files.createTempDirectory(COMPILATION_ROOT,
                    "classes-java" + javaVersion + "-");
            List<String> compilerArguments = new ArrayList<>();
            if (currentJavaFeatureVersion() >= 9) {
                compilerArguments.add("--release");
                compilerArguments.add(javaVersion);
            } else {
                compilerArguments.add("-source");
                compilerArguments.add("1.8");
                compilerArguments.add("-target");
                compilerArguments.add("1.8");
            }
            compilerArguments.add("-d");
            compilerArguments.add(outputDirectory.toString());

            for (String sourceName : sourceNames(javaVersion)) {
                Path sourceFile = sourceDirectory.resolve(sourceName);
                String resourceName = "/bytecode_tests/sources/" + sourceName;
                try (InputStream source = BytecodeFixtureClassLoader.class
                        .getResourceAsStream(resourceName)) {
                    if (source == null) {
                        throw new IOException("Could not find fixture source: " + resourceName);
                    }
                    Files.copy(source, sourceFile);
                }
                compilerArguments.add(sourceFile.toString());
            }

            ByteArrayOutputStream compilerOutput = new ByteArrayOutputStream();
            int exitCode = compiler.run(null, compilerOutput, compilerOutput,
                    compilerArguments.toArray(new String[0]));
            if (exitCode != 0) {
                throw new IOException("javac failed for Java " + javaVersion + ": "
                        + compilerOutput.toString());
            }

            COMPILED_FIXTURES.put(javaVersion, outputDirectory);
            return outputDirectory;
        }
    }

    private static List<String> sourceNames(String javaVersion) throws IOException {
        int version;
        try {
            version = Integer.parseInt(javaVersion);
        } catch (NumberFormatException e) {
            throw new IOException("Unsupported Java fixture version: " + javaVersion, e);
        }

        List<String> sourceNames = new ArrayList<>();
        sourceNames.add("CompatibilityFixture.java");
        if (version >= 17) {
            sourceNames.add("SwitchExpressionFixture.java");
        }
        if (version >= 24) {
            sourceNames.add("RecordFixture.java");
        }
        return sourceNames;
    }

    private static int currentJavaFeatureVersion() {
        String version = System.getProperty("java.specification.version", "8");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int dot = version.indexOf('.');
        if (dot >= 0) {
            version = version.substring(0, dot);
        }
        return Integer.parseInt(version);
    }

    private static Path createCompilationRoot() {
        try {
            return Files.createTempDirectory("evosuite-bytecode-fixtures-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
