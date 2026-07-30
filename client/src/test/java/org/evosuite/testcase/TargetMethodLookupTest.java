/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.testcase;

import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.synthesizer.TargetContext;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.MethodUtil;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

class TargetMethodLookupTest {

    @AfterEach
    void clearTargetContext() {
        TargetContext.clearActive();
    }

    @Test
    void targetLookupRequiresDeclaringClassAsWellAsSignature() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor constructor = new GenericConstructor(
                StringBuilder.class.getConstructor(), StringBuilder.class);
        ConstructorStatement constructorStatement = new ConstructorStatement(
                test, constructor, Collections.emptyList());
        VariableReference stringBuilder = test.addStatement(constructorStatement);

        Method otherLength = StringBuilder.class.getMethod("length");
        String signature = "length" + MethodUtil.getSignature(otherLength);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(otherLength, StringBuilder.class), stringBuilder,
                Collections.emptyList()));

        TargetContext.setActive(new TargetContext(String.class.getName(), signature, null));

        Assertions.assertNotNull(test.findMethodCallStatement(signature));
        Assertions.assertNull(test.findTargetMethodCallStatement(),
                "a same-signature call on another class is not the target");

        StringPrimitiveStatement targetReceiver = new StringPrimitiveStatement(test, "value");
        VariableReference string = test.addStatement(targetReceiver);
        Method targetLength = String.class.getMethod("length");
        MethodStatement expected = new MethodStatement(test,
                new GenericMethod(targetLength, String.class), string, Collections.emptyList());
        test.addStatement(expected);

        Assertions.assertSame(expected, test.findTargetMethodCallStatement());
    }

    @Test
    void targetExecutableLookupFindsConstructorEntry() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference argument = test.addStatement(
                new StringPrimitiveStatement(test, "value"));
        GenericConstructor constructor = new GenericConstructor(
                StringBuilder.class.getConstructor(String.class), StringBuilder.class);
        ConstructorStatement expected = new ConstructorStatement(
                test, constructor, Collections.singletonList(argument));
        test.addStatement(expected);

        TargetContext.setActive(new TargetContext(
                StringBuilder.class.getName(),
                constructor.getNameWithDescriptor(),
                null,
                true));

        Assertions.assertSame(expected, test.findTargetExecutableStatement());
        Assertions.assertNull(test.findTargetMethodCallStatement(),
                "the legacy method-only lookup must remain method-only");
    }

    @Test
    void missingExactTargetHasMaximalLegitimacyDistance() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        GenericConstructor constructor = new GenericConstructor(
                StringBuilder.class.getConstructor(), StringBuilder.class);
        VariableReference receiver = test.addStatement(new ConstructorStatement(
                test, constructor, Collections.emptyList()));
        Method otherLength = StringBuilder.class.getMethod("length");
        String signature = "length" + MethodUtil.getSignature(otherLength);
        test.addStatement(new MethodStatement(test,
                new GenericMethod(otherLength, StringBuilder.class), receiver,
                Collections.emptyList()));
        TargetContext.setActive(new TargetContext(String.class.getName(), signature, null));

        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(test);

        Assertions.assertEquals(Double.MAX_VALUE, chromosome.getLegitimacyDistance());
    }
}
