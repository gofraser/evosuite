package org.evosuite.testcase;

import org.evosuite.Properties;
import org.evosuite.setup.TestClusterUtils;
import org.evosuite.testcase.dmon.DmonPromotionOperator;
import com.examples.with.different.packagename.fields.FieldAccessBase;
import com.examples.with.different.packagename.fields.other.FieldAccessChild;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.reflection.PrivateFieldStatement;
import org.evosuite.testcase.synthesizer.var.VariableCodeGenerationUtil;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.evosuite.utils.generic.JavacScratchCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldCreationAccessibilityCompileTest {
    private String oldPrefix;
    private String oldTarget;
    private String oldMethod;

    @BeforeEach
    void setUp() {
        oldPrefix = Properties.CLASS_PREFIX;
        oldTarget = Properties.TARGET_CLASS;
        oldMethod = Properties.TARGET_METHOD;
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        Properties.TARGET_CLASS = getClass().getName();
        Properties.TARGET_METHOD = "use(I)V";
        Properties.resetTargetClass();
    }

    @AfterEach
    void tearDown() {
        Properties.CLASS_PREFIX = oldPrefix;
        Properties.TARGET_CLASS = oldTarget;
        Properties.TARGET_METHOD = oldMethod;
        Properties.resetTargetClass();
    }

    public static void use(int value) {
    }

    @Test
    void promotedSuperclassFieldsDoNotLeakIntoDirectFieldReads() throws Exception {
        Properties.CLASS_PREFIX = "com.examples.with.different.packagename.fields";
        Properties.TARGET_CLASS = "com.examples.with.different.packagename.fields.FieldAccessBase";
        Properties.resetTargetClass();
        Class<?> child = instrumentedChild();
        TestCase test = new DefaultTestCase();
        test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(child.getConstructor(), child), Collections.emptyList()));
        VariableReference destination = test.addStatement(new IntPrimitiveStatement(test, 0));
        List<VariableReference> values = test.getObjects(int.class, test.size());
        for (VariableReference value : values) {
            if (value instanceof FieldReference) {
                test.addStatement(new AssignmentStatement(test, destination, value));
            }
        }
        assertCompiles(test, Properties.CLASS_PREFIX);
    }

    @Test
    void originalVisibilityIsScopedToTheDefiningLoader() throws Exception {
        Properties.CLASS_PREFIX = "com.examples.with.different.packagename.fields";
        Class<?> promotedChild = instrumentedChild();
        Field promoted = promotedChild.getSuperclass().getDeclaredField("source");
        assertTrue(java.lang.reflect.Modifier.isPublic(promoted.getModifiers()),
                "runtime promotion must remain in place");
        assertFalse(java.lang.reflect.Modifier.isPublic(
                org.evosuite.setup.TestUsageChecker.getOriginalFieldModifiers(promoted)));
        assertFalse(org.evosuite.setup.TestUsageChecker.canUse(promoted, promotedChild),
                "package-private fields are not inherited by a foreign-package receiver");

        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                FieldAccessBase.class.getName().replace('.', '/'), null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "source", "I", null, null).visitEnd();
        writer.visitEnd();
        class OtherLoader extends ClassLoader {
            Class<?> define(byte[] bytes) {
                return defineClass(FieldAccessBase.class.getName(), bytes, 0, bytes.length);
            }
        }
        Field publicField = new OtherLoader().define(writer.toByteArray()).getDeclaredField("source");
        assertTrue(java.lang.reflect.Modifier.isPublic(
                org.evosuite.setup.TestUsageChecker.getOriginalFieldModifiers(publicField)),
                "another loader's same-named public field must not inherit the recorded visibility");
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        assertFalse(org.evosuite.setup.TestUsageChecker.isCallableFromTestPackage(promoted));
        assertTrue(org.evosuite.setup.TestUsageChecker.isCallableFromTestPackage(publicField));
    }

    private static Class<?> instrumentedChild() throws Exception {
        class FixtureLoader extends ClassLoader {
            Class<?> instrument(Class<?> original) throws Exception {
                String name = original.getName().replace('.', '/');
                org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(name);
                org.evosuite.instrumentation.InstrumentationConfig config =
                        new org.evosuite.instrumentation.InstrumentationConfig() {
                            @Override public boolean makeAccessible() { return true; }
                        };
                byte[] bytes = new org.evosuite.instrumentation.BytecodeInstrumentation(config)
                        .transformBytes(this, name, reader);
                return defineClass(original.getName(), bytes, 0, bytes.length);
            }
        }
        FixtureLoader loader = new FixtureLoader();
        loader.instrument(FieldAccessBase.class);
        return loader.instrument(FieldAccessChild.class);
    }

    @Test
    void synthesizerDoesNotCreateAnInaccessibleFieldAssignment() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference child = addChild(test);
        VariableReference value = test.addStatement(new IntPrimitiveStatement(test, 0));
        test.addStatement(new MethodStatement(test,
                new GenericMethod(getClass().getMethod("use", int.class), getClass()), null,
                Collections.singletonList(value)));
        int originalSize = test.size();
        Field field = FieldAccessBase.class.getDeclaredField("source");
        VariableReference result = VariableCodeGenerationUtil.generatePublicFieldSetterOrGetter(
                test, child, "I", new GenericField(field, FieldAccessChild.class), false, null);
        assertCompiles(test);
        assertNull(result, "direct field synthesis must not offer a foreign superclass field");
        assertEquals(originalSize, test.size(), "rejection must not insert setup statements");
    }

    @Test
    void dmonUsesReflectionForForeignInstanceFields() throws Exception {
        assertDmonUsesReflection("buildInstanceFieldAssignment", "source", false);
    }

    @Test
    void dmonUsesReflectionForForeignStaticFields() throws Exception {
        assertDmonUsesReflection("buildStaticFieldAssignment", "staticSource", true);
    }

    private void assertDmonUsesReflection(String factory, String fieldName, boolean isStatic)
            throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference child = addChild(test);
        VariableReference value = test.addStatement(new IntPrimitiveStatement(test, 1));
        Field field = FieldAccessBase.class.getDeclaredField(fieldName);
        Method builder = isStatic
                ? DmonPromotionOperator.class.getDeclaredMethod(factory, TestCase.class, Field.class,
                        VariableReference.class)
                : DmonPromotionOperator.class.getDeclaredMethod(factory, TestCase.class, Field.class,
                        VariableReference.class, VariableReference.class);
        builder.setAccessible(true);
        Statement statement = (Statement) (isStatic ? builder.invoke(null, test, field, value)
                : builder.invoke(null, test, field, child, value));
        test.addStatement(statement);
        assertCompiles(test);
        assertInstanceOf(PrivateFieldStatement.class, statement,
                "DMoN must route inaccessible non-private fields through its reflective path");
        TestCase cloned = test.clone();
        assertCompiles(cloned);
        assertCompiles(cloned.clone());
    }

    @Test
    void dmonStaticFieldOnHiddenClassRendersWithoutInaccessibleCast() throws Exception {
        TestCase test = new DefaultTestCase();
        VariableReference value = test.addStatement(new IntPrimitiveStatement(test, 1));
        Field field = com.examples.with.different.packagename.fields.HiddenStaticHolderAccess.holderClass()
                .getDeclaredField("hiddenStatic");
        Method builder = DmonPromotionOperator.class.getDeclaredMethod("buildStaticFieldAssignment",
                TestCase.class, Field.class, VariableReference.class);
        builder.setAccessible(true);
        Statement statement = (Statement) builder.invoke(null, test, field, value);
        test.addStatement(statement);
        assertInstanceOf(PrivateFieldStatement.class, statement);
        assertCompiles(test);
        TestCase cloned = test.clone();
        assertCompiles(cloned);
        assertCompiles(cloned.clone());
    }

    private static VariableReference addChild(TestCase test) throws Exception {
        return test.addStatement(new ConstructorStatement(test,
                new GenericConstructor(FieldAccessChild.class.getConstructor(), FieldAccessChild.class),
                Collections.emptyList()));
    }

    private static void assertCompiles(TestCase test) {
        assertCompiles(test, "org.evosuite.testcase");
    }

    private static void assertCompiles(TestCase test, String packageName) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        test.accept(visitor);
        StringBuilder source = new StringBuilder("package " + packageName + ";\n");
        for (Class<?> imported : visitor.getImports()) {
            if (imported.getCanonicalName() != null) {
                source.append("import ").append(imported.getCanonicalName()).append(";\n");
            }
        }
        String name = "FieldAccessProbe" + System.nanoTime();
        source.append("public class ").append(name)
                .append(" { public static void run() throws Throwable {\n")
                .append(visitor.getCode()).append("\n} }\n");
        JavacScratchCompiler.Result result = JavacScratchCompiler.compile(
                "evosuite-field-access", name, source.toString());
        assertTrue(result.success, source + "\n" + result.stderr);
    }
}
