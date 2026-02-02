package org.evosuite.coverage.line;

import org.evosuite.Properties;
import org.evosuite.ga.archive.Archive;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.testsuite.TestSuiteFitnessFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Abstract fitness function for a whole test suite for all lines.
 */
public abstract class AbstractLineCoverageSuiteFitness extends TestSuiteFitnessFunction {

    private static final long serialVersionUID = -6369027784777941998L;

    protected static final Logger logger = LoggerFactory.getLogger(AbstractLineCoverageSuiteFitness.class);

    // target goals
    protected final int numLines;
    protected final Map<Integer, TestFitnessFunction> lineGoals = new LinkedHashMap<>();

    protected final Set<Integer> removedLines = new LinkedHashSet<>();
    protected final Set<Integer> toRemoveLines = new LinkedHashSet<>();

    // Some stuff for debug output
    private int maxCoveredLines = 0;
    private double bestFitness = Double.MAX_VALUE;

    public AbstractLineCoverageSuiteFitness() {
        List<LineCoverageTestFitness> goals = new LineCoverageFactory().getCoverageGoals();
        for (LineCoverageTestFitness goal : goals) {
            lineGoals.put(goal.getLine(), goal);
            if (Properties.TEST_ARCHIVE)
                Archive.getArchiveInstance().addTarget(goal);
        }
        this.numLines = lineGoals.size();
        logger.info("Total line coverage goals: " + this.numLines);
    }

    @Override
    public boolean updateCoveredGoals() {
        if (!Properties.TEST_ARCHIVE) {
            return false;
        }

        for (Integer goalID : this.toRemoveLines) {
            TestFitnessFunction ff = this.lineGoals.remove(goalID);
            if (ff != null) {
                this.removedLines.add(goalID);
            } else {
                throw new IllegalStateException("goal to remove not found");
            }
        }

        this.toRemoveLines.clear();
        logger.debug("Current state of archive: " + Archive.getArchiveInstance().toString());

        assert this.numLines == this.lineGoals.size() + this.removedLines.size();

        return true;
    }

    /**
     * Iterate over all execution results and summarize statistics
     *
     * @param results
     * @param coveredLines
     * @return
     */
    protected boolean analyzeTraces(List<ExecutionResult> results, Set<Integer> coveredLines) {
        boolean hasTimeoutOrTestException = false;

        for (ExecutionResult result : results) {
            if (result.hasTimeout() || result.hasTestException()) {
                hasTimeoutOrTestException = true;
                continue;
            }

            TestChromosome test = new TestChromosome();
            test.setTestCase(result.test);
            test.setLastExecutionResult(result);
            test.setChanged(false);

            for (Integer goalID : this.lineGoals.keySet()) {
                TestFitnessFunction goal = this.lineGoals.get(goalID);

                double fit = goal.getFitness(test, result); // archive is updated by the TestFitnessFunction class

                if (fit == 0.0) {
                    coveredLines.add(goalID); // helper to count the number of covered goals
                    this.toRemoveLines.add(goalID); // goal to not be considered by the next iteration of the evolutionary algorithm
                }
            }
        }

        return hasTimeoutOrTestException;
    }

    /**
     * Hook to provide additional fitness guidance (e.g., control dependencies).
     * @param results the execution results
     * @return the additional fitness value (default 0.0)
     */
    protected double getAdditionalFitness(List<ExecutionResult> results) {
        return 0.0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Execute all tests and count covered lines
     */
    @Override
    public double getFitness(TestSuiteChromosome suite) {
        logger.trace("Calculating line coverage fitness");
        double fitness = 0.0;

        List<ExecutionResult> results = runTestSuite(suite);

        // Add additional guidance (e.g., control dependencies)
        double additionalFitness = getAdditionalFitness(results);
        fitness += additionalFitness;
        if (additionalFitness > 0) {
            logger.debug("Additional guidance fitness: " + additionalFitness);
        }

        Set<Integer> coveredLines = new LinkedHashSet<>();
        boolean hasTimeoutOrTestException = analyzeTraces(results, coveredLines);

        int totalLines = this.numLines;
        int numCoveredLines = coveredLines.size() + this.removedLines.size();

        logger.debug("Covered " + numCoveredLines + " out of " + totalLines + " lines, " + removedLines.size() + " in archive");
        fitness += normalize(totalLines - numCoveredLines);

        printStatusMessages(suite, numCoveredLines, fitness);

        if (totalLines > 0)
            suite.setCoverage(this, (double) numCoveredLines / (double) totalLines);
        else
            suite.setCoverage(this, 1.0);

        suite.setNumOfCoveredGoals(this, numCoveredLines);
        suite.setNumOfNotCoveredGoals(this, totalLines - numCoveredLines);

        if (hasTimeoutOrTestException) {
            logger.info("Test suite has timed out, setting fitness to max value " + totalLines);
            fitness = totalLines;
        }

        updateIndividual(suite, fitness);

        assert (numCoveredLines <= totalLines) : "Covered " + numCoveredLines + " vs total goals " + totalLines;
        assert (fitness >= 0.0);
        assert (fitness != 0.0 || numCoveredLines == totalLines) : "Fitness: " + fitness + ", "
                + "coverage: " + numCoveredLines + "/" + totalLines;
        assert (suite.getCoverage(this) <= 1.0) && (suite.getCoverage(this) >= 0.0) : "Wrong coverage value "
                + suite.getCoverage(this);

        return fitness;
    }

    /**
     * Some useful debug information
     *
     * @param coveredLines
     * @param fitness
     */
    protected void printStatusMessages(TestSuiteChromosome suite,
                                     int coveredLines, double fitness) {
        if (coveredLines > maxCoveredLines) {
            maxCoveredLines = coveredLines;
            logger.info("(Lines) Best individual covers " + coveredLines + "/"
                    + this.numLines + " lines");
            logger.info("Fitness: " + fitness + ", size: " + suite.size() + ", length: "
                    + suite.totalLengthOfTestCases());
        }

        if (fitness < bestFitness) {
            logger.info("(Fitness) Best individual covers " + coveredLines + "/"
                    + this.numLines + " lines");
            bestFitness = fitness;
            logger.info("Fitness: " + fitness + ", size: " + suite.size() + ", length: "
                    + suite.totalLengthOfTestCases());

        }
    }
}
