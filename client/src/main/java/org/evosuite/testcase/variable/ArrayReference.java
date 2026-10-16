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
package org.evosuite.testcase.variable;

import org.apache.commons.lang3.ArrayUtils;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Represents a reference to an array in the test case.
 *
 * <p>Note: The array length is currently stored in both ArrayReference and ArrayStatement
 * (if created by one). This duplication should be addressed in future refactorings to
 * avoid inconsistencies.
 *
 * @author Gordon Fraser
 */
public class ArrayReference extends VariableReferenceImpl {

    private static final long serialVersionUID = 3309591356542131910L;

    private int[] lengths;

    /**
     * Constructor for ArrayReference.
     *
     * @param tc    a {@link org.evosuite.testcase.TestCase} object.
     * @param clazz a {@link java.lang.Class} object.
     */
    public ArrayReference(TestCase tc, Class<?> clazz) {
        this(tc, GenericClassFactory.get(clazz),
                new int[ArrayStatement.determineDimensions(clazz)]);
    }

    /**
     * Constructor for ArrayReference.
     *
     * @param tc      a {@link org.evosuite.testcase.TestCase} object.
     * @param clazz   a {@link GenericClass} object.
     * @param lengths an array of int.
     */
    public ArrayReference(TestCase tc, GenericClass<?> clazz, int[] lengths) {
        super(tc, clazz);
        assert (lengths.length > 0);
        setLengths(lengths);
    }

    /**
     * Constructor for ArrayReference.
     *
     * @param tc          a {@link org.evosuite.testcase.TestCase} object.
     * @param clazz       a {@link GenericClass} object.
     * @param arrayLength a int.
     */
    public ArrayReference(TestCase tc, GenericClass<?> clazz, int arrayLength) {
        this(tc, clazz, new int[]{arrayLength});
    }

    /**
     * Returns the indices of an array of the given length that are worth offering as
     * candidate variables or assignment targets.
     *
     * <p>Arrays created by an {@link org.evosuite.testcase.statements.ArrayStatement} are short,
     * but an array returned by the SUT takes its length from the runtime value, which can be
     * arbitrarily large (e.g. {@code SecureRandom.getSeed(int)}). Materialising one
     * {@link ArrayIndex} per element of such an array on every call exhausts the heap, so for
     * long arrays only a bounded number of indices is returned: the leading ones plus randomly
     * chosen ones from the remainder. Arrays of at most {@link #MAX_CANDIDATE_INDICES} elements
     * are enumerated completely and consume no randomness.
     *
     * @param length the (first dimension) length of the array
     * @return ascending list of at most {@link #MAX_CANDIDATE_INDICES} distinct valid indices
     */
    public static List<Integer> getCandidateIndices(int length) {
        if (length <= 0) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>(Math.min(length, MAX_CANDIDATE_INDICES));
        if (length <= MAX_CANDIDATE_INDICES) {
            for (int i = 0; i < length; i++) {
                result.add(i);
            }
            return result;
        }
        int leading = MAX_CANDIDATE_INDICES / 2;
        for (int i = 0; i < leading; i++) {
            result.add(i);
        }
        Set<Integer> random = new TreeSet<>();
        while (random.size() < MAX_CANDIDATE_INDICES - leading) {
            random.add(Randomness.nextInt(leading, length));
        }
        result.addAll(random);
        return result;
    }

    /**
     * Upper bound on the number of indices {@link #getCandidateIndices(int)} returns.
     */
    public static final int MAX_CANDIDATE_INDICES = 20;

    /**
     * getArrayLength.
     *
     * @return a int.
     */
    public int getArrayLength() {
        return lengths[0];
    }

    /**
     * setArrayLength.
     *
     * @param l a int.
     */
    public void setArrayLength(int l) {
        assert (l >= 0);
        lengths[0] = l;
    }

    /**
     * Getter for the field <code>lengths</code>.
     *
     * @return a {@link java.util.List} object.
     */
    public List<Integer> getLengths() {
        return Arrays.asList(ArrayUtils.toObject(lengths));
    }

