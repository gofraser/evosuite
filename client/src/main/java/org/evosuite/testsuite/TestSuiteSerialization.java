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
package org.evosuite.testsuite;

import org.evosuite.TestGenerationContext;
import org.evosuite.runtime.util.Inputs;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.utils.DebuggingObjectOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Andrea Arcuri on 08/06/15.
 */
public class TestSuiteSerialization {

    private static final Logger logger = LoggerFactory.getLogger(TestSuiteSerialization.class);


    /**
     * Serializes a list of test suites to the specified target file.
     *
     * @param list   the list of test suites to save.
     * @param target the file to save the tests to.
     * @return true if the tests were saved successfully, false otherwise.
     * @throws IllegalArgumentException if list or target is null.
     */
    public static boolean saveTests(List<TestSuiteChromosome> list, File target) throws IllegalArgumentException {
        Inputs.checkNull(list, target);

        File parent = target.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        try (ObjectOutputStream out = new DebuggingObjectOutputStream(new FileOutputStream(target))) {
            for (TestSuiteChromosome ts : list) {
                for (TestChromosome tc : ts.getTestChromosomes()) {
                    out.writeObject(tc);
                }
            }

            out.flush();
            out.close();
        } catch (IOException e) {
            logger.error("Failed to open/handle " + target.getAbsolutePath() + " for writing: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Serializes a single test suite to the specified target file.
     *
     * @param ts     the test suite to save.
     * @param target the file to save the test suite to.
     * @return true if the test suite was saved successfully, false otherwise.
     * @throws IllegalArgumentException if an error occurs.
     */
    public static boolean saveTests(TestSuiteChromosome ts, File target) throws IllegalArgumentException {
        File parent = target.getParentFile();
        if (!parent.exists()) {
            parent.mkdirs();
        }

        try (ObjectOutputStream out = new DebuggingObjectOutputStream(new FileOutputStream(target))) {
            for (TestChromosome tc : ts.getTestChromosomes()) {
                out.writeObject(tc);
            }

            out.flush();
            out.close();
        } catch (IOException e) {
            logger.error("Failed to open/handle " + target.getAbsolutePath() + " for writing: " + e.getMessage());
            return false;
        }

        return true;
    }


    /**
     * Serializes a list of test suites to a file within the specified folder.
     *
     * @param ts       the list of test suites to save.
     * @param folder   the folder where the file should be created.
     * @param fileName the name of the file.
     * @return true if the tests were saved successfully, false otherwise.
     * @throws IllegalArgumentException if ts, folder, or fileName is null.
     */
    public static boolean saveTests(List<TestSuiteChromosome> ts, File folder, String fileName)
            throws IllegalArgumentException {
        Inputs.checkNull(ts, folder, fileName);

        if (!folder.exists()) {
            folder.mkdirs();
        }

        File target = new File(folder, fileName);
        return saveTests(ts, target);
    }

    /**
     * Loads tests from a file within the specified folder.
     *
     * @param folder   the folder containing the file.
     * @param fileName the name of the file.
     * @return a list of loaded test chromosomes.
     * @throws IllegalArgumentException if folder or fileName is null.
     */
    public static List<TestChromosome> loadTests(File folder, String fileName) throws IllegalArgumentException {
        Inputs.checkNull(folder, fileName);
        File target = new File(folder, fileName);
        return loadTests(target);
    }

    /**
     * Loads tests from the file at the specified path.
     *
     * @param target the path to the file.
     * @return a list of loaded test chromosomes.
     * @throws IllegalArgumentException if the path is null.
     */
    public static List<TestChromosome> loadTests(String target) throws IllegalArgumentException {
        return loadTests(new File(target));
    }

    /**
     * Loads tests from the specified file.
     *
     * @param target the file to load tests from.
     * @return a list of loaded test chromosomes.
     * @throws IllegalArgumentException if target is null.
     */
    public static List<TestChromosome> loadTests(File target) throws IllegalArgumentException {
        Inputs.checkNull(target);

        List<TestChromosome> list = new ArrayList<>();

        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(target))) {

            try {
                Object obj = in.readObject();
                while (obj != null) {
                    if (obj instanceof TestChromosome) {
                        //this check might fail if old version is used, and EvoSuite got updated
                        TestChromosome tc = (TestChromosome) obj;
                        for (Statement st : tc.getTestCase()) {
                            st.changeClassLoader(TestGenerationContext.getInstance().getClassLoaderForSUT());
                        }

                        list.add(tc);
                    }
                    obj = in.readObject();
                }
            } catch (EOFException e) {
                //fine
            } catch (Exception e) {
                logger.warn("Problems when reading a serialized test from " + target.getAbsolutePath() + " : "
                        + e.getMessage());
            }

            in.close();
        } catch (FileNotFoundException e) {
            logger.warn("Cannot load tests because file does not exist: " + target.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to open/handle " + target.getAbsolutePath() + " for reading: " + e.getMessage());
        }

        return list;
    }
}
