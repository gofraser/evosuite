package com.examples.with.different.packagename.objectconstruction.construction;

/**
 * Fixture for construction fitness (EvoObj). The constructor validates its argument, so building
 * a valid receiver for {@code run()} requires a good constructor value; a bad one throws before the
 * call can execute.
 */
public class ConstructorValidated {

	private final int value;

	public ConstructorValidated(int value) {
		if (value < 10) {
			throw new IllegalArgumentException("value must be >= 10");
		}
		this.value = value;
	}

	public int run() {
		return value;
	}
}
