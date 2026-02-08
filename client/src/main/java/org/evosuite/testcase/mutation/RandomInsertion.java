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
package org.evosuite.testcase.mutation;

import org.evosuite.Properties;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.PrimitiveStatement;
import org.evosuite.testcase.variable.NullReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.ListUtil;
import org.evosuite.utils.Randomness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An insertion strategy that allows for modification of test cases by inserting random statements.
 */
public class RandomInsertion implements InsertionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(RandomInsertion.class);

    @Override
    public int insertStatement(TestCase test, int lastPosition) {
        double r = Randomness.nextDouble();
        int oldSize = test.size();

        /*
            TODO: if allow inserting a UUT method in the middle of a test,
             we need to handle case of not breaking any initializing bounded variable
         */

        int position = INSERTION_ERROR;

        if (Properties.INSERTION_UUT + Properties.INSERTION_ENVIRONMENT + Properties.INSERTION_PARAMETER != 1.0) {
            // Assertions are often disabled, so we log a warning instead of asserting
            logger.warn("Insertion probabilities do not sum to 1.0: UUT={}, Environment={}, Parameter={}",
                    Properties.INSERTION_UUT, Properties.INSERTION_ENVIRONMENT, Properties.INSERTION_PARAMETER);
        }

        // Determine intended action based on probabilities
        boolean attemptUUT = Properties.INSERTION_UUT > 0 && r <= Properties.INSERTION_UUT;
        boolean attemptEnv = Properties.INSERTION_ENVIRONMENT > 0 && r > Properties.INSERTION_UUT && r <= Properties.INSERTION_UUT + Properties.INSERTION_ENVIRONMENT;

        // Fallback or explicit parameter insertion
        // If we didn't pick UUT or ENV, then we picked PARAM.
        // Also if we picked UUT or ENV but they are not possible (no calls available), we might fall back.

        boolean success = false;

        if (attemptUUT && TestCluster.getInstance().getNumTestCalls() > 0) {
            // Insert a call to the UUT
            // We assume insertion should happen after the last valid position
            position = lastPosition + 1;
            success = TestFactory.getInstance().insertRandomCall(test, position);
            if (success) {
                logger.debug("Inserted random call to UUT");
            }
        } else if (attemptEnv && TestCluster.getInstance().getNumOfEnvironmentCalls() > 0) {
             /*
                Insert a call to the environment, i.e., external resources for the test case such
                as handles to files on the file system, sockets that open network connections, etc.
                As such call is likely to depend on many constraints, we do not specify here the
                position of where it ll happen.
             */
            position = TestFactory.getInstance().insertRandomCallOnEnvironment(test, lastPosition);
            success = (position >= 0);
            if (success) {
                logger.debug("Inserted random call to Environment");
            }
        }

        // If neither UUT nor ENV were successful (either not chosen, or failed/not available), try Parameter insertion
        if (!success) {
            // Insert a call to a variable (one that is used as a parameter for some function call
            // in the test case). The idea is to mutate the parameter so that new program states
            // can be reached in the function call.
            position = insertCallOnObject(test, lastPosition);
            success = (position != INSERTION_ERROR);
            if (success) {
                logger.debug("Inserted random call on object");
            }
        }

        // If even that failed, try UUT again as a last resort fallback if calls are available
        if (!success && TestCluster.getInstance().getNumTestCalls() > 0) {
            logger.debug("Adding new call on UUT because other strategies failed");
            // To be consistent with "after lastPosition"
            position = lastPosition + 1;
            success = TestFactory.getInstance().insertRandomCall(test, position);
        }

        // This can happen if insertion had side effect of adding further previous statements in the
        // test, e.g., to handle input parameters.
        if (test.size() - oldSize > 1) {
            // Adjust position to point to the last inserted statement (approximately)
            // or rather, return the new "last valid position".
            position += (test.size() - oldSize - 1);
        }

        if (success) {
            return position;
        } else {
            return INSERTION_ERROR;
        }
    }

    private int insertCallOnObject(TestCase test, int lastPosition) {
        VariableReference var = selectRandomVariableForCall(test, lastPosition);
        if (var != null) {
            // find the last position where the selected variable is used in the test case
            final int lastUsage = test.getReferences(var).stream()
                    .mapToInt(VariableReference::getStPosition)
                    .max().orElse(var.getStPosition());

            int position;
            if (lastUsage > var.getStPosition() + 1) {
                // If there is more than 1 statement where it is used, we randomly choose a position
                position = Randomness.nextInt(var.getStPosition() + 1, // call has to be after the object is created
                        lastUsage                // but before the last usage
                );
            } else if (lastUsage == var.getStPosition()) {
                // The variable isn't used
                position = lastUsage + 1;
            } else {
                // The variable is used at only one position, we insert at exactly that position
                position = lastUsage;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Inserting call at position " + position + ", chosen var: "
                        + var.getName() + ", distance: " + var.getDistance() + ", class: "
                        + var.getClassName());
            }

            if (TestFactory.getInstance().insertRandomCallOnObjectAt(test, var, position)) {
                return position;
            }
        }
        return INSERTION_ERROR;
    }

    /**
     * In the given test case {@code test}, returns a random variable up to the specified {@code
     * position} for a subsequent call. If the test case is empty or the position is {@code 0},
     * {@code null} is returned.
     *
     * @param test     the test case from which to select the variable
     * @param position the position in the test case up to which a variable should be selected
     * @return the selected variable or {@code null} (see above)
     */
    private VariableReference selectRandomVariableForCall(TestCase test, int position) {
        if (test.isEmpty() || position == 0) {
            return null;
        }

        List<VariableReference> allVariables = test.getObjects(position);

        List<VariableReference> candidateVariables = allVariables.stream()
                .filter(var -> isValidVariableForCall(test, var))
                .collect(Collectors.toList());

        if (candidateVariables.isEmpty()) {
            return null;
        } else if (Properties.SORT_OBJECTS) {
            candidateVariables.sort(Comparator.comparingInt(VariableReference::getDistance));
            return ListUtil.selectRankBiased(candidateVariables);
        } else {
            return Randomness.choice(candidateVariables);
        }
    }

    private boolean isValidVariableForCall(TestCase test, VariableReference var) {
        if (var instanceof NullReference) {
            return false;
        }
        if (var.isVoid()) {
            return false;
        }
        if (var.getGenericClass().isObject()) {
            return false;
        }
        if (var.isPrimitive()) {
            return false;
        }
        if (var.isWrapperType()) {
            return false;
        }
        if (var.isString()) {
            return false;
        }

        // Check statement types
        if (test.getStatement(var.getStPosition()) instanceof PrimitiveStatement) {
            return false;
        }
        if (test.getStatement(var.getStPosition()) instanceof FunctionalMockStatement) {
            return false;
        }

        // Check usages / SUT
        // Note: this check has been added only recently, to avoid having added calls to UUT in the middle of the test
        return test.hasReferences(var) || var.getVariableClass().equals(Properties.getInitializedTargetClass());
    }

}
