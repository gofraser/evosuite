package org.evosuite.testcase.synthesizer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import org.objectweb.asm.Type;

public class ReflectionUtil {

	/**
	 * Returns the JVM descriptor of {@code m}, the form under which its bytecode is looked up.
	 */
	public static String getSignature(Executable m) {
		return m instanceof Method
				? Type.getMethodDescriptor((Method) m)
				: Type.getConstructorDescriptor((Constructor<?>) m);
	}
}
