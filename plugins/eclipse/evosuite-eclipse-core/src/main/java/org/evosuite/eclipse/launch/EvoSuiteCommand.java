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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /**
     * Criteria whose goals EvoSuite does not credit to the existing tests it runs for
     * {@code -Djunit}: it records the lines, branches and mutants those tests reach, but not the
     * methods they call or the values they pass and return. On a class whose tests the search
     * started from, each such goal that the existing tests cover is still open at the end, and
     * the minimised suite keeps a near-copy of an existing test for it.
     */
    static final Set<String> NOT_CREDITED_TO_EXISTING_TESTS = Set.of("OUTPUT", "INPUT", "METHOD",
            "METHODNOEXCEPTION");

    /** The properties of {@link #outputLocations()}. */
    static final List<String> OUTPUT_PROPERTIES = List.of("test_dir", "junit_suffix", "report_dir", "llm_trace_dir");

    private final String javaExecutable;
    private final String evosuiteJar;
    private final String targetClass;
    private final List<String> projectClasspath;
    private final File projectDir;
    private final File testDir;
    private final File reportDir;
    private final GenerationOptions options;
    private final List<String> targetMethods;
    private List<String> seedTests = List.of();
    private File seedSourceDir;

    public EvoSuiteCommand(String javaExecutable, String evosuiteJar, String targetClass,
                           List<String> projectClasspath, File projectDir, File testDir,
                           File reportDir, GenerationOptions options) {
        this(javaExecutable, evosuiteJar, targetClass, projectClasspath, projectDir, testDir, reportDir, options,
                List.of());
    }

    /**
     * @param targetMethods the methods to restrict the tests to, as {@link GenerationTarget#methods()}
     *                      names them; empty for the whole class
     */
    public EvoSuiteCommand(String javaExecutable, String evosuiteJar, String targetClass,
                           List<String> projectClasspath, File projectDir, File testDir,
                           File reportDir, GenerationOptions options, List<String> targetMethods) {
        this.targetMethods = List.copyOf(targetMethods);
        this.javaExecutable = javaExecutable;
        this.evosuiteJar = evosuiteJar;
        this.targetClass = targetClass;
        this.projectClasspath = new ArrayList<>(projectClasspath);
        this.projectDir = projectDir;
        this.testDir = testDir;
        this.reportDir = reportDir;
        this.options = options;
    }

    /**
     * Extends existing tests: EvoSuite parses {@code testClasses} from their sources to start the
     * search from their tests, never carving them from an execution, and then runs them compiled,
     * so that the new suite only covers what they leave uncovered. The project classpath must
     * contain the compiled test classes.
     *
     * @param sourceDir the source folder that contains all of {@code testClasses}
     * @return this command
     */
    public EvoSuiteCommand seededBy(List<String> testClasses, File sourceDir) {
        this.seedTests = List.copyOf(testClasses);
        this.seedSourceDir = sourceDir;
        return this;
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
        String criterion = seedTests.isEmpty() ? options.criterion : extensionCriteria(options.criterion);
        if (!criterion.isEmpty()) {
            args.add("-criterion");
            args.add(criterion);
        }
        if (options.clientMemory > 0) {
            args.add("-mem");
            args.add(Integer.toString(options.clientMemory));
        }
        args.add("-Dsearch_budget=" + options.searchBudget);
        args.add("-Dassertions=" + options.assertions);
        args.add("-Dminimize=" + options.minimize);
        // Only where they differ from EvoSuite's defaults, to keep the command line readable.
        if (!"MUTATION".equals(options.assertionStrategy)) {
            args.add("-Dassertion_strategy=" + options.assertionStrategy);
        }
        if (!"NUMBERED".equals(options.testNaming)) {
            args.add("-Dtest_naming_strategy=" + options.testNaming);
        }
        if (!"TYPE_BASED".equals(options.variableNaming)) {
            args.add("-Dvariable_naming_strategy=" + options.variableNaming);
        }
        if (options.testComments) {
            args.add("-Dtest_comments=true");
        }
        if (options.testTimeout > 0) {
            args.add("-Dtimeout=" + options.testTimeout);
        }
        args.add("-Dtest_format=" + options.testFormat);
        // The progress bar is how the plug-in follows the search; see ProgressParser.
        args.add("-Dshow_progress=true");
        addLlmArguments(args);
        if (!targetMethods.isEmpty()) {
            // Restricts the coverage goals and the calls in the tests to these methods, while
            // constructors stay available to create the object under test.
            args.add("-Dtarget_method_list=" + String.join(":", targetMethods));
        }
        // Last, so that the user's own arguments win over everything above; among them, the pom's
        // win over the IDE's, and its extraArgs over its evosuiteProperties, as with Maven.
        args.addAll(splitArguments(options.extraArguments));
        options.pomProperties.forEach((name, value) -> args.add("-D" + name + "=" + value));
        args.addAll(splitArguments(options.pomExtraArguments));
        // After the user's arguments, since EvoSuite takes the last value of a property: the
        // plug-in reads the suite from where it told EvoSuite to write it, under the name it
        // told it to use, and finds nothing if another value wins.
        outputLocations().forEach((name, value) -> args.add("-D" + name + "=" + value));
        if (options.llmSwitchedOff()) {
            // After everything else: search only overrides an LLM the pom or arguments switch on.
            args.add("-Dllm_provider=" + GenerationOptions.LLM_NONE);
        }
        if (!seedTests.isEmpty()) {
            // After the user's arguments too, since the run is pointless without them: the tests
            // are parsed from source, and the carver, which would execute them to extract
            // statements instead, stays off whatever else is configured.
            String testClasses = String.join(":", seedTests);
            args.add("-Dtest_factory=PARSED_JUNIT");
            args.add("-Dselected_junit=" + testClasses);
            args.add("-Dseed_test_source_dir=" + seedSourceDir.getAbsolutePath());
            args.add("-Djunit=" + testClasses);
            args.add("-Dcarve_object_pool=false");
            args.add("-Dtest_carving=false");
        }
        return args;
    }

    /**
     * The properties that say where EvoSuite writes, in the order they are passed. The test
     * folder is often the plug-in's temporary folder, and the report folder is in its state area;
     * EvoSuite's default for the LLM traces is {@code evosuite-report/llm-traces} in the project,
     * where the traces, which contain prompts built from the project's code, could end up
     * committed.
     */
    private Map<String, String> outputLocations() {
        Map<String, String> locations = new LinkedHashMap<>();
        // In the order of OUTPUT_PROPERTIES.
        locations.put("test_dir", testDir.getAbsolutePath());
        locations.put("junit_suffix", options.testSuffix);
        locations.put("report_dir", reportDir.getAbsolutePath());
        locations.put("llm_trace_dir", new File(reportDir, "llm-traces").getAbsolutePath());
        return locations;
    }

    /**
     * The {@linkplain #outputLocations() output locations} that the additional arguments or the
     * pom try to set, such as {@code test_dir}: the plug-in's own values override them.
     */
    public static List<String> overriddenUserProperties(GenerationOptions options) {
        List<String> user = new ArrayList<>(splitArguments(options.extraArguments));
        options.pomProperties.forEach((name, value) -> user.add("-D" + name + "=" + value));
        user.addAll(splitArguments(options.pomExtraArguments));
        return OUTPUT_PROPERTIES.stream()
                .filter(name -> user.stream().anyMatch(a -> a.startsWith("-D" + name + "=")))
                .collect(Collectors.toList());
    }

    /**
     * The criteria for a run that extends existing tests: {@code criterion}, or EvoSuite's
     * default set if it is empty, without the {@linkplain #NOT_CREDITED_TO_EXISTING_TESTS
     * criteria} whose goals would keep copies of the existing tests. Unchanged if nothing else
     * would remain.
     */
    static String extensionCriteria(String criterion) {
        List<String> criteria = criterion.isBlank() ? GenerationOptions.DEFAULT_CRITERIA
                : List.of(criterion.trim().split("\\s*:\\s*"));
        List<String> credited = criteria.stream()
                .filter(c -> !NOT_CREDITED_TO_EXISTING_TESTS.contains(c.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toList());
        return credited.isEmpty() ? criterion : String.join(":", credited);
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

    /**
     * Whether a suite file carries {@code @EvoRunnerParameters}, as the suites EvoSuite generates
     * do. A file of a suite's name without it is a test class someone wrote, such as
     * {@code FooTest} when the suite suffix and folder are those of the project's own tests.
     */
    public static boolean isEvoSuiteSuite(File suite) {
        try {
            // Also matches the qualified @org.evosuite.runtime.EvoRunnerParameters.
            return Files.readString(suite.toPath(), StandardCharsets.ISO_8859_1).contains("EvoRunnerParameters");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Why no tests are generated for {@code className}: its suite file is {@code suite}, which
     * EvoSuite did not generate.
     */
    public static String notAnEvoSuiteSuite(String className, File suite) {
        return className.substring(className.lastIndexOf('.') + 1) + ": the new tests would go into " + suite.getName()
                + ", which is not a suite generated by EvoSuite and stays as it is. Choose another test class "
                + "suffix or folder in the EvoSuite settings.";
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
