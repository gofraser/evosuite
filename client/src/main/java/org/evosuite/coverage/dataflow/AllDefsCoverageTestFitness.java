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
import org.evosuite.coverage.statement.StatementCoverageTestFitness;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

/**
 * Evaluate fitness of a single test case with respect to one Definition-Use
 * pair.
 *
 * <p>For more information look at the comment from method getDistance().
 *
 * @author Andre Mis
 */
public class AllDefsCoverageTestFitness extends TestFitnessFunction {

    private static final long serialVersionUID = 1L;
    // The definition and the use goals refer to the class loader of the client, which cannot be sent
    // to the master: only the identity of the definition is serialized, so that the goal can be
    // compared, printed and hashed on the master, and the rest is looked up again, if the pool is
    // available on the receiving side.
    private transient Definition targetDef;
    private transient int defId;
    private transient String targetClass;
    private transient String targetMethod;
    private transient String description;
    private final TestFitnessFunction goalDefinitionFitness;
    private transient Map<Use, DefUseCoverageTestFitness> uses;

    /**
     * Constructor for AllDefsCoverageTestFitness.
     *
     * @param def  a {@link org.evosuite.coverage.dataflow.Definition} object.
     * @param uses a {@link java.util.Map} object.
     */
    public AllDefsCoverageTestFitness(Definition def,
                                      Map<Use, DefUseCoverageTestFitness> uses) {
        this.targetDef = def;
        this.defId = def.defId;
        this.targetClass = def.getClassName();
        this.targetMethod = def.getMethodName();
        this.description = def.toString();
        this.goalDefinitionFitness = new StatementCoverageTestFitness(def.getClassName(),
                def.getMethodName(), def.getInstructionId());
        this.uses = uses;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getFitness(TestChromosome individual, ExecutionResult result) {

        boolean archive = Properties.TEST_ARCHIVE;
        Properties.TEST_ARCHIVE = false;
        double defFitness = goalDefinitionFitness.getFitness(individual, result);
        if (defFitness == 0.0) {
            individual.getTestCase().removeCoveredGoal(goalDefinitionFitness);
        }
        Properties.TEST_ARCHIVE = archive;

        if (defFitness > 0) {
            return 1 + normalize(defFitness);
        }

        // TODO: filter all objects
        // TODO: compute minimum over all use-fitnesses
        // TODO: return that minimum after normalization, stop once a use-fitness is 0

        // first inefficient version:
        double min = Double.MAX_VALUE;
        for (Use use : uses.keySet()) {
            double useFitness = uses.get(use).getFitness(individual, result);
            if (useFitness == 0) {
                return 0;
            }
            if (useFitness < min) {
                min = useFitness;
            }
        }

        return min;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((targetDef == null) ? defId : targetDef.hashCode());
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
        AllDefsCoverageTestFitness other = (AllDefsCoverageTestFitness) obj;
        if (targetDef == null || other.targetDef == null) {
            return defId == other.defId && targetClass.equals(other.targetClass)
                    && targetMethod.equals(other.targetMethod);
        }
        return targetDef.equals(other.targetDef);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.evosuite.testcase.TestFitnessFunction#compareTo(org.evosuite.testcase.TestFitnessFunction)
     */
    @Override
    public int compareTo(TestFitnessFunction other) {
        if (other instanceof AllDefsCoverageTestFitness) {
            AllDefsCoverageTestFitness otherFitness = (AllDefsCoverageTestFitness) other;
            if (targetDef != null && otherFitness.targetDef != null) {
                return targetDef.compareTo(otherFitness.targetDef);
            }
            int result = targetClass.compareTo(otherFitness.targetClass);
            if (result == 0) {
                result = targetMethod.compareTo(otherFitness.targetMethod);
            }
            if (result == 0) {
                result = Integer.compare(defId, otherFitness.defId);
            }
            return result;
        }
        return compareClassName(other);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "AllDef-Goal " + description;
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

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        oos.writeInt(defId);
        oos.writeUTF(targetClass);
        oos.writeUTF(targetMethod);
        oos.writeUTF(description);
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        ois.defaultReadObject();
        defId = ois.readInt();
        targetClass = ois.readUTF();
        targetMethod = ois.readUTF();
        description = ois.readUTF();

        // The pool is empty on the master side of the RMI connection, where the definition and the
        // use goals stay null (see DefUseCoverageTestFitness).
        targetDef = DefUsePool.getDefinitionByDefId(defId);
        if (targetDef != null) {
            uses = DefUseCoverageFactory.getRegisteredGoalsForDefinition(targetDef);
        }
    }

}
