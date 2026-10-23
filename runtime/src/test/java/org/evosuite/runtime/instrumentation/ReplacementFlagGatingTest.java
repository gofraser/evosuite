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

/**
 * The call rewrites that the method adapter applies without consulting the replacement cache
 * (generic Swing calls, Socket.connect, sleep in a Thread subclass) belong to the same settings
 * as the mock family they serve.
 */
public class ReplacementFlagGatingTest {

    private static final String SWING_MOCK = PackageInfo.getNameWithSlash(
            org.evosuite.runtime.mock.javax.swing.MockHeadlessSwing.class);
    private static final String SOCKET_DISPATCH = PackageInfo.getNameWithSlash(
            org.evosuite.runtime.mock.java.net.SocketConnectDispatch.class);
    private static final String MOCK_THREAD = PackageInfo.getNameWithSlash(
            org.evosuite.runtime.mock.java.lang.MockThread.class);

    @BeforeEach
    public void setup() {
        RuntimeSettings.deactivateAllMocking();
        MethodCallReplacementCache.resetSingleton();
    }

    @AfterEach
    public void cleanup() {
        RuntimeSettings.deactivateAllMocking();
        MethodCallReplacementCache.resetSingleton();
    }

    private static boolean callsStatic(String className, String owner) throws Exception {
        byte[] original;
        try (java.io.InputStream in = ReplacementFlagGatingTest.class.getClassLoader()
                .getResourceAsStream(className + ".class")) {
            Assertions.assertNotNull(in, "Missing test class bytes");
            original = in.readAllBytes();
        }
        byte[] transformed = new RuntimeInstrumentation().transformBytes(
                ReplacementFlagGatingTest.class.getClassLoader(), className, new ClassReader(original), false);
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, ClassReader.SKIP_FRAMES);
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.getOpcode() == Opcodes.INVOKESTATIC && owner.equals(min.owner)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    public void swingCallsAreRewrittenOnlyWithGuiMocking() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.mockGUI = false;
        Assertions.assertFalse(callsStatic("com/examples/CallsAppPanel", SWING_MOCK));

        RuntimeSettings.mockGUI = true;
        MethodCallReplacementCache.resetSingleton();
        Assertions.assertTrue(callsStatic("com/examples/CallsAppPanel", SWING_MOCK));
    }

    @Test
    public void socketConnectIsRewrittenOnlyWithVirtualNetwork() throws Exception {
        RuntimeSettings.mockJVMNonDeterminism = true;
        RuntimeSettings.useVNET = false;
        Assertions.assertFalse(callsStatic("com/examples/CallsSocketConnect", SOCKET_DISPATCH));

        RuntimeSettings.useVNET = true;
        MethodCallReplacementCache.resetSingleton();
        Assertions.assertTrue(callsStatic("com/examples/CallsSocketConnect", SOCKET_DISPATCH));
    }

    @Test
    public void threadSubclassSleepIsRewrittenOnlyWithJvmMocking() throws Exception {
        RuntimeSettings.useVNET = true;
        RuntimeSettings.mockJVMNonDeterminism = false;
        Assertions.assertFalse(callsStatic("com/examples/ThreadSubclassSleeper", MOCK_THREAD));

        RuntimeSettings.mockJVMNonDeterminism = true;
        MethodCallReplacementCache.resetSingleton();
        Assertions.assertTrue(callsStatic("com/examples/ThreadSubclassSleeper", MOCK_THREAD));
    }
}
