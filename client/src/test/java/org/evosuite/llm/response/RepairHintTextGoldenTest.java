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
package org.evosuite.llm.response;

import org.evosuite.Properties;
import org.evosuite.llm.LlmFeature;
import org.evosuite.llm.LlmMessage;
import org.evosuite.llm.LlmRequest;
import org.evosuite.llm.LlmService;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testparser.ParseDiagnostic;
import org.evosuite.testparser.ParseResult;
import org.evosuite.testparser.TestParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.evosuite.llm.LlmRequestMatchers.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the complete text of repair messages for one input per hint family, so
 * that moving hint code cannot change a prompt unnoticed and every intended
 * prompt change shows up as a diff of the golden file.
 *
 * <p>Run with {@code -Drepair.hints.golden.update=true} to rewrite the golden
 * file in the source tree after an intended change.
 */
class RepairHintTextGoldenTest {

    private static final String GOLDEN_RESOURCE = "repair-hint-golden.txt";
    private static final String UPDATE_PROPERTY = "repair.hints.golden.update";

    private static final String SUT_CONTEXT =
            "// com.example.Shape [interface]\n"
                    + "  concrete subtypes: com.example.Circle, com.example.Square\n"
                    + "  double area()\n"
                    + "// com.example.Circle [class]\n"
                    + "  Circle(double)\n"
                    + "  double area()\n"
                    + "  double radius()\n"
                    + "// com.example.Widget [abstract]\n"
                    + "  String describe()\n";

    private String originalTargetClass;
    private boolean originalPromptCache;
    private boolean originalHintsEnabled;
    private boolean originalHintsAlwaysOn;
    private int originalHintsMax;
    private int originalHintsCooldown;
    private boolean originalDependencyCode;

