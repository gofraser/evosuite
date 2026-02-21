package org.evosuite.coverage.method;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTrace;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericAccessibleObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.*;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.evosuite.testcase.statements.MethodStatement;

public class MethodCoverageFitnessTest {

    @BeforeEach
    public void setUp() {
        Properties.BREAK_ON_EXCEPTION = true;
        Properties.TEST_ARCHIVE = false;
    }

    private MethodStatement createMockMethodStatement(TestCase tc, String className, String methodName, String descriptor, int position) {
        MethodStatement ms = mock(MethodStatement.class);
        when(ms.getDeclaringClassName()).thenReturn(className);
        when(ms.getMethodName()).thenReturn(methodName);
        when(ms.getDescriptor()).thenReturn(descriptor);
        when(ms.getPosition()).thenReturn(position);
        when(ms.getAssertions()).thenReturn(new HashSet<>());
        VariableReference retval = mock(VariableReference.class);
        when(ms.getReturnValue()).thenReturn(retval);
        // DefaultTestCase.addStatement asserts isValid. We mock isValid to true
        when(ms.isValid()).thenReturn(true);
        return ms;
    }

    @Test
    public void testMethodCoverageFitness() {
        MethodCoverageTestFitness fitness = new MethodCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        MethodStatement statement = createMockMethodStatement(testCase, "com.example.Foo", "bar", "()V", 0);
        testCase.addStatement(statement);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(0.0, fit, 0.001);
    }

    @Test
    public void testMethodCoverageFitnessNotCovered() {
        MethodCoverageTestFitness fitness = new MethodCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        MethodStatement statement = createMockMethodStatement(testCase, "com.example.Other", "baz", "()V", 0);
        testCase.addStatement(statement);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(1.0, fit, 0.001);
    }

    @Test
    public void testMethodCoverageFitnessWithExceptionBefore() {
        MethodCoverageTestFitness fitness = new MethodCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        MethodStatement exceptionStmt = createMockMethodStatement(testCase, "com.example.Other", "baz", "()V", 0);
        testCase.addStatement(exceptionStmt);

        MethodStatement statement = createMockMethodStatement(testCase, "com.example.Foo", "bar", "()V", 1);
        testCase.addStatement(statement);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);
        Map<Integer, Throwable> exceptions = new HashMap<>();
        exceptions.put(0, new RuntimeException());
        result.setThrownExceptions(exceptions);

        Properties.BREAK_ON_EXCEPTION = true;
        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(1.0, fit, 0.001);

        Properties.BREAK_ON_EXCEPTION = false;
        fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(0.0, fit, 0.001);
    }

    @Test
    public void testMethodNoExceptionCoverageFitness() {
        MethodNoExceptionCoverageTestFitness fitness = new MethodNoExceptionCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        MethodStatement statement = createMockMethodStatement(testCase, "com.example.Foo", "bar", "()V", 0);
        testCase.addStatement(statement);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(0.0, fit, 0.001);
    }

    @Test
    public void testMethodNoExceptionCoverageFitnessWithException() {
        MethodNoExceptionCoverageTestFitness fitness = new MethodNoExceptionCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        MethodStatement statement = createMockMethodStatement(testCase, "com.example.Foo", "bar", "()V", 0);
        testCase.addStatement(statement);

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);
        Map<Integer, Throwable> exceptions = new HashMap<>();
        exceptions.put(0, new RuntimeException());
        result.setThrownExceptions(exceptions);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(1.0, fit, 0.001);
    }

    @Test
    public void testMethodTraceCoverageFitness() {
        MethodTraceCoverageTestFitness fitness = new MethodTraceCoverageTestFitness("com.example.Foo", "bar()V");

        TestCase testCase = new DefaultTestCase();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        result.setTrace(trace);

        Map<String, Integer> methodCount = new HashMap<>();
        methodCount.put("com.example.Foo.bar()V", 1);
        when(trace.getMethodExecutionCount()).thenReturn(methodCount);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(0.0, fit, 0.001);
    }

    @Test
    public void testMethodTraceCoverageFitnessInnerClass() {
        MethodTraceCoverageTestFitness fitness = new MethodTraceCoverageTestFitness("com.example.Foo.Inner", "bar()V");

        TestCase testCase = new DefaultTestCase();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(testCase);

        ExecutionResult result = new ExecutionResult(testCase);
        ExecutionTrace trace = mock(ExecutionTrace.class);
        result.setTrace(trace);

        Map<String, Integer> methodCount = new HashMap<>();
        methodCount.put("com.example.Foo$Inner.bar()V", 1);
        when(trace.getMethodExecutionCount()).thenReturn(methodCount);

        double fit = fitness.getFitness(chromosome, result);
        Assertions.assertEquals(0.0, fit, 0.001);
    }
}
