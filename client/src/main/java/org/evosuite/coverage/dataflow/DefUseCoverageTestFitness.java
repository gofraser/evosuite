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
package org.evosuite.coverage.dataflow;

import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.TestGenerationContext;
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.ga.Chromosome;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.RawControlFlowGraph;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.utils.ArrayUtil;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluate fitness of a single test case with respect to one Definition-Use
 * pair
 * <p>
 * For more information look at the comment from method getDistance()
 *
 * @author Andre Mis
 */
public class DefUseCoverageTestFitness extends TestFitnessFunction {

    public enum DefUsePairType {
        INTRA_METHOD, INTER_METHOD, INTRA_CLASS, PARAMETER
    }

    private static final long serialVersionUID = 1L;

    /**
     * Constant <code>singleFitnessTime=0l</code>
     */
    public static long singleFitnessTime = 0L;

    // debugging flags
    private final static boolean DEBUG = Properties.DEFUSE_DEBUG_MODE;
    private final static boolean PRINT_DEBUG = false;

    // the Definition-Use pair
    private String goalVariable;
    private transient Use goalUse;
    private transient Definition goalDefinition;

    private DefUsePairType type;

    private TestFitnessFunction goalDefinitionFitness;
    private TestFitnessFunction goalUseFitness;

    // coverage information
    private Integer coveringObjectId = -1;
    private boolean covered = false;

    // constructors

    /**
     * Creates a Definition-Use-Coverage goal for the given Definition and Use
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
     * Used for Parameter-Uses
     * <p>
     * Creates a goal that tries to cover the given Use
     *
     * @param use a {@link org.evosuite.coverage.dataflow.Use} object.
     */
    public DefUseCoverageTestFitness(Use use) {
        if (!use.isParameterUse())
            throw new IllegalArgumentException(
                    "this constructor is only for Parameter-Uses");

        initParameterUse(use);
    }

    private void initRegularDefUse(Definition def, Use use, DefUsePairType type) {
        //if (!def.getVariableName().equals(use.getVariableName()))
        //	throw new IllegalArgumentException(
        //	        "expect def and use to be for the same variable: \n" + def.toString()
        //	                + "\n" + use.toString());
        if (def.isLocalDU() && !type.equals(DefUsePairType.INTRA_METHOD))
            throw new IllegalArgumentException(
                    "local variables can only be part of INTRA-METHOD pairs: \ntype:"
                            + type + "\ndef:" + def + "\nuse:"
                            + use.toString());

        this.goalDefinition = def;
        this.goalUse = use;
        this.goalVariable = def.getVariableName();
        this.goalDefinitionFitness = new StatementCoverageTestFitness(goalDefinition.getClassName(), goalDefinition.getMethodName(), goalDefinition.getInstructionId());
        this.goalUseFitness = new StatementCoverageTestFitness(goalUse.getClassName(), goalUse.getMethodName(), goalUse.getInstructionId());

        this.type = type;
    }

