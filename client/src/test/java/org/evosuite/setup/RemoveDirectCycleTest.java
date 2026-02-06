package org.evosuite.setup;

import org.evosuite.utils.generic.GenericClass;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Set;
import org.evosuite.utils.generic.GenericAccessibleObject;

public class RemoveDirectCycleTest {

    public static class Y {}

    public static class X {
        public X() {}
        public X(Y y) {}
        public Y getY() { return new Y(); }
    }

    @Before
    public void setUp() {
        TestCluster.reset();
    }

    @Test
    public void testRemoveDirectCycleAggressiveness() throws Exception {
        TestCluster cluster = TestCluster.getInstance();

        GenericClass<?> typeX = GenericClassFactory.get(X.class);
        GenericClass<?> typeY = GenericClassFactory.get(Y.class);

        // Generators for X
        GenericConstructor xCtor1 = new GenericConstructor(X.class.getConstructor(), typeX);
        GenericConstructor xCtor2 = new GenericConstructor(X.class.getConstructor(Y.class), typeX);

        cluster.addGenerator(typeX, xCtor1);
        cluster.addGenerator(typeX, xCtor2);

        // Generator for Y: x.getY()
        GenericMethod yGen = new GenericMethod(X.class.getMethod("getY"), typeX);
        cluster.addGenerator(typeY, yGen);

        // Verification before clean up
        assertTrue(cluster.hasGenerator(typeX));
        assertTrue(cluster.hasGenerator(typeY));

        // Run cleanup
        cluster.removeUnusableGenerators();

        // Verification after clean up
        assertTrue("X should still have generators", cluster.hasGenerator(typeX));

        // This is expected to FAIL with current buggy implementation
        assertTrue("Y should still have generator because X has a no-arg constructor", cluster.hasGenerator(typeY));
    }
}
