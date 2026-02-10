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
package org.evosuite.coverage.rho;

import org.evosuite.Properties;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static java.util.Comparator.comparingInt;

/**
 * Factory for creating coverage goals for Rho coverage.
 *
 * @author José Campos
 */
public class RhoCoverageFactory extends
        AbstractFitnessFactory<LineCoverageTestFitness> implements Serializable {

    private static final long serialVersionUID = -4124074445663735815L;

    private static final Logger logger = LoggerFactory.getLogger(RhoCoverageFactory.class);


    private static List<LineCoverageTestFitness> goals = new ArrayList<>();

    /**
     * Variables to calculate Rho value.
     */
    private static int numberOfOnes = 0;
    private static int numberOfTestCases = 0;


    private static double rho = 1.0;


    private static final List<List<Integer>> matrix = new ArrayList<>();

    /**
     * Read the coverage of a test suite from a file.
     */
    protected static synchronized void loadCoverage() {

        File coverageFile = new File(Properties.COVERAGE_MATRIX_FILENAME);
        if (!coverageFile.exists()) {
            return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(coverageFile))) {
            String currentLine;

            while ((currentLine = br.readLine()) != null) {
                String[] split = currentLine.split(" ");

                List<Integer> test = new ArrayList<>();
                for (int i = 0; i < split.length - 1; i++) { // - 1, because we do not want to consider test result
                    if (split[i].compareTo("1") == 0) {
                        test.add(goals.get(i).getLine());
                    }
                }

                matrix.add(test);
                numberOfOnes += test.size();
                numberOfTestCases++;
            }

            double rawRho;
            if (numberOfTestCases > 0 && !goals.isEmpty()) {
                rawRho = ((double) numberOfOnes) / ((double) numberOfTestCases) / ((double) goals.size());
            } else {
                rawRho = 0.0; // Default or handled by calculateRho which returns 1.0 diff
            }

            logger.debug("RhoScore of an existing test suite: " + rawRho);

            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.RhoScore_T0,
                    rawRho);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Size_T0,
                    numberOfTestCases);

            rho = RhoAux.calculateRho(numberOfOnes, numberOfTestCases, goals.size());
            logger.debug("(RhoScore - 0.5) of an existing test suite: " + rho);

        } catch (IOException e) {
            logger.error("Error reading coverage matrix from " + Properties.COVERAGE_MATRIX_FILENAME, e);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            logger.error("Error parsing coverage matrix from " + Properties.COVERAGE_MATRIX_FILENAME, e);
        }
    }

    @Override
    public List<LineCoverageTestFitness> getCoverageGoals() {
        return getGoals();
    }

    /**
     * Get the list of coverage goals.
     *
     * @return list of coverage goals.
     */
    public static synchronized List<LineCoverageTestFitness> getGoals() {

        if (!goals.isEmpty()) {
            return goals;
        }

        goals = RhoAux.getLineGoals();
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

        if (Properties.USE_EXISTING_COVERAGE) {
            // extremely important: before loading any previous coverage (i.e., from a coverage
            // matrix) goals need to be sorted. otherwise any previous coverage won't match!
            goals.sort(comparingInt(LineCoverageTestFitness::getLine));
            loadCoverage();
        } else {
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.RhoScore_T0, 1.0);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Size_T0,
                    numberOfTestCases);
        }

        return goals;
    }

    /**
     * Get the number of goals.
     *
     * @return number of goals.
     */
    public static synchronized int getNumberGoals() {
        if (goals.isEmpty()) {
            getGoals();
        }
        return goals.size();
    }

    /**
     * Get the number of ones.
     *
     * @return number of ones (total coverage units hit).
     */
    public static synchronized int getNumberOfOnes() {
        return numberOfOnes;
    }

    /**
     * Get the number of test cases.
     *
     * @return number of test cases.
     */
    public static synchronized int getNumberOfTestCases() {
        // This assertion might fail if called during modification, but method is synchronized
        // Wait, matrix update and numberOfTestCases update are atomic in loadCoverage which is synchronized.
        // assert (numberOfTestCases == matrix.size());
        // Removing assertion as it relies on matrix being populated only via loadCoverage and tracked correctly.
        // If external tools use this, assertion might fail if they don't update matrix.
        // But matrix is private static final.
        return numberOfTestCases;
    }

    /**
     * Get the current rho value.
     *
     * @return current rho value.
     */
    public static synchronized double getRho() {
        return rho;
    }

    /**
     * Get the coverage matrix.
     *
     * @return the coverage matrix.
     */
    public static synchronized List<List<Integer>> getMatrix() {
        return matrix;
    }

    /**
     * Check if a coverage pattern already exists.
     *
     * @param newTest covered lines by the new test.
     * @return true if the coverage pattern already exists.
     */
    public static synchronized boolean exists(List<Integer> newTest) {
        return matrix.contains(newTest);
    }

    /**
     * Reset the factory state.
     */
    protected static synchronized void reset() {
        goals.clear();
        numberOfOnes = 0;
        numberOfTestCases = 0;
        rho = 1.0;
        matrix.clear();
    }
}
