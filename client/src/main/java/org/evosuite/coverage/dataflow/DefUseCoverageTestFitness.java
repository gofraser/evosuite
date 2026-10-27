/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.coverage.dataflow;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.utils.ArrayUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Objects;

/**
 * Evaluate fitness of a single test case with respect to one Definition-Use
 * pair.
 *
 * <p>For more information look at the comment from method getDistance().
 *
 * @author Andre Mis
 */
public class DefUseCoverageTestFitness extends TestFitnessFunction {

    public enum DefUsePairType {
        INTRA_METHOD, INTER_METHOD, INTRA_CLASS, PARAMETER
    }

    private static final long serialVersionUID = 1L;

    // debugging flags
    private static final boolean DEBUG = Properties.DEFUSE_DEBUG_MODE;
    private static final boolean PRINT_DEBUG = false;

    // the Definition-Use pair
    private String goalVariable;
    private transient Use goalUse;
    private transient Definition goalDefinition;

    // The use and the definition refer to the class loader of the client, so only their identity is
    // serialized, which keeps the goal comparable and printable where the pool is empty (master).
    private transient int useId;
    private transient int defId;
    private transient String targetClass;
    private transient String targetMethod;
    private transient String useDescription;
    private transient String defDescription;

    private DefUsePairType type;

    private TestFitnessFunction goalDefinitionFitness;
    private TestFitnessFunction goalUseFitness;

    // coverage information
    private Integer coveringObjectId = -1;
    private boolean covered = false;

    // constructors

    /**
     * Creates a Definition-Use-Coverage goal for the given Definition and Use.
     *
     * @param def  a {@link org.evosuite.coverage.dataflow.Definition} object.
     * @param use  a {@link org.evosuite.coverage.dataflow.Use} object.
     * @param type a
     *             {@link org.evosuite.coverage.dataflow.DefUseCoverageTestFitness.DefUsePairType}
     *             object.
     */
    public DefUseCoverageTestFitness(Definition def, Use use, DefUsePairType type) {
        Objects.requireNonNull(def, "null given for definition. type: " + type.toString());
        Objects.requireNonNull(use, "null given for use. def was " + def + ". type: " + type);

        initRegularDefUse(def, use, type);
    }

    /**
     * Used for Parameter-Uses.
     *
     * <p>Creates a goal that tries to cover the given Use.
     *
     * @param use a {@link org.evosuite.coverage.dataflow.Use} object.
     */
    public DefUseCoverageTestFitness(Use use) {
        if (!use.isParameterUse()) {
            throw new IllegalArgumentException(
                    "this constructor is only for Parameter-Uses");
        }

        initParameterUse(use);
    }

    private void initRegularDefUse(Definition def, Use use, DefUsePairType type) {
        //if (!def.getVariableName().equals(use.getVariableName()))
        //  throw new IllegalArgumentException(
        //          "expect def and use to be for the same variable: \n" + def.toString()
        //                  + "\n" + use.toString());
        if (def.isLocalDU() && !type.equals(DefUsePairType.INTRA_METHOD)) {
            throw new IllegalArgumentException(
                    "local variables can only be part of INTRA-METHOD pairs: \ntype:"
                            + type + "\ndef:" + def + "\nuse:"
                            + use.toString());
        }

        this.goalDefinition = def;
        this.goalUse = use;
        this.goalVariable = def.getVariableName();
        this.goalDefinitionFitness = new StatementCoverageTestFitness(goalDefinition.getClassName(),
                goalDefinition.getMethodName(), goalDefinition.getInstructionId());
        this.goalUseFitness = new StatementCoverageTestFitness(goalUse.getClassName(),
                goalUse.getMethodName(), goalUse.getInstructionId());

        this.type = type;
        rememberIdentity();
    }

    private void initParameterUse(Use use) {
        goalVariable = use.getVariableName();
        goalDefinition = null;
        goalDefinitionFitness = null;
        goalUse = use;
        goalUseFitness = new StatementCoverageTestFitness(goalUse.getClassName(),
                goalUse.getMethodName(), goalUse.getInstructionId());

        this.type = DefUsePairType.PARAMETER;
        rememberIdentity();
    }

    private void rememberIdentity() {
        useId = goalUse.useId;
        targetClass = goalUse.getClassName();
        targetMethod = goalUse.getMethodName();
        useDescription = goalUse.toString();
        if (goalDefinition == null) {
            defId = 0;
            defDescription = "Parameter-Definition " + goalUse.getLocalVariableSlot()
                    + " for method " + goalUse.getMethodName();
        } else {
            defId = goalDefinition.defId;
            defDescription = goalDefinition.toString();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Calculates the DefUseCoverage test fitness for this goal.
     *
     * <p>Look at DefUseFitnessCalculator.calculateDUFitness() for more
     * information.
     */
    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {
        preFitnessDebugInfo(result, true);

        DefUseFitnessCalculator calculator = new DefUseFitnessCalculator(this,
                individual, result);

        // Deactivate coverage archive while measuring fitness, as auxiliar fitness functions
        // could attempt to claim coverage for it in the archive
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        double fitness = calculator.calculateDUFitness();

        Properties.TEST_ARCHIVE = archive;

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE) && fitness == 0.0) {
            setCovered(individual, result.getTrace(), -1); // TODO objectId wrong
        }

        postFitnessDebugInfo(individual, result, fitness);

        updateIndividual(individual, fitness);

        return fitness;
    }

