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
