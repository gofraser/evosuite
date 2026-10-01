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
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase.fm;

import com.examples.with.different.packagename.OverloadedFormatInterface;
import com.googlecode.gentyref.TypeToken;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Created by foo on 20/12/15.
 */
public class MethodDescriptorTest {

    private interface BoundedFoo<T extends Number> {
        String foo(T parameter);
    }

    @Test
    public void testMatcher() throws Exception {

        Class<?> klass = Graphics2D.class;
        Method m = klass.getDeclaredMethod("getRenderingHint", RenderingHints.Key.class);

        MethodDescriptor md = new MethodDescriptor(m, GenericClassFactory.get(m.getReturnType()));

        String res = md.getInputParameterMatchers();
        assertTrue(res.contains("nullable("), res);
        assertTrue(res.contains("RenderingHints"), res);
        assertTrue(res.contains("Key"), res);

        assertFalse(res.contains("$"), res);
    }

    @Test
    public void testMatcherResolvesConstrainedGenericParameter() throws Exception {
        Method m = BoundedFoo.class.getDeclaredMethod("foo", Number.class);
        MethodDescriptor md = new MethodDescriptor(
                m,
                GenericClassFactory.get(new TypeToken<BoundedFoo<Integer>>() {
                }.getType()));

        String res = md.getInputParameterMatchers();
        assertTrue(res.contains("anyInt()"), res);
        assertFalse(res.contains("nullable(java.lang.Number.class)"), res);
    }

    /**
     * A method overloaded on its last parameter (Wrapper / Wrapper[] / Object / Object[]), shaped
     * after {@code UnicodeLanguage#format}. The {@code Object} overload used to render a bare
     * {@code any()} matcher, which javac cannot use to pick between the four overloads because
     * {@code any()} returns an unconstrained {@code <T> T}. It must now render a matcher whose
     * static type is fixed to {@code Object}.
     */
    @Test
    public void testOverloadedObjectParameterRendersTypedMatcher() throws Exception {
        Method m = OverloadedFormatInterface.class.getDeclaredMethod(
                "format", String.class, String.class, Object.class);
        MethodDescriptor md = new MethodDescriptor(
                m, GenericClassFactory.get(OverloadedFormatInterface.class));

        String res = md.getInputParameterMatchers();
        assertFalse(res.contains("any()"), res);
        assertTrue(res.contains("nullable(java.lang.Object.class)"), res);
    }

    /**
     * A non-overloaded {@code Object} parameter must also render something javac accepts (it
     * never had an ambiguity problem, but the fix touches every {@code Object}-typed slot, not
     * just the ambiguous ones).
     */
    @Test
    public void testNonOverloadedObjectParameterRendersValidMatcher() throws Exception {
        Method m = OverloadedFormatInterface.class.getDeclaredMethod("formatSingleObject", Object.class);
        MethodDescriptor md = new MethodDescriptor(
                m, GenericClassFactory.get(OverloadedFormatInterface.class));

        String res = md.getInputParameterMatchers();
        assertFalse(res.contains("any()"), res);
        assertTrue(res.contains("nullable(java.lang.Object.class)"), res);
    }

    /**
     * Array-typed and primitive-typed parameters must keep rendering valid matchers: arrays via
     * the existing {@code nullable(X[].class)} fallback, primitives via their dedicated
     * {@code anyXxx()} methods. Neither path is touched by the {@code Object.class} fix, but both
     * are exercised here to confirm that is still true.
     */
    @Test
    public void testArrayAndPrimitiveParametersRenderValidMatchers() throws Exception {
        Method arrayMethod = OverloadedFormatInterface.class.getDeclaredMethod("formatWithArray", Object[].class);
        MethodDescriptor arrayMd = new MethodDescriptor(
                arrayMethod, GenericClassFactory.get(OverloadedFormatInterface.class));
        String arrayRes = arrayMd.getInputParameterMatchers();
        assertTrue(arrayRes.contains("nullable(java.lang.Object[].class)"), arrayRes);

        Method primitiveMethod = OverloadedFormatInterface.class.getDeclaredMethod("formatWithPrimitive", int.class);
        MethodDescriptor primitiveMd = new MethodDescriptor(
                primitiveMethod, GenericClassFactory.get(OverloadedFormatInterface.class));
        String primitiveRes = primitiveMd.getInputParameterMatchers();
        assertTrue(primitiveRes.contains("anyInt()"), primitiveRes);
    }
}
