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
 * EvoSuite is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.llm.postprocess;

import org.evosuite.Properties;
import org.evosuite.assertion.Assertion;
import org.evosuite.assertion.TemplateCodeAssertion;
import org.evosuite.junit.naming.methods.NumberedTestNameGenerationStrategy;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.TestPresentationMetadata;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.UninterpretedStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class LlmPostProcessingEditApplierTest {

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response) {
        return apply(test, references, response, true, null);
    }

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response,
            boolean assertionsAllowed) {
        return apply(test, references, response, assertionsAllowed, null);
    }

    private static LlmPostProcessingEditApplier.ApplyResult apply(
            TestCase test, LlmPostProcessingReferences references, LlmPostProcessingResponse response,
            boolean assertionsAllowed, ExecutionResult executionResult) {
        return LlmPostProcessingEditApplier.apply(test, references, response, assertionsAllowed,
                executionResult, PostProcessingOptions.fromProperties());
    }

    private Properties.OutputFormat originalOutputFormat;

    @BeforeEach
    void enableFeatures() {
        originalOutputFormat = Properties.TEST_FORMAT;
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
    }

    @AfterEach
    void restoreFeatures() {
        Properties.LLM_POSTPROCESSING_TEST_NAMES = true;
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = true;
        Properties.LLM_POSTPROCESSING_COMMENTS = true;
        Properties.LLM_POSTPROCESSING_SECTION_BREAKS = true;
        Properties.LLM_POSTPROCESSING_ASSERTIONS = true;
        Properties.TEST_FORMAT = originalOutputFormat;
    }

    @Test
    void apply_recordsAcceptedNamesAndReadabilityEdits() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new StringPrimitiveStatement(test, "value"));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        String json = "{"
                + "\"schemaVersion\":2,"
                + "\"testName\":\"usesReadableNames\","
                + "\"variableNames\":{\"v0\":\"count\",\"v1\":\"label\"},"
                + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Initialize the count.\"}],"
                + "\"sectionBreaksAfter\":[\"s0\"]"
                + "}";
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                json, references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(1, result.getTestNamesApplied());
        assertEquals(2, result.getVariableNamesApplied());
        assertEquals(1, result.getCommentsApplied());
        assertEquals(1, result.getSectionBreaksApplied());
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertNotNull(metadata);
        assertEquals("usesReadableNames", metadata.getTestName());
        assertEquals("count", metadata.getVariableName(0));
        assertEquals("label", metadata.getVariableName(1));
        assertEquals("Initialize the count.", metadata.getCommentsAfter(0).get(0));
        assertTrue(metadata.hasSectionBreakAfter(0));
    }

    @Test
    void testNameStrategy_prefersAcceptedNameAndFallsBackOtherwise() {
        DefaultTestCase named = new DefaultTestCase();
        named.addStatement(new IntPrimitiveStatement(named, 1));
        TestPresentationMetadata.getOrCreate(named).setTestName("customName");
        DefaultTestCase fallback = new DefaultTestCase();
        fallback.addStatement(new IntPrimitiveStatement(fallback, 2));
        NumberedTestNameGenerationStrategy numbered =
                new NumberedTestNameGenerationStrategy(java.util.Arrays.<TestCase>asList(named, fallback),
                        Collections.emptyList());
        LlmPostProcessingTestNameStrategy strategy = new LlmPostProcessingTestNameStrategy(numbered);

        assertEquals("customName", strategy.getName(named));
        assertEquals("test1", strategy.getName(fallback));
    }

    @Test
    void testCodeVisitor_usesAcceptedVariableNamesAndReadabilityMetadata() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\",\"v1\":\"label\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Initialize the count.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\"]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(test, references, response);

        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("int count = 7;"));
        assertTrue(code.contains("int label = 8;"));
        assertTrue(code.contains("// Initialize the count."));
        assertTrue(code.indexOf("// Initialize the count.") < code.indexOf("int count = 7;"), code);
        assertTrue(code.indexOf("int count = 7;") < code.indexOf("int label = 8;"), code);
    }

    @Test
    void apply_honorsDisabledFeatureSwitches() {
        Properties.LLM_POSTPROCESSING_VARIABLE_NAMES = false;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"count\"}}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(0, result.getVariableNamesApplied());
        assertNull(TestPresentationMetadata.get(test).getVariableName(0));
    }

    @Test
    void apply_resolvesDuplicateVariableNamesDeterministically() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"value\",\"v1\":\"value\"}}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(2, result.getVariableNamesApplied());
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertEquals("value", metadata.getVariableName(0));
        assertEquals("value2", metadata.getVariableName(1));

        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String code = visitor.getCode();
        assertTrue(code.contains("int value = 7;"), code);
        assertTrue(code.contains("int value2 = 8;"), code);
    }

    @Test
    void apply_resolvesVariableNameCollisionsAgainstExistingRenderedLocals() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        String originalCode = test.toCode();
        assertTrue(originalCode.contains("int int0 = 7;"), originalCode);
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,\"variableNames\":{\"v0\":\"int0\"}}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(1, result.getVariableNamesApplied());
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertEquals("int0", metadata.getVariableName(0),
                "A variable may retain its own collision-free rendered name");

        String code = test.toCode();
        assertTrue(code.contains("int " + metadata.getVariableName(0) + " = 7;"), code);
        assertTrue(code.contains(" = 8;"), code);
    }

    @Test
    void apply_rejectsReadabilityMetadataAtTerminatingExceptionPosition() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        test.addStatement(new IntPrimitiveStatement(test, 8));
        ExecutionResult executionResult = new ExecutionResult(test);
        executionResult.reportNewThrownException(1, new RuntimeException("boom"));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"comments\":["
                        + "{\"afterStatementId\":\"s0\",\"text\":\"Before throw.\"},"
                        + "{\"afterStatementId\":\"s1\",\"text\":\"After throw.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\",\"s1\"]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response, false, executionResult);

        assertEquals(1, result.getCommentsApplied());
        assertEquals(1, result.getSectionBreaksApplied());
        TestPresentationMetadata metadata = TestPresentationMetadata.get(test);
        assertEquals("Before throw.", metadata.getCommentsAfter(0).get(0));
        assertTrue(metadata.getCommentsAfter(1).isEmpty());
        assertTrue(metadata.hasSectionBreakAfter(0));
        assertFalse(metadata.hasSectionBreakAfter(1));
    }

    @Test
    void apply_attachesTemplateAssertionsRenderedWithAcceptedVariableNames() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\","
                        + "\"purpose\":\"The generated value is retained.\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(1, result.getAssertionsApplied());
        assertEquals(1, test.getStatement(0).getAssertions().size());

        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("int count = 7;"));
        assertTrue(code.contains("assertEquals(7, count); // The generated value is retained."));
        assertFalse(code.contains("assertEquals(7, v0);"));
    }

    @Test
    void apply_doesNotChangePrimitiveStatementValues() {
        DefaultTestCase test = new DefaultTestCase();
        IntPrimitiveStatement intStatement = new IntPrimitiveStatement(test, 7);
        StringPrimitiveStatement stringStatement = new StringPrimitiveStatement(test, "original");
        test.addStatement(intStatement);
        test.addStatement(stringStatement);
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"testName\":\"keepsInputs\","
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"comments\":[{\"afterStatementId\":\"s0\",\"text\":\"Do not rewrite inputs.\"}],"
                        + "\"sectionBreaksAfter\":[\"s0\"],"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"NOT_EQUALS\","
                        + "\"expected\":\"99\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        apply(test, references, response);

        assertEquals(Integer.valueOf(7), intStatement.getValue());
        assertEquals("original", stringStatement.getValue());
    }

    @Test
    void apply_dropsAssertionWithUnknownSymbolicVariableId() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v9\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        LlmPostProcessingEditApplier.ApplyResult result =
                apply(test, references, response);

        assertEquals(0, result.getAssertionsApplied());
        assertTrue(test.getStatement(0).getAssertions().isEmpty());
    }

    @Test
    void templateAssertionRenderer_lowersNotEqualsForJUnit3() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT3;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"NOT_EQUALS\","
                        + "\"expected\":\"8\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        apply(test, references, response);

        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("assertFalse(java.util.Objects.equals(8, count));"));
        assertFalse(code.contains("assertNotEquals"));
    }

    @Test
    void templateAssertionRenderer_usesNotEqualsForJUnit4And5() {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT5;
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"NOT_EQUALS\","
                        + "\"expected\":\"8\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();

        apply(test, references, response);

        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("assertNotEquals(8, count);"));
    }

    @Test
    void metadataCopyTo_remapsPositionAnchorsAndDropsInvalidTargets() {
        DefaultTestCase source = new DefaultTestCase();
        source.addStatement(new IntPrimitiveStatement(source, 7));
        source.addStatement(new StringPrimitiveStatement(source, "value"));
        TestPresentationMetadata sourceMetadata = TestPresentationMetadata.getOrCreate(source);
        sourceMetadata.setTestName("copiedName");
        sourceMetadata.putVariableName(0, "count");
        sourceMetadata.putVariableName(1, "label");
        sourceMetadata.addCommentAfter(0, "source comment");
        sourceMetadata.addSectionBreakAfter(1);

        DefaultTestCase target = new DefaultTestCase();
        target.addStatement(new UninterpretedStatement(target, void.class, "System.gc();"));
        target.addStatement(new IntPrimitiveStatement(target, 7));

        TestPresentationMetadata.copyTo(source, target, 1);

        TestPresentationMetadata targetMetadata = TestPresentationMetadata.get(target);
        assertNotNull(targetMetadata);
        assertEquals("copiedName", targetMetadata.getTestName());
        assertEquals("count", targetMetadata.getVariableName(1));
        assertNull(targetMetadata.getVariableName(2), "out-of-range target position must be dropped");
        assertEquals("source comment", targetMetadata.getCommentsAfter(1).get(0));
        assertFalse(targetMetadata.hasSectionBreakAfter(2));
    }

    @Test
    void defaultTestCaseClone_preservesPostProcessingMetadata() {
        DefaultTestCase source = new DefaultTestCase();
        source.addStatement(new IntPrimitiveStatement(source, 7));
        source.addStatement(new StringPrimitiveStatement(source, "value"));
        TestPresentationMetadata sourceMetadata = TestPresentationMetadata.getOrCreate(source);
        sourceMetadata.setTestName("cloneKeepsReadableEdits");
        sourceMetadata.putVariableName(0, "count");
        sourceMetadata.putVariableName(1, "label");
        sourceMetadata.addCommentAfter(0, "Prepare the count.");
        sourceMetadata.addSectionBreakAfter(0);

        DefaultTestCase clone = source.clone();

        TestPresentationMetadata cloneMetadata = TestPresentationMetadata.get(clone);
        assertNotNull(cloneMetadata);
        assertEquals("cloneKeepsReadableEdits", cloneMetadata.getTestName());
        assertEquals("count", cloneMetadata.getVariableName(0));
        assertEquals("label", cloneMetadata.getVariableName(1));
        assertEquals("Prepare the count.", cloneMetadata.getCommentsAfter(0).get(0));
        assertTrue(cloneMetadata.hasSectionBreakAfter(0));

        TestCodeVisitor visitor = new TestCodeVisitor();
        clone.accept(visitor);
        String code = visitor.getCode();
        assertTrue(code.contains("int count = 7;"));
        assertTrue(code.contains("// Prepare the count."));
        assertTrue(code.contains("String label = \"value\";"));
    }

    @Test
    void metadataSnapshotRestoreReplacesMutableCollections() {
        TestPresentationMetadata metadata = new TestPresentationMetadata();
        metadata.setTestName("before");
        metadata.putVariableName(0, "count");
        metadata.addCommentAfter(0, "before comment");
        metadata.addSectionBreakAfter(0);
        TestPresentationMetadata snapshot = metadata.copy();

        metadata.setTestName("after");
        metadata.putVariableName(1, "other");
        metadata.addCommentAfter(0, "after comment");
        metadata.addSectionBreakAfter(1);
        metadata.replaceWith(snapshot);

        assertEquals("before", metadata.getTestName());
        assertEquals("count", metadata.getVariableName(0));
        assertNull(metadata.getVariableName(1));
        assertEquals(1, metadata.getCommentsAfter(0).size());
        assertEquals("before comment", metadata.getCommentsAfter(0).get(0));
        assertTrue(metadata.hasSectionBreakAfter(0));
        assertFalse(metadata.hasSectionBreakAfter(1));
    }

    @Test
    void defaultTestCaseSerialization_preservesPostProcessingMetadata() throws Exception {
        DefaultTestCase source = new DefaultTestCase();
        source.addStatement(new IntPrimitiveStatement(source, 7));
        source.addStatement(new StringPrimitiveStatement(source, "value"));
        TestPresentationMetadata sourceMetadata = TestPresentationMetadata.getOrCreate(source);
        sourceMetadata.setTestName("serializedReadableEdits");
        sourceMetadata.putVariableName(0, "count");
        sourceMetadata.putVariableName(1, "label");
        sourceMetadata.addCommentAfter(0, "Prepare the count.");
        sourceMetadata.addSectionBreakAfter(0);

        DefaultTestCase restored = roundTrip(source);

        TestPresentationMetadata restoredMetadata = TestPresentationMetadata.get(restored);
        assertNotNull(restoredMetadata);
        assertEquals("serializedReadableEdits", restoredMetadata.getTestName());
        assertEquals("count", restoredMetadata.getVariableName(0));
        assertEquals("label", restoredMetadata.getVariableName(1));
        assertEquals("Prepare the count.", restoredMetadata.getCommentsAfter(0).get(0));
        assertTrue(restoredMetadata.hasSectionBreakAfter(0));

        TestCodeVisitor visitor = new TestCodeVisitor();
        restored.accept(visitor);
        String code = visitor.getCode();
        assertTrue(code.contains("int count = 7;"));
        assertTrue(code.contains("// Prepare the count."));
        assertTrue(code.contains("String label = \"value\";"));
    }

    @Test
    void defaultTestCaseSerialization_preservesTemplateAssertions() throws Exception {
        DefaultTestCase source = new DefaultTestCase();
        source.addStatement(new IntPrimitiveStatement(source, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(source);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"variableNames\":{\"v0\":\"count\"},"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\","
                        + "\"purpose\":\"The value is stable.\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(source, references, response);

        DefaultTestCase restored = roundTrip(source);

        assertEquals(1, restored.getAssertions().size());
        Assertion assertion = restored.getAssertions().get(0);
        assertInstanceOf(TemplateCodeAssertion.class, assertion);
        TemplateCodeAssertion template = (TemplateCodeAssertion) assertion;
        assertEquals(TemplateCodeAssertion.Placement.END_OF_TEST, template.getPlacement());
        assertEquals("The value is stable.", template.getPurpose());
        assertDoesNotThrow(() -> template.changeClassLoader(getClass().getClassLoader()));

        TestCodeVisitor visitor = new TestCodeVisitor();
        restored.accept(visitor);
        assertTrue(visitor.getCode().contains("assertEquals(7, count); // The value is stable."),
                visitor.getCode());
    }

    @Test
    void templateAssertionsParticipateInTestCaseAssertionLifecycle() {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new IntPrimitiveStatement(test, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(test);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(test, references, response);

        assertTrue(test.hasAssertions());
        assertEquals(1, test.getAssertions().size());
        assertEquals(test.size() + 1, test.sizeWithAssertions());

        Assertion assertion = test.getAssertions().get(0);
        assertSame(test.getStatement(0), assertion.getStatement());
        assertInstanceOf(TemplateCodeAssertion.class, assertion);
        TemplateCodeAssertion template = (TemplateCodeAssertion) assertion;
        assertEquals(TemplateCodeAssertion.Placement.END_OF_TEST, template.getPlacement());
        assertTrue(template.isValid());

        test.removeAssertion(assertion);
        assertFalse(test.hasAssertions());

        apply(test, references, response);
        assertTrue(test.hasAssertions());
        test.removeAssertions();
        assertFalse(test.hasAssertions());
    }

    @Test
    void templateAssertionsCopyThroughAddAssertionsWithHostStatement() {
        DefaultTestCase source = new DefaultTestCase();
        source.addStatement(new IntPrimitiveStatement(source, 7));
        LlmPostProcessingReferences references = LlmPostProcessingReferences.from(source);
        LlmPostProcessingResponse response = LlmPostProcessingResponseParser.parse(
                "{\"schemaVersion\":2,"
                        + "\"assertions\":[{\"assertionId\":\"a0\",\"kind\":\"EQUALS\","
                        + "\"expected\":\"7\",\"actual\":\"v0\"}]}",
                references.toParseContext(PostProcessingOptions.fromProperties())).getResponse();
        apply(source, references, response);

        DefaultTestCase target = new DefaultTestCase();
        target.addStatement(new IntPrimitiveStatement(target, 7));
        target.addAssertions(source);

        assertEquals(1, target.getAssertions().size());
        Assertion copied = target.getAssertions().get(0);
        assertSame(target.getStatement(0), copied.getStatement());
        assertInstanceOf(TemplateCodeAssertion.class, copied);
        assertEquals(TemplateCodeAssertion.Placement.END_OF_TEST,
                ((TemplateCodeAssertion) copied).getPlacement());

        TestCodeVisitor visitor = new TestCodeVisitor();
        target.accept(visitor);
        assertTrue(visitor.getCode().contains("assertEquals(7, "));
        assertFalse(visitor.getCode().contains("assertEquals(7, v0);"));
    }

    private static DefaultTestCase roundTrip(DefaultTestCase source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(source);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (DefaultTestCase) input.readObject();
        }
    }
}
