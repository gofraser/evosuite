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

package org.evosuite.testcase.variable;

/**
 * Represents the symbolic name of the symbolic value of an array length.
 *
 * @author Ignacio Lebrero
 */
public class ArraySymbolicLengthName {

    /**
     * Separator.
     */
    public static final String ARRAY_LENGTH_NAME_SEPARATOR = "_";
    /**
     * Separator regex.
     */
    public static final String ARRAY_LENGTH_NAME_SEPARATOR_REGEX = "\\_";
    /**
     * Suffix.
     */
    public static final String ARRAY_LENGTH_SYMBOLIC_NAME_SUFFIX = "length";
    public static final String ARRAY_LENGTH_SYMBOLIC_NAME_INVALID_FOR_NAME_EXCEPTION =
            "Array length symbolic name invalid for name: ";

    /**
     * Sections amount.
     */
    public static final int ARRAY_LENGTH_SYMBOLIC_NAME_SECTIONS_AMOUNT = 3;
    /**
     * Dimension position.
     */
    public static final int ARRAY_LENGTH_SYMBOLIC_NAME_DIMENSION_POSITION = 2;
    /**
     * Array name position.
     */
    public static final int ARRAY_LENGTH_SYMBOLIC_NAME_ARRAY_NAME_POSITION = 0;
    /**
     * Dimension tag position.
     */
    public static final int ARRAY_LENGTH_SYMBOLIC_NAME_DIMENSION_TAG_POSITION = 1;

    private final int dimension;

    private final String arrayReferenceName;
    private final String symbolicName;

    /**
     * Constructor for ArraySymbolicLengthName using a symbolic name.
     *
     * @param symbolicName the symbolic name to parse.
     */
    public ArraySymbolicLengthName(String symbolicName) {
        if (!isArraySymbolicLengthVariableName(symbolicName)) {
            throw new IllegalArgumentException(ARRAY_LENGTH_SYMBOLIC_NAME_INVALID_FOR_NAME_EXCEPTION + symbolicName);
        }

        String[] symbolicNameSections = symbolicName.split(ARRAY_LENGTH_NAME_SEPARATOR_REGEX);
        this.arrayReferenceName = symbolicNameSections[ARRAY_LENGTH_SYMBOLIC_NAME_ARRAY_NAME_POSITION];
        this.dimension = Integer.parseInt(
                symbolicNameSections[ARRAY_LENGTH_SYMBOLIC_NAME_DIMENSION_POSITION]
        );
        this.symbolicName = symbolicName;
    }

    /**
     * Constructor for ArraySymbolicLengthName using array reference name and dimension.
     *
     * @param arrayReferenceName the name of the array reference.
     * @param dimension the dimension of the array.
     */
    public ArraySymbolicLengthName(String arrayReferenceName, int dimension) {
        this.arrayReferenceName = arrayReferenceName;
        this.dimension = dimension;
        this.symbolicName = buildSymbolicLengthDimensionName(arrayReferenceName, dimension);
    }

    /**
     * Getter for the field <code>dimension</code>.
     *
     * @return a int.
     */
    public int getDimension() {
        return dimension;
    }

    /**
     * Getter for the field <code>symbolicName</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getSymbolicName() {
        return symbolicName;
    }

    /**
     * Getter for the field <code>arrayReferenceName</code>.
     *
     * @return a {@link java.lang.String} object.
     */
    public String getArrayReferenceName() {
        return arrayReferenceName;
    }

    /**
     * Builds the name of an array length symbolic variable.
     *
     * @param arrayReferenceName the reference name.
     * @param dimension the dimension.
     * @return the built symbolic length dimension name.
     */
    public static String buildSymbolicLengthDimensionName(String arrayReferenceName, int dimension) {
        return new StringBuilder()
                .append(arrayReferenceName)
                .append(ARRAY_LENGTH_NAME_SEPARATOR)
                .append(ARRAY_LENGTH_SYMBOLIC_NAME_SUFFIX)
                .append(ARRAY_LENGTH_NAME_SEPARATOR)
                .append(dimension)
                .toString();
    }

    /**
     * Checks whether a symbolic variable name corresponds to an array's length.
     *
     * @param symbolicVariableName the symbolic variable name.
     * @return true if the symbolic variable name corresponds to an array's length, false otherwise.
     */
    public static boolean isArraySymbolicLengthVariableName(String symbolicVariableName) {
        String[] nameSections = symbolicVariableName.split(ARRAY_LENGTH_NAME_SEPARATOR_REGEX);

        return nameSections.length == ARRAY_LENGTH_SYMBOLIC_NAME_SECTIONS_AMOUNT
                && nameSections[ARRAY_LENGTH_SYMBOLIC_NAME_DIMENSION_TAG_POSITION]
                .equals(ARRAY_LENGTH_SYMBOLIC_NAME_SUFFIX);
    }
}
