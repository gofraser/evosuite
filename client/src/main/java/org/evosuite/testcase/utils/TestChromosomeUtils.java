package org.evosuite.testcase.utils;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.statements.*;
import org.evosuite.testcase.variable.VariableReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestChromosomeUtils {

    private static final Logger logger = LoggerFactory.getLogger(TestChromosomeUtils.class);

    private TestChromosomeUtils() {}

    /**
     * This method checks whether the test has only primitive type statements. Indeed,
     * crossover and mutation can lead to tests with no method calls (methods or constructors
     * call), thus, when executed they will never cover something in the class under test.
     *
     * @param test to check
     * @return true if the test has at least one method or constructor call (i.e., the test may
     * cover something when executed; false otherwise
     */
    public static boolean hasMethodCall(TestChromosome test) {
        boolean flag = false;
        TestCase tc = test.getTestCase();
        for (Statement s : tc) {
            if (s instanceof MethodStatement) {
                MethodStatement ms = (MethodStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
            if (s instanceof ConstructorStatement) {
                ConstructorStatement ms = (ConstructorStatement) s;
                boolean isTargetMethod = ms.getDeclaringClassName().equals(Properties.TARGET_CLASS);
                if (isTargetMethod) {
                    return true;
                }
            }
        }
        return flag;
    }

    /**
     * When a test case is changed via crossover and/or mutation, it can contains some
     * primitive variables that are not used as input (or to store the output) of method calls.
     * Thus, this method removes all these "trash" statements.
     *
     * @param chromosome the chromosome.
     * @return true or false depending on whether "unused variables" are removed
     */
    public static boolean removeUnusedVariables(TestChromosome chromosome) {
        int sizeBefore = chromosome.size();
        TestCase t = chromosome.getTestCase();
        List<Integer> toDelete = new ArrayList<>(chromosome.size());
        boolean hasDeleted = false;

        int num = 0;
        for (Statement s : t) {
            VariableReference var = s.getReturnValue();
            boolean delete = false;
            delete = delete || s instanceof PrimitiveStatement;
            delete = delete || s instanceof ArrayStatement;
            // StringPrimitiveStatement extends PrimitiveStatement so it is covered

            if (!t.hasReferences(var) && delete) {
                toDelete.add(num);
                hasDeleted = true;
            }
            num++;
        }
        toDelete.sort(Collections.reverseOrder());
        for (Integer position : toDelete) {
            t.remove(position);
        }
        int sizeAfter = chromosome.size();
        if (hasDeleted) {
            logger.debug("Removed {} unused statements", (sizeBefore - sizeAfter));
        }
        return hasDeleted;
    }
}
