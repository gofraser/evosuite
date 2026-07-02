package com.examples.with.different.packagename.objectconstruction.reflection;

/**
 * Fixture for the EvoObj reflection fallback (plan E2). The private leaf field {@code secret} has
 * no reachable public setter and no constructor that assigns it (it is left at its default value),
 * so establishing the state the target branch reads is only possible through the reflection
 * fallback ({@code PrivateFieldStatement}).
 */
public class NoSetterFieldExample {

	private int secret;

	public void targetM(int index) {
		if (secret == 42) {
			System.currentTimeMillis();
		}
	}
}
