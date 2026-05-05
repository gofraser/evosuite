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
package org.evosuite.testcase.execution;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.rmi.ClientServices;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.statistics.RuntimeVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.evosuite.runtime.sandbox.Sandbox;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Compiles and executes Java snippets used by fallback parser artifacts.
 */
public final class ExecutableSnippetEngine {

    private static final Logger logger = LoggerFactory.getLogger(ExecutableSnippetEngine.class);

    public static final ExecutableSnippetEngine INSTANCE = new ExecutableSnippetEngine();
    private static final Pattern NOT_A_STATEMENT_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+not a statement");
    private static final Pattern PACKAGE_NOT_VISIBLE_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+package\\s+.+\\s+is not visible");
    private static final Pattern CANNOT_FIND_SYMBOL_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+cannot find symbol");
    private static final Pattern STATEMENTS_OUTSIDE_METHOD_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+statements\\s+not\\s+expected\\s+outside\\s+of\\s+methods\\s+and\\s+initializers");
    private static final Pattern MISSING_RETURN_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+missing\\s+return\\s+statement");
    private static final Pattern ABSTRACT_INSTANTIATION_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+.*is abstract; cannot be instantiated");
    private static final Pattern PROTECTED_ACCESS_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+.*has protected access in .*");
    private static final Pattern CTOR_CANNOT_APPLY_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+constructor .* cannot be applied to given types;");
    private static final Pattern NON_PUBLIC_ACCESS_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+.* is not public in .*; cannot be accessed from outside package");
    private static final Pattern INCOMPATIBLE_TYPES_PATTERN =
            Pattern.compile("\\.java:(\\d+):\\s+error:\\s+incompatible types:.*");
    private static final Pattern UNREADABLE_CLASSPATH_ENTRY_PATTERN =
            Pattern.compile("error:\\s+error\\s+reading\\s+([^;\\r\\n]+);\\s+java\\.net\\.URISyntaxException",
                    Pattern.CASE_INSENSITIVE);
    /**
     * Matches references to internal-access-only JDK FQNs that javac would reject
     * from application code (module export restrictions / dynamically generated
     * classes). Limited to {@code sun.*}, {@code jdk.internal.*},
     * {@code jdk.javadoc.internal.*}, and {@code com.sun.proxy.*}: {@code com.sun.*} contains public, stable APIs
     * (e.g. {@code com.sun.net.httpserver}) that can legitimately appear in SUT
     * code, so a blanket {@code com.sun.*} filter would drop valid references.
     */
    private static final Pattern FORBIDDEN_FQN_IN_SOURCE_PATTERN =
            Pattern.compile("\\b(?:sun\\.|jdk\\.internal\\.|jdk\\.javadoc\\.internal\\.|com\\.sun\\.proxy\\.)[A-Za-z0-9_$.]+");
    /**
     * Blocks direct security-manager mutation in LLM fallback snippets.
     * Security manager state is controlled by EvoSuite runtime/sandbox setup,
     * and snippet-level mutation can create generation vs rerun mismatches.
     */
    private static final Pattern FORBIDDEN_SECURITY_MANAGER_MUTATION_PATTERN =
            Pattern.compile("\\b(?:java\\.lang\\.)?System\\s*\\.\\s*setSecurityManager\\s*\\(");
    /**
     * Blocks construction of heavyweight top-level AWT/Swing windows in snippet
     * fallback code. In headless and/or mocked GUI environments these can still
     * trigger peer/X11 initialization paths (eg {@code XWindow.initIDs()},
     * {@code XFramePeer}) and create generation-time seeding failures.
     */
    private static final Pattern FORBIDDEN_HEAVY_GUI_CONSTRUCTION_PATTERN =
            Pattern.compile("\\bnew\\s+(?:(?:java\\.awt\\.)?(?:Window|Frame|Dialog|FileDialog)|"
                    + "(?:javax\\.swing\\.)?(?:JFrame|JDialog|JWindow))\\s*\\(");
    private static final Pattern HEAVY_GUI_NEW_EXPRESSION_PATTERN =
            Pattern.compile("\\bnew\\s+((?:java\\.awt\\.)?(?:Window|Frame|Dialog|FileDialog)|"
                    + "(?:javax\\.swing\\.)?(?:JFrame|JDialog|JWindow))\\s*\\(");
    private static final Pattern VFS_IO_NEW_EXPRESSION_PATTERN =
            Pattern.compile("\\bnew\\s+((?:java\\.io\\.)?(?:File|RandomAccessFile|FileInputStream|FileOutputStream|FileReader|FileWriter|PrintStream|PrintWriter))\\s*\\(");

