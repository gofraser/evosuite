package com.examples.with.different.packagename.objectconstruction.construction;

/**
 * Fixture for construction fitness (EvoObj). The constructor validates its argument, so a
 * method-no-exception goal for {@code scaled()} can only be covered once a valid instance is built.
 * The graded construction distance gives the search a gradient toward a valid constructor argument;
 * plain binary method-no-exception fitness does not.
 */
public class ConstructionTarget {

	private final int value;

	public ConstructionTarget(int value) {
		if (value < 500) {
			throw new IllegalArgumentException("value too small: " + value);
		}
		this.value = value;
	}

	public int scaled() {
		return value * 2;
	}
}
