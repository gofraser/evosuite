package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class TestCaseExecutorStuckStackTest {

    private static StackTraceElement frame(String method, int line) {
        return new StackTraceElement("a.B", method, "B.java", line);
    }

    @Test
    public void testDeepRecursionIsFoldedIntoOneLine() {
        StackTraceElement[] stack = new StackTraceElement[75000];
        stack[0] = frame("top", 1);
        for (int i = 1; i < stack.length - 1; i++) {
            stack[i] = frame("recurse", 1658);
        }
        stack[stack.length - 1] = frame("bottom", 9);

        List<String> lines = TestCaseExecutor.summarizeStuckStack(stack);

        Assertions.assertEquals(3, lines.size());
        Assertions.assertTrue(lines.get(1).contains("and 74997 identical frames more"), lines.get(1));
    }

    @Test
    public void testLongStackKeepsTopAndBottom() {
        StackTraceElement[] stack = new StackTraceElement[500];
        for (int i = 0; i < stack.length; i++) {
            stack[i] = frame("m" + i, i);
        }

        List<String> lines = TestCaseExecutor.summarizeStuckStack(stack);

        Assertions.assertEquals(TestCaseExecutor.MAX_LOGGED_TOP_FRAMES
                + TestCaseExecutor.MAX_LOGGED_BOTTOM_FRAMES + 1, lines.size());
        Assertions.assertTrue(lines.get(0).contains("m0"));
        Assertions.assertTrue(lines.get(lines.size() - 1).contains("m499"));
        Assertions.assertTrue(lines.contains("... 450 more frames omitted ..."), lines.toString());
    }

    @Test
    public void testShortStackIsKeptAsIs() {
        StackTraceElement[] stack = {frame("a", 1), frame("b", 2), frame("a", 1)};

        Assertions.assertEquals(3, TestCaseExecutor.summarizeStuckStack(stack).size());
    }
}