    /** An output stream that silently discards all bytes written to it. */
    private static final OutputStream DISCARD = new OutputStream() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void write(int b) {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    private static final class Binding {
        private final Type type;
        private final Object value;

        private Binding(Type type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class StatementResult {
        private final Object returnValue;
        private final Map<String, Object> updatedValues;

        private StatementResult(Object returnValue, Map<String, Object> updatedValues) {
            this.returnValue = returnValue;
            this.updatedValues = updatedValues;
        }

        public Object getReturnValue() {
            return returnValue;
        }

        public Map<String, Object> getUpdatedValues() {
            return updatedValues;
        }
    }

    private static final class CompiledSnippet {
        private final Method method;

        private CompiledSnippet(Method method) {
            this.method = method;
        }
    }

    private static final class SnippetCompilationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SnippetCompilationException(String message) {
            super(message);
        }

        private SnippetCompilationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SnippetEngineRuntimeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SnippetEngineRuntimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<String, CompiledSnippet> cache = new ConcurrentHashMap<>();
    private final Path compilationDir;
    /**
     * Parent-loader-keyed cache of snippet {@link URLClassLoader}s. Keys are weak
     * so that SUT loaders from previous generations can be garbage-collected when
     * no longer referenced elsewhere; values are {@link WeakReference}s because a
     * {@code URLClassLoader} strongly retains its parent, which would otherwise
     * pin the weak key through the map.
     */
    private final Map<ClassLoader, WeakReference<URLClassLoader>> snippetClassLoaders =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, WeakReference<URLClassLoader>>());
    private final AtomicInteger compileFailures = new AtomicInteger();
    private final AtomicInteger runtimeFailures = new AtomicInteger();
    private final AtomicInteger statementExecutionFailures = new AtomicInteger();
    private final AtomicInteger assertionEvaluationFailures = new AtomicInteger();
    private final Map<String, String> sanitizedCompilerClasspathEntries = new ConcurrentHashMap<>();

    /**
     * Single-thread executor used for snippet compilation.  Its thread is
     * registered as privileged with the EvoSuite sandbox so that filesystem
     * I/O (writing source files, invoking javac) is not blocked by the
     * security manager even when compilation is triggered from an
     * unprivileged test-execution thread.
     */
    private final ExecutorService compilationExecutor;

    private static final Map<String, String> HEAVY_GUI_MOCK_CONSTRUCTOR_REPLACEMENTS =
            createHeavyGuiMockConstructorReplacements();
    private static final Map<String, String> VFS_IO_MOCK_CONSTRUCTOR_REPLACEMENTS =
            createVfsIoMockConstructorReplacements();

    private ExecutableSnippetEngine() {
        this.compilationDir = new File(System.getProperty("java.io.tmpdir"), "evosuite-snippets").toPath();
        this.compilationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EvoSuite-SnippetCompiler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers the compilation executor's thread as privileged with the
     * EvoSuite sandbox.  Must be called from a thread that is itself
     * privileged (e.g. the main EvoSuite thread) <em>after</em> the sandbox
     * has been initialized.  Safe to call multiple times or when the sandbox
     * is not active — both cases are no-ops.
     */
    public void registerCompilationThreadAsPrivileged() {
        if (!Sandbox.isSecurityManagerInitialized()) {
            return;
        }
        try {
            Future<Thread> future = compilationExecutor.submit(Thread::currentThread);
            Thread compilerThread = future.get();
            Sandbox.addPrivilegedThread(compilerThread);
            logger.debug("Registered snippet compilation thread '{}' as privileged",
                    compilerThread.getName());
        } catch (Exception e) {
            logger.warn("Could not register snippet compilation thread as privileged: {}",
                    e.getMessage());
        }
    }

    /** Executes the given source code snippet with the provided variable bindings and returns the result. */
    public StatementResult executeStatement(String sourceCode,
                                            Map<String, Type> variableTypes,
                                            Map<String, Object> variableValues,
                                            String returnExpression) throws Throwable {
        try {
            Map<String, Binding> bindings = toBindings(variableTypes, variableValues);
            ClassLoader parentLoader = resolveSnippetParentClassLoader();
            String cacheKey = "STMT|" + classLoaderKey(parentLoader) + "|"
                    + sourceCode + "|" + returnExpression + "|" + signature(bindings);
            CompiledSnippet snippet = cache.computeIfAbsent(cacheKey,
                    key -> compileSnippet(key,
                            buildStatementClassSource(key, sourceCode, bindings, returnExpression),
                            parentLoader));
            Map<String, Object> values = new LinkedHashMap<>(variableValues);
            Object returnValue = invoke(snippet, values);
            return new StatementResult(returnValue, values);
        } catch (Throwable t) {
            increment(RuntimeVariable.LLM_Fallback_Statement_Execution_Failures, statementExecutionFailures);
            if (t instanceof SnippetEngineRuntimeException) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, runtimeFailures);
            }
            throw t;
        }
    }

    /**
     * Evaluates the given assertion code snippet with the provided variable bindings
     * and returns the boolean result.
     */
    public boolean evaluateAssertion(String assertionCode,
                                     Map<String, Type> variableTypes,
                                     Map<String, Object> variableValues) throws Throwable {
        try {
            Map<String, Binding> bindings = toBindings(variableTypes, variableValues);
            ClassLoader parentLoader = resolveSnippetParentClassLoader();
            String cacheKey = "ASSERT|" + classLoaderKey(parentLoader) + "|"
                    + assertionCode + "|" + signature(bindings);
            CompiledSnippet snippet = cache.computeIfAbsent(cacheKey,
                    key -> compileSnippet(key, buildAssertionClassSource(key, assertionCode, bindings), parentLoader));
            Map<String, Object> values = new LinkedHashMap<>(variableValues);
            Object result = invoke(snippet, values);
            return Boolean.TRUE.equals(result);
        } catch (AssertionError assertionError) {
            return false;
        } catch (Throwable t) {
            increment(RuntimeVariable.LLM_Fallback_Assertion_Evaluation_Failures, assertionEvaluationFailures);
            if (t instanceof SnippetEngineRuntimeException) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Runtime_Failures, runtimeFailures);
            }
            throw t;
        }
    }

    private Object invoke(CompiledSnippet snippet, Map<String, Object> values) throws Throwable {
        boolean shouldEnableMockFramework =
                RuntimeSettings.mockJVMNonDeterminism || Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
        boolean guiGuardEnabled = Properties.REPLACE_GUI || RuntimeSettings.mockGUI;
        boolean wasMockFrameworkEnabled = MockFramework.isEnabled();
        boolean disabledHeadless = false;
        try {
            if (shouldEnableMockFramework) {
                MockFramework.enable();
            }
            if (guiGuardEnabled) {
                GuiSupport.disableHeadlessForMockConstruction();
                disabledHeadless = true;
            }
            return snippet.method.invoke(null, values);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new SnippetEngineRuntimeException("Could not execute compiled snippet", e);
        } finally {
            if (disabledHeadless) {
                GuiSupport.restoreHeadlessAfterMockConstruction();
            }
            if (!wasMockFrameworkEnabled) {
                MockFramework.disable();
            }
        }
    }

    private Map<String, Binding> toBindings(Map<String, Type> variableTypes, Map<String, Object> variableValues) {
        Map<String, Binding> bindings = new LinkedHashMap<>();
        for (Map.Entry<String, Type> entry : variableTypes.entrySet()) {
            String name = entry.getKey();
            bindings.put(name, new Binding(entry.getValue(), variableValues.get(name)));
        }
        return bindings;
    }

    /**
     * Compiles a snippet, delegating the filesystem I/O to the privileged
     * compilation executor so that the sandbox does not block it.
     */
    private CompiledSnippet compileSnippet(String key, String source, ClassLoader parentLoader) {
        Callable<CompiledSnippet> task = () -> doCompileSnippet(key, source, parentLoader);
        try {
            return compilationExecutor.submit(task).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SnippetCompilationException) {
                throw (SnippetCompilationException) cause;
            }
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("Snippet compilation failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("Snippet compilation interrupted", e);
        }
    }

    /** Performs the actual compilation — always runs on the privileged compilation thread. */
    private CompiledSnippet doCompileSnippet(String key, String source, ClassLoader parentLoader) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("No Java compiler available in current runtime");
        }

        try {
            Files.createDirectories(compilationDir);
            String className = classNameFor(key);
            Path sourceFile = compilationDir.resolve(className + ".java");
            Path classFile = compilationDir.resolve(className + ".class");
            String workingSource = sanitizeForbiddenPackageUsageInSource(source);
            Files.write(sourceFile, workingSource.getBytes(StandardCharsets.UTF_8));

            String classpath = buildCompilationClasspath(parentLoader);
            String diagnostics = null;
            final int maxRepairPasses = 3;
            for (int pass = 0; pass <= maxRepairPasses; pass++) {
                diagnostics = compileSource(compiler, classpath, sourceFile);
                if (diagnostics == null) {
                    break;
                }
                boolean repaired = false;
                String repairedClasspath = sanitizeClasspathForKnownCompileIssues(classpath, diagnostics);
                if (repairedClasspath != null && !repairedClasspath.equals(classpath)) {
                    classpath = repairedClasspath;
                    repaired = true;
                }
                String repairedSource = sanitizeSourceForKnownCompileIssues(workingSource, diagnostics);
                if (repairedSource != null && !repairedSource.equals(workingSource)) {
                    workingSource = repairedSource;
                    Files.write(sourceFile, workingSource.getBytes(StandardCharsets.UTF_8));
                    repaired = true;
                }
                if (!repaired) {
                    break;
                }
            }
            if (diagnostics != null) {
                increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
                String message = "Snippet compilation failed for " + className;
                if (!diagnostics.isEmpty()) {
                    message += ": " + diagnostics;
                }
                logger.debug("Snippet compilation classpath: {}", classpath);
                logger.debug("Snippet source:\n{}", workingSource);
                throw new SnippetCompilationException(message);
            }

            URLClassLoader snippetClassLoader = getOrCreateSnippetClassLoader(parentLoader);
            Class<?> compiledClass = Class.forName(className, true, snippetClassLoader);
            Method method = compiledClass.getMethod("run", Map.class);
            safeDelete(sourceFile);
            safeDelete(classFile);
            return new CompiledSnippet(method);
        } catch (IOException | ReflectiveOperationException e) {
            increment(RuntimeVariable.LLM_Fallback_Snippet_Compile_Failures, compileFailures);
            throw new SnippetCompilationException("Could not compile snippet", e);
        }
    }

    private String compileSource(JavaCompiler compiler, String classpath, Path sourceFile) throws IOException {
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        List<String> compilerArgs = buildCompilerArguments(classpath, sourceFile);
        int compilationResult = compiler.run(
                null,
                DISCARD,
                errStream,
                compilerArgs.toArray(new String[0])
        );
        if (compilationResult == 0) {
            return null;
        }
        return errStream.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private List<String> buildCompilerArguments(String classpath, Path sourceFile) {
        List<String> args = new ArrayList<>();
        // Snippet execution does not rely on annotation processing. Disable processors
        // to avoid classpath-dependent processor crashes (eg lombok on JDK 9+ modules)
        // and reduce compile latency for fallback snippets.
        args.add("-proc:none");
        args.add("-classpath");
        args.add(classpath);
        args.add("-d");
        args.add(compilationDir.toString());
        args.add(sourceFile.toString());
        return args;
    }

    private String sanitizeSourceForKnownCompileIssues(String source, String diagnostics) {
        boolean changed = false;
        Set<Integer> targetLines = new LinkedHashSet<>();
        collectDiagnosticLineNumbers(diagnostics, NOT_A_STATEMENT_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, PACKAGE_NOT_VISIBLE_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, CANNOT_FIND_SYMBOL_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, STATEMENTS_OUTSIDE_METHOD_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, ABSTRACT_INSTANTIATION_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, PROTECTED_ACCESS_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, CTOR_CANNOT_APPLY_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, NON_PUBLIC_ACCESS_PATTERN, targetLines);
        collectDiagnosticLineNumbers(diagnostics, INCOMPATIBLE_TYPES_PATTERN, targetLines);

        String candidate = source;
        if (!targetLines.isEmpty()) {
            String[] lines = source.split("\\R", -1);
            for (int lineNumber : targetLines) {
                int index = lineNumber - 1;
                if (index < 0 || index >= lines.length) {
                    continue;
                }
                String original = lines[index];
                if (original.trim().isEmpty()) {
                    continue;
                }
                String indent = leadingWhitespace(original);
                lines[index] = indent + "/* evosuite: removed uncompilable llm statement */";
                changed = true;
            }
            candidate = String.join("\n", lines);
        }

        if (diagnostics != null && diagnostics.contains("missing return statement")) {
            String withTrailingReturn = ensureMissingReturnFixes(candidate);
            if (!withTrailingReturn.equals(candidate)) {
                candidate = withTrailingReturn;
                changed = true;
            }
        }

        return changed ? candidate : null;
    }

    /**
     * Adds a conservative trailing {@code return null;} to the generated
     * {@code run(...)} method when javac reports a missing return statement.
     */
    private String ensureMissingReturnFixes(String source) {
        String fixed = ensureRunMethodTrailingReturn(source);
        fixed = ensureNestedNonVoidMethodsHaveTrailingReturn(fixed);
        return fixed;
    }

    private String ensureRunMethodTrailingReturn(String source) {
        final String signature = "public static Object run(";
        int signatureIndex = source.indexOf(signature);
        if (signatureIndex < 0) {
            return source;
        }
        int openBraceIndex = source.indexOf('{', signatureIndex);
        if (openBraceIndex < 0) {
            return source;
        }

        int depth = 0;
        int closeBraceIndex = -1;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    closeBraceIndex = i;
                    break;
                }
            }
        }
        if (closeBraceIndex <= openBraceIndex) {
            return source;
        }

        String body = source.substring(openBraceIndex + 1, closeBraceIndex).trim();
        String[] bodyLines = body.split("\\R");
        String lastNonEmpty = "";
        for (String line : bodyLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lastNonEmpty = trimmed;
            }
        }
        if (lastNonEmpty.startsWith("return ") || "return;".equals(lastNonEmpty)
                || lastNonEmpty.startsWith("throw ")) {
            return source;
        }

        String insertion = "\n    return null;\n";
        return source.substring(0, closeBraceIndex) + insertion + source.substring(closeBraceIndex);
    }

    private String ensureNestedNonVoidMethodsHaveTrailingReturn(String source) {
        java.util.regex.Pattern methodHeader = java.util.regex.Pattern.compile(
                "(?m)^([ \\t]*)(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?"
                        + "([A-Za-z_$][A-Za-z0-9_$\\[\\]<>?,\\s.]*)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*"
                        + "\\([^;{}\\n]*\\)\\s*(?:throws\\s+[^{\\n]+)?\\{");
        java.util.regex.Matcher matcher = methodHeader.matcher(source);

        List<Integer> insertPositions = new ArrayList<>();
        List<String> insertTexts = new ArrayList<>();

        while (matcher.find()) {
            String indent = matcher.group(1);
            String returnType = normalizeReturnType(matcher.group(2));
            if (isVoidReturnType(returnType)) {
                continue;
            }

            int openBraceIndex = source.indexOf('{', matcher.end() - 1);
            if (openBraceIndex < 0) {
                continue;
            }
            int closeBraceIndex = findMatchingBrace(source, openBraceIndex);
            if (closeBraceIndex <= openBraceIndex) {
                continue;
            }

            String body = source.substring(openBraceIndex + 1, closeBraceIndex).trim();
            if (body.isEmpty()) {
                insertPositions.add(closeBraceIndex);
                insertTexts.add("\n" + indent + "  " + defaultReturnStatement(returnType) + "\n");
                continue;
            }

            String[] bodyLines = body.split("\\R");
            String lastNonEmpty = "";
            for (String line : bodyLines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lastNonEmpty = trimmed;
                }
            }
            if (lastNonEmpty.startsWith("return ") || "return;".equals(lastNonEmpty)
                    || lastNonEmpty.startsWith("throw ")) {
                continue;
            }

            insertPositions.add(closeBraceIndex);
            insertTexts.add("\n" + indent + "  " + defaultReturnStatement(returnType) + "\n");
        }

        if (insertPositions.isEmpty()) {
            return source;
        }

        StringBuilder out = new StringBuilder(source);
        for (int i = insertPositions.size() - 1; i >= 0; i--) {
            out.insert(insertPositions.get(i), insertTexts.get(i));
        }
        return out.toString();
    }

    private int findMatchingBrace(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String normalizeReturnType(String rawReturnType) {
        if (rawReturnType == null) {
            return "";
        }
        return rawReturnType.trim().replaceAll("\\s+", " ");
    }

    private boolean isVoidReturnType(String returnType) {
        return "void".equals(returnType) || "Void".equals(returnType) || "java.lang.Void".equals(returnType);
    }

    private String defaultReturnStatement(String returnType) {
        if ("boolean".equals(returnType)) {
            return "return false;";
        }
        if ("byte".equals(returnType)) {
            return "return (byte)0;";
        }
        if ("short".equals(returnType)) {
            return "return (short)0;";
        }
        if ("int".equals(returnType)) {
            return "return 0;";
        }
        if ("long".equals(returnType)) {
            return "return 0L;";
        }
        if ("float".equals(returnType)) {
            return "return 0.0f;";
        }
        if ("double".equals(returnType)) {
            return "return 0.0d;";
        }
        if ("char".equals(returnType)) {
            return "return '\\0';";
        }
        return "return null;";
    }

    private String sanitizeForbiddenPackageUsageInSource(String source) {
        String[] lines = source.split("\\R", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FORBIDDEN_FQN_IN_SOURCE_PATTERN.matcher(line).find()) {
                String indent = leadingWhitespace(line);
                lines[i] = indent + "/* evosuite: removed forbidden jdk internal reference */";
                changed = true;
            } else if (FORBIDDEN_SECURITY_MANAGER_MUTATION_PATTERN.matcher(line).find()) {
                i = sanitizeSecurityManagerMutation(lines, i);
                changed = true;
            } else if (FORBIDDEN_HEAVY_GUI_CONSTRUCTION_PATTERN.matcher(line).find()) {
                String rewritten = rewriteHeavyGuiConstructionToMocks(line);
                if (rewritten != null) {
                    lines[i] = rewritten;
                } else {
                    String indent = leadingWhitespace(line);
                    lines[i] = indent + "/* evosuite: removed forbidden heavyweight gui construction */";
                }
                changed = true;
            } else if (isVirtualFsRewriteEnabled() && VFS_IO_NEW_EXPRESSION_PATTERN.matcher(line).find()) {
                String rewritten = rewriteVfsIoConstructionToMocks(line);
                if (rewritten != null) {
                    lines[i] = rewritten;
                    changed = true;
                }
            }
        }
        return changed ? String.join("\n", lines) : source;
    }

    /**
     * Replaces the entire {@code System.setSecurityManager(...);} statement, including
     * multiline anonymous-class arguments, with comments. This avoids leaving orphaned
     * method fragments (eg checkPermission/checkExit) in snippet method scope.
     *
     * @return index of the last consumed line
     */
    private int sanitizeSecurityManagerMutation(String[] lines, int startLine) {
        int lineCount = lines.length;
        int lineIndex = startLine;
        int parenthesisDepth = 0;
        int braceDepth = 0;
        boolean sawTerminator = false;
        boolean firstLine = true;
        int scanStart = 0;

        Matcher matcher = FORBIDDEN_SECURITY_MANAGER_MUTATION_PATTERN.matcher(lines[startLine]);
        if (matcher.find()) {
            scanStart = matcher.start();
        }

        while (lineIndex < lineCount) {
            String original = lines[lineIndex];
            String segment = firstLine && scanStart < original.length()
                    ? original.substring(scanStart)
                    : original;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '(') {
                    parenthesisDepth++;
                } else if (c == ')') {
                    parenthesisDepth--;
                } else if (c == '{') {
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                } else if (c == ';') {
                    sawTerminator = true;
                }
            }

            String indent = leadingWhitespace(original);
            lines[lineIndex] = indent + (lineIndex == startLine
                    ? "/* evosuite: removed forbidden security-manager mutation */"
                    : "/* evosuite: removed forbidden security-manager mutation (cont.) */");

            if (sawTerminator && parenthesisDepth <= 0 && braceDepth <= 0) {
                break;
            }
            lineIndex++;
            firstLine = false;
        }
        return Math.min(lineIndex, lineCount - 1);
    }

    private String rewriteHeavyGuiConstructionToMocks(String line) {
        Matcher matcher = HEAVY_GUI_NEW_EXPRESSION_PATTERN.matcher(line);
        StringBuffer rewritten = new StringBuffer();
        boolean anyReplacement = false;
        boolean unresolvedHeavyCtor = false;
        while (matcher.find()) {
            String typeToken = matcher.group(1);
            String replacementType = HEAVY_GUI_MOCK_CONSTRUCTOR_REPLACEMENTS.get(typeToken);
            if (replacementType == null) {
                unresolvedHeavyCtor = true;
                continue;
            }
            anyReplacement = true;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("new " + replacementType + "("));
        }
        if (!anyReplacement || unresolvedHeavyCtor) {
            return null;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private String rewriteVfsIoConstructionToMocks(String line) {
        Matcher matcher = VFS_IO_NEW_EXPRESSION_PATTERN.matcher(line);
        StringBuffer rewritten = new StringBuffer();
        boolean anyReplacement = false;
        boolean unresolvedCtor = false;
        while (matcher.find()) {
            String typeToken = matcher.group(1);
            String replacementType = VFS_IO_MOCK_CONSTRUCTOR_REPLACEMENTS.get(typeToken);
            if (replacementType == null) {
                unresolvedCtor = true;
                continue;
            }
            anyReplacement = true;
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("new " + replacementType + "("));
        }
        if (!anyReplacement || unresolvedCtor) {
            return null;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private boolean isVirtualFsRewriteEnabled() {
        return Properties.VIRTUAL_FS;
    }

    private static Map<String, String> createHeavyGuiMockConstructorReplacements() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("Window", "org.evosuite.runtime.mock.java.awt.MockWindow");
        map.put("java.awt.Window", "org.evosuite.runtime.mock.java.awt.MockWindow");
        map.put("Frame", "org.evosuite.runtime.mock.java.awt.MockFrame");
        map.put("java.awt.Frame", "org.evosuite.runtime.mock.java.awt.MockFrame");
        map.put("Dialog", "org.evosuite.runtime.mock.javax.swing.MockJDialog");
        map.put("java.awt.Dialog", "org.evosuite.runtime.mock.javax.swing.MockJDialog");
        map.put("FileDialog", "org.evosuite.runtime.mock.java.awt.MockFileDialog");
        map.put("java.awt.FileDialog", "org.evosuite.runtime.mock.java.awt.MockFileDialog");
        map.put("JFrame", "org.evosuite.runtime.mock.javax.swing.MockJFrame");
        map.put("javax.swing.JFrame", "org.evosuite.runtime.mock.javax.swing.MockJFrame");
        map.put("JDialog", "org.evosuite.runtime.mock.javax.swing.MockJDialog");
        map.put("javax.swing.JDialog", "org.evosuite.runtime.mock.javax.swing.MockJDialog");
        map.put("JWindow", "org.evosuite.runtime.mock.javax.swing.MockJWindow");
        map.put("javax.swing.JWindow", "org.evosuite.runtime.mock.javax.swing.MockJWindow");
        return map;
    }

    private static Map<String, String> createVfsIoMockConstructorReplacements() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("File", "org.evosuite.runtime.mock.java.io.MockFile");
        map.put("java.io.File", "org.evosuite.runtime.mock.java.io.MockFile");
        map.put("RandomAccessFile", "org.evosuite.runtime.mock.java.io.MockRandomAccessFile");
        map.put("java.io.RandomAccessFile", "org.evosuite.runtime.mock.java.io.MockRandomAccessFile");
        map.put("FileInputStream", "org.evosuite.runtime.mock.java.io.MockFileInputStream");
        map.put("java.io.FileInputStream", "org.evosuite.runtime.mock.java.io.MockFileInputStream");
        map.put("FileOutputStream", "org.evosuite.runtime.mock.java.io.MockFileOutputStream");
        map.put("java.io.FileOutputStream", "org.evosuite.runtime.mock.java.io.MockFileOutputStream");
        map.put("FileReader", "org.evosuite.runtime.mock.java.io.MockFileReader");
        map.put("java.io.FileReader", "org.evosuite.runtime.mock.java.io.MockFileReader");
        map.put("FileWriter", "org.evosuite.runtime.mock.java.io.MockFileWriter");
        map.put("java.io.FileWriter", "org.evosuite.runtime.mock.java.io.MockFileWriter");
        map.put("PrintStream", "org.evosuite.runtime.mock.java.io.MockPrintStream");
        map.put("java.io.PrintStream", "org.evosuite.runtime.mock.java.io.MockPrintStream");
        map.put("PrintWriter", "org.evosuite.runtime.mock.java.io.MockPrintWriter");
        map.put("java.io.PrintWriter", "org.evosuite.runtime.mock.java.io.MockPrintWriter");
        return map;
    }

    private void collectDiagnosticLineNumbers(String diagnostics, Pattern pattern, Set<Integer> targetLines) {
        Matcher matcher = pattern.matcher(diagnostics);
        while (matcher.find()) {
            try {
                targetLines.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private String buildStatementClassSource(String key,
                                             String sourceCode,
                                             Map<String, Binding> bindings,
                                             String returnExpression) {
        String className = classNameFor(key);
        String normalizedBody = sanitizeUnmatchedTopLevelClosingBraces(sourceCode);
        StringBuilder src = new StringBuilder();
        appendImports(src, bindings, normalizedBody, returnExpression);
        src.append("public class ").append(className).append(" {\n");
        appendCompatibilityHelpers(src);
        src.append("  @SuppressWarnings(\"unchecked\")\n");
        src.append("  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n");
        appendVariableDeclarations(src, bindings);
        // Rewrite bare "return;" → "return null;" so it is compatible with the
        // Object return type of this wrapper method.  The LLM sometimes produces
        // void-style returns inside try/catch or guard blocks.
        src.append(normalizedBody.replaceAll("(?m)^(\\s*)return\\s*;", "$1return null;")).append("\n");
        appendVariableWriteBack(src, bindings);
        if (returnExpression != null && !returnExpression.trim().isEmpty()) {
            src.append("    return ").append(returnExpression).append(";\n");
        } else {
            src.append("    return null;\n");
        }
        src.append("  }\n");
        src.append("}\n");
        return src.toString();
    }

    /**
     * Best-effort guard for malformed preserved snippet fragments that contain
     * unmatched top-level closing braces. Such braces can prematurely close the
     * generated run(...) method, causing subsequent "__vars.put(...)" lines to be
     * compiled at class scope ("class, interface, enum, or record expected").
     */
    private String sanitizeUnmatchedTopLevelClosingBraces(String sourceCode) {
        if (sourceCode == null || sourceCode.isEmpty()) {
            return sourceCode;
        }
        String[] lines = sourceCode.split("\\R", -1);
        int depth = 0;
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int localDepth = depth;
            boolean unmatchedClose = false;
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') {
                    localDepth++;
                } else if (c == '}') {
                    if (localDepth == 0) {
                        unmatchedClose = true;
                        break;
                    }
                    localDepth--;
                }
            }
            if (unmatchedClose) {
                String indent = leadingWhitespace(line);
                lines[i] = indent + "/* evosuite: removed unmatched top-level closing brace */";
                changed = true;
                continue;
            }
            depth = localDepth;
        }
        return changed ? String.join("\n", lines) : sourceCode;
    }

    private String buildAssertionClassSource(String key,
                                             String assertionCode,
                                             Map<String, Binding> bindings) {
        String className = classNameFor(key);
        StringBuilder src = new StringBuilder();
        appendImports(src, bindings, assertionCode, null);
        src.append("import static org.junit.jupiter.api.Assertions.*;\n");
        src.append("import static org.junit.jupiter.api.Assumptions.*;\n");
        src.append("public class ").append(className).append(" {\n");
        appendCompatibilityHelpers(src);
        src.append("  @SuppressWarnings(\"unchecked\")\n");
        src.append("  public static Object run(java.util.Map<String,Object> __vars) throws Throwable {\n");
        appendVariableDeclarations(src, bindings);
        src.append(assertionCode).append("\n");
        src.append("    return Boolean.TRUE;\n");
        src.append("  }\n");
        src.append("}\n");
        return src.toString();
    }

    private void appendVariableDeclarations(StringBuilder src, Map<String, Binding> bindings) {
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            String name = entry.getKey();
            Type type = entry.getValue().type;
            Class<?> raw = rawTypeFor(type);
            src.append("    ").append(typeName(raw)).append(" ").append(name).append(" = ");
            src.append(readExpression(raw, name)).append(";\n");
        }
    }

    private void appendVariableWriteBack(StringBuilder src, Map<String, Binding> bindings) {
        for (String name : bindings.keySet()) {
            src.append("    __vars.put(\"").append(escape(name)).append("\", ").append(name).append(");\n");
        }
    }

    /**
     * Emits import statements for all classes known to the TestCluster and
     * all binding types, so that unqualified class names (including inner
     * classes like enums) used in the snippet source code resolve correctly.
     */
    private void appendImports(StringBuilder src,
                               Map<String, Binding> bindings,
                               String sourceCode,
                               String returnExpression) {
        Set<String> imports = new LinkedHashSet<>();
        Set<String> wildcardClassImports = new LinkedHashSet<>();
        Set<String> targetWildcardPackageImports = new LinkedHashSet<>();
        Set<Class<?>> knownClasses = new LinkedHashSet<>();

        // Import public classes from the TestCluster — these are the SUT and
        // its dependencies that EvoSuite has analyzed.
        // We track simple names to avoid collisions (e.g., two classes both
        // named "Color" from different packages).
        Map<String, String> simpleNameToFqn = new LinkedHashMap<>();
        Set<String> collidingSimpleNames = new LinkedHashSet<>();
        try {
            for (Class<?> cls : org.evosuite.setup.TestCluster.getInstance().getAnalyzedClasses()) {
                rememberKnownClass(cls, knownClasses);
                addClassImport(cls, simpleNameToFqn, collidingSimpleNames);
                // Add a wildcard import if the class has at least one public
                // inner class/enum.  If getDeclaredClasses() fails (e.g.,
                // because a transitive dependency like javax.media.j3d is
                // absent), we emit the wildcard import optimistically — losing
                // all inner types is worse than a private-access error on one.
                String canonical = cls.getCanonicalName();
                if (isImportableFqn(canonical)) {
                    try {
                        if (hasPublicDeclaredClass(cls)) {
                            wildcardClassImports.add(canonical);
                        }
                    } catch (NoClassDefFoundError ignored) {
                        wildcardClassImports.add(canonical);
                    }
                }
            }
        } catch (Exception ignored) {
            // TestCluster may not be initialized
        }

        // Also import the SUT package explicitly so same-package collaborators
        // (often referenced with simple names by LLM output) resolve reliably.
        String targetClass = Properties.TARGET_CLASS;
        if (targetClass != null) {
            int lastDot = targetClass.lastIndexOf('.');
            if (lastDot > 0) {
                String targetPkg = targetClass.substring(0, lastDot);
                if (!isForbiddenSnippetImportPackage(targetPkg) && !"java.lang".equals(targetPkg)) {
                    targetWildcardPackageImports.add(targetPkg);
                }
            }
        }

        // Bindings can introduce many packages (eg java.sql.*) that create
        // ambiguous simple-name resolution (Date/Element/Document). Import
        // binding raw types as single-type imports instead of package wildcards.
        for (Binding binding : bindings.values()) {
            Class<?> raw = rawTypeFor(binding.type);
            if (raw.isArray()) {
                raw = rawComponentType(raw);
            }
            rememberKnownClass(raw, knownClasses);
            addClassImport(raw, simpleNameToFqn, collidingSimpleNames);
        }

        seedKnownClassesFromSource(sourceCode, returnExpression, knownClasses);
        ensureSnippetImports(sourceCode, returnExpression, bindings, knownClasses, simpleNameToFqn, collidingSimpleNames);

        for (Map.Entry<String, String> entry : simpleNameToFqn.entrySet()) {
            if (!collidingSimpleNames.contains(entry.getKey())) {
                imports.add(entry.getValue());
            }
        }

        // JUnit 5 assertion/assumption methods (assertEquals, assertThrows, assumeTrue, etc.)
        src.append("import static org.junit.jupiter.api.Assertions.*;\n");
        src.append("import static org.junit.jupiter.api.Assumptions.*;\n");
        src.append("import org.junit.jupiter.api.*;\n");
        src.append("import java.util.*;\n");
        // Frequently used JDK types referenced by LLM output without FQN.
        src.append("import java.io.*;\n");
        // Use package wildcard imports for XML helpers to avoid duplicate
        // single-type imports (eg other libraries also defining Document/Element).
        src.append("import javax.xml.parsers.*;\n");
        src.append("import org.w3c.dom.*;\n");
        src.append("import org.xml.sax.*;\n");

        for (String name : imports) {
            src.append("import ").append(name).append(";\n");
        }
        // Wildcard-import each analyzed class that has public inner types
        for (String className : wildcardClassImports) {
            src.append("import ").append(className).append(".*;\n");
        }
        for (String pkg : targetWildcardPackageImports) {
            src.append("import ").append(pkg).append(".*;\n");
        }
    }

    private void rememberKnownClass(Class<?> cls, Set<Class<?>> knownClasses) {
        if (cls == null) {
            return;
        }
        Class<?> raw = cls;
        while (raw.isArray()) {
            raw = raw.getComponentType();
        }
        if (!raw.isPrimitive()) {
            knownClasses.add(raw);
        }
    }

    private void seedKnownClassesFromSource(String sourceCode,
                                            String returnExpression,
                                            Set<Class<?>> knownClasses) {
        String combined = ((sourceCode == null) ? "" : sourceCode) + "\n"
                + ((returnExpression == null) ? "" : returnExpression);
        if (combined.trim().isEmpty()) {
            return;
        }

        Matcher matcher = Pattern.compile(
                "\\b((?:[a-z_][A-Za-z0-9_]*\\.)+[A-Z][A-Za-z0-9_$]*(?:\\.[A-Z][A-Za-z0-9_$]*)*)\\b")
                .matcher(combined);
        while (matcher.find()) {
            Class<?> resolved = tryLoadSourceLevelClassName(matcher.group(1));
            if (resolved != null) {
                rememberKnownClass(resolved, knownClasses);
            }
        }
    }

    private void ensureSnippetImports(String sourceCode,
                                      String returnExpression,
                                      Map<String, Binding> bindings,
                                      Set<Class<?>> knownClasses,
                                      Map<String, String> simpleNameToFqn,
                                      Set<String> collidingSimpleNames) {
        String combined = ((sourceCode == null) ? "" : sourceCode) + "\n"
                + ((returnExpression == null) ? "" : returnExpression);
        if (combined.trim().isEmpty()) {
            return;
        }

        Set<String> boundNames = new LinkedHashSet<>();
        if (bindings != null) {
            boundNames.addAll(bindings.keySet());
        }

        Set<String> seen = new LinkedHashSet<>();
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.", boundNames, seen,
                knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined, "\\b([A-Z][A-Za-z0-9_]*)\\s*\\.class\\b", boundNames, seen,
                knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined, "\\(\\s*([A-Z][A-Za-z0-9_]*)\\s*\\)", boundNames, seen,
                knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined, "\\binstanceof\\s+([A-Z][A-Za-z0-9_]*)\\b", boundNames, seen,
                knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined,
                "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*(?:<[^>]*>)?\\s*\\(",
                boundNames, seen, knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined,
                "\\b(?:public|protected|private)\\s+"
                        + "(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+)*"
                        + "([A-Z][A-Za-z0-9_]*)\\s*(?:<[^\\n\\r{};=]*>)?\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(",
                boundNames, seen, knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerSnippetImports(combined,
                "\\b([A-Z][A-Za-z0-9_]*)\\s*(?:<[^;{}()=]*>)?\\s*(?:\\[\\s*\\])*\\s+"
                        + "[A-Za-z_$][A-Za-z0-9_$]*\\s*(?==|;|,|\\))",
                boundNames, seen, knownClasses, simpleNameToFqn, collidingSimpleNames);
        registerGenericTypeArgumentImports(combined, boundNames, seen, knownClasses, simpleNameToFqn,
                collidingSimpleNames);
        registerThrownTypeImports(combined, boundNames, seen, knownClasses, simpleNameToFqn,
                collidingSimpleNames);
    }

    private void registerSnippetImports(String text,
                                        String regex,
                                        Set<String> boundNames,
                                        Set<String> seen,
                                        Set<Class<?>> knownClasses,
                                        Map<String, String> simpleNameToFqn,
                                        Set<String> collidingSimpleNames) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String simpleName = matcher.group(1);
            if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                continue;
            }
            Class<?> resolved = resolveSimpleClassName(simpleName, knownClasses);
            if (resolved != null) {
                rememberKnownClass(resolved, knownClasses);
                addClassImport(resolved, simpleNameToFqn, collidingSimpleNames);
            }
        }
    }

    private void registerThrownTypeImports(String text,
                                           Set<String> boundNames,
                                           Set<String> seen,
                                           Set<Class<?>> knownClasses,
                                           Map<String, String> simpleNameToFqn,
                                           Set<String> collidingSimpleNames) {
        Matcher throwsMatcher = Pattern.compile("\\bthrows\\s+([^\\{;]+)").matcher(text);
        while (throwsMatcher.find()) {
            String clause = throwsMatcher.group(1);
            Matcher typeMatcher = Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                    .matcher(clause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName, knownClasses);
                if (resolved != null) {
                    rememberKnownClass(resolved, knownClasses);
                    addClassImport(resolved, simpleNameToFqn, collidingSimpleNames);
                }
            }
        }
    }

    private void registerGenericTypeArgumentImports(String text,
                                                    Set<String> boundNames,
                                                    Set<String> seen,
                                                    Set<Class<?>> knownClasses,
                                                    Map<String, String> simpleNameToFqn,
                                                    Set<String> collidingSimpleNames) {
        Matcher matcher = Pattern.compile("<([^<>]+)>").matcher(text);
        while (matcher.find()) {
            String genericClause = matcher.group(1);
            Matcher typeMatcher = Pattern.compile("(?<![A-Za-z0-9_$.])([A-Z][A-Za-z0-9_]*)\\b")
                    .matcher(genericClause);
            while (typeMatcher.find()) {
                String simpleName = typeMatcher.group(1);
                if (!seen.add(simpleName) || boundNames.contains(simpleName)) {
                    continue;
                }
                Class<?> resolved = resolveSimpleClassName(simpleName, knownClasses);
                if (resolved != null) {
                    rememberKnownClass(resolved, knownClasses);
                    addClassImport(resolved, simpleNameToFqn, collidingSimpleNames);
                }
            }
        }
    }

    private Class<?> resolveSimpleClassName(String simpleName, Set<Class<?>> knownClasses) {
        if (simpleName == null || simpleName.isEmpty()) {
            return null;
        }

        for (Class<?> knownClass : knownClasses) {
            if (simpleName.equals(knownClass.getSimpleName())) {
                return knownClass;
            }
        }

        for (Class<?> knownClass : knownClasses) {
            Class<?> nested = resolveNestedSimpleName(knownClass, simpleName);
            if (nested != null) {
                return nested;
            }
        }

        String[] commonPackages = new String[]{
                "java.lang",
                "java.util",
                "java.util.concurrent",
                "java.util.regex",
                "java.util.stream",
                "java.nio",
                "java.nio.charset",
                "java.nio.file",
                "java.io",
                "java.net",
                "java.time",
                "java.math",
                "java.lang.reflect",
                "java.awt",
                "javax.naming",
                "javax.naming.directory",
                "javax.naming.ldap",
                "javax.swing",
                "org.junit.jupiter.api"
        };

        for (String pkg : commonPackages) {
            Class<?> resolved = tryLoadClass(pkg + "." + simpleName);
            if (resolved != null) {
                return resolved;
            }
        }

        for (Class<?> knownClass : knownClasses) {
            Package knownPackage = knownClass.getPackage();
            if (knownPackage == null || knownPackage.getName() == null || knownPackage.getName().isEmpty()) {
                continue;
            }
            Class<?> sibling = tryLoadClass(knownPackage.getName() + "." + simpleName);
            if (sibling != null) {
                return sibling;
            }
        }

        if (Properties.CLASS_PREFIX != null && !Properties.CLASS_PREFIX.trim().isEmpty()) {
            return tryLoadClass(Properties.CLASS_PREFIX + "." + simpleName);
        }
        return null;
    }

    private Class<?> resolveNestedSimpleName(Class<?> owner, String simpleName) {
        if (owner == null || simpleName == null || simpleName.isEmpty()) {
            return null;
        }
        for (Class<?> nested : safeDeclaredClasses(owner)) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }
        return null;
    }

    private List<Class<?>> safeDeclaredClasses(Class<?> owner) {
        try {
            Class<?>[] declared = owner.getDeclaredClasses();
            List<Class<?>> result = new ArrayList<>(declared.length);
            Collections.addAll(result, declared);
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private Class<?> tryLoadClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        ClassLoader loader = resolveSnippetParentClassLoader();
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?> tryLoadSourceLevelClassName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return null;
        }
        Class<?> direct = tryLoadClass(typeName);
        if (direct != null) {
            return direct;
        }

        int packageEnd = -1;
        Matcher packageMatcher = Pattern.compile("^(?:[a-z_][A-Za-z0-9_]*\\.)+").matcher(typeName);
        if (packageMatcher.find()) {
            packageEnd = packageMatcher.end();
        }
        String packagePrefix = packageEnd > 0 ? typeName.substring(0, packageEnd) : "";
        String remainder = typeName.substring(packagePrefix.length());
        if (remainder.indexOf('.') < 0) {
            return null;
        }

        String[] classSegments = remainder.split("\\.");
        StringBuilder candidate = new StringBuilder(packagePrefix).append(classSegments[0]);
        for (int i = 1; i < classSegments.length; i++) {
            candidate.append('$').append(classSegments[i]);
            Class<?> resolved = tryLoadClass(candidate.toString());
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private void appendCompatibilityHelpers(StringBuilder src) {
        src.append("  @SuppressWarnings(\"unchecked\")\n");
        src.append("  private static <T> T mock(Class<T> type) {\n");
        src.append("    if (type == null) return null;\n");
        src.append("    if (type.isInterface()) {\n");
        src.append("      Object proxy = java.lang.reflect.Proxy.newProxyInstance(\n");
        src.append("          type.getClassLoader(),\n");
        src.append("          new Class<?>[]{type},\n");
        src.append("          (p, m, a) -> defaultValue(m.getReturnType()));\n");
        src.append("      return (T) proxy;\n");
        src.append("    }\n");
        src.append("    return null;\n");
        src.append("  }\n");
        src.append("  private static Object defaultValue(Class<?> returnType) {\n");
        src.append("    if (returnType == null || !returnType.isPrimitive()) return null;\n");
        src.append("    if (returnType == boolean.class) return Boolean.FALSE;\n");
        src.append("    if (returnType == byte.class) return Byte.valueOf((byte)0);\n");
        src.append("    if (returnType == short.class) return Short.valueOf((short)0);\n");
        src.append("    if (returnType == int.class) return Integer.valueOf(0);\n");
        src.append("    if (returnType == long.class) return Long.valueOf(0L);\n");
        src.append("    if (returnType == float.class) return Float.valueOf(0.0f);\n");
        src.append("    if (returnType == double.class) return Double.valueOf(0.0d);\n");
        src.append("    if (returnType == char.class) return Character.valueOf('\\0');\n");
        src.append("    return null;\n");
        src.append("  }\n");
    }

    private boolean hasPublicDeclaredClass(Class<?> cls) {
        for (Class<?> inner : cls.getDeclaredClasses()) {
            if (java.lang.reflect.Modifier.isPublic(inner.getModifiers())) {
                return true;
            }
        }
        return false;
    }

    private void addClassImport(Class<?> cls,
                                Map<String, String> simpleNameToFqn,
                                Set<String> collidingSimpleNames) {
        Class<?> importCandidate = cls;
        while (importCandidate.isArray()) {
            importCandidate = importCandidate.getComponentType();
        }
        if (importCandidate.isPrimitive()) {
            return;
        }
        if (!java.lang.reflect.Modifier.isPublic(importCandidate.getModifiers())) {
            return;
        }
        String name = importCandidate.getCanonicalName();
        if (!isImportableFqn(name) || name.startsWith("java.lang.")) {
            return;
        }
        Package pkg = importCandidate.getPackage();
        if (pkg != null && isForbiddenSnippetImportPackage(pkg.getName())) {
            return;
        }
        String simpleName = importCandidate.getSimpleName();
        if (simpleNameToFqn.containsKey(simpleName)) {
            collidingSimpleNames.add(simpleName);
        } else {
            simpleNameToFqn.put(simpleName, name);
        }
    }

    private Class<?> rawComponentType(Class<?> arrayType) {
        Class<?> component = arrayType;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        return component;
    }

    private String readExpression(Class<?> rawType, String varName) {
        String accessor = "__vars.get(\"" + escape(varName) + "\")";
        if (!rawType.isPrimitive()) {
            return "(" + typeName(rawType) + ")" + accessor;
        }
        if (rawType == boolean.class) {
            return "((java.lang.Boolean)" + accessor + ").booleanValue()";
        }
        if (rawType == byte.class) {
            return "((java.lang.Number)" + accessor + ").byteValue()";
        }
        if (rawType == short.class) {
            return "((java.lang.Number)" + accessor + ").shortValue()";
        }
        if (rawType == int.class) {
            return "((java.lang.Number)" + accessor + ").intValue()";
        }
        if (rawType == long.class) {
            return "((java.lang.Number)" + accessor + ").longValue()";
        }
        if (rawType == float.class) {
            return "((java.lang.Number)" + accessor + ").floatValue()";
        }
        if (rawType == double.class) {
            return "((java.lang.Number)" + accessor + ").doubleValue()";
        }
        if (rawType == char.class) {
            return "((java.lang.Character)" + accessor + ").charValue()";
        }
        return "(" + typeName(rawType) + ")" + accessor;
    }

    private String typeName(Class<?> rawType) {
        if (rawType.isArray()) {
            return rawType.getCanonicalName();
        }
        if (rawType.isPrimitive()) {
            return rawType.getName();
        }
        String canonical = rawType.getCanonicalName();
        return canonical != null ? canonical : rawType.getName().replace('$', '.');
    }

    private boolean isImportableFqn(String canonicalName) {
        return canonicalName != null && canonicalName.indexOf('.') >= 0;
    }

    private boolean isForbiddenSnippetImportPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }
        return packageName.startsWith("jdk.")
                || packageName.startsWith("sun.")
                || packageName.startsWith("com.sun.");
    }

    private String classNameFor(String key) {
        return "EvosuiteSnippet_" + hash(key);
    }

    private String buildCompilationClasspath(ClassLoader parentLoader) {
        Set<String> entries = new LinkedHashSet<>();
        appendNormalizedClasspathEntries(Properties.CP, entries);
        appendNormalizedClasspathEntries(System.getProperty("java.class.path"), entries);
        appendClassLoaderEntries(parentLoader, entries);
        appendClassLoaderEntries(Thread.currentThread().getContextClassLoader(), entries);
        return String.join(File.pathSeparator,
                sanitizeClasspathEntriesForCompiler(new ArrayList<>(entries), Collections.emptySet()));
    }

    private void appendNormalizedClasspathEntries(String classpath, Set<String> entries) {
        if (classpath == null || classpath.trim().isEmpty()) {
            return;
        }
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            String normalized = normalizeClasspathEntry(entry);
            if (normalized != null && !normalized.isEmpty()) {
                entries.add(normalized);
            }
        }
    }

    private String normalizeClasspathEntry(String entry) {
        if (entry == null) {
            return null;
        }
        String normalized = entry.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() >= 2
                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        while (normalized.endsWith("\\")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("file:")) {
            try {
                return new File(new URL(normalized).toURI()).getAbsolutePath();
            } catch (Exception ignored) {
                // Best effort fallback for malformed file: URL-like entries.
                if (normalized.startsWith("file:/")) {
                    normalized = normalized.substring("file:".length());
                }
            }
        }
        return normalized;
    }

    public String sanitizeClasspathForKnownCompileIssues(String classpath, String diagnostics) {
        if (classpath == null || classpath.isEmpty() || diagnostics == null || diagnostics.isEmpty()) {
            return null;
        }

        LinkedHashSet<String> badBasenames = new LinkedHashSet<>();
        Matcher matcher = UNREADABLE_CLASSPATH_ENTRY_PATTERN.matcher(diagnostics);
        while (matcher.find()) {
            String entry = normalizeClasspathEntry(matcher.group(1));
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            String baseName = new File(entry).getName();
            if (!baseName.isEmpty()) {
                badBasenames.add(baseName);
            }
        }
        if (badBasenames.isEmpty()) {
            return null;
        }

        String repaired = String.join(File.pathSeparator,
                sanitizeClasspathEntriesForCompiler(
                        new ArrayList<>(java.util.Arrays.asList(classpath.split(Pattern.quote(File.pathSeparator)))),
                        badBasenames));
        if (repaired.equals(classpath)) {
            return null;
        }
        return repaired;
    }

    private String sanitizeClasspathEntryForCompiler(String entry) {
        return sanitizeClasspathEntryForCompiler(entry, false);
    }

    private List<String> sanitizeClasspathEntriesForCompiler(List<String> rawEntries, Set<String> seedBadBasenames) {
        List<String> normalizedEntries = new ArrayList<>();
        LinkedHashSet<String> seenEntries = new LinkedHashSet<>();
        LinkedHashSet<String> problematicBasenames = new LinkedHashSet<>();
        if (seedBadBasenames != null) {
            problematicBasenames.addAll(seedBadBasenames);
        }
        LinkedHashSet<String> forcedSanitizationEntries = new LinkedHashSet<>();

        for (String rawEntry : rawEntries) {
            String normalizedEntry = normalizeClasspathEntry(rawEntry);
            if (normalizedEntry == null || normalizedEntry.isEmpty()) {
                continue;
            }
            File jarFile = asExistingJarFile(normalizedEntry);
            if (jarFile != null) {
                List<String> expandedEntries = expandManifestOnlyClasspathJar(jarFile);
                if (!expandedEntries.isEmpty()) {
                    for (String expandedEntry : expandedEntries) {
                        if (expandedEntry != null && !expandedEntry.isEmpty() && seenEntries.add(expandedEntry)) {
                            normalizedEntries.add(expandedEntry);
                        }
                    }
                    continue;
                }
            }

            if (seenEntries.add(normalizedEntry)) {
                normalizedEntries.add(normalizedEntry);
            }

            if (jarFile == null) {
                continue;
            }

            String baseName = jarFile.getName();
            boolean seedMatch = !baseName.isEmpty() && problematicBasenames.contains(baseName);
            if (seedMatch || containsMalformedManifestClassPathEntry(jarFile)) {
                forcedSanitizationEntries.add(jarFile.getAbsolutePath());
                if (!baseName.isEmpty()) {
                    problematicBasenames.add(baseName);
                }
            }
        }

        boolean changed;
        do {
            changed = false;
            for (String normalizedEntry : normalizedEntries) {
                File jarFile = asExistingJarFile(normalizedEntry);
                if (jarFile == null) {
                    continue;
                }

                String absolutePath = jarFile.getAbsolutePath();
                if (forcedSanitizationEntries.contains(absolutePath)) {
                    continue;
                }

                if (referencesManifestClassPathEntry(jarFile, problematicBasenames)) {
                    forcedSanitizationEntries.add(absolutePath);
                    String baseName = jarFile.getName();
                    if (!baseName.isEmpty()) {
                        problematicBasenames.add(baseName);
                    }
                    changed = true;
                }
            }
        } while (changed);

        List<String> sanitizedEntries = new ArrayList<>(normalizedEntries.size());
        for (String normalizedEntry : normalizedEntries) {
            File jarFile = asExistingJarFile(normalizedEntry);
            boolean forceSanitization = jarFile != null
                    && forcedSanitizationEntries.contains(jarFile.getAbsolutePath());
            sanitizedEntries.add(sanitizeClasspathEntryForCompiler(normalizedEntry, forceSanitization));
        }
        return sanitizedEntries;
    }

    private List<String> expandManifestOnlyClasspathJar(File jarFile) {
        if (jarFile == null
                || !isManifestOnlyClasspathJar(jarFile)
                || containsMalformedManifestClassPathEntry(jarFile)) {
            return Collections.emptyList();
        }

        List<String> expandedEntries = readManifestClassPathEntries(jarFile);
        if (expandedEntries.isEmpty()) {
            return Collections.emptyList();
        }
        return expandedEntries;
    }

    private File asExistingJarFile(String entry) {
        if (entry == null || entry.isEmpty() || !entry.endsWith(".jar")) {
            return null;
        }
        File jarFile = new File(entry);
        return jarFile.isFile() ? jarFile : null;
    }

    private boolean isManifestOnlyClasspathJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return false;
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!"META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            logger.debug("Could not inspect jar {} for manifest-only classpath expansion: {}",
                    jarFile,
                    e.getMessage());
        }
        return false;
    }

    private List<String> readManifestClassPathEntries(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return Collections.emptyList();
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return Collections.emptyList();
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return Collections.emptyList();
            }

            List<String> expandedEntries = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            String[] manifestEntries = classPath.trim().split("\\s+");
            for (String manifestEntry : manifestEntries) {
                String resolved = resolveManifestClassPathEntry(jarFile, manifestEntry);
                if (resolved != null && !resolved.isEmpty() && seen.add(resolved)) {
                    expandedEntries.add(resolved);
                }
            }
            return expandedEntries;
        } catch (IOException e) {
            logger.debug("Could not read manifest classpath entries from {}: {}",
                    jarFile,
                    e.getMessage());
        }
        return Collections.emptyList();
    }

    private String resolveManifestClassPathEntry(File jarFile, String manifestEntry) {
        if (jarFile == null || manifestEntry == null || manifestEntry.trim().isEmpty()) {
            return null;
        }

        String normalizedEntry = manifestEntry.trim();
        File directFile = new File(normalizedEntry);
        if (directFile.isAbsolute()) {
            return normalizeClasspathEntry(directFile.getAbsolutePath());
        }

        try {
            URI resolved = jarFile.getParentFile().toURI().resolve(normalizedEntry);
            if (resolved.getScheme() == null || "file".equalsIgnoreCase(resolved.getScheme())) {
                return normalizeClasspathEntry(new File(resolved).getAbsolutePath());
            }
            return normalizeClasspathEntry(resolved.toString());
        } catch (IllegalArgumentException ignored) {
            // Fall through to path-style resolution for malformed relative entries.
        }

        return normalizeClasspathEntry(new File(jarFile.getParentFile(), normalizedEntry).getAbsolutePath());
    }

    private boolean referencesManifestClassPathEntry(File jarFile, Set<String> badBasenames) {
        if (jarFile == null || badBasenames == null || badBasenames.isEmpty()) {
            return false;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return false;
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            String[] entries = classPath.trim().split("\\s+");
            for (String manifestEntry : entries) {
                String baseName = manifestEntryBaseName(manifestEntry);
                if (!baseName.isEmpty() && badBasenames.contains(baseName)) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar manifest {} for snippet compiler dependency sanitization: {}",
                    jarFile,
                    e.getMessage());
        }
        return false;
    }

    private String manifestEntryBaseName(String manifestEntry) {
        if (manifestEntry == null) {
            return "";
        }

        String normalized = manifestEntry.trim();
        while (normalized.endsWith("\\") || normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return "";
        }

        try {
            URI uri = new URI(normalized);
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                normalized = uri.getPath();
            }
        } catch (URISyntaxException ignored) {
            // Fall back to path-style parsing for malformed or relative manifest entries.
        }

        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        normalized = normalized.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0) {
            normalized = normalized.substring(slashIndex + 1);
        }
        return normalized.trim();
    }

    private String sanitizeClasspathEntryForCompiler(String entry, boolean forceSanitization) {
        if (entry == null || entry.isEmpty()) {
            return entry;
        }
        File jarFile = asExistingJarFile(entry);
        if (jarFile == null) {
            return entry;
        }

        String absolutePath = jarFile.getAbsolutePath();
        String cached = sanitizedCompilerClasspathEntries.get(absolutePath);
        if (cached != null && (!forceSanitization || !absolutePath.equals(cached))) {
            return cached;
        }

        if (!forceSanitization && !containsMalformedManifestClassPathEntry(jarFile)) {
            sanitizedCompilerClasspathEntries.put(absolutePath, absolutePath);
            return absolutePath;
        }

        String sanitized = extractJarWithoutManifestClassPath(jarFile);
        sanitizedCompilerClasspathEntries.put(absolutePath, sanitized);
        if (!absolutePath.equals(sanitized)) {
            logger.warn("Using sanitized snippet compiler classpath entry {} -> {}",
                    absolutePath, sanitized);
        }
        return sanitized;
    }

    private boolean containsMalformedManifestClassPathEntry(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            java.util.jar.Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            if (attributes == null) {
                return false;
            }
            String classPath = attributes.getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null || classPath.trim().isEmpty()) {
                return false;
            }

            String[] entries = classPath.trim().split("\\s+");
            for (String manifestEntry : entries) {
                if (manifestEntry == null || manifestEntry.isEmpty()) {
                    continue;
                }
                if (manifestEntry.indexOf('\\') >= 0) {
                    return true;
                }
                try {
                    new URI(manifestEntry);
                } catch (URISyntaxException e) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.debug("Could not inspect jar manifest {} for snippet compiler sanitization: {}",
                    jarFile,
                    e.getMessage());
        }
        return false;
    }

    private String extractJarWithoutManifestClassPath(File jarFile) {
        try {
            File targetDir = Files.createTempDirectory("evosuite-snippet-compiler-cp-").toFile();
            targetDir.deleteOnExit();

            try (JarFile jar = new JarFile(jarFile)) {
                java.util.Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                        // Avoid javac reading malformed Class-Path from this manifest.
                        continue;
                    }
                    if (name.startsWith("/")) {
                        name = name.substring(1);
                    }
                    if (name.isEmpty()) {
                        continue;
                    }

                    File out = new File(targetDir, name);
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) {
                            logger.debug("Could not create directory while sanitizing jar: {}", out);
                        }
                        continue;
                    }

                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        logger.debug("Could not create parent directory while sanitizing jar: {}", parent);
                        continue;
                    }

                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    out.deleteOnExit();
                }
            }
            return targetDir.getAbsolutePath();
        } catch (IOException e) {
            logger.warn("Failed to sanitize malformed jar {} for snippet compiler classpath: {}",
                    jarFile.getAbsolutePath(),
                    e.getMessage());
            return jarFile.getAbsolutePath();
        }
    }

    private URLClassLoader getOrCreateSnippetClassLoader(ClassLoader parentLoader) {
        ClassLoader key = parentLoader != null ? parentLoader : ClassLoader.getSystemClassLoader();
        synchronized (snippetClassLoaders) {
            WeakReference<URLClassLoader> existing = snippetClassLoaders.get(key);
            URLClassLoader cached = existing == null ? null : existing.get();
            if (cached != null) {
                return cached;
            }
            try {
                URLClassLoader fresh = new URLClassLoader(
                        new URL[]{compilationDir.toUri().toURL()}, key);
                snippetClassLoaders.put(key, new WeakReference<>(fresh));
                return fresh;
            } catch (IOException e) {
                throw new IllegalStateException("Could not initialize snippet class loader", e);
            }
        }
    }

    private ClassLoader resolveSnippetParentClassLoader() {
        try {
            ClassLoader sutLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
            if (sutLoader != null) {
                return sutLoader;
            }
        } catch (Throwable ignored) {
            // Best effort: TestGenerationContext may be unavailable in some test-only contexts.
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            return contextLoader;
        }
        ClassLoader ownLoader = ExecutableSnippetEngine.class.getClassLoader();
        return ownLoader != null ? ownLoader : ClassLoader.getSystemClassLoader();
    }

    private String classLoaderKey(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        return loader.getClass().getName() + "@" + System.identityHashCode(loader);
    }

    private void appendClassLoaderEntries(ClassLoader loader, Set<String> entries) {
        ClassLoader current = loader;
        while (current != null) {
            if (current instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) current).getURLs()) {
                    if (!"file".equalsIgnoreCase(url.getProtocol())) {
                        continue;
                    }
                    try {
                        String path = new File(url.toURI()).getAbsolutePath();
                        if (!path.isEmpty()) {
                            entries.add(path);
                        }
                    } catch (Exception ignored) {
                        // Best effort only.
                    }
                }
            }
            current = current.getParent();
        }
    }

    private Class<?> rawTypeFor(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        if (type instanceof GenericArrayType) {
            Class<?> component = rawTypeFor(((GenericArrayType) type).getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return Object.class;
    }

    private String signature(Map<String, Binding> bindings) {
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            signature.append(entry.getKey())
                    .append(":")
                    .append(entry.getValue().type == null ? "java.lang.Object" : entry.getValue().type.getTypeName())
                    .append(";");
        }
        return signature.toString();
    }

    private String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                out.append(String.format("%02x", hashed[i]));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void safeDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort temp cleanup.
        }
    }

    public Map<String, Object> emptyValues() {
        return Collections.emptyMap();
    }

    private void increment(RuntimeVariable variable, AtomicInteger counter) {
        ClientServices.track(variable, counter.incrementAndGet());
    }

    /** Resets all metric counters to zero (for use in tests only). */
    public void resetMetricsForTesting() {
        compileFailures.set(0);
        runtimeFailures.set(0);
        statementExecutionFailures.set(0);
        assertionEvaluationFailures.set(0);
    }

    /** Returns the number of snippet compilation failures recorded since this engine was created. */
    public int getCompileFailures() {
        return compileFailures.get();
    }

    public int getRuntimeFailures() {
        return runtimeFailures.get();
    }

    public int getStatementExecutionFailures() {
        return statementExecutionFailures.get();
    }

    public int getAssertionEvaluationFailures() {
        return assertionEvaluationFailures.get();
    }
}
