package org.evosuite.testcase.localsearch;

import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public abstract class AbstractStringLocalSearch extends StatementLocalSearch {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected String oldValue;
    protected ExecutionResult oldResult;
    protected boolean oldChanged;

    protected void backup(TestChromosome test, StringPrimitiveStatement p) {
        oldValue = p.getValue();
        oldResult = test.getLastExecutionResult();
        oldChanged = test.isChanged();
    }

    protected void restore(TestChromosome test, StringPrimitiveStatement p) {
        p.setValue(oldValue);
        test.setLastExecutionResult(oldResult);
        test.setChanged(oldChanged);
    }

    protected boolean removeCharacters(LocalSearchObjective<TestChromosome> objective,
                                     TestChromosome test, StringPrimitiveStatement p, int statement) {

        boolean improvement = false;
        backup(test, p);

        for (int i = oldValue.length() - 1; i >= 0; i--) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                break;
            }
            String newString = oldValue.substring(0, i) + oldValue.substring(i + 1);
            p.setValue(newString);
            logger.info(" " + i + " " + oldValue + "/" + oldValue.length() + " -> "
                    + newString + "/" + newString.length());
            if (objective.hasImproved(test)) {
                logger.info("Has improved");
                backup(test, p);
                improvement = true;
            } else {
                logger.info("Has not improved");
                restore(test, p);
            }
        }

        return improvement;
    }

    protected boolean addCharacters(LocalSearchObjective<TestChromosome> objective,
                                  TestChromosome test, StringPrimitiveStatement p, int statement) {

        boolean improvement = false;
        backup(test, p);

        boolean add = true;

        while (add) {
            add = false;
            int position = oldValue.length();
            char[] characters = Arrays.copyOf(oldValue.toCharArray(), position + 1);
            for (char replacement = 9; replacement < 128; replacement++) {
                if (LocalSearchBudget.getInstance().isFinished()) {
                    return improvement;
                }
                characters[position] = replacement;
                String newString = new String(characters);
                p.setValue(newString);

                if (objective.hasImproved(test)) {
                    backup(test, p);
                    improvement = true;
                    add = true;
                    break;
                } else {
                    restore(test, p);
                }
            }
        }

        add = true;
        while (add) {
            add = false;
            int position = 0;
            char[] characters = (" " + oldValue).toCharArray();
            for (char replacement = 9; replacement < 128; replacement++) {
                if (LocalSearchBudget.getInstance().isFinished()) {
                    return improvement;
                }
                characters[position] = replacement;
                String newString = new String(characters);
                p.setValue(newString);

                if (objective.hasImproved(test)) {
                    backup(test, p);
                    improvement = true;
                    add = true;
                    break;
                } else {
                    restore(test, p);
                }
            }
        }

        return improvement;
    }
}
