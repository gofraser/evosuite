/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 */
package org.evosuite.testcase;

import org.evosuite.TestGenerationContext;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EntityWithParametersStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VariableResolverConcreteFactoryTest {

    private final VariableResolutionConfig concreteConfig =
            new VariableResolutionConfig.Builder()
                    .withAllowNull(false)
                    .withCanUseMocks(true)
                    .withCanReuseExistingVariables(false)
                    .withPreferConcreteGenerators(true)
                    .build();

    @Test
    public void createsPathWithPathsFactoryInsteadOfMock() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference result = resolver().createObject(
                test, Path.class, 0, GenerationContext.fromDepth(0), concreteConfig);

        assertEquals(Path.class, result.getType());
        assertEquals(3, test.size());
        assertTrue(test.getStatement(result.getStPosition()) instanceof MethodStatement);
        MethodStatement factory = (MethodStatement) test.getStatement(result.getStPosition());
        assertEquals("java.nio.file.Paths", factory.getMethod().getMethod().getDeclaringClass().getName());
        assertNoMocks(test);
    }

    @Test
    public void createsCharsetUriAndFileWithStablePublicFactories() throws Exception {
        assertConcreteFactory(Charset.class, MethodStatement.class);
        assertConcreteFactory(URI.class, MethodStatement.class);
        assertConcreteFactory(File.class, ConstructorStatement.class);
    }

    @Test
    public void createsBoundedLeafStreamsInsteadOfRecursiveDecorators() throws Exception {
        assertLeafStream(InputStream.class, ByteArrayInputStream.class);
        assertLeafStream(Reader.class, StringReader.class);
    }

    @Test
    public void createsGeneratedInterfaceThroughItsPublicFactory() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference result = resolver().createObject(
                test, FactoryBackedDocument.class, 0,
                GenerationContext.fromDepth(0), concreteConfig);

        assertEquals(2, test.size());
        assertEquals(FactoryBackedDocument.class, result.getVariableClass());
        assertTrue(test.getStatement(0) instanceof MethodStatement);
        MethodStatement factory = (MethodStatement) test.getStatement(0);
        assertEquals("newInstance", factory.getMethod().getName());
        assertEquals(FactoryBackedDocument.Factory.class,
                factory.getMethod().getMethod().getDeclaringClass());
        MethodStatement root = (MethodStatement) test.getStatement(1);
        assertEquals("addNewRoot", root.getMethod().getName());
        assertEquals(result, root.getCallee());
    }

    @Test
    public void readsPublicStaticConstantDirectlyForStableConstruction() throws Exception {
        TestCase test = new DefaultTestCase();

        VariableReference result = resolver().createObject(
                test, CodingErrorAction.class, 0, GenerationContext.fromDepth(0), concreteConfig);

        assertEquals(1, test.size());
        assertTrue(test.getStatement(result.getStPosition()) instanceof FieldStatement);
        FieldStatement statement = (FieldStatement) test.getStatement(result.getStPosition());
        assertEquals("IGNORE", statement.getField().getName());
        assertNoMocks(test);
    }

    @Test
    public void synthesisUsesScalarForUnconstrainedObjectInsteadOfArbitraryGraph()
            throws Exception {
        TestCase test = new DefaultTestCase();
        boolean previous = TestGenerationContext.getInstance().getObjectRuleRuntime()
                .setSynthesisConstruction(true);
        VariableReference result;
        try {
            result = resolver().resolveVariable(
                    test, Object.class, 0, GenerationContext.fromDepth(2), concreteConfig);
        } finally {
            TestGenerationContext.getInstance().getObjectRuleRuntime()
                    .setSynthesisConstruction(previous);
        }

        assertEquals(1, test.size());
        assertTrue(test.getStatement(0) instanceof StringPrimitiveStatement);
        assertEquals(String.class, result.getVariableClass());
        assertTrue(((StringPrimitiveStatement) test.getStatement(0)).isObjectRuleSlot());
        assertFalse(TestGenerationContext.getInstance().getObjectRuleRuntime()
                .isSynthesisConstruction());
        assertNoMocks(test);
    }


    private static void assertConcreteFactory(Class<?> type, Class<?> statementType) throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference result = resolver().createObject(
                test,
                type,
                0,
                GenerationContext.fromDepth(0),
                new VariableResolutionConfig.Builder()
                        .withAllowNull(false)
                        .withCanUseMocks(true)
                        .withCanReuseExistingVariables(false)
                        .withPreferConcreteGenerators(true)
                        .build());

        assertTrue(statementType.isInstance(test.getStatement(result.getStPosition())));
        EntityWithParametersStatement factory =
                (EntityWithParametersStatement) test.getStatement(result.getStPosition());
        VariableReference replacement = new VariableReferenceImpl(test, String.class);
        factory.replaceParameterReference(replacement, 0);
        assertEquals(replacement, factory.getParameterReferences().get(0));
        assertNoMocks(test);
    }

    private static void assertLeafStream(Class<?> requestedType, Class<?> concreteType)
            throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference result = resolver().createObject(
                test,
                requestedType,
                0,
                GenerationContext.fromDepth(0),
                new VariableResolutionConfig.Builder()
                        .withAllowNull(false)
                        .withCanUseMocks(true)
                        .withCanReuseExistingVariables(false)
                        .withPreferConcreteGenerators(true)
                        .build());

        assertEquals(2, test.size());
        assertEquals(concreteType, result.getVariableClass());
        assertTrue(test.getStatement(result.getStPosition()) instanceof ConstructorStatement);
        assertNoMocks(test);
    }

    private static VariableResolver resolver() {
        return new VariableResolver(TestFactory.getInstance());
    }

    private static void assertNoMocks(TestCase test) {
        for (int position = 0; position < test.size(); position++) {
            assertFalse(test.getStatement(position) instanceof FunctionalMockStatement);
        }
    }

    public interface FactoryBackedDocument {
        Object addNewRoot();

        final class Factory {
            private Factory() {
            }

            public static FactoryBackedDocument newInstance() {
                return () -> new Object();
            }
        }
    }

}
