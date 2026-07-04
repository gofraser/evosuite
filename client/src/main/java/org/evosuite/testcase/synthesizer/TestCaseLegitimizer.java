package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.testcase.MutationPositionDiscriminator;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.testcase.synthesizer.var.VarRelevance;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local search that makes a synthesized object-construction template executable up to its target
 * method call (paper §3.3). A synthesized template frequently throws before reaching
 * the target call (e.g., an NPE from a not-yet-initialized field); the legitimizer runs a small
 * niching GA that mutates the primitive/null "slots" preceding the throwing statement until the
 * test executes through to the target call.
 *
 * <p>This is the simplified variant: the legitimacy
 * distance is the coarse "number of statements not executed before the target call". The fork's
 * finer branch-fitness refinement (which relied on the flag-branch fitness and a
 * fully-instrumented auxiliary class loader for dependency classes — infrastructure that does not
 * exist upstream) is deferred; see EVOOBJ-NOTES. The coarse distance still reaches 0 exactly when
 * the target call executes, and the niching selection provides the search gradient.
 */
public class TestCaseLegitimizer {

    private static final Logger logger = LoggerFactory.getLogger(TestCaseLegitimizer.class);

    /** Start of the overall legitimization phase; set by the GAs (M5), read for the total budget. */
    public static long startTime;

    public static int optimizionPopulationSize = 20;

    private PartialGraph graph;
    private Map<DepVariableWrapper, VarRelevance> graph2CodeMap;

    private static final TestCaseLegitimizer legitimizer = new TestCaseLegitimizer();

    private TestCaseLegitimizer() {
    }

    public static TestCaseLegitimizer getInstance() {
        return legitimizer;
    }

    /**
     * Runs the local legitimization search on {@code testChromosome}. Returns the best individual
     * found (distance 0 if legitimization succeeded), or {@code null} if the test does not contain
     * a target-method call.
     */
    public TestChromosome legitimize(TestChromosome testChromosome, PartialGraph graph,
            Map<DepVariableWrapper, VarRelevance> graph2CodeMap) {
        this.graph = graph;
        this.graph2CodeMap = graph2CodeMap;

        TestCase test = testChromosome.getTestCase();
        MethodStatement targetCallStat = test.findTargetMethodCallStatement();
        if (targetCallStat == null) {
            return null;
        }

        double legitimacyDistance = testChromosome.getLegitimacyDistance();
        if (legitimacyDistance == 0) {
            return testChromosome;
        }

        List<TestChromosome> population = initializePopulation(test);

        MutationPositionDiscriminator.discriminator.resetFrozenIteration();
        long t1 = System.currentTimeMillis();
        long t2 = t1;
        while (legitimacyDistance != 0
                && (t2 - t1) < Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET * 1000L
                && TimeController.getInstance().isThereStillTimeInThisPhase()) {
            evolve(population);
            legitimacyDistance = population.get(0).getLegitimacyDistance();
            t2 = System.currentTimeMillis();
            MutationPositionDiscriminator.discriminator.decreaseFrozenIteration();
        }

        return population.get(0);
    }

    private List<TestChromosome> initializePopulation(TestCase test) {
        List<TestChromosome> population = new ArrayList<>();
        for (int i = 0; i < optimizionPopulationSize; i++) {
            TestCase copy = test.clone();
            TestChromosome t = new TestChromosome();
            t.setTestCase(copy);
            population.add(t);
        }
        return population;
    }

