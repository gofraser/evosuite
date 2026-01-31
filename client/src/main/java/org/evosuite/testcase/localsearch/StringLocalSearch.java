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
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.utils.Randomness;

/**
 * <p>
 * StringLocalSearch class.
 * </p>
 *
 * @author fraser
 */
public class StringLocalSearch extends AbstractStringLocalSearch {

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

            logger.info("Probing string " + originalValue + " ->" + p.getCode());
            int result = objective.hasChanged(test);
            if (result < 0) {
                backup(test, p);
            } else {
                restore(test, p);
            }
            if (result != 0) {
                affected = true;
                logger.info("String affects fitness");
                break;
            }
        }

        if (affected) {

            boolean hasImproved = false;

            logger.info("Applying local search to string " + p.getCode());
            // First try to remove each of the characters
            logger.info("Removing characters");
            if (removeCharacters(objective, test, p, statement))
                hasImproved = true;
            logger.info("Statement: " + p.getCode());

            // Second, try to replace each of the characters with each of the 64 possible characters
            logger.info("Replacing characters");
            if (replaceCharacters(objective, test, p, statement))
                hasImproved = true;
            logger.info("Statement: " + p.getCode());

            // Third, try to add characters
            logger.info("Adding characters");
            if (addCharacters(objective, test, p, statement))
                hasImproved = true;
            logger.info("Statement: " + p.getCode());

            logger.info("Resulting string: " + p.getValue());
            return hasImproved;
            //} else {
            //	logger.info("Not applying local search to string as it does not improve fitness");
        }

        return false;
    }

    private boolean replaceCharacters(LocalSearchObjective<TestChromosome> objective,
                                      TestChromosome test, StringPrimitiveStatement p, int statement) {

        logger.info(" -> In replacement");
        boolean improvement = false;
        backup(test, p);

        for (int i = 0; i < oldValue.length(); i++) {
            if (LocalSearchBudget.getInstance().isFinished()) {
                return improvement;
            }
            char oldChar = oldValue.charAt(i);
            logger.info(" -> Character " + i + ": " + oldChar);
            char[] characters = oldValue.toCharArray();
            for (char replacement = 9; replacement < 128; replacement++) {
                if (LocalSearchBudget.getInstance().isFinished())
                    return improvement;

                if (replacement != oldChar) {
                    characters[i] = replacement;
                    String newString = new String(characters);
                    p.setValue(newString);
                    logger.info(" " + i + " " + oldValue + "/" + oldValue.length()
                            + " -> " + newString + "/" + newString.length());
                    //logger.debug(" " + i + " " + oldValue + "/" + oldValue.length()
                    //        + " -> " + newString + "/" + newString.length());

                    if (objective.hasImproved(test)) {
                        backup(test, p);
                        //oldChar = replacement;
                        improvement = true;

                        // If this change has improved fitness we can move on to the next character
                        break;
                    } else {
                        characters[i] = oldChar;
                        restore(test, p);
                    }
                }
            }
        }

        return improvement;
    }

}