    /**
     * Used by DefUseCoverageSuiteFitness.
     *
     * <p>Simply call getFitness(TestChromosome,ExecutionResult) with a dummy
     * TestChromosome. The chromosome is used only for updateIndividual()
     * anyways.
     *
     * @param result a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     * @return a double.
     */
    public double getFitness(ExecutionResult result) {
        TestChromosome dummy = new TestChromosome();
        return getFitness(dummy, result);
    }

    // debugging methods

    /**
     * setCovered.
     *
     * @param individual a {@link org.evosuite.ga.Chromosome} object.
     * @param trace      a {@link org.evosuite.testcase.execution.ExecutionTrace} object.
     * @param objectId   a {@link java.lang.Integer} object.
     */
    public void setCovered(TestChromosome individual, ExecutionTrace trace,
                           Integer objectId) {
        if (PRINT_DEBUG) {
            logger.debug("goal COVERED by object " + objectId);
            logger.debug("==============================================================");
        }
        this.coveringObjectId = objectId;
        updateIndividual(individual, 0);

        if (DEBUG) {
            if (!DefUseFitnessCalculator.traceCoversGoal(this, individual, trace)) {
                throw new IllegalStateException("calculation flawed. goal wasn't covered");
            }
        }
    }

    private void preFitnessDebugInfo(ExecutionResult result, boolean respectPrintFlag) {
        if (PRINT_DEBUG || !respectPrintFlag) {
            System.out.println("==============================================================");
            System.out.println("current goal: " + this);
            System.out.println("current test:");
            System.out.println(result.test.toCode());
        }
    }

    private void postFitnessDebugInfo(Chromosome<?> individual, ExecutionResult result,
                                      double fitness) {
        if (DEBUG) {
            if (fitness != 0) {
                if (PRINT_DEBUG) {
                    System.out.println("goal NOT COVERED. fitness: " + fitness);
                    System.out.println("==============================================================");
                }
                if (DefUseFitnessCalculator.traceCoversGoal(this, individual,
                        result.getTrace())) {
                    throw new IllegalStateException(
                            "calculation flawed. goal was covered but fitness was "
                                    + fitness);
                }
            }
        }
    }

    //  ---         Getter      ---

    /**
     * Getter for the field <code>goalVariable</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getGoalVariable() {
        return goalVariable;
    }

    /**
     * Getter for the field <code>goalDefinition</code>.
     *
     * @return a {@link org.evosuite.coverage.dataflow.Definition} object.
     */
    public Definition getGoalDefinition() {
        return goalDefinition;
    }

    /**
     * Getter for the field <code>goalUse</code>.
     *
     * @return a {@link org.evosuite.coverage.dataflow.Use} object.
     */
    public Use getGoalUse() {
        return goalUse;
    }

    /**
     * Getter for the field <code>goalUseFitness</code>.
     *
     * @return a {@link org.evosuite.testcase.TestFitnessFunction} object.
     */
    public TestFitnessFunction getGoalUseFitness() {
        return goalUseFitness;
    }

    /**
     * Getter for the field <code>goalDefinitionFitness</code>.
     *
     * @return a {@link org.evosuite.testcase.TestFitnessFunction} object.
     */
    public TestFitnessFunction getGoalDefinitionFitness() {
        return goalDefinitionFitness;
    }

    /**
     * isInterMethodPair.
     *
     * @return a boolean.
     */
    public boolean isInterMethodPair() {
        return type.equals(DefUsePairType.INTER_METHOD);
    }

    /**
     * isIntraClassPair.
     *
     * @return a boolean.
     */
    public boolean isIntraClassPair() {
        return type.equals(DefUsePairType.INTRA_CLASS);
    }

    /**
     * Getter for the field <code>type</code>.
     *
     * @return a
     *         {@link org.evosuite.coverage.dataflow.DefUseCoverageTestFitness.DefUsePairType}
     *         object.
     */
    public DefUsePairType getType() {
        return type;
    }

    /**
     * isParameterGoal.
     *
     * @return a boolean.
     */
    public boolean isParameterGoal() {
        return goalDefinition == null;
    }