    /**
     * Returns lengths as defined by the structural array declaration in the test model
     * (ie, {@link ArrayStatement}), if available. Falls back to runtime-inferred lengths.
     */
    public List<Integer> getStructuralLengths() {
        for (int i = 0; i < testCase.size(); i++) {
            Statement statement = testCase.getStatement(i);
            if (statement.getReturnValue().equals(this) && statement instanceof ArrayStatement) {
                return ((ArrayStatement) statement).getLengths();
            }
        }
        return getLengths();
    }

    /**
     * Returns the first-dimension structural length when available.
     */
    public int getStructuralArrayLength() {
        List<Integer> structuralLengths = getStructuralLengths();
        if (structuralLengths.isEmpty()) {
            return 0;
        }
        return structuralLengths.get(0);
    }

    /**
     * Setter for the field <code>lengths</code>.
     *
     * @param lengths an array of int.
     */
    public void setLengths(int[] lengths) {
        this.lengths = new int[lengths.length];
        for (int i = 0; i < lengths.length; i++) {
            this.lengths[i] = lengths[i];
        }
    }

    /**
     * Setter for an element of the field <code>lengths</code>.
     *
     * @param length an int.
     * @param index  an int.
     */
    public void setLength(int length, int index) {
        this.lengths[index] = length;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Create a copy of the current variable.
     */
    @Override
    public VariableReference copy(TestCase newTestCase, int offset) {
        VariableReference newRef = newTestCase.getStatement(getStPosition() + offset).getReturnValue();
        if (newRef instanceof ArrayReference) {
            ArrayReference otherArray = (ArrayReference) newRef;
            otherArray.setLengths(lengths);
            return otherArray;
        } else {
            // FIXME: This part should be redundant

            if (newRef.getComponentType() != null) {
                ArrayReference otherArray = new ArrayReference(newTestCase, type, lengths);
                newTestCase.getStatement(getStPosition() + offset).setRetval(otherArray);
                return otherArray;
            } else {
                // This may happen when cloning a method statement which returns an Object that in fact is an array
                // We'll just create a new ArrayReference in this case.
                ArrayReference otherArray = new ArrayReference(newTestCase, type, lengths);
                newTestCase.getStatement(getStPosition() + offset).setRetval(otherArray);
                return otherArray;
            }
        }
    }

    /**
     * getArrayDimensions.
     *
     * @return a int.
     */
    public int getArrayDimensions() {
        return lengths.length;
    }

    /**
     * Setter for the field <code>lengths</code>.
     *
     * @param lengths a {@link java.util.List} object.
     */
    public void setLengths(List<Integer> lengths) {
        this.lengths = new int[lengths.size()];
        int idx = 0;
        for (Integer length : lengths) {
            this.lengths[idx] = length;
            idx++;
        }
    }

    /**
     * getMaximumIndex.
     *
     * @return the maximum index.
     */
    public int getMaximumIndex() {
        int max = 0;

        for (Statement s : testCase) {
            for (VariableReference var : s.getVariableReferences()) {
                if (var instanceof ArrayIndex) {
                    ArrayIndex index = (ArrayIndex) var;
                    if (index.getArray().equals(this)) {
                        List<Integer> indices = index.getArrayIndices();
                        if (!indices.isEmpty()) {
                            max = Math.max(max, indices.get(0));
                        }
                    }
                }
            }
        }
        return max;
    }

    /**
     * isInitialized.
     *
     * @param index the index.
     * @return true if initialized.
     */
    public boolean isInitialized(int index) {
        return isInitialized(index, testCase.size());
    }

    /**
     * isInitialized.
     *
     * @param index    the index.
     * @param position the position.
     * @return true if initialized.
     */
    public boolean isInitialized(int index, int position) {
        int pos = 0;
        for (Statement s : testCase) {
            if (pos++ >= position) {
                return false;
            }

            if (s instanceof AssignmentStatement) {
                VariableReference ret = s.getReturnValue();
                if (ret instanceof ArrayIndex) {
                    ArrayIndex ai = (ArrayIndex) ret;
                    List<Integer> indices = ai.getArrayIndices();
                    if (ai.getArray().equals(this) && !indices.isEmpty() && indices.get(0) == index) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
