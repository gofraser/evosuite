/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.testcase.localsearch;

import org.evosuite.Properties;
import org.evosuite.ga.localsearch.LocalSearchBudget;
import org.evosuite.ga.localsearch.LocalSearchObjective;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.utils.Randomness;

/**
 * @author Gordon Fraser
 */
public class StringAVMLocalSearch extends AbstractStringLocalSearch {

    /* (non-Javadoc)
     * @see org.evosuite.testcase.LocalSearch#doSearch(org.evosuite.testcase.TestChromosome, int, org.evosuite.ga.LocalSearchObjective)
     */

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean doSearch(TestChromosome test, int statement,
                            LocalSearchObjective<TestChromosome> objective) {
        StringPrimitiveStatement p = (StringPrimitiveStatement) test.getTestCase().getStatement(statement);
        backup(test, p);

        // TODO: First apply 10 random mutations to determine if string influences _uncovered_ branch

        boolean affected = false;
        String originalValue = p.getValue();


        for (int i = 0; i < Properties.LOCAL_SEARCH_PROBES; i++) {
            if (Randomness.nextDouble() > 0.5)
                p.increment();
            else
                p.randomize();

            int result = objective.hasChanged(test);
            if (result < 0) {
                backup(test, p);
            } else {
                restore(test, p);
            }
            if (result != 0) {
                affected = true;
                break;
            }
        }

        if (affected) {

            boolean hasImproved = false;

            // First try to remove each of the characters
            if (removeCharacters(objective, test, p, statement))
                hasImproved = true;

            // Second, try to replace each of the characters with each of the 64 possible characters
            if (replaceCharacters(objective, test, p, statement))
                hasImproved = true;

            // Third, try to add characters
            if (addCharacters(objective, test, p, statement))
                hasImproved = true;

            return hasImproved;
            //} else {
            //	logger.info("Not applying local search to string as it does not improve fitness");
        }

        return false;
    }

    private boolean replaceCharacters(LocalSearchObjective<TestChromosome> objective,
                                      TestChromosome test, StringPrimitiveStatement p, int statement) {

        boolean improvement = false;
        backup(test, p);
        for (int i = 0; i < oldValue.length(); i++) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                return improvement;
            }

            boolean done = false;
            while (!done) {
                if (LocalSearchBudget.getInstance().isFinished()) {
                    return improvement;
                }
                done = true;
                // Try +1

                char oldChar = oldValue.charAt(i);
                char[] characters = oldValue.toCharArray();
                char replacement = oldChar;

                replacement += 1;
                characters[i] = replacement;
                String newString = new String(characters);
                p.setValue(newString);
                logger.info(" " + i + " " + oldValue + "/" + oldValue.length() + " -> "
                        + newString + "/" + newString.length());

                if (objective.hasImproved(test)) {
                    done = false;

                    iterate(2, objective, test, p, i, statement);
                    oldValue = p.getValue();
                    oldResult = test.getLastExecutionResult();

                } else {
                    // Restore original, try -1
                    p.setValue(oldValue);
                    test.setLastExecutionResult(oldResult);
                    test.setChanged(false);

                    replacement -= 2;
                    characters[i] = replacement;
                    newString = new String(characters);
                    p.setValue(newString);

                    if (objective.hasImproved(test)) {
                        done = false;
                        iterate(-2, objective, test, p, i, statement);
                        oldValue = p.getValue();
                        oldResult = test.getLastExecutionResult();

                    } else {
                        p.setValue(oldValue);
                        test.setLastExecutionResult(oldResult);
                        test.setChanged(false);
                    }
                }
            }
        }

        return improvement;
    }

    private boolean iterate(long delta, LocalSearchObjective<TestChromosome> objective,
                            TestChromosome test, StringPrimitiveStatement p, int character,
                            int statement) {

        boolean improvement = false;
        oldValue = p.getValue();
        ExecutionResult oldResult = test.getLastExecutionResult();

        char oldChar = oldValue.charAt(character);
        char[] characters = oldValue.toCharArray();
        char replacement = oldChar;

        replacement += delta;
        characters[character] = replacement;
        String newString = new String(characters);
        p.setValue(newString);

        while (objective.hasImproved(test)) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                break;
            }
            oldValue = p.getValue();
            oldResult = test.getLastExecutionResult();
            improvement = true;
            delta = 2 * delta;
            replacement += delta;
            characters[character] = replacement;
            newString = new String(characters);
            p.setValue(newString);
        }

        p.setValue(oldValue);
        test.setLastExecutionResult(oldResult);
        test.setChanged(false);

        return improvement;

    }

}
