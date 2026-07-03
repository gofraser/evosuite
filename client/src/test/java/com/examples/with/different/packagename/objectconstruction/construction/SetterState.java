package com.examples.with.different.packagename.objectconstruction.construction;

/**
 * Fixture for construction fitness (EvoObj). {@code run()} throws unless the private field
 * {@code value} has been set positive via the bean setter {@code setValue} — so a template must
 * construct and configure the state before it can call {@code run} cleanly.
 */
public class SetterState {

	private int value;

	public void setValue(int value) {
		this.value = value;
	}

	public void run() {
		if (value <= 0) {
			throw new IllegalStateException("value must be positive");
		}
	}
}