    private void initParameterUse(Use use) {
        goalVariable = use.getVariableName();
        goalDefinition = null;
        goalDefinitionFitness = null;
        goalUse = use;
        goalUseFitness = new StatementCoverageTestFitness(goalUse.getClassName(), goalUse.getMethodName(), goalUse.getInstructionId());

        this.type = DefUsePairType.PARAMETER;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Calculates the DefUseCoverage test fitness for this goal
     * <p>
     * Look at DefUseCoverageCalculations.calculateDUFitness() for more
     * information
     */
    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {
        preFitnessDebugInfo(result, true);

        long start = System.currentTimeMillis();

        DefUseFitnessCalculator calculator = new DefUseFitnessCalculator(this,
                individual, result);

        // Deactivate coverage archive while measuring fitness, as auxiliar fitness functions
        // could attempt to claim coverage for it in the archive
        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;

        double fitness = calculator.calculateDUFitness();

        Properties.TEST_ARCHIVE = archive;

        if (ArrayUtil.contains(Properties.CRITERION, Criterion.DEFUSE) && fitness == 0.0)
            setCovered(individual, result.getTrace(), -1); // TODO objectId wrong

        postFitnessDebugInfo(individual, result, fitness);

        singleFitnessTime += System.currentTimeMillis() - start;

        updateIndividual(individual, fitness);

        return fitness;
    }

    /**
     * Used by DefUseCoverageSuiteFitness
     * <p>
     * Simply call getFitness(TestChromosome,ExecutionResult) with a dummy
     * TestChromosome The chromosome is used only for updateIndividual()
     * anyways.
     *
     * @param result a {@link org.evosuite.testcase.execution.ExecutionResult} object.
     * @return a double.
     */
    public double getFitness(ExecutionResult result) {
        TestChromosome dummy = new TestChromosome();
        return getFitness(dummy, result);
    }

    public boolean isAlias() {
        return goalDefinition != null && !goalUse.getVariableName().equals(goalDefinition.getVariableName());
    }

    /**
     * Returns the definitions to the goalVaraible coming after the
     * goalDefinition and before the goalUse in their respective methods
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<BytecodeInstruction> getPotentialOverwritingDefinitions() {
        Set<BytecodeInstruction> instructionsInBetween = getInstructionsInBetweenDU();
        if (goalDefinition != null)
            return DefUseExecutionTraceAnalyzer.getOverwritingDefinitionsIn(goalDefinition,
                    instructionsInBetween);
        else
            return DefUseExecutionTraceAnalyzer.getDefinitionsIn(goalVariable,
                    instructionsInBetween);
    }

    /**
     * Return a set containing all CFGVertices that occur in the complete CFG
     * after the goalDefinition and before the goalUse.
     * <p>
     * It's pretty much the union of getInstructionsAfterGoalDefinition() and
     * getInstructionsBeforeGoalUse(), except if the DU is in one method and the
     * goalDefinition comes before the goalUse, then the intersection of the two
     * sets is returned.
     * <p>
     * If the goalDefinition is a Parameter-Definition only the CFGVertices
     * before the goalUse are considered.
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<BytecodeInstruction> getInstructionsInBetweenDU() {
        Set<BytecodeInstruction> previousInstructions = getInstructionsBeforeGoalUse();
        if (goalDefinition != null) {
            Set<BytecodeInstruction> laterInstructions = getInstructionsAfterGoalDefinition();
            if (goalDefinition.getInstructionId() < goalUse.getInstructionId()
                    && goalDefinition.getMethodName().equals(goalUse.getMethodName())) {
                // they are in the same method and definition comes before use => intersect sets
                previousInstructions.retainAll(laterInstructions);
            } else {
                // otherwise take the union
                previousInstructions.addAll(laterInstructions);
            }
        }
        return previousInstructions;
    }

    /**
     * Returns a set containing all CFGVertices in the goal definition method
     * that come after the definition.
     * <p>
     * Look at ControlFlowGraph.getLaterInstructionInMethod() for details
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<BytecodeInstruction> getInstructionsAfterGoalDefinition() {
        RawControlFlowGraph cfg = GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getRawCFG(goalDefinition.getClassName(),
                goalDefinition.getMethodName());
        BytecodeInstruction defVertex = cfg.getInstruction(goalDefinition.getInstructionId());
        Set<BytecodeInstruction> r = cfg.getLaterInstructionsInMethod(defVertex);
        //		for (BytecodeInstruction v : r) {
        //			v.setMethodName(goalDefinition.getMethodName());
        //			v.setClassName(goalDefinition.getClassName());
        //		}
        return r;
    }

    /**
     * Returns a set containing all CFGVertices in the goal use method that come
     * before the goal use.
     * <p>
     * Look at ControlFlowGraph.getPreviousInstructionInMethod() for details
     *
     * @return a {@link java.util.Set} object.
     */
    public Set<BytecodeInstruction> getInstructionsBeforeGoalUse() {
        RawControlFlowGraph cfg = GraphPool.getInstance(TestGenerationContext.getInstance().getClassLoaderForSUT()).getRawCFG(goalUse.getClassName(),
                goalUse.getMethodName());
        BytecodeInstruction useVertex = cfg.getInstruction(goalUse.getInstructionId());
        Set<BytecodeInstruction> r = cfg.getPreviousInstructionsInMethod(useVertex);
        //		for (BytecodeInstruction v : r) {
        //			v.setMethodName(goalUse.getMethodName());
        //			v.setClassName(goalUse.getClassName());
        //		}
        return r;
    }

    // debugging methods

    /**
     * <p>
     * setCovered
     * </p>
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

        if (DEBUG)
            if (!DefUseFitnessCalculator.traceCoversGoal(this, individual, trace))
                throw new IllegalStateException("calculation flawed. goal wasn't covered");
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
                        result.getTrace()))
                    throw new IllegalStateException(
                            "calculation flawed. goal was covered but fitness was "
                                    + fitness);
            }
        }
    }

    // 	---			Getter 		---

    /**
     * <p>
     * Getter for the field <code>goalVariable</code>.
     * </p>
     *
     * @return a {@link java.lang.String} object.
     */
    public String getGoalVariable() {
        return goalVariable;
    }

    /**
     * <p>
     * Getter for the field <code>coveringObjectId</code>.
     * </p>
     *
     * @return a int.
     */
    public int getCoveringObjectId() {
        return coveringObjectId;
    }

    /**
     * <p>
     * Getter for the field <code>goalDefinition</code>.
     * </p>
     *
     * @return a {@link org.evosuite.coverage.dataflow.Definition} object.
     */
    public Definition getGoalDefinition() {
        return goalDefinition;
    }

    /**
     * <p>
     * Getter for the field <code>goalUse</code>.
     * </p>
     *
     * @return a {@link org.evosuite.coverage.dataflow.Use} object.
     */
    public Use getGoalUse() {
        return goalUse;
    }

    /**
     * <p>
     * Getter for the field <code>goalUseFitness</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.TestFitnessFunction} object.
     */
    public TestFitnessFunction getGoalUseFitness() {
        return goalUseFitness;
    }

    /**
     * <p>
     * Getter for the field <code>goalDefinitionFitness</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.TestFitnessFunction} object.
     */
    public TestFitnessFunction getGoalDefinitionFitness() {
        return goalDefinitionFitness;
    }

    /**
     * <p>
     * isInterMethodPair
     * </p>
     *
     * @return a boolean.
     */
    public boolean isInterMethodPair() {
        return type.equals(DefUsePairType.INTER_METHOD);
    }

    /**
     * <p>
     * isIntraClassPair
     * </p>
     *
     * @return a boolean.
     */
    public boolean isIntraClassPair() {
        return type.equals(DefUsePairType.INTRA_CLASS);
    }

    /**
     * <p>
     * Getter for the field <code>type</code>.
     * </p>
     *
     * @return a
     * {@link org.evosuite.coverage.dataflow.DefUseCoverageTestFitness.DefUsePairType}
     * object.
     */
    public DefUsePairType getType() {
        return type;
    }

    /**
     * <p>
     * isParameterGoal
     * </p>
     *
     * @return a boolean.
     */
    public boolean isParameterGoal() {
        return goalDefinition == null;
    }

    // ---		Inherited from Object 			---

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        StringBuilder r = new StringBuilder();
        r.append(type.toString());
        r.append("-Definition-Use-Pair");
        r.append("\n\t");
        if (isParameterGoal())
            r.append("Parameter-Definition ").append(goalUse.getLocalVariableSlot())
                    .append(" for method ").append(goalUse.getMethodName());
        else
            r.append(goalDefinition.toString());
        r.append("\n\t");
        r.append(goalUse.toString());
        return r.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
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
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        DefUseCoverageTestFitness other = (DefUseCoverageTestFitness) obj;
        if (!Objects.equals(goalDefinition, other.goalDefinition))
            return false;
        if (!Objects.equals(goalUse, other.goalUse))
            return false;
        if (!Objects.equals(goalVariable, other.goalVariable))
            return false;
        return type == other.type;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#compareTo(org.evosuite.testcase.TestFitnessFunction)
     */
    @Override
    public int compareTo(TestFitnessFunction other) {
        if (other instanceof DefUseCoverageTestFitness) {
            DefUseCoverageTestFitness otherFitness = (DefUseCoverageTestFitness) other;
            if (this.goalDefinition == null && otherFitness.goalDefinition != null) {
                return -1;
            }
            if (this.goalDefinition != null && otherFitness.goalDefinition == null) {
                return 1;
            }
            if (this.goalDefinition != null) {
                int defCompare = goalDefinition.compareTo(otherFitness.goalDefinition);
                if (defCompare != 0) return defCompare;
            }
            return goalUse.compareTo(otherFitness.goalUse);
        }
        return compareClassName(other);
    }

    /**
     * @return the covered
     */
    public boolean isCovered() {
        return covered;
    }

    /**
     * @param covered the covered to set
     */
    public void setCovered(boolean covered) {
        this.covered = covered;
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetClass()
     */
    @Override
    public String getTargetClass() {
        return goalUse.getClassName();
    }

    /* (non-Javadoc)
     * @see org.evosuite.testcase.TestFitnessFunction#getTargetMethod()
     */
    @Override
    public String getTargetMethod() {
        return goalUse.getMethodName();
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException,
            IOException {
        DefUsePairType type = (DefUsePairType) ois.readObject();
        Integer useId = (Integer) ois.readObject();
        Integer defId = (Integer) ois.readObject();
        Use use = DefUsePool.getUseByUseId(useId);

        if (use == null)
            throw new IOException("Unable to restore DefUseCoverageTestFitness: Use with ID " + useId + " not found in pool.");

        if (type == DefUsePairType.PARAMETER) {
            initParameterUse(use);
        } else {
            Definition def = DefUsePool.getDefinitionByDefId(defId);
            if (def == null) {
                throw new IOException("Unable to restore DefUseCoverageTestFitness: Definition with ID " + defId + " not found in pool.");
            }
            initRegularDefUse(def, use, type);
        }
    }

    /**
     * Serialize, but need to abstract classloader away
     *
     * @param oos
     * @throws IOException
     */
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.writeObject(type);
        oos.writeObject(goalUse.useId);
        if (goalDefinition != null)
            oos.writeObject(goalDefinition.defId);
        else
            oos.writeObject(0);
    }
}
