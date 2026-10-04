package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing the same field (instance or static) or the same array element twice must
 * render the same access expression both times. The assignment target is not a
 * declared local, so it must never be given a fresh suffixed name ("obj.f_1").
 * The rendered code is compiled with javac rather than matched as a string.
 */
public class RepeatedTargetWriteCompileTest {

    private String originalTargetClass;
    private String originalClassPrefix;
    private String originalSubPrefix;
    private String originalProjectPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        originalSubPrefix = Properties.SUB_PREFIX;
        originalProjectPrefix = Properties.PROJECT_PREFIX;
        Properties.TARGET_CLASS = RepeatedTargetWriteCompileTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.SUB_PREFIX = originalSubPrefix;
        Properties.PROJECT_PREFIX = originalProjectPrefix;
        Properties.resetTargetClass();
    }

    public static class Holder {
        public int value;
        public static int shared;
    }

    @Test
    public void testSameInstanceFieldWrittenTwiceCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.class.getDeclaredConstructor(), Holder.class),
                Collections.<VariableReference>emptyList()));
        VariableReference one = tc.addStatement(new IntPrimitiveStatement(tc, 1));
        VariableReference two = tc.addStatement(new IntPrimitiveStatement(tc, 2));
        // Each statement owns its own reference object, as in generated tests.
        tc.addStatement(new AssignmentStatement(tc, new FieldReference(tc,
                new GenericField(Holder.class.getField("value"), Holder.class), holder), one));
        tc.addStatement(new AssignmentStatement(tc, new FieldReference(tc,
                new GenericField(Holder.class.getField("value"), Holder.class), holder), two));

        assertRendersAndCompiles(tc);
    }

    @Test
    public void testSameStaticFieldWrittenTwiceCompiles() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference one = tc.addStatement(new IntPrimitiveStatement(tc, 1));
        VariableReference two = tc.addStatement(new IntPrimitiveStatement(tc, 2));
        tc.addStatement(new AssignmentStatement(tc, new FieldReference(tc,
                new GenericField(Holder.class.getField("shared"), Holder.class)), one));
        tc.addStatement(new AssignmentStatement(tc, new FieldReference(tc,
                new GenericField(Holder.class.getField("shared"), Holder.class)), two));

        assertRendersAndCompiles(tc);
    }

    @Test
    public void testSameArrayIndexWrittenTwiceCompiles() {
        TestCase tc = new DefaultTestCase();
        ArrayStatement array = new ArrayStatement(tc, int[].class, 3);
        tc.addStatement(array);
        VariableReference one = tc.addStatement(new IntPrimitiveStatement(tc, 1));
        VariableReference two = tc.addStatement(new IntPrimitiveStatement(tc, 2));
        tc.addStatement(new AssignmentStatement(tc,
                new ArrayIndex(tc, array.getArrayReference(), 1), one));
        tc.addStatement(new AssignmentStatement(tc,
                new ArrayIndex(tc, array.getArrayReference(), 1), two));

        assertRendersAndCompiles(tc);
    }

    private static void assertRendersAndCompiles(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();
        assertFalse(code.matches("(?s).*[\\w\\]]_\\d+\\s*=.*"),
                "A field or array element target was given a suffixed name:\n" + code);

        StringBuilder source = new StringBuilder("package org.evosuite.testcase;\n");
        for (Class<?> imported : visitor.getImports()) {
            String canonical = imported.getCanonicalName();
            if (canonical != null) {
                source.append("import ").append(canonical).append(";\n");
            }
        }
        String className = "RepeatedTargetWriteProbe" + System.nanoTime();
        source.append("public class ").append(className).append(" {\n")
                .append("    public static void run() throws Throwable {\n");
        for (String line : code.split("\\R")) {
            source.append("        ").append(line).append("\n");
        }
        source.append("    }\n}\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-repeated-target-write-compile", className, source.toString());
        assertTrue(result.success, "Rendered test did not compile:\n" + source + "\n" + result.stderr);
    }
}
