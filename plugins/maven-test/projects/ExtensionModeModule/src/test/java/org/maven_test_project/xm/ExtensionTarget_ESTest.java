package org.maven_test_project.xm;

import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EvoRunnerParameters
public class ExtensionTarget_ESTest {

    @RegisterExtension
    static EvoSuiteExtension runner = new EvoSuiteExtension(ExtensionTarget_ESTest.class);

    @Test
    public void testTwice() {
        assertEquals(10, ExtensionTarget.twice(5));
    }

    @Test
    public void testExtensionEnvTargetClassIsCovered() {
        ExtensionEnvTarget target = new ExtensionEnvTarget();
        assertNotNull(target);
        assertFalse(ExtensionEnvTarget.check());
    }

    @Test
    public void testExtensionProfileTargetClassIsCovered() {
        assertEquals(0, ExtensionProfileTarget.clampToNonNegative(-7));
        assertEquals(5, ExtensionProfileTarget.clampToNonNegative(5));
    }
}
