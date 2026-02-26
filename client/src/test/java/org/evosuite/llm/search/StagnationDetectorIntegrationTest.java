package org.evosuite.llm.search;

import org.evosuite.Properties;
import org.evosuite.llm.*;
import org.evosuite.llm.mock.MockChatLanguageModel;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StagnationDetectorIntegrationTest {

    private static final String SIMPLE_JUNIT_RESPONSE =
            "```java\n" +
                    "import org.junit.Test;\n" +
                    "public class GeneratedLlmTest {\n" +
                    "  @Test\n" +
                    "  public void generatedTest() {\n" +
                    "  }\n" +
                    "}\n" +
                    "```";

    @Test
    void triggersAndRequestsHelpAfterStagnation() {
        MockChatLanguageModel model = new MockChatLanguageModel();
        model.enqueue(LlmFeature.STAGNATION, SIMPLE_JUNIT_RESPONSE);
        LlmService service = createService(model, 2);

        TestFitnessFunction goal = mock(TestFitnessFunction.class);
        when(goal.toString()).thenReturn("uncovered-goal");

        StagnationDetector detector = new StagnationDetector(service, false, 1, 1);
        try {
            assertFalse(detector.checkStagnation(1.0));
            assertTrue(detector.checkStagnation(1.0));

            List<TestChromosome> help = detector.requestHelp(
                    Collections.singleton(goal),
                    Collections.singletonList(new TestChromosome()));
            assertFalse(help.isEmpty(), "stagnation detector should inject at least one chromosome");
        } finally {
            service.close();
        }
    }

    private static LlmService createService(LlmService.ChatLanguageModel model, int budget) {
        LlmConfiguration configuration = new LlmConfiguration(
                Properties.LlmProvider.NONE,
                "mock",
                "",
                "",
                0.0,
                1024,
                2,
                0,
                1,
                false,
                Paths.get("target/llm-test-traces"),
                "stagnation-integration");
        return new LlmService(model,
                new LlmBudgetCoordinator.Local(budget),
                configuration,
                new LlmStatistics(),
                new LlmTraceRecorder(configuration));
    }
}
