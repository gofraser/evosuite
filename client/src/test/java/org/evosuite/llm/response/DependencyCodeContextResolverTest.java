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
package org.evosuite.llm.response;

import org.evosuite.llm.prompt.SutContextProvider;
import org.evosuite.setup.TestCluster;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyCodeContextResolverTest {

    @Test
    void keepsTargetMethodAndStubsOthers() {
        String source = ""
                + "package com.dep;\n"
                + "public class Helper {\n"
                + "    public int targetMethod(int x) {\n"
                + "        if (x < 0) throw new IllegalArgumentException(\"x must be non-negative\");\n"
                + "        return x * 2;\n"
                + "    }\n"
                + "    public String otherMethod(String s) {\n"
                + "        return s.trim().toUpperCase();\n"
                + "    }\n"
                + "}\n";
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                fixedProvider(source), neverProvider());

        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.Helper", "targetMethod", 4);
        Optional<String> excerpt = resolver.resolveExcerpt(frame, 4000);

        assertTrue(excerpt.isPresent(), "expected an excerpt to be returned");
        String body = excerpt.get();
        assertTrue(body.contains("targetMethod"), "target method must be retained");
        assertTrue(body.contains("x must be non-negative"),
                "target method body must be retained verbatim");
        assertTrue(body.contains("otherMethod"), "other method signature stays");
        assertFalse(body.contains("toUpperCase"),
                "other method body should be stubbed away: " + body);
        assertTrue(body.contains("decompiled source"),
                "header should label the source mode");
    }

    @Test
    void keepsClinitWhenFrameIsStaticInitializer() {
        String source = ""
                + "package com.dep;\n"
                + "public class StaticHolder {\n"
                + "    public static final String VALUE;\n"
                + "    static {\n"
                + "        VALUE = System.getProperty(\"unset.property\").toString();\n"
                + "    }\n"
                + "    public void unrelated() { System.out.println(\"hello\"); }\n"
                + "}\n";
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                fixedProvider(source), neverProvider());

        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.StaticHolder", "<clinit>", 5);
        Optional<String> excerpt = resolver.resolveExcerpt(frame, 4000);

        assertTrue(excerpt.isPresent());
        String body = excerpt.get();
        assertTrue(body.contains("System.getProperty"),
                "static initializer body must be retained");
        assertFalse(body.contains("hello"),
                "unrelated method body should be stubbed: " + body);
    }

    @Test
    void cachesAcrossCalls() {
        CountingProvider counter = new CountingProvider("public class A { public void target() {} }");
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(counter, neverProvider());

        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.A", "target", 1);
        resolver.resolveExcerpt(frame, 4000);
        resolver.resolveExcerpt(frame, 4000);
        resolver.resolveExcerpt(frame, 4000);

        assertEquals(1, counter.calls,
                "decompiled output should be cached for repeated lookups in the same conversation");
    }

    @Test
    void fallsBackToBytecodeWhenSourceUnavailable() {
        String fakeBytecode = ""
                + "// class version 52.0 (52)\n"
                + "public class com/dep/B {\n"
                + "  // access flags 0x1\n"
                + "  public boom()V\n"
                + "    NEW java/lang/RuntimeException\n"
                + "    DUP\n"
                + "    LDC \"boom-marker\"\n"
                + "    INVOKESPECIAL java/lang/RuntimeException.<init> (Ljava/lang/String;)V\n"
                + "    ATHROW\n"
                + "    MAXSTACK = 3\n"
                + "    MAXLOCALS = 1\n"
                + "  // access flags 0x1\n"
                + "  public other()V\n"
                + "    RETURN\n"
                + "    MAXSTACK = 0\n"
                + "    MAXLOCALS = 1\n"
                + "}\n";
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                neverProvider(), fixedProvider(fakeBytecode));

        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.B", "boom", 0);
        Optional<String> excerpt = resolver.resolveExcerpt(frame, 4000);

        assertTrue(excerpt.isPresent(), "bytecode fallback should produce an excerpt");
        String body = excerpt.get();
        assertTrue(body.contains("disassembled bytecode"));
        assertTrue(body.contains("boom-marker"), "target method body kept");
    }

    @Test
    void returnsEmptyWhenBothProvidersYieldNothing() {
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                neverProvider(), neverProvider());
        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.Missing", "target", 0);

        assertFalse(resolver.resolveExcerpt(frame, 4000).isPresent());
    }

    @Test
    void respectsBudgetByHardTruncating() {
        StringBuilder big = new StringBuilder("package com.dep; public class C {\n");
        big.append("  public void target() {\n");
        for (int i = 0; i < 200; i++) {
            big.append("    int x").append(i).append(" = ").append(i).append(";\n");
        }
        big.append("  }\n}\n");
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                fixedProvider(big.toString()), neverProvider());

        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.C", "target", 5);
        Optional<String> excerpt = resolver.resolveExcerpt(frame, 600);

        assertTrue(excerpt.isPresent());
        // Header text adds a small fixed overhead; allow some slack.
        assertTrue(excerpt.get().length() <= 800,
                "expected output to be hard-truncated near the budget, got " + excerpt.get().length());
    }

    @Test
    void belowMinimumBudgetReturnsEmpty() {
        DependencyCodeContextResolver resolver = new DependencyCodeContextResolver(
                fixedProvider("public class A { public void target() {} }"), neverProvider());
        DependencyFailureAnalysis.Frame frame =
                new DependencyFailureAnalysis.Frame("com.dep.A", "target", 0);

        assertFalse(resolver.resolveExcerpt(frame, 32).isPresent());
    }

    private static SutContextProvider fixedProvider(String text) {
        return new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                return Optional.of(text);
            }

            @Override
            public String modeLabel() {
                return "fixed";
            }
        };
    }

    private static SutContextProvider neverProvider() {
        return new SutContextProvider() {
            @Override
            public Optional<String> getContext(String className, TestCluster cluster) {
                return Optional.empty();
            }

            @Override
            public String modeLabel() {
                return "never";
            }
        };
    }

    private static class CountingProvider implements SutContextProvider {
        private final String payload;
        int calls;

        CountingProvider(String payload) {
            this.payload = payload;
        }

        @Override
        public Optional<String> getContext(String className, TestCluster cluster) {
            calls++;
            return Optional.of(payload);
        }

        @Override
        public String modeLabel() {
            return "counting";
        }
    }
}
