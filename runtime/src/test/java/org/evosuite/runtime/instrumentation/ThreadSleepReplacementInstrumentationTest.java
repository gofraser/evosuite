package org.evosuite.runtime.instrumentation;

import org.evosuite.PackageInfo;
import org.evosuite.runtime.RuntimeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class ThreadSleepReplacementInstrumentationTest {

    @BeforeEach
    public void setup() {
        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void cleanup() {
        RuntimeSettings.mockJVMNonDeterminism = false;
        MethodCallReplacementCache.resetSingleton();
    }

    @Test
    public void testThreadSubclassUnqualifiedSleepIsReplaced() throws Exception {
        java.io.InputStream classStream = getClass().getClassLoader()
                .getResourceAsStream("com/examples/ThreadSubclassSleeper.class");
        Assertions.assertNotNull(classStream, "Missing test class bytes");
        byte[] originalBytes = classStream.readAllBytes();
        ClassReader reader = new ClassReader(originalBytes);

        RuntimeInstrumentation instrumentation = new RuntimeInstrumentation();
        byte[] transformedBytes = instrumentation.transformBytes(
                getClass().getClassLoader(),
                "com/examples/ThreadSubclassSleeper",
                reader,
                false);

        ClassNode node = new ClassNode();
        new ClassReader(transformedBytes).accept(node, ClassReader.SKIP_FRAMES);
        MethodNode run = null;
        for (MethodNode m : node.methods) {
            if ("run".equals(m.name) && "()V".equals(m.desc)) {
                run = m;
                break;
            }
        }
        Assertions.assertNotNull(run, "Expected run() method");

        String mockThreadOwner = PackageInfo.getNameWithSlash(org.evosuite.runtime.mock.java.lang.MockThread.class);
        boolean replaced = false;
        for (AbstractInsnNode insn = run.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (min.getOpcode() == Opcodes.INVOKESTATIC
                        && "sleep".equals(min.name)
                        && "(J)V".equals(min.desc)
                        && mockThreadOwner.equals(min.owner)) {
                    replaced = true;
                    break;
                }
            }
        }

        Assertions.assertTrue(replaced, "Expected unqualified Thread subclass sleep() to be replaced");
    }
}