    /**
     * Coarse legitimacy distance: {@code position(targetCall) - executedStatements + 1},
     * clamped at 0. It is 0 exactly when execution reaches the target-method call (including the
     * case where the target call itself throws).
     */
    public void updateLegitimacyDistance(TestChromosome individual) {
        TestCase test = individual.getTestCase();
        MethodStatement targetCallStat = test.findTargetMethodCallStatement();
        if (targetCallStat == null) {
            individual.setLegitimacyDistance(0);
            return;
        }

        if (individual.getLastExecutionResult() == null) {
            ExecutionResult result = TestCaseExecutor.runTest(individual.getTestCase());
            individual.setLastExecutionResult(result);
        }

        int numOfExecutedStatements = individual.getLastExecutionResult().getExecutedStatements();
        double legitimacyDistance = targetCallStat.getPosition() - numOfExecutedStatements + 1;
        if (legitimacyDistance < 0) {
            legitimacyDistance = 0;
        }
        individual.setLegitimacyDistance(legitimacyDistance);

        Integer exceptionPosition = individual.getLastExecutionResult().getFirstPositionOfThrownException();
        if (exceptionPosition != null && exceptionPosition == targetCallStat.getPosition()) {
            individual.setLegitimacyDistance(0);
        }
    }

    private void evolve(List<TestChromosome> population) {
        List<TestChromosome> newPopulation = breedOffSpring(population);
        population.addAll(newPopulation);
        select(population);
    }

    private List<TestChromosome> breedOffSpring(List<TestChromosome> population) {
        List<TestChromosome> newPop = new ArrayList<>();

        for (TestChromosome parent : population) {
            updateLegitimacyDistance(parent);
            TestChromosome offspring = parent.clone();
            mutateRelevantStatements(offspring);
            offspring.clearCachedResults();
            updateLegitimacyDistance(offspring);
            newPop.add(offspring);
        }

        return newPop;
    }

    /**
     * Niching selection: individuals are bucketed by (exception position, exception cause); the
     * best of each bucket is picked round-robin. This preserves diversity of failure modes so the
     * search does not collapse onto a single locally-optimal exception.
     */
    private void select(List<TestChromosome> population) {
        Map<String, List<TestChromosome>> exceptionBucket = new LinkedHashMap<>();

        for (TestChromosome individual : population) {
            ExecutionResult result = individual.getLastExecutionResult();
            Integer exceptionPosition = result == null ? null : result.getFirstPositionOfThrownException();
            if (exceptionPosition != null) {
                Throwable t = result.getExceptionThrownAtPosition(exceptionPosition);
                String cause;
                if (t != null && t.getStackTrace().length > 0) {
                    cause = t.getStackTrace()[0].getClassName() + "@" + t.getStackTrace()[0].getLineNumber();
                } else if (t != null) {
                    cause = t.getCause() == null ? t.getClass().getCanonicalName() : String.valueOf(t.getCause());
                } else {
                    cause = "null";
                }
                String message = exceptionPosition + ":" + cause;

                exceptionBucket.computeIfAbsent(message, k -> new ArrayList<>()).add(individual);
            } else {
                // At least one individual reached the target call: rank by distance and stop.
                population.sort(new PopulationComparator());
                // Trim so the population cannot grow across iterations on this early-return path.
                if (population.size() > optimizionPopulationSize) {
                    population.subList(optimizionPopulationSize, population.size()).clear();
                }
                return;
            }
        }

        for (List<TestChromosome> list : exceptionBucket.values()) {
            list.sort(new PopulationComparator());
        }

        List<TestChromosome> newPop = new ArrayList<>();
        int frontier = 0;
        while (newPop.size() < optimizionPopulationSize && !exceptionBucket.isEmpty()) {
            int addedThisPass = 0;
            for (List<TestChromosome> list : exceptionBucket.values()) {
                if (list.size() > frontier) {
                    newPop.add(list.get(frontier));
                    addedThisPass++;
                }
                if (newPop.size() >= optimizionPopulationSize) {
                    break;
                }
            }
            if (addedThisPass == 0) {
                // all buckets exhausted before reaching the target size
                break;
            }
            frontier++;
        }

        population.clear();
        population.addAll(newPop);
        population.sort(new PopulationComparator());
    }

