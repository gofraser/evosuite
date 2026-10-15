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

    /** The environment variable EvoSuite reads the LLM API key from when none is configured. */
    public static final String API_KEY_VARIABLE = "EVOSUITE_LLM_API_KEY";

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
        // EvoSuite's default is evosuite-report/llm-traces in the project, where the traces, which
        // contain prompts built from the project's code, could end up committed.
        args.add("-Dllm_trace_dir=" + new File(reportDir, "llm-traces").getAbsolutePath());
        addLlmArguments(args);
        // Last, so that the user's own arguments win over everything above; among them, the pom's
        // win over the IDE's, and its extraArgs over its evosuiteProperties, as with Maven.
        args.addAll(splitArguments(options.extraArguments));
        options.pomProperties.forEach((name, value) -> args.add("-D" + name + "=" + value));
        args.addAll(splitArguments(options.pomExtraArguments));
        return args;
    }

    /**
     * The LLM settings, if a provider is set. The API key is deliberately not among them: the
     * command line is visible to other users of the machine and is echoed to the console, so the
     * key travels in the {@code EVOSUITE_LLM_API_KEY} environment variable instead.
     */
    private void addLlmArguments(List<String> args) {
        if (!options.usesLlm()) {
            return;
        }
        args.add("-Dllm_provider=" + options.llmProvider);
        if (!options.llmModel.isEmpty()) {
            args.add("-Dllm_model=" + options.llmModel);
        }
        String baseUrl = options.llmBaseUrl;
        if (baseUrl.isEmpty() && GenerationOptions.LLM_OLLAMA.equals(options.llmProvider)) {
            baseUrl = GenerationOptions.OLLAMA_DEFAULT_URL;
        }
        if (!baseUrl.isEmpty()) {
            args.add("-Dllm_base_url=" + baseUrl);
        }
        if (GenerationOptions.APPROACH_LLM_SINGLE_PROMPT.equals(options.llmApproach)) {
            args.add("-Dstrategy=LLMSTRATEGY");
            args.add("-Dllm_strategy_mode=SINGLE_PROMPT");
        } else if (GenerationOptions.APPROACH_LLM_ITERATIVE.equals(options.llmApproach)) {
            args.add("-Dstrategy=LLMSTRATEGY");
            args.add("-Dllm_strategy_mode=ITERATIVE_BUDGETED");
        }
        if (!"OFF".equals(options.llmSeeding)) {
            args.add("-Dllm_seeding_profile=" + options.llmSeeding);
        }
        if (options.llmOnStagnation) {
            args.add("-Dllm_on_stagnation=true");
        }
        if (options.llmOperators) {
            args.add("-Dllm_operator_enabled=true");
        }
        if (options.llmPostprocessing) {
            args.add("-Dllm_postprocessing_enabled=true");
        }
        if (options.llmMaxCalls > 0) {
            args.add("-Dllm_max_calls=" + options.llmMaxCalls);
        }
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

    /**
     * {@code arguments} joined for display, with any API key masked: keys belong in the
     * environment, but a user may still have put one into the additional arguments or the pom.
     */
    public static String forDisplay(List<String> arguments) {
        return arguments.stream()
                .map(a -> a.startsWith("-Dllm_api_key=") ? "-Dllm_api_key=****" : a)
                .collect(Collectors.joining(" "));
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