    @BeforeEach
    void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalPromptCache = Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT;
        Properties.TARGET_CLASS = "com.example.Target";
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = false;
        originalHintsEnabled = Properties.LLM_REPAIR_HINTS_ENABLED;
        originalHintsAlwaysOn = Properties.LLM_REPAIR_HINTS_ALWAYS_ON;
        originalHintsMax = Properties.LLM_REPAIR_HINTS_MAX_PER_ATTEMPT;
        originalHintsCooldown = Properties.LLM_REPAIR_HINTS_COOLDOWN_ATTEMPTS;
        originalDependencyCode = Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR;
        Properties.LLM_REPAIR_HINTS_ENABLED = true;
        Properties.LLM_REPAIR_HINTS_ALWAYS_ON = true;
        Properties.LLM_REPAIR_HINTS_MAX_PER_ATTEMPT = 6;
        Properties.LLM_REPAIR_HINTS_COOLDOWN_ATTEMPTS = 1;
        Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = true;
    }

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.LLM_PROMPT_CACHE_STATIC_CONTEXT = originalPromptCache;
        Properties.LLM_REPAIR_HINTS_ENABLED = originalHintsEnabled;
        Properties.LLM_REPAIR_HINTS_ALWAYS_ON = originalHintsAlwaysOn;
        Properties.LLM_REPAIR_HINTS_MAX_PER_ATTEMPT = originalHintsMax;
        Properties.LLM_REPAIR_HINTS_COOLDOWN_ATTEMPTS = originalHintsCooldown;
        Properties.LLM_INCLUDE_DEPENDENCY_CODE_ON_REPAIR = originalDependencyCode;
    }

    @Test
    void repairMessagesMatchGoldenText() throws Exception {
        StringBuilder actual = new StringBuilder();
        for (Case c : repairCases()) {
            actual.append("=== repair: ").append(c.name).append(" ===\n");
            actual.append(c.render()).append("\n");
        }
        for (CombinedCase c : combinedCases()) {
            actual.append("=== combined: ").append(c.name).append(" ===\n");
            actual.append(c.render()).append("\n");
        }

        String rendered = actual.toString();
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Path target = Paths.get("src", "test", "resources", "org", "evosuite", "llm", "response",
                    GOLDEN_RESOURCE);
            Files.createDirectories(target.getParent());
            Files.write(target, rendered.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(readGolden(), rendered);
    }

    private String readGolden() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN_RESOURCE)) {
            assertNotNull(in, "missing golden file " + GOLDEN_RESOURCE);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static TestRepairLoop newLoop() {
        // Only the sent message matters here; the model's reply is irrelevant.
        LlmService service = mock(LlmService.class);
        when(service.query(any(LlmRequest.class))).thenReturn(text(null));
        return new TestRepairLoop(
                service, mock(TestParser.class), new LlmResponseParser(),
                mock(ClusterExpansionManager.class),
                testCase -> new ExecutionResult(testCase), 3,
                null, SUT_CONTEXT);
    }

    private static ParseResult parseResult(String name) {
        return new ParseResult(new DefaultTestCase(), name);
    }

    private static Map<ParseResult, ExecutionFailureContext> failures(Object... pairs) {
        Map<ParseResult, ExecutionFailureContext> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 3) {
            ParseResult pr = (ParseResult) pairs[i];
            Throwable thrown = (Throwable) pairs[i + 2];
            map.put(pr, new ExecutionFailureContext((String) pairs[i + 1], pinStackTraces(thrown), null));
        }
        return map;
    }

    /** Replaces the test-runner frames, which would otherwise select a dependency excerpt. */
    private static Throwable pinStackTraces(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            StackTraceElement[] trace = t.getStackTrace();
            if (trace.length == 1 && "Source.java".equals(trace[0].getFileName())) {
                continue;
            }
            t.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.example.Target", "run", "Target.java", 5)});
        }
        return thrown;
    }

    private static Throwable withFrame(Throwable t, String className, String method) {
        t.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(className, method, "Source.java", 10)});
        return t;
    }

    /** One call of the private repair-request path; returns the user message it sent. */
    private static final class Case {
        final String name;
        final String error;
        final String previousResponse;
        final Map<ParseResult, ExecutionFailureContext> failures;
        final int repairAttempt;
        final int repeat;
        final boolean headlessEscalated;

        Case(String name, String error, String previousResponse,
             Map<ParseResult, ExecutionFailureContext> failures,
             int repairAttempt, int repeat, boolean headlessEscalated) {
            this.name = name;
            this.error = error;
            this.previousResponse = previousResponse;
            this.failures = failures;
            this.repairAttempt = repairAttempt;
            this.repeat = repeat;
            this.headlessEscalated = headlessEscalated;
        }

        String render() throws Exception {
            TestRepairLoop loop = newLoop();
            RepairConversation state = new RepairConversation();
            if (headlessEscalated) {
                state.shouldSkipRepair(error, error, new ArrayList<String>());
            }
            Method requestRepair = TestRepairLoop.class.getDeclaredMethod("requestRepair",
                    RepairConversation.class, List.class, String.class, String.class, LlmFeature.class,
                    List.class, int.class, Map.class, long.class);
            requestRepair.setAccessible(true);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < repeat; i++) {
                List<LlmMessage> conversation = new ArrayList<>();
                requestRepair.invoke(loop, state, conversation, previousResponse, error,
                        LlmFeature.SEEDING, Collections.<String>emptyList(), repairAttempt + i,
                        failures, TestRepairLoop.NO_REPAIR_DEADLINE);
                if (i > 0) {
                    out.append("\n--- turn ").append(i + 1).append(" ---\n");
                }
                out.append(conversation.get(conversation.size() - 1).getContent());
            }
            return out.toString();
        }
    }

    /** One call of the batch repair-message builder. */
    private static final class CombinedCase {
        final String name;
        final List<ParseResult> kept;
        final List<ParseResult> droppedAtParse;
        final List<ParseResult> droppedAtExecution;
        final Map<ParseResult, ExecutionFailureContext> failures;

        CombinedCase(String name, List<ParseResult> kept, List<ParseResult> droppedAtParse,
                     List<ParseResult> droppedAtExecution,
                     Map<ParseResult, ExecutionFailureContext> failures) {
            this.name = name;
            this.kept = kept;
            this.droppedAtParse = droppedAtParse;
            this.droppedAtExecution = droppedAtExecution;
            this.failures = failures;
        }

        String render() throws Exception {
            Method build = TestRepairLoop.class.getDeclaredMethod(
                    "buildCombinedRepairMessage", List.class, List.class, List.class, Map.class);
            build.setAccessible(true);
            try {
                return (String) build.invoke(newLoop(), kept, droppedAtParse, droppedAtExecution, failures);
            } catch (InvocationTargetException e) {
                throw new AssertionError(e.getCause());
            }
        }
    }

    private static List<Case> repairCases() {
        List<Case> cases = new ArrayList<>();
        Map<ParseResult, ExecutionFailureContext> none = Collections.emptyMap();

        cases.add(new Case("dependency missing",
                "java.lang.NoClassDefFoundError: org/apache/commons/Missing",
                null,
                failures(parseResult("testDep"), "java.lang.NoClassDefFoundError: org/apache/commons/Missing",
                        new NoClassDefFoundError("org/apache/commons/Missing")),
                2, 1, false));
        cases.add(new Case("missing class symbol",
                "error: cannot find symbol\n  symbol:   class Widgetz\n  location: class FooTest",
                "Widgetz w = new Widgetz();",
                none, 2, 1, false));
        cases.add(new Case("initialization aftershock",
                "java.lang.NoClassDefFoundError: Could not initialize class com.dep.Poisoned",
                null,
                failures(parseResult("testInit"),
                        "java.lang.NoClassDefFoundError: Could not initialize class com.dep.Poisoned",
                        new NoClassDefFoundError("Could not initialize class com.dep.Poisoned")),
                2, 1, false));
        cases.add(new Case("static initializer failure",
                "java.lang.ExceptionInInitializerError",
                null,
                failures(parseResult("testClinit"), "java.lang.ExceptionInInitializerError",
                        withFrame(new ExceptionInInitializerError(new IllegalStateException("boom")),
                                "com.dep.Poisoned", "<clinit>")),
                2, 1, false));
        cases.add(new Case("headless",
                "java.awt.AWTError: Local GraphicsEnvironment must not be null (HeadlessException)",
                null, none, 2, 1, false));
        cases.add(new Case("headless escalated",
                "java.awt.AWTError: Local GraphicsEnvironment must not be null (HeadlessException)",
                null, none, 3, 1, true));
        cases.add(new Case("instantiation",
                "java.lang.InstantiationException: com.example.Widget is abstract; cannot be instantiated",
                null, none, 2, 1, false));

        ParseResult mismatch = parseResult("testMismatch");
        mismatch.setExpectedExceptionClass("java.io.IOException");
        ParseResult shaded = parseResult("testShaded");
        cases.add(new Case("expectation mismatch",
                "Expected java.io.IOException but got IllegalStateException",
                null,
                failures(mismatch, "java.lang.IllegalStateException: closed",
                        new IllegalStateException("closed"),
                        shaded, "org.evosuite.runtime.mock.java.lang.MockThrowable: boom",
                        new RuntimeException("boom")),
                2, 1, false));

        String npeDirect = "java.lang.NullPointerException: Cannot invoke \"org.w3c.dom.Element.getAttribute"
                + "(String)\" because \"elem\" is null\nParsed test code excerpt:\n```java\n"
                + "Element elem = null;\nsut.read(elem);\n```";
        cases.add(new Case("npe direct test variable",
                npeDirect, "Element elem = null;", none, 2, 1, false));
        String npeInside = "java.lang.NullPointerException: Cannot invoke \"com.example.Shape.area()\" "
                + "because \"shape\" is null";
        cases.add(new Case("npe inside sut",
                npeInside, "Shape shape = null;\nsut.measure(shape);",
                failures(parseResult("testNpe"), npeInside, new NullPointerException("npe"),
                        parseResult("testOther"), "org.evosuite.runtime.mock.java.lang.MockThrowable: x",
                        new RuntimeException("x")),
                2, 1, false));
        cases.add(new Case("npe synthetic local",
                "java.lang.NullPointerException: because \"<local1>\" is null",
                null, none, 2, 1, false));
        cases.add(new Case("npe without receiver",
                "java.lang.NullPointerException",
                "Shape s = new Circle(1.0);", none, 2, 1, false));
        cases.add(new Case("npe only in sibling",
                "java.lang.NullPointerException in one test; IllegalStateException in another",
                null,
                failures(parseResult("testIse"), "java.lang.IllegalStateException: x",
                        new IllegalStateException("x")),
                2, 1, false));

        cases.add(new Case("mockito misuse",
                "org.mockito.exceptions.misusing.UnfinishedStubbingException: Unfinished stubbing",
                null, none, 2, 1, false));
        cases.add(new Case("not a mock and spy",
                "org.mockito.exceptions.misusing.NotAMockException: Argument passed to when() is not a mock! "
                        + "Mockito.spy(list) then doReturn(1).when(spy).size()",
                null, none, 2, 1, false));
        cases.add(new Case("stream precondition",
                "java.lang.IllegalArgumentException: InputStream cannot be null",
                null, none, 2, 1, false));
        cases.add(new Case("event source precondition",
                "java.lang.IllegalArgumentException: null source at java.util.EventObject.<init>",
                null, none, 2, 1, false));
        cases.add(new Case("indexed fixture",
                "java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5",
                null, none, 2, 1, false));
        cases.add(new Case("reflective invocation",
                "java.lang.reflect.InvocationTargetException at Method.invoke via getDeclaredMethod",
                null, none, 2, 1, false));
        cases.add(new Case("reflective assertThrows fallback",
                "Snippet compilation failed: InvocationTargetException __llm_fallback3 = null;",
                null, none, 2, 1, false));
        cases.add(new Case("missing method on variable",
                "error: cannot find symbol\n  symbol:   method radius()\n  location: variable radius of type "
                        + "com.example.Circle\nerror: cannot find symbol\n  symbol:   method area2()\n"
                        + "  location: variable c of type com.example.Circle\nNo method named perimeter in "
                        + "com.example.Circle",
                "Circle c = new Circle(1.0);", none, 2, 1, false));
        cases.add(new Case("missing member and package",
                "error: cannot find symbol\n  symbol:   variable size\n  location: variable o of type "
                        + "java.lang.Object\nerror: cannot find symbol\n  symbol:   variable radius\n"
                        + "  location: variable c of type com.example.Circle\nerror: package Mode does not exist",
                null, none, 2, 1, false));
        cases.add(new Case("unknown symbol without location",
                "error: cannot find symbol in expression",
                null, none, 2, 1, false));
        cases.add(new Case("missing class symbols with fallback",
                "error: cannot find symbol\n  symbol:   class Node\n  location: class FooTest\n"
                        + "Node __llm_fallback1 = null; new Node( org.dom4j NodeComparator\n"
                        + "error: cannot find symbol\n  symbol:   class Circle\n  location: class FooTest",
                null, none, 2, 1, false));
        cases.add(new Case("member access recurrent",
                "FooTest.java:12: error: secret() has private access in com.example.Vault\n"
                        + "lexer.state.type",
                null, none, 2, 2, false));
        cases.add(new Case("false positive mock",
                "org.evosuite.runtime.FalsePositiveException: Mock call to findName which was not presented "
                        + "when the test was generated\n[Dependency Stack] com.example.Repo.findName(Repo.java:3)",
                null, none, 2, 1, false));
        cases.add(new Case("false positive mock without name",
                "org.evosuite.runtime.FalsePositiveException: Mock call to something odd",
                null, none, 2, 1, false));
        cases.add(new Case("no test methods with path import",
                "Parser produced no test methods.",
                "import com/example/Shape;\nclass FooTest {}", none, 2, 1, false));
        cases.add(new Case("no test methods with alias import",
                "Parser produced no test methods.",
                "import com.example.Shape as S;\nclass FooTest {}", none, 2, 1, false));
        cases.add(new Case("no test methods empty response",
                "Parser produced no test methods.\nThe LLM response was empty or contained no Java code.",
                null, none, 2, 1, false));
        cases.add(new Case("no test methods with test annotation",
                "Parser produced no test methods.",
                "class FooTest { @Test void t( }", none, 2, 1, false));
        cases.add(new Case("no test methods prose",
                "Parser produced no test methods.",
                "Here is an explanation.", none, 2, 1, false));
        cases.add(new Case("collaborator fallback",
                "Snippet failure near: com.example.Shape __llm_fallback2 = null;",
                null, none, 2, 1, false));
        cases.add(new Case("synthetic fallback npe",
                "java.lang.NullPointerException on __llm_fallback1.read()",
                null, none, 2, 1, false));
        cases.add(new Case("anonymous interface",
                "Snippet compilation failed: illegal start of type near new java.lang.Runnable() { void run }",
                null, none, 2, 1, false));
        cases.add(new Case("anonymous abstract",
                "error: <anonymous FooTest$1> is not abstract and does not override abstract method get(int) in "
                        + "new java.util.AbstractList() {",
                "List<String> l = new java.util.AbstractList() { };", none, 2, 1, false));
        cases.add(new Case("anonymous concrete",
                "Snippet compilation failed: ';' expected near new java.lang.Thread() { }",
                null, none, 2, 1, false));
        cases.add(new Case("context facts",
                "java.lang.NullPointerException: because \"s\" is null",
                "Shape s = new Shape();\nCircle k = null;\nShape w = new Circle(2.0);",
                none, 2, 1, false));
        cases.add(new Case("context facts null declaration",
                "java.lang.NullPointerException: because \"k\" is null",
                "Circle k = null;", none, 2, 1, false));
        cases.add(new Case("context facts type mismatch",
                "java.lang.NullPointerException: because \"w\" is null",
                "Shape w = new Circle(2.0);", none, 2, 1, false));
        cases.add(new Case("fallback strategy",
                "Preserved as UninterpretedStatement: setField(sut, \"x\", 1)",
                null, none, 2, 1, false));
        cases.add(new Case("rule hints with cooldown",
                "java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Integer",
                null,
                failures(parseResult("testCast"),
                        "java.lang.ClassCastException: class java.lang.String cannot be cast to class "
                                + "java.lang.Integer",
                        new ClassCastException("class java.lang.String cannot be cast to class java.lang.Integer")),
                2, 3, false));
        cases.add(new Case("catalogue on later turn",
                "java.lang.IllegalStateException: plain failure",
                null, none, 3, 1, false));
        return cases;
    }

    private static List<CombinedCase> combinedCases() {
        List<CombinedCase> cases = new ArrayList<>();

        ParseResult kept = parseResult("testKept");
        ParseResult parseDropped = parseResult("testParseDropped");
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "Cannot resolve method scope: helper", 3, "helper.run()"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "Cannot resolve unscoped method call: build()", 4, "build()"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "No method named perimeter in com.example.Circle", 5, "c.perimeter()"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "No matching method for call", 6, "c.x()"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "Unknown array variable: arr", 7, "arr[0] = 1"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "Unresolved variable: v", 8, "v.go()"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.ERROR,
                "Cannot resolve type: InventedType", 9, "InventedType t;"));
        parseDropped.addDiagnostic(new ParseDiagnostic(ParseDiagnostic.Severity.WARNING,
                "Stranded LLM_REPAIR_ACTION_REQUIRED: assign the variable", 10, "Foo f;"));

        String npe = "java.lang.NullPointerException: Cannot invoke \"com.example.Shape.area()\" because "
                + "\"shape\" is null";
        ParseResult npeA = parseResult("testNpeA");
        ParseResult npeB = parseResult("testNpeB");
        cases.add(new CombinedCase("shared npe with parse hints",
                Collections.singletonList(kept),
                Collections.singletonList(parseDropped),
                java.util.Arrays.asList(npeA, npeB),
                failures(npeA, npe, new NullPointerException(), npeB, npe, new NullPointerException())));

        String anon = "Snippet compilation failed: illegal start of type near new java.lang.Runnable() {";
        ParseResult anonA = parseResult("testAnonA");
        ParseResult anonB = parseResult("testAnonB");
        cases.add(new CombinedCase("shared anonymous snippet",
                Collections.<ParseResult>emptyList(), Collections.<ParseResult>emptyList(),
                java.util.Arrays.asList(anonA, anonB),
                failures(anonA, anon, null, anonB, anon, null)));

        String init = "java.lang.NoClassDefFoundError: Could not initialize class com.dep.Poisoned";
        ParseResult initA = parseResult("testInitA");
        ParseResult initB = parseResult("testInitB");
        cases.add(new CombinedCase("shared initialization failure",
                Collections.singletonList(kept), Collections.<ParseResult>emptyList(),
                java.util.Arrays.asList(initA, initB),
                failures(initA, init, null, initB, init, null)));

        String bounds = "java.lang.IndexOutOfBoundsException: Index 3 out of bounds for length 2";
        ParseResult boundsA = parseResult("testBoundsA");
        ParseResult boundsB = parseResult("testBoundsB");
        cases.add(new CombinedCase("shared indexed fixture",
                Collections.singletonList(kept), Collections.<ParseResult>emptyList(),
                java.util.Arrays.asList(boundsA, boundsB),
                failures(boundsA, bounds, null, boundsB, bounds, null)));

        String mock = "org.evosuite.runtime.FalsePositiveException: Mock call to findName which was not "
                + "presented when the test was generated";
        ParseResult mockA = parseResult("testMockA");
        ParseResult mockB = parseResult("testMockB");
        cases.add(new CombinedCase("shared false positive mock",
                Collections.singletonList(kept), Collections.<ParseResult>emptyList(),
                java.util.Arrays.asList(mockA, mockB),
                failures(mockA, mock, null, mockB, mock, null)));
        return cases;
    }
}
