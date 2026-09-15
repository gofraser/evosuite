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
package org.evosuite.llm.prompt;

import org.evosuite.llm.search.ProblemCard;
import org.evosuite.llm.search.ProblemCardFormatter;
import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the detail-line rendering shared by operator targets and problem
 * cards: evidence capping, concrete-example resolution (existing test
 * reference vs. inline code), the suggested-action line, and the identity
 * helpers used to recognise a test across copies.
 */
class TargetSectionTest {

    private static TestCase testCase(String marker) {
        DefaultTestCase test = new DefaultTestCase();
        test.addStatement(new StringPrimitiveStatement(test, marker));
        return test;
    }

    private static TestCase testCaseWithStatements(int count) {
        DefaultTestCase test = new DefaultTestCase();
        for (int i = 0; i < count; i++) {
            test.addStatement(new StringPrimitiveStatement(test, "statement-" + i));
        }
        return test;
    }

    private static ProblemCard.ConcreteExample onlyExample(TestCase testCase, String description) {
        return ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("t")
                .concreteExample(testCase, description)
                .build()
                .getConcreteExamples()
                .get(0);
    }

    // ---- evidence capping ----------------------------------------------------

    @Test
    void evidenceIsCappedAtMaxEvidence() {
        List<String> evidence = Arrays.asList("l1", "l2", "l3", "l4", "l5");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, evidence, 3, Collections.emptyList(), Collections.emptyList(), "");