    private void mutateRelevantStatements(TestChromosome individual) {
        Statement statOfExp = individual.getStatementReportingException();
        if (statOfExp == null) {
            return;
        }

        TestCase test = individual.getTestCase();
        double[] mutationProbability = MutationPositionDiscriminator.calculateMutationProbability(individual);
        MutationPositionDiscriminator.normalizeProbability(mutationProbability);
        List<Statement> influencingStatements = checkInfluencingStatements(test, statOfExp);

        for (Statement refStatement : influencingStatements) {
            Statement statement = test.getStatement(refStatement.getPosition());

            TestFactory testFactory = TestFactory.getInstance();

            if (statement.isReflectionStatement()) {
                continue;
            }

            int oldDistance = statement.getReturnValue().getDistance();
            if (statement instanceof NullStatement) {
                int pos = statement.getPosition();
                if (pos + 1 < test.size()) {
                    Statement nextStatement = test.getStatement(pos + 1);
                    if (testFactory.changeNullStatement(test, statement)) {
                        statement = test.getStatement(nextStatement.getPosition() - 1);
                    }
                } else {
                    // Trailing null statement: no following statement to track, so just replace it.
                    testFactory.changeNullStatement(test, statement);
                    statement = test.getStatement(test.size() - 1);
                }
            }

            double dice = Randomness.nextDouble();
            MutationPositionDiscriminator.discriminator.setPurpose(individual.getFitnessValues());
            double mutationProb = MutationPositionDiscriminator.discriminator.isFrozen()
                    ? 0.5 : mutationProbability[refStatement.getPosition()];
            if (dice > mutationProb) {
                continue;
            }

            boolean reuse = Randomness.nextBoolean();
            if (reuse) {
                if (statement instanceof PrimitiveStatement) {
                    @SuppressWarnings("rawtypes")
                    PrimitiveStatement pStatement = (PrimitiveStatement) statement;
                    List<Object> candidates = searchForReusableVariables(influencingStatements,
                            pStatement.getReturnType());
                    if (!candidates.isEmpty()) {
                        Object value = Randomness.choice(candidates);
                        pStatement.setValue(value);
                    }
                }
            } else {
                try {
                    statement.mutate(test, testFactory);
                } catch (Exception e) {
                    logger.debug("Legitimization mutation failed: {}", e.toString());
                }
            }

            statement.getReturnValue().setDistance(oldDistance);
        }
    }

    private List<Statement> checkInfluencingStatements(TestCase test, Statement statOfExp) {
        List<Statement> influencingStatements = new ArrayList<>();
        for (int i = 0; i < test.size(); i++) {
            Statement statement = test.getStatement(i);
            if ((statement instanceof PrimitiveStatement || statement instanceof AssignmentStatement)
                    && statement.getPosition() <= statOfExp.getPosition()) {
                influencingStatements.add(statement);
            }
        }

        return influencingStatements;
    }

    private List<Object> searchForReusableVariables(List<Statement> influencingStatements, Type returnType) {
        List<Object> list = new ArrayList<>();
        for (Statement stat : influencingStatements) {
            if (stat.getReturnType().equals(returnType)) {
                if (stat instanceof PrimitiveStatement) {
                    list.add(((PrimitiveStatement<?>) stat).getValue());
                }
            } else if (stat instanceof AssignmentStatement) {
                AssignmentStatement aStat = (AssignmentStatement) stat;
                VariableReference varRef = aStat.getReturnValue();
                if (varRef instanceof ArrayIndex && returnType.toString().equals("int")) {
                    ArrayIndex index = (ArrayIndex) varRef;
                    list.add(index.getArrayIndex());
                }
            }
        }
        return list;
    }

    static class PopulationComparator implements Comparator<TestChromosome> {
        @Override
        public int compare(TestChromosome o1, TestChromosome o2) {
            return Double.compare(o1.getLegitimacyDistance(), o2.getLegitimacyDistance());
        }
    }

    public PartialGraph getGraph() {
        return graph;
    }

    public Map<DepVariableWrapper, VarRelevance> getGraph2CodeMap() {
        return graph2CodeMap;
    }
}
