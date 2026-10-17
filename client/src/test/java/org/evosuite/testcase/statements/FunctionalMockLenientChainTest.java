package org.evosuite.testcase.statements;

import org.evosuite.runtime.LenientMockAnswer;
import org.evosuite.runtime.instrumentation.RemoveFinalClassAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicInteger;

public class FunctionalMockLenientChainTest {

    public interface Node {
        Node parent();
    }

    public interface Builder {
        Builder with(String value);

        String build();
    }

    public abstract static class SelfCallingBase {
        private final String name;

        protected SelfCallingBase() {
            name = describe();
        }

        public abstract String describe();

        public String name() {
            return name;
        }
    }

    public static class SelfCallingConcrete {
        private final int size;

        public SelfCallingConcrete() {
            size = computeSize();
        }

        public int computeSize() {
            return 1;
        }

        @Override
        public int hashCode() {
            return computeSize();
        }

        @Override
        public boolean equals(Object other) {
            return other == this;
        }
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

        Assertions.assertEquals(LenientMockAnswer.MAX_CHAIN + 1, length);
    }

    /**
     * The lenient answer hands out a mock only when it is called, so a fluent chain is as long
     * as the code under test makes it, up to the chain bound: nothing recurses while a mock is
     * created.
     */
    @Test
    public void testFluentChainIsBoundedByTheChainLimitOnly() {
        Builder builder = Mockito.mock(Builder.class,
                (org.mockito.stubbing.Answer<?>) FunctionalMockStatement::lenientDefaultAnswer);

        Builder current = builder;
        for (int i = 0; i < LenientMockAnswer.MAX_CHAIN; i++) {
            current = current.with("x");
            Assertions.assertNotNull(current, "call " + (i + 1) + " of the fluent chain");
        }
        Assertions.assertNull(current.with("x"));
        Assertions.assertEquals("", builder.with("x").build());
    }

    /**
     * Mockito creates a mock without calling any of its methods, so the lenient answer never
     * runs while a mock it hands out is being created: it is not re-entered on one thread.
     */
    @Test
    public void testCreatingAMockDoesNotCallItsAnswer() {
        for (Class<?> type : new Class<?>[]{Node.class, Builder.class, SelfCallingBase.class,
                SelfCallingConcrete.class}) {
            AtomicInteger calls = new AtomicInteger();
            Answer<Object> counting = invocation -> {
                calls.incrementAndGet();
                return FunctionalMockStatement.lenientDefaultAnswer(invocation);
            };
            Object mock = Mockito.mock(type, counting);
            Assertions.assertNotNull(mock);
            Assertions.assertEquals(0, calls.get(), "answers while a mock of " + type.getSimpleName()
                    + " was created");
        }
    }

    public static class Stripped {
    }

    public interface StrippedSource {
        Stripped stripped();
    }

    /**
     * A class that was final before instrumentation is not handed out as a mock during the
     * search, as the test JVM cannot mock it; the answer of the generated test does not know
     * about instrumentation.
     */
    @Test
    public void testOriginallyFinalReturnTypeIsAnsweredWithNull() {
        RemoveFinalClassAdapter.finalClasses.add(Stripped.class.getName());
        try {
            StrippedSource search = Mockito.mock(StrippedSource.class,
                    (org.mockito.stubbing.Answer<?>) FunctionalMockStatement::lenientDefaultAnswer);
            StrippedSource replay = Mockito.mock(StrippedSource.class, new LenientMockAnswer());

            Assertions.assertNull(search.stripped());
            Assertions.assertNotNull(replay.stripped());
        } finally {
            RemoveFinalClassAdapter.finalClasses.remove(Stripped.class.getName());
        }
    }
}
