package org.evosuite.testcase.localsearch;

import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.numeric.NumericalPrimitiveStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for local search on numerical values.
 * Provides common slicing optimization and AVM iteration logic.
 */
public abstract class NumericalLocalSearch<T> extends StatementLocalSearch {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected T oldValue;

    @SuppressWarnings("unchecked")
    @Override
    public boolean doSearch(TestChromosome test, int statement,
                            LocalSearchObjective<TestChromosome> objective) {

        TestCase slice = test.getTestCase().clone();
        int newPos = slice.sliceFor(slice.getStatement(statement).getReturnValue());
        TestCase oldTest = test.getTestCase();
        test.setTestCase(slice);
        test.setChanged(true);

        int oldStatement = statement;
        statement = newPos;

        NumericalPrimitiveStatement<T> p = (NumericalPrimitiveStatement<T>) test.getTestCase().getStatement(statement);

        logger.info("Applying search to: " + p.getCode());

        boolean improved = executeSearch(test, statement, objective, p);

        if (improved) {
             NumericalPrimitiveStatement<T> ps = (NumericalPrimitiveStatement<T>) oldTest.getStatement(oldStatement);
             ps.setValue(p.getValue());
        }
        test.setChanged(true);
        test.setTestCase(oldTest);

        logger.info("Finished local search with result " + p.getCode());
        return improved;
    }

    protected abstract boolean executeSearch(TestChromosome test, int statement,
                                             LocalSearchObjective<TestChromosome> objective,
                                             NumericalPrimitiveStatement<T> p);

    protected boolean performAVM(TestChromosome test, int statement,
                                 LocalSearchObjective<TestChromosome> objective,
                                 double initialDelta, double factor,
                                 NumericalPrimitiveStatement<T> p) {
        boolean improved = false;
        oldValue = p.getValue();
        ExecutionResult oldResult = test.getLastExecutionResult();

        boolean done = false;
        while (!done) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                break;
            }
            done = true;

            // Try +delta
            p.increment(initialDelta);
            logger.info("Trying increment of " + p.getCode());

            if (objective.hasImproved(test)) {
                done = false;
                improved = true;
                iterate(factor * initialDelta, factor, objective, test, p, statement);
                oldValue = p.getValue();
                oldResult = test.getLastExecutionResult();
            } else {
                // Restore original, try -delta
                p.setValue(oldValue);
                test.setLastExecutionResult(oldResult);
                test.setChanged(false);

                p.increment(-initialDelta);
                logger.info("Trying decrement of " + p.getCode());

                if (objective.hasImproved(test)) {
                    done = false;
                    improved = true;
                    iterate(-factor * initialDelta, factor, objective, test, p, statement);
                    oldValue = p.getValue();
                    oldResult = test.getLastExecutionResult();
                } else {
                    p.setValue(oldValue);
                    test.setLastExecutionResult(oldResult);
                    test.setChanged(false);
                }
            }
        }
        return improved;
    }

    protected boolean iterate(double delta, double factor, LocalSearchObjective<TestChromosome> objective,
                            TestChromosome test, NumericalPrimitiveStatement<T> p, int statement) {

        boolean improvement = false;
        T oldValue = p.getValue();
        ExecutionResult oldResult = test.getLastExecutionResult();

        p.increment(delta);
        logger.info("Trying increment " + delta + " of " + p.getCode());
        while (objective.hasImproved(test)) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                break;
            }
            oldValue = p.getValue();
            oldResult = test.getLastExecutionResult();
            improvement = true;
            delta = factor * delta;
            p.increment(delta);
            logger.info("Trying increment " + delta + " of " + p.getCode());
        }
        // logger.info("No improvement on " + p.getCode());

        p.setValue(oldValue);
        test.setLastExecutionResult(oldResult);
        test.setChanged(false);
        // logger.info("Final value of this iteration: " + p.getValue());

        return improvement;
    }
}