    // ---      Inherited from Object           ---

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder r = new StringBuilder();
        r.append(type.toString());
        r.append("-Definition-Use-Pair");
        r.append("\n\t");
        r.append(defDescription);
        r.append("\n\t");
        r.append(useDescription);
        return r.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        if (goalUse == null) {
            result = prime * result + defId;
            result = prime * result + useId;
            result = prime * result + ((goalVariable == null) ? 0 : goalVariable.hashCode());
            result = prime * result + ((type == null) ? 0 : type.hashCode());
            return result;
        }
        result = prime * result
                + ((goalDefinition == null) ? 0 : goalDefinition.hashCode());
        result = prime * result + ((goalUse == null) ? 0 : goalUse.hashCode());
        result = prime * result + ((goalVariable == null) ? 0 : goalVariable.hashCode());
        result = prime * result + ((type == null) ? 0 : type.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DefUseCoverageTestFitness other = (DefUseCoverageTestFitness) obj;
        if (goalUse == null || other.goalUse == null) {
            return type == other.type && useId == other.useId && defId == other.defId
                    && targetClass.equals(other.targetClass) && targetMethod.equals(other.targetMethod)
                    && Objects.equals(goalVariable, other.goalVariable);
        }
        if (!Objects.equals(goalDefinition, other.goalDefinition)) {
            return false;
        }
        if (!Objects.equals(goalUse, other.goalUse)) {
            return false;
        }
        if (!Objects.equals(goalVariable, other.goalVariable)) {
            return false;
        }
        return type == other.type;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.evosuite.testcase.TestFitnessFunction#compareTo(org.evosuite.testcase.TestFitnessFunction)
     */
    @Override
    public int compareTo(TestFitnessFunction other) {
        if (other instanceof DefUseCoverageTestFitness) {
            DefUseCoverageTestFitness otherFitness = (DefUseCoverageTestFitness) other;
            if (goalUse == null || otherFitness.goalUse == null) {
                int result = targetClass.compareTo(otherFitness.targetClass);
                if (result == 0) {
                    result = targetMethod.compareTo(otherFitness.targetMethod);
                }
                if (result == 0) {
                    result = Integer.compare(defId, otherFitness.defId);
                }
                if (result == 0) {
                    result = Integer.compare(useId, otherFitness.useId);
                }
                return result;
            }
            if (this.goalDefinition == null && otherFitness.goalDefinition != null) {
                return -1;
            }
            if (this.goalDefinition != null && otherFitness.goalDefinition == null) {
                return 1;
            }
            if (this.goalDefinition != null) {
                int defCompare = goalDefinition.compareTo(otherFitness.goalDefinition);
                if (defCompare != 0) {
                    return defCompare;
                }
            }
            if (this.goalUse == null && otherFitness.goalUse != null) {
                return -1;
            }
            if (this.goalUse != null && otherFitness.goalUse == null) {
                return 1;
            }
            if (this.goalUse != null && otherFitness.goalUse != null) {
                return goalUse.compareTo(otherFitness.goalUse);
            }
            return 0;
        }
        return compareClassName(other);
    }

    /**
     * Returns true if this goal is covered.
     *
     * @return the covered
     */
    public boolean isCovered() {
        return covered;
    }

    /**
     * Sets whether this goal is covered.
     *
     * @param covered the covered to set
     */
    public void setCovered(boolean covered) {
        this.covered = covered;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetClass()
     */
    @Override
    public String getTargetClass() {
        return targetClass;
    }

    /**
     * {@inheritDoc}
     *
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetMethod()
     */
    @Override
    public String getTargetMethod() {
        return targetMethod;
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        DefUsePairType type = (DefUsePairType) ois.readObject();
        useId = (Integer) ois.readObject();
        defId = (Integer) ois.readObject();
        goalVariable = (String) ois.readObject();
        targetClass = (String) ois.readObject();
        targetMethod = (String) ois.readObject();
        useDescription = (String) ois.readObject();
        defDescription = (String) ois.readObject();
        this.type = type;
        Use use = DefUsePool.getUseByUseId(useId);

        // The pool may be empty on the master side during RMI deserialization.
        // In this case, we can't fully reconstruct the object, so we leave
        // the transient fields (goalUse, goalDefinition) as null and compare and print
        // the goal by the identity read above.
        if (use == null) {
            return;
        }

        if (type == DefUsePairType.PARAMETER) {
            initParameterUse(use);
        } else {
            Definition def = DefUsePool.getDefinitionByDefId(defId);
            if (def == null) {
                return;
            }
            initRegularDefUse(def, use, type);
        }
    }

    /**
     * Serialize, but need to abstract classloader away.
     *
     * @param oos the output stream
     * @throws IOException the IO exception
     */
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.writeObject(type);
        oos.writeObject(useId);
        oos.writeObject(defId);
        oos.writeObject(goalVariable);
        oos.writeObject(targetClass);
        oos.writeObject(targetMethod);
        oos.writeObject(useDescription);
        oos.writeObject(defDescription);
    }
}