        String expected = TargetSection.DETAIL_PREFIX + "l1\n"
                + TargetSection.DETAIL_PREFIX + "l2\n"
                + TargetSection.DETAIL_PREFIX + "l3\n";
        assertEquals(expected, sb.toString());
    }

    @Test
    void maxIntegerRendersAllEvidenceLines() {
        List<String> evidence = Arrays.asList("l1", "l2", "l3", "l4", "l5");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, evidence, Integer.MAX_VALUE, Collections.emptyList(),
                Collections.emptyList(), "");

        for (String line : evidence) {
            assertTrue(sb.toString().contains(TargetSection.DETAIL_PREFIX + line));
        }
        assertEquals(5, sb.toString().split("\n").length);
    }

    // ---- concrete example: existing-test reference ----------------------------

    @Test
    void concreteExampleMatchingExistingTestByIdentityIsReferencedByNumber() {
        TestCase exemplar = testCase("marker");
        List<TestCase> existingTests = Collections.singletonList(exemplar);
        ProblemCard.ConcreteExample example = onlyExample(exemplar, "desc");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, Collections.emptyList(), 3,
                Collections.singletonList(example), existingTests, "");

        String expected = TargetSection.DETAIL_PREFIX + "desc"
                + String.format(TargetSection.SEE_EXISTING_TEST_FORMAT, 1) + "\n";
        assertEquals(expected, sb.toString());
    }

    @Test
    void concreteExampleMatchingExistingTestByEqualCodeIsReferencedByNumber() {
        TestCase exemplar = testCase("same-code");
        TestCase equalCodeCopy = testCase("same-code");
        List<TestCase> existingTests = Collections.singletonList(equalCodeCopy);
        ProblemCard.ConcreteExample example = onlyExample(exemplar, "desc");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, Collections.emptyList(), 3,
                Collections.singletonList(example), existingTests, "");

        String expected = TargetSection.DETAIL_PREFIX + "desc"
                + String.format(TargetSection.SEE_EXISTING_TEST_FORMAT, 1) + "\n";
        assertEquals(expected, sb.toString());
    }

    // ---- concrete example: inline rendering ------------------------------------

    @Test
    void concreteExampleNotAmongExistingTestsIsInlined() {
        TestCase notExisting = testCaseWithStatements(3);
        ProblemCard.ConcreteExample example = onlyExample(notExisting, "desc");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, Collections.emptyList(), 3,
                Collections.singletonList(example), Collections.emptyList(), "");

        String rendered = sb.toString();
        assertTrue(rendered.startsWith(TargetSection.DETAIL_PREFIX + "desc"
                + String.format(TargetSection.INLINE_EXAMPLE_FORMAT, TargetSection.INLINE_EXAMPLE_STATEMENTS)));
        assertTrue(rendered.contains("```java\n"));
        assertTrue(rendered.contains("statement-0"));
        assertFalse(rendered.contains(TargetSection.TRUNCATION_MARKER),
                "3 statements is below the inline cap, so no truncation marker should appear");
    }

    @Test
    void inlinedExampleWithMoreThanEightStatementsIsTruncated() {
        TestCase notExisting = testCaseWithStatements(10);
        ProblemCard.ConcreteExample example = onlyExample(notExisting, "desc");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, Collections.emptyList(), 3,
                Collections.singletonList(example), Collections.emptyList(), "");

        String rendered = sb.toString();
        assertTrue(rendered.contains("statement-7"));
        assertFalse(rendered.contains("statement-8"));
        assertTrue(rendered.contains(TargetSection.TRUNCATION_MARKER));
    }

    @Test
    void inlinedExampleWithFewerThanEightStatementsIsNotTruncated() {
        TestCase notExisting = testCaseWithStatements(5);
        ProblemCard.ConcreteExample example = onlyExample(notExisting, "desc");
        StringBuilder sb = new StringBuilder();

        TargetSection.appendDetails(sb, Collections.emptyList(), 3,
                Collections.singletonList(example), Collections.emptyList(), "");

        assertFalse(sb.toString().contains(TargetSection.TRUNCATION_MARKER));
    }

    // ---- suggested action -------------------------------------------------------

    @Test
    void emptyActionHintEmitsNoSuggestedActionLine() {
        StringBuilder sb = new StringBuilder();
        TargetSection.appendDetails(sb, Collections.emptyList(), 3, Collections.emptyList(),
                Collections.emptyList(), "");
        assertEquals("", sb.toString());
    }

    @Test
    void nullActionHintEmitsNoSuggestedActionLine() {
        StringBuilder sb = new StringBuilder();
        TargetSection.appendDetails(sb, Collections.emptyList(), 3, Collections.emptyList(),
                Collections.emptyList(), null);
        assertEquals("", sb.toString());
    }

    @Test
    void nonEmptyActionHintEmitsSuggestedActionLine() {
        StringBuilder sb = new StringBuilder();
        TargetSection.appendDetails(sb, Collections.emptyList(), 3, Collections.emptyList(),
                Collections.emptyList(), "do the thing");

        String expected = TargetSection.DETAIL_PREFIX + TargetSection.SUGGESTED_ACTION_PREFIX
                + "do the thing" + "\n";
        assertEquals(expected, sb.toString());
    }

    // ---- indexOfTest / canonicalCode --------------------------------------------

    @Test
    void indexOfTestReturnsMinusOneForNullList() {
        assertEquals(-1, TargetSection.indexOfTest(null, testCase("x")));
    }

    @Test
    void indexOfTestReturnsMinusOneForNullTarget() {
        assertEquals(-1, TargetSection.indexOfTest(Collections.singletonList(testCase("x")), null));
    }

    @Test
    void canonicalCodeCollapsesWhitespace() {
        TestCase multiStatement = testCaseWithStatements(3);
        String canonical = TargetSection.canonicalCode(multiStatement);

        assertFalse(canonical.contains("\n"), "newlines between statements must collapse to spaces");
        assertFalse(canonical.contains("  "), "consecutive whitespace must collapse to a single space");
        assertEquals(canonical.trim(), canonical, "canonical code must be trimmed");
    }

    @Test
    void canonicalCodeOfNullIsEmpty() {
        assertEquals("", TargetSection.canonicalCode(null));
    }

    @Test
    void canonicalCodeDistinguishesStringLiteralsDifferingOnlyByInnerWhitespace() {
        TestCase singleSpace = testCase("a b");
        TestCase doubleSpace = testCase("a  b");

        assertFalse(TargetSection.canonicalCode(singleSpace).equals(TargetSection.canonicalCode(doubleSpace)),
                "tests whose only difference is whitespace inside a string literal must not collide");
    }

    // ---- normaliseWhitespaceOutsideLiterals -------------------------------------

    @Test
    void normaliseCollapsesWhitespaceOutsideLiterals() {
        assertEquals("int x = 1;",
                TargetSection.normaliseWhitespaceOutsideLiterals("int  x =\n 1;"));
    }

    @Test
    void normalisePreservesInnerWhitespaceOfStringLiterals() {
        assertEquals("\"a b\"", TargetSection.normaliseWhitespaceOutsideLiterals("\"a b\""));
        assertEquals("\"a  b\"", TargetSection.normaliseWhitespaceOutsideLiterals("\"a  b\""));
    }

    @Test
    void normaliseKeepsEscapedQuoteInsideStringLiteralAndResumesAfterClosingQuote() {
        String input = "\"say \\\"hi  there\\\"\"  int  x = 1;";
        String expected = "\"say \\\"hi  there\\\"\" int x = 1;";

        assertEquals(expected, TargetSection.normaliseWhitespaceOutsideLiterals(input));
    }

    @Test
    void normalisePreservesSpaceInsideCharLiteral() {
        assertEquals("' '", TargetSection.normaliseWhitespaceOutsideLiterals("' '"));
    }

    @Test
    void normaliseHandlesBackslashEscapedBackslashBeforeClosingQuote() {
        String input = "\"x\\\\\"";

        assertEquals(input, TargetSection.normaliseWhitespaceOutsideLiterals(input));
    }

    @Test
    void normaliseCollapsesTabsAndNewlinesOutsideLiterals() {
        assertEquals("a b c",
                TargetSection.normaliseWhitespaceOutsideLiterals("a\tb\n\nc"));
    }

    @Test
    void canonicalCodeTrimsLeadingAndTrailingWhitespace() {
        assertEquals("x", TargetSection.normaliseWhitespaceOutsideLiterals("  x  ").trim());
    }

    @Test
    void normaliseCopiesUnterminatedLiteralVerbatimToTheEnd() {
        String input = "int x = \"unterminated  literal";

        assertEquals(input, TargetSection.normaliseWhitespaceOutsideLiterals(input));
    }

    // ---- ProblemCardFormatter full-text rendering --------------------------------

    @Test
    void problemCardFormatterRendersExactExpectedText() {
        TestCase exemplar = testCase("marker");
        List<TestCase> existingTests = Collections.singletonList(exemplar);

        ProblemCard card = ProblemCard.builder(ProblemCardType.UNREACHED_METHOD)
                .title("Method not reached")
                .evidence(Arrays.asList("evidence one", "evidence two"))
                .relatedGoals(Collections.emptyList())
                .impact(1.0).blockage(1.0).confidence(1.0)
                .concreteExample(exemplar, "Concrete desc")
                .build();

        String formatted = ProblemCardFormatter.format(Collections.singletonList(card), existingTests);

        String expected = String.join("\n",
                "1. [UNREACHED_METHOD] Method not reached (priority=1.000, goals=0)",
                TargetSection.DETAIL_PREFIX + "evidence one",
                TargetSection.DETAIL_PREFIX + "evidence two",
                TargetSection.DETAIL_PREFIX + "Concrete desc"
                        + String.format(TargetSection.SEE_EXISTING_TEST_FORMAT, 1),
                TargetSection.DETAIL_PREFIX + TargetSection.SUGGESTED_ACTION_PREFIX
                        + ProblemCardType.UNREACHED_METHOD.getActionHint());

        assertEquals(expected, formatted);
    }
}
