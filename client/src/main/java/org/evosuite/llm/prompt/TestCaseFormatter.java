package org.evosuite.llm.prompt;

import org.evosuite.testcase.TestCase;

/**
 * Converts EvoSuite test cases into prompt-friendly text.
 */
public class TestCaseFormatter {

    public String format(TestCase testCase) {
        if (testCase == null) {
            return "";
        }
        return testCase.toCode();
    }
}
