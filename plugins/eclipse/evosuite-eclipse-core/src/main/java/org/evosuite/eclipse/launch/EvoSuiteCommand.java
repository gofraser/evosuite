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
package org.evosuite.eclipse.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The command line that generates tests for one class in a separate JVM.
 *
 * <p>EvoSuite runs out of process: its master keeps global state in static fields, installs
 * instrumentation, and spawns its own client JVM, none of which belongs inside the IDE. The
 * project directory is the base directory, as on the command line, so that an
 * {@code evosuite-files/evosuite.properties} in the project still applies; reports go to the
 * plug-in's state area instead of cluttering the project.
 */
public final class EvoSuiteCommand {

    private final String javaExecutable;
    private final String evosuiteJar;
    private final String targetClass;
    private final List<String> projectClasspath;
    private final File projectDir;
    private final File testDir;
    private final File reportDir;
    private final GenerationOptions options;

    public EvoSuiteCommand(String javaExecutable, String evosuiteJar, String targetClass,
                           List<String> projectClasspath, File projectDir, File testDir,
                           File reportDir, GenerationOptions options) {
        this.javaExecutable = javaExecutable;
        this.evosuiteJar = evosuiteJar;
        this.targetClass = targetClass;
        this.projectClasspath = new ArrayList<>(projectClasspath);
        this.projectDir = projectDir;
        this.testDir = testDir;
        this.reportDir = reportDir;
        this.options = options;
    }

    public File getWorkingDirectory() {
        return projectDir;
    }

    public List<String> toArguments() {
        List<String> args = new ArrayList<>();
        args.add(javaExecutable);
        args.add("-jar");
        args.add(evosuiteJar);
        args.add("-class");
        args.add(targetClass);
        args.add("-projectCP");
        args.add(String.join(File.pathSeparator, projectClasspath));
        args.add("-base_dir");
        args.add(projectDir.getAbsolutePath());
        if (!options.criterion.isEmpty()) {
            args.add("-criterion");
            args.add(options.criterion);
        }
        if (options.clientMemory > 0) {
            args.add("-mem");
            args.add(Integer.toString(options.clientMemory));
        }
        args.add("-Dtest_dir=" + testDir.getAbsolutePath());
        args.add("-Dreport_dir=" + reportDir.getAbsolutePath());
        args.add("-Dsearch_budget=" + options.searchBudget);
        args.add("-Dassertions=" + options.assertions);
        args.add("-Dminimize=" + options.minimize);
        args.add("-Dtest_format=" + options.testFormat);
        args.add("-Djunit_suffix=" + options.testSuffix);
        // The progress bar is how the plug-in follows the search; see ProgressParser.
        args.add("-Dshow_progress=true");
        // Last, so that the user's own arguments win over everything above.
        args.addAll(splitArguments(options.extraArguments));
        return args;
    }

    /**
     * Command lines longer than this go through an argument file. Windows caps a whole command
     * line at 32767 characters, and a Maven project's classpath alone can exceed that.
     */
    public static final int MAX_INLINE_LENGTH = 8000;

    /**
     * {@code arguments} as they can be passed to {@link ProcessBuilder}: unchanged if short
     * enough, otherwise the java executable followed by {@code @argumentFile}, which then holds
     * the rest. The java launcher reads argument files since Java 9.
     */
    public static List<String> fitCommandLine(List<String> arguments, File argumentFile, int maxLength)
            throws IOException {
        int length = arguments.stream().mapToInt(a -> a.length() + 3).sum();
        if (length <= maxLength) {
            return arguments;
        }
        List<String> lines = arguments.subList(1, arguments.size()).stream()
                .map(EvoSuiteCommand::quoteForArgumentFile)
                .collect(Collectors.toList());
        Files.write(argumentFile.toPath(), lines, nativeCharset());
        return List.of(arguments.get(0), "@" + argumentFile.getAbsolutePath());
    }

    /** In an argument file a backslash escapes the next character inside quotes. */
    static String quoteForArgumentFile(String argument) {
        return '"' + argument.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** The launcher reads argument files in the platform's native encoding. */
    static Charset nativeCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        try {
            return nativeEncoding == null ? Charset.defaultCharset() : Charset.forName(nativeEncoding);
        } catch (IllegalArgumentException e) {
            return Charset.defaultCharset();
        }
    }

    /** The file EvoSuite writes the test suite for {@code targetClass} to. */
    public static File suiteFile(File testDir, String targetClass, String suffix) {
        return new File(testDir, targetClass.replace('.', File.separatorChar) + suffix + ".java");
    }

    /**
     * Splits a string into arguments at unquoted whitespace. Single and double quotes group,
     * and are removed. Outside single quotes a backslash escapes a following quote, backslash or
     * whitespace character and is kept literally otherwise, so Windows paths need no doubling.
     */
    public static List<String> splitArguments(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && escapes(line, i)) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (c == '\\' && escapes(line, i)) {
                current.append(line.charAt(++i));
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    result.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            result.add(current.toString());
        }
        return result;
    }

    private static boolean escapes(String line, int backslash) {
        if (backslash + 1 >= line.length()) {
            return false;
        }
        char next = line.charAt(backslash + 1);
        return next == '"' || next == '\'' || next == '\\' || Character.isWhitespace(next);
    }
}
