package org.evosuite.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestUsageCheckerMissingSignatureTest {
    @TempDir
    Path fixtureRoot;
    private static final String PREFIX = "org/evosuite/setup/missing/";
    private static final String CONTRACT = PREFIX + "Contract";
    private static final String OWNER = PREFIX + "Owner";
    private static final String BASE = PREFIX + "Base";
    private static final String ABSTRACT = PREFIX + "AbstractOverride";

    @Test
    void ownImplementationSurvivesAnUnrelatedMissingSignature() throws Exception {
        FixtureLoader loader = new FixtureLoader(fixtureRoot);
        Class<?> contract = loader.define(CONTRACT, interfaceBytes("I"));
        Class<?> owner = loader.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT, "I", false, true));
        Method method = contract.getMethod("value");
        assertThrows(NoClassDefFoundError.class, owner::getMethods);
        assertThrows(NoClassDefFoundError.class, owner::getDeclaredMethods);
        assertEquals(7, method.invoke(owner.getDeclaredConstructor().newInstance()));
        assertTrue(TestUsageChecker.canUse(method, owner));
    }

    @Test
    void unreadableAbstractOverrideMustHideItsConcreteSuperclass() throws Exception {
        FixtureLoader loader = new FixtureLoader(fixtureRoot);
        Class<?> contract = loader.define(CONTRACT, interfaceBytes("I"));
        loader.define(BASE, classBytes(BASE, "java/lang/Object", CONTRACT, "I", false, false));
        Class<?> abstractOwner = loader.define(ABSTRACT, classBytes(ABSTRACT, BASE, null, "I", true, true));
        Class<?> owner = loader.define(OWNER, classBytes(OWNER, ABSTRACT, null, null, false, false));
        assertThrows(NoClassDefFoundError.class, owner::getMethods);
        assertThrows(NoClassDefFoundError.class, abstractOwner::getDeclaredMethods);
        assertFalse(TestUsageChecker.canUse(contract.getMethod("value"), owner),
                "the unreadable nearest abstract declaration still hides Base.value()");
    }

    @Test
    void classfileFallbackRequiresTheFullReturnDescriptor() throws Exception {
        FixtureLoader loader = new FixtureLoader(fixtureRoot);
        Class<?> contract = loader.define(CONTRACT, interfaceBytes("Ljava/lang/Object;"));
        Class<?> owner = loader.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT,
                "Ljava/lang/String;", false, true));
        assertThrows(NoClassDefFoundError.class, owner::getMethods);
        assertFalse(TestUsageChecker.canUse(contract.getMethod("value"), owner),
                "String value() cannot implement Object value() without its bridge");
    }

    @Test
    void missingSignatureDoesNotSupplyAnAbsentImplementation() throws Exception {
        FixtureLoader loader = new FixtureLoader(fixtureRoot);
        Class<?> contract = loader.define(CONTRACT, interfaceBytes("I"));
        Class<?> owner = loader.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT, null, false, true));
        assertThrows(NoClassDefFoundError.class, owner::getMethods);
        assertFalse(TestUsageChecker.canUse(contract.getMethod("value"), owner));
    }

    @Test
    void missingOrWrongClassResourcesRemainConservative() throws Exception {
        FixtureLoader loader = new FixtureLoader(fixtureRoot);
        Class<?> contract = loader.define(CONTRACT, interfaceBytes("I"));
        Class<?> owner = loader.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT, "I", false, true));
        Method method = contract.getMethod("value");
        loader.resources.remove(OWNER + ".class");
        assertFalse(TestUsageChecker.canUse(method, owner));
        loader.replaceResource(OWNER + ".class", classBytes(PREFIX + "OtherOwner", "java/lang/Object",
                CONTRACT, "I", false, false));
        assertFalse(TestUsageChecker.canUse(method, owner));
    }

    @Test
    void parentResourceCannotDescribeAChildLoaderDefinition() throws Exception {
        FixtureLoader parent = new FixtureLoader(fixtureRoot);
        Class<?> contract = parent.define(CONTRACT, interfaceBytes("I"));
        parent.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT, "I", false, false));
        FixtureLoader child = new FixtureLoader(fixtureRoot, parent);
        Class<?> owner = child.define(OWNER, classBytes(OWNER, "java/lang/Object", CONTRACT, null, false, true));
        child.resourceOverride = parent.getResource(OWNER + ".class");
        assertThrows(NoClassDefFoundError.class, owner::getMethods);
        assertFalse(TestUsageChecker.canUse(contract.getMethod("value"), owner),
                "a concrete parent-loader resource does not describe the child's abstract gap");
    }

    private static byte[] interfaceBytes(String returnDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                CONTRACT, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()" + returnDescriptor,
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classBytes(String name, String parent, String contract,
                                     String returnDescriptor, boolean isAbstract, boolean missingSignature) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER
                        | (isAbstract ? Opcodes.ACC_ABSTRACT : 0), name, null, parent,
                contract == null ? null : new String[]{contract});
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        if (returnDescriptor != null) {
            MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC
                            | (isAbstract ? Opcodes.ACC_ABSTRACT : 0), "value", "()" + returnDescriptor,
                    null, null);
            if (!isAbstract) {
                value.visitCode();
                if (returnDescriptor.equals("I")) {
                    value.visitIntInsn(Opcodes.BIPUSH, 7);
                    value.visitInsn(Opcodes.IRETURN);
                } else {
                    value.visitInsn(Opcodes.ACONST_NULL);
                    value.visitInsn(Opcodes.ARETURN);
                }
                value.visitMaxs(1, 1);
            }
            value.visitEnd();
        }
        if (missingSignature) {
            MethodVisitor unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC, "unrelated",
                    "()Lorg/evosuite/setup/missing/Absent;", null, null);
            unrelated.visitCode();
            unrelated.visitInsn(Opcodes.ACONST_NULL);
            unrelated.visitInsn(Opcodes.ARETURN);
            unrelated.visitMaxs(1, 1);
            unrelated.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> resources = new HashMap<>();
        private final Path directory;
        private URL resourceOverride;

        FixtureLoader(Path root) {
            this(root, TestUsageCheckerMissingSignatureTest.class.getClassLoader());
        }

        FixtureLoader(Path root, ClassLoader parent) {
            super(parent);
            try {
                directory = Files.createTempDirectory(root, "loader-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Class<?> define(String name, byte[] bytes) throws IOException {
            replaceResource(name + ".class", bytes);
            CodeSource source = new CodeSource(directory.toUri().toURL(), (Certificate[]) null);
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length,
                    new ProtectionDomain(source, null, this, null));
        }

        void replaceResource(String name, byte[] bytes) throws IOException {
            resources.put(name, bytes);
            Path file = directory.resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }

        @Override
        public URL getResource(String name) {
            if (resourceOverride != null && name.equals(OWNER + ".class")) {
                return resourceOverride;
            }
            if (!resources.containsKey(name)) {
                return super.getResource(name);
            }
            try {
                return directory.resolve(name).toUri().toURL();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
