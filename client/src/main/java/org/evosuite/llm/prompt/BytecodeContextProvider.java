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
package org.evosuite.llm.prompt;

import org.evosuite.setup.TestCluster;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

/**
 * Provides disassembled bytecode context for the CUT using ASM's TraceClassVisitor.
 */
public class BytecodeContextProvider implements SutContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(BytecodeContextProvider.class);

    @Override
    public Optional<String> getContext(String className, TestCluster cluster) {
        if (className == null || className.trim().isEmpty()) {
            return Optional.empty();
        }
        try {
            String resourcePath = className.replace('.', '/') + ".class";
            InputStream is = getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                logger.debug("Class file not found on classpath: {}", resourcePath);
                return Optional.empty();
            }
            try {
                ClassReader reader = new ClassReader(is);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                reader.accept(new TraceClassVisitor(pw), ClassReader.SKIP_DEBUG);
                return Optional.of(filterInaccessibleMembers(sw.toString()));
            } finally {
                is.close();
            }
        } catch (IOException e) {
            logger.debug("Failed to disassemble bytecode for {}: {}", className, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.debug("Unexpected error disassembling {}: {}", className, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public String modeLabel() {
        return "Disassembled bytecode";
    }

    ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : getClass().getClassLoader();
    }

    /**
     * Remove private/protected member declarations (and private/protected method bodies)
     * from bytecode context to avoid nudging the LLM toward inaccessible members.
     */
    static String filterInaccessibleMembers(String disassembly) {
        if (disassembly == null || disassembly.isEmpty()) {
            return disassembly;
        }
        String[] lines = disassembly.split("\\R", -1);
        StringBuilder out = new StringBuilder(disassembly.length());
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("private ") || trimmed.startsWith("protected ")) {
                boolean isMethod = trimmed.contains("(") && !trimmed.endsWith(";");
                i++;
                if (isMethod) {
                    while (i < lines.length) {
                        String t = lines[i].trim();
                        if (t.startsWith("// access flags")) {
                            break;
                        }
                        i++;
                    }
                }
                continue;
            }

            if (trimmed.startsWith("// access flags") && i + 1 < lines.length) {
                String declLine = lines[i + 1];
                String declTrim = declLine.trim();
                boolean inaccessible = declTrim.startsWith("private ") || declTrim.startsWith("protected ");
                if (inaccessible) {
                    boolean isMethod = declTrim.contains("(") && !declTrim.endsWith(";");
                    i += 2; // skip access flags + declaration
                    if (isMethod) {
                        while (i < lines.length) {
                            String t = lines[i].trim();
                            if (t.startsWith("// access flags")) {
                                break;
                            }
                            i++;
                        }
                    }
                    continue;
                }
            }

            out.append(line);
            if (i < lines.length - 1) {
                out.append(System.lineSeparator());
            }
            i++;
        }
        return out.toString();
    }
}
