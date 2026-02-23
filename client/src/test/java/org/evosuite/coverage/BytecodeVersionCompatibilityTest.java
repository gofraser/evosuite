package org.evosuite.coverage;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.graphs.cfg.BytecodeInstructionPool;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class BytecodeVersionCompatibilityTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"8", "11", "17", "24"})
    public void testCompatibilityFixture(String javaVersion) throws Exception {
        String className = "bytecode_tests.CompatibilityFixture";
        Properties.TARGET_CLASS = className;
        
        BytecodeFixtureClassLoader loader = new BytecodeFixtureClassLoader(javaVersion);
        
        byte[] rawBytes = loader.getClassBytes(className);
        assertNotNull(rawBytes, "Failed to load raw bytes for Java " + javaVersion);
        assertTrue(rawBytes.length > 0);
        
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        
        // This will instrument the class and register instructions with BytecodeInstructionPool
        byte[] instrumentedBytes = instrumentation.transformBytes(loader, className.replace('.', '/'), new ClassReader(rawBytes));
        
        assertNotNull(instrumentedBytes, "Instrumentation failed for Java " + javaVersion);
        assertTrue(instrumentedBytes.length > 0);
        
        // Verify goals extracted. It should be exactly the same regardless of bytecode version!
        int branchCount = org.evosuite.coverage.branch.BranchPool.getInstance(loader).getBranchCountForClass(className);
        int methodCount = org.evosuite.graphs.cfg.BytecodeInstructionPool.getInstance(loader).knownMethods(className).size();
        
        // CompatibilityFixture has testControlFlow (with loops and switch), testExceptionHandling, skip, and <init>.
        // Let's print out the exact numbers and assert they are > 0.
        // We will then assert they equal the baseline for Java 8.
        
        assertTrue(branchCount > 0, "No branches found for Java " + javaVersion);
        assertTrue(methodCount > 2, "Methods not found for Java " + javaVersion);
        
        // Execute the method to collect an ExecutionTrace
        Class<?> fixtureClass = loader.defineClassFromBytes(className, instrumentedBytes);
        Object instance = fixtureClass.getDeclaredConstructor().newInstance();
        Method method = fixtureClass.getMethod("testControlFlow", int.class, int.class);
        
        ExecutionTracer.enable();
        ExecutionTracer.setCheckCallerThread(false);
        ExecutionTracer.getExecutionTracer().clear();
        
        Object result = method.invoke(instance, 5, 10);
        
        ExecutionTrace trace = ExecutionTracer.getExecutionTracer().getTrace();
        ExecutionTracer.disable();
        
        Set<Integer> coveredLines = trace.getCoveredLines(className);
        Set<Integer> coveredTrueBranches = trace.getCoveredTrueBranches();
        Set<Integer> coveredFalseBranches = trace.getCoveredFalseBranches();
        
        assertTrue(coveredLines.size() > 0, "No lines covered during execution for Java " + javaVersion);
        
        // Assert parity with Java 8
        if (!javaVersion.equals("8")) {
            BytecodeFixtureClassLoader loader8 = new BytecodeFixtureClassLoader("8");
            byte[] raw8 = loader8.getClassBytes(className);
            BytecodeInstrumentation inst8 = new BytecodeInstrumentation();
            byte[] instrumented8 = inst8.transformBytes(loader8, className.replace('.', '/'), new ClassReader(raw8));
            
            int branch8 = org.evosuite.coverage.branch.BranchPool.getInstance(loader8).getBranchCountForClass(className);
            int method8 = org.evosuite.graphs.cfg.BytecodeInstructionPool.getInstance(loader8).knownMethods(className).size();
            
            assertEquals(branch8, branchCount, "Branch count mismatch between Java 8 and Java " + javaVersion);
            assertEquals(method8, methodCount, "Method count mismatch between Java 8 and Java " + javaVersion);
            
            // Execute Java 8 version
            Class<?> fixtureClass8 = loader8.defineClassFromBytes(className, instrumented8);
            Object instance8 = fixtureClass8.getDeclaredConstructor().newInstance();
            Method method8_obj = fixtureClass8.getMethod("testControlFlow", int.class, int.class);
            
            ExecutionTracer.enable();
            ExecutionTracer.setCheckCallerThread(false);
            ExecutionTracer.getExecutionTracer().clear();
            
            Object result8 = method8_obj.invoke(instance8, 5, 10);
            
            ExecutionTrace trace8 = ExecutionTracer.getExecutionTracer().getTrace();
            ExecutionTracer.disable();
            
            assertEquals(result8, result, "Method execution result mismatch");
            assertEquals(trace8.getCoveredLines(className).size(), coveredLines.size(), "Covered lines mismatch");
            assertEquals(trace8.getCoveredTrueBranches().size(), coveredTrueBranches.size(), "Covered true branches mismatch");
            assertEquals(trace8.getCoveredFalseBranches().size(), coveredFalseBranches.size(), "Covered false branches mismatch");
        }
    }
}
