package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModernLanguageFeaturesTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        BytecodeInstructionPool.clearAll();
    }

    @Test
    public void testSwitchExpressionsJava17() throws Exception {
        String className = "bytecode_tests.SwitchExpressionFixture";
        Properties.TARGET_CLASS = className;

        BytecodeFixtureClassLoader loader = new BytecodeFixtureClassLoader("17");

        byte[] rawBytes = loader.getClassBytes(className);
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        byte[] instrumentedBytes = instrumentation.transformBytes(loader, className.replace('.', '/'), new ClassReader(rawBytes));

        assertNotNull(instrumentedBytes);
        int branchCount = org.evosuite.coverage.branch.BranchPool.getInstance(loader).getBranchCountForClass(className);
        assertTrue(branchCount > 0, "No branches found for switch expression in Java 17");
    }

    @Test
    public void testRecordsJava24() throws Exception {
        String className = "bytecode_tests.RecordFixture";
        Properties.TARGET_CLASS = className;

        // Records are fully supported in Java 16+. We built ours for 24.
        BytecodeFixtureClassLoader loader = new BytecodeFixtureClassLoader("24");

        byte[] rawBytes = loader.getClassBytes(className);
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        byte[] instrumentedBytes = instrumentation.transformBytes(loader, className.replace('.', '/'), new ClassReader(rawBytes));

        assertNotNull(instrumentedBytes);
        int methodCount = org.evosuite.graphs.cfg.BytecodeInstructionPool.getInstance(loader).knownMethods(className).size();
        
        // A record has implicitly generated methods like equals, hashCode, toString, and accessor methods.
        assertTrue(methodCount >= 4, "A Record class should have at least 4 methods (init, equals, hashCode, toString, accessors)");
    }
}
