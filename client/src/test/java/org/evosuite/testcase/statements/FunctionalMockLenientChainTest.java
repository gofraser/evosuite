package org.evosuite.testcase.statements;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class FunctionalMockLenientChainTest {

    public interface Node {
        Node parent();
    }

    private static int chainLength(Node start) {
        int length = 0;
        for (Node n = start; n != null; n = n.parent()) {
            length++;
            Assertions.assertTrue(length < 10_000, "the chain of mocked parents never ends");
        }
        return length;
    }

    @Test
    public void testChainOfMockedParentsEnds() {
        Node leaf = Mockito.mock(Node.class,
                (org.mockito.stubbing.Answer<?>) FunctionalMockStatement::lenientDefaultAnswer);

        int length = chainLength(leaf);

        Assertions.assertEquals(FunctionalMockStatement.MAX_LENIENT_MOCK_CHAIN + 1, length);
    }
}
