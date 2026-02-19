/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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

package org.evosuite.instrumentation;

import org.evosuite.Properties;
import org.evosuite.runtime.instrumentation.RuntimeInstrumentation;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

public class AdapterChainTest {

    @Before
    public void setup() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void testBasicTransformation() throws IOException {
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation();
        String className = "com/examples/with/different/packagename/SimpleInteger";
        InputStream is = getClass().getResourceAsStream("/" + className + ".class");
        Assert.assertNotNull(is);
        ClassReader reader = new ClassReader(is);
        
        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(), className, reader);
        Assert.assertNotNull(transformed);
        
        // Verify with ASM CheckClassAdapter
        ClassReader resultReader = new ClassReader(transformed);
        CheckClassAdapter.verify(resultReader, true, new java.io.PrintWriter(System.err));
    }

    @Test
    public void testTransformationWithStaticReset() throws IOException {
        Properties.RESET_STATIC_FIELDS = true;
        InstrumentationConfig config = InstrumentationConfig.fromProperties();
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation(config);
        
        String className = "com/examples/with/different/packagename/SimpleInteger";
        InputStream is = getClass().getResourceAsStream("/" + className + ".class");
        Assert.assertNotNull(is);
        ClassReader reader = new ClassReader(is);
        
        byte[] transformed = instrumentation.transformBytes(getClass().getClassLoader(), className, reader);
        Assert.assertNotNull(transformed);
        
        // Check if __STATIC_RESET exists in transformed bytecode
        ClassReader resultReader = new ClassReader(transformed);
        final boolean[] foundReset = {false};
        resultReader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("__STATIC_RESET")) {
                    foundReset[0] = true;
                }
                return null;
            }
        }, 0);
        Assert.assertTrue("Should have found __STATIC_RESET", foundReset[0]);
    }

    @Test
    public void testProjectPrefixStillAppliesTestabilityTransformationWhenTTDisabled() throws Exception {
        Properties.TT = false;
        Properties.PROJECT_PREFIX = "com.examples.with.different.packagename";
        InstrumentationConfig config = InstrumentationConfig.fromProperties();
        BytecodeInstrumentation instrumentation = new BytecodeInstrumentation(config);

        Method method = BytecodeInstrumentation.class
                .getDeclaredMethod("shouldApplyTestabilityTransformation", String.class);
        method.setAccessible(true);
        boolean applies = (boolean) method.invoke(instrumentation,
                "com.examples.with.different.packagename.SimpleInteger");

        Assert.assertTrue("Project prefix classes must still receive testability transformations", applies);
    }
}
