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
                return Optional.of(sw.toString());
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
}
