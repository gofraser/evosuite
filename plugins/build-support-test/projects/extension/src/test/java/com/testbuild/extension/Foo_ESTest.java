package com.testbuild.extension;

import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EvoRunnerParameters
public class Foo_ESTest {

    @RegisterExtension
    static EvoSuiteExtension runner = new EvoSuiteExtension(Foo_ESTest.class);

    @Test
    public void testAdd() {
        assertEquals(3, Foo.add(1, 2));
    }
}
