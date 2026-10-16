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
package org.evosuite.testcase;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code new HashMap<String, List<String>>(hashMap0)} with a
 * {@code HashMap<String, groovy.lang.Sequence>} argument, where Sequence extends a raw ArrayList.
 * javac finds no suitable constructor because the raw supertype only converts unchecked at the top
 * level, not inside the copy constructor's {@code Map<? extends K, ? extends V>} argument.
 */
public class RawSupertypeWildcardArgumentCompileTest {

    @SuppressWarnings("rawtypes")
    public static class RawList extends ArrayList {
    }

    @Test
    public void copyConstructorWithRawSupertypeValuesCompiles() throws Exception {
        String oldPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        try {
            TestCase tc = new DefaultTestCase();
            Type sourceType = TypeUtils.parameterize(HashMap.class, String.class, RawList.class);
            VariableReference source = tc.addStatement(new ConstructorStatement(tc,
                    new GenericConstructor(HashMap.class.getConstructor(), sourceType),
                    Collections.emptyList()));
            Type listOfString = TypeUtils.parameterize(List.class, String.class);
            Type targetType = TypeUtils.parameterize(HashMap.class, String.class, listOfString);
            tc.addStatement(new ConstructorStatement(tc,
                    new GenericConstructor(HashMap.class.getConstructor(Map.class), targetType),
                    Collections.singletonList(source)));

            TestCodeVisitor visitor = new TestCodeVisitor();
            tc.accept(visitor);
            StringBuilder source1 = new StringBuilder("package org.evosuite.testcase;\n");
            for (Class<?> imported : visitor.getImports()) {
                String canonical = imported.getCanonicalName();
                if (canonical != null) {
                    source1.append("import ").append(canonical).append(";\n");
                }
            }
            String name = "RawSupertypeWildcardProbe";
            source1.append("public class ").append(name)
                    .append(" { public static void run() throws Throwable {\n")
                    .append(visitor.getCode()).append("}}\n");
            JavacScratchCompiler.Result compiled = JavacScratchCompiler.compile(
                    "raw-supertype-wildcard", name, source1.toString());
            assertTrue(compiled.success, source1 + "\n" + compiled.stderr);
        } finally {
            Properties.CLASS_PREFIX = oldPrefix;
        }
    }
}
