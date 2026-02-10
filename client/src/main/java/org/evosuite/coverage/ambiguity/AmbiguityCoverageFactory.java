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
package org.evosuite.coverage.ambiguity;

import org.evosuite.Properties;
import org.evosuite.coverage.line.LineCoverageTestFitness;
import org.evosuite.coverage.rho.RhoAux;
import org.evosuite.rmi.ClientServices;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.AbstractFitnessFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Comparator.comparingInt;

/**
 * Factory for ambiguity coverage fitness functions.
 *
 * @author José Campos
 */
public class AmbiguityCoverageFactory extends
        AbstractFitnessFactory<LineCoverageTestFitness> implements Serializable {

    private static final long serialVersionUID = 1424282176155102252L;

    private static final Logger logger = LoggerFactory.getLogger(AmbiguityCoverageFactory.class);


    private static List<LineCoverageTestFitness> goals = new ArrayList<>();


    private static List<StringBuilder> transposedMatrix = new ArrayList<>();


    private static double maxAmbiguityScore = Double.MAX_VALUE;

    /**
     * Read the coverage of a test suite from a file.
     */
    protected static void loadCoverage() {

        if (!new File(Properties.COVERAGE_MATRIX_FILENAME).exists()) {
            return;
        }

        BufferedReader br = null;

        try {
            String currentLine;
            br = new BufferedReader(new FileReader(Properties.COVERAGE_MATRIX_FILENAME));

            List<StringBuilder> matrix = new ArrayList<>();
            while ((currentLine = br.readLine()) != null) {
                currentLine = currentLine.replace(" ", "");
                // we do not want to consider test result
                currentLine = currentLine.substring(0, currentLine.length() - 1);
                matrix.add(new StringBuilder(currentLine));
            }

            transposedMatrix = tranposeMatrix(matrix);
            // double ag = AmbiguityCoverageFactory.getDefaultAmbiguity(transposedMatrix) 
            // * 1.0 / ((double) goals.size());
            double ag = TestFitnessFunction.normalize(
                    AmbiguityCoverageFactory.getDefaultAmbiguity(transposedMatrix));
            logger.info("AmbiguityScore of an existing test suite: " + ag);

            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.AmbiguityScore_T0, ag);
            ClientServices.getInstance().getClientNode()
                    .trackOutputVariable(RuntimeVariable.Size_T0, matrix.size());
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }


    @Override
    public List<LineCoverageTestFitness> getCoverageGoals() {
        return getGoals();
    }

    /**
     * Returns the coverage goals.
     *
     * @return a list of {@link LineCoverageTestFitness} objects.
     */
    public static List<LineCoverageTestFitness> getGoals() {

        if (!goals.isEmpty()) {
            return goals;
        }

        goals = RhoAux.getLineGoals();
        ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Total_Goals, goals.size());

        maxAmbiguityScore = (1.0) // goals.size() / goals.size()
                * ((((double) goals.size()) - 1.0) / 2.0);

        if (Properties.USE_EXISTING_COVERAGE) {
            // extremely important: before loading any previous coverage (i.e., from a coverage
            // matrix) goals need to be sorted. otherwise any previous coverage won't match!
            goals.sort(comparingInt(LineCoverageTestFitness::getLine));
            loadCoverage();
        } else {
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.AmbiguityScore_T0, 1.0);
            ClientServices.getInstance().getClientNode().trackOutputVariable(RuntimeVariable.Size_T0, 0);
        }

        return goals;
    }

    /**
     * Returns the transposed matrix.
     *
     * @return a list of {@link StringBuilder} objects.
     */
    public static List<StringBuilder> getTransposedMatrix() {
        return transposedMatrix;
    }

    /**
     * Transposes the given matrix.
     *
     * @param matrix the matrix to be transposed.
     * @return the transposed matrix.
     */
    private static List<StringBuilder> tranposeMatrix(List<StringBuilder> matrix) {

        int numberOfComponents = matrix.get(0).length();
        List<StringBuilder> newMatrix = new ArrayList<>();

        for (int componentIndex = 0; componentIndex < numberOfComponents; componentIndex++) {
            StringBuilder str = new StringBuilder();
            for (StringBuilder testCase : matrix) {
                str.append(testCase.charAt(componentIndex));
            }

            newMatrix.add(str);
        }

        return newMatrix;
    }

    /**
     * Returns the maximum ambiguity score.
     *
     * @return the maximum ambiguity score.
     */
    public static double getMaxAmbiguityScore() {
        return maxAmbiguityScore;
    }

    /**
     * Returns the default ambiguity score for the given transposed matrix.
     *
     * @param matrix the transposed matrix.
     * @return the default ambiguity score.
     */
    protected static double getDefaultAmbiguity(List<StringBuilder> matrix) {

        int numberOfComponents = matrix.size();
        Map<String, Integer> groups = new HashMap<>();

        for (StringBuilder s : matrix) {
            if (!groups.containsKey(s.toString())) {
                // in the beginning they are ambiguity, so they belong to the same group '1'
                groups.put(s.toString(), 1);
            } else {
                groups.put(s.toString(), groups.get(s.toString()) + 1);
            }
        }

        return getAmbiguity(numberOfComponents, groups);
    }

    /**
     * Returns the ambiguity score for the given number of components and groups.
     *
     * @param numberOfComponents the number of components.
     * @param groups             the groups of components.
     * @return the ambiguity score.
     */
    public static double getAmbiguity(int numberOfComponents, Map<String, Integer> groups) {

        double fit = 0.0;
        for (String s : groups.keySet()) {
            double cardinality = groups.get(s);
            if (cardinality == 1.0) {
                continue;
            }

            fit += (cardinality / ((double) numberOfComponents))
                    * ((cardinality - 1.0) / 2.0);
        }

        return fit;
    }

    // only for testing
    protected static void reset() {
        goals.clear();
        transposedMatrix.clear();
    }
}
