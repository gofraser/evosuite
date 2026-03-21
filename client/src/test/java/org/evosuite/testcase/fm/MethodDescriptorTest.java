/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.fm;

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
}
