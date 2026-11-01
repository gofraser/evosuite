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
package org.evosuite.instrumentation.error;

import org.evosuite.Properties;
import org.evosuite.runtime.instrumentation.AnnotatedLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the exact instruction sequence the error-branch instrumentation emits around calls that
 * throw on an empty collection or on a bad index, so that changes to how the guards are built
 * cannot alter the instrumented bytecode.
 */
public class EmptyCollectionInstrumentationBytecodeTest {

    private static final String GOLDEN = "empty-collection-instrumentation.txt";

    private Properties.ErrorInstrumentation[] saved;

    @BeforeEach
    public void setUp() {
        saved = Properties.ERROR_INSTRUMENTATION;
        Properties.ERROR_INSTRUMENTATION = new Properties.ErrorInstrumentation[]{
                Properties.ErrorInstrumentation.QUEUE, Properties.ErrorInstrumentation.DEQUE,
                Properties.ErrorInstrumentation.STACK, Properties.ErrorInstrumentation.VECTOR,
                Properties.ErrorInstrumentation.LIST, Properties.ErrorInstrumentation.LINKEDHASHSET};
    }

    @AfterEach
    public void tearDown() {
        Properties.ERROR_INSTRUMENTATION = saved;
    }

    private static String instrument(int opcode, String owner, String name, String desc, boolean itf) {
        Textifier textifier = new Textifier();
        TraceMethodVisitor trace = new TraceMethodVisitor(textifier);
        MethodVisitor marking = new MethodVisitor(Opcodes.ASM9, trace) {
            @Override
            public void visitLabel(Label label) {
                super.visitLabel(label);
                if (label instanceof AnnotatedLabel) {
                    AnnotatedLabel tag = (AnnotatedLabel) label;
                    super.visitLdcInsn("TAG " + (tag.isStartTag() ? "start" : "end") + " ignore=" + tag.shouldIgnore());
                }
            }
        };
        ErrorConditionMethodAdapter adapter = new ErrorConditionMethodAdapter(marking, "Fixture", "m",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "(L" + owner + ";)V");
        adapter.visitCode();
        adapter.visitVarInsn(Opcodes.ALOAD, 0);
        for (Type arg : Type.getArgumentTypes(desc)) {
            switch (arg.getSort()) {
                case Type.INT:
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                    adapter.visitInsn(Opcodes.ICONST_1);
                    break;
                default:
                    adapter.visitInsn(Opcodes.ACONST_NULL);
            }
        }
        adapter.visitMethodInsn(opcode, owner, name, desc, itf);
        if (Type.getReturnType(desc).getSort() != Type.VOID) {
            adapter.visitInsn(Opcodes.POP);
        }
        adapter.visitInsn(Opcodes.RETURN);
        adapter.visitMaxs(0, 0);
        adapter.visitEnd();
        StringWriter out = new StringWriter();
        textifier.print(new PrintWriter(out));
        return "=== " + owner + "." + name + desc + (itf ? " itf" : "") + "\n" + out;
    }

    private static String dump() {
        String obj = "()Ljava/lang/Object;";
        StringBuilder sb = new StringBuilder();
        String[] queueOwners = {"java/util/Queue", "java/util/PriorityQueue", "java/util/Deque",
                "java/util/concurrent/LinkedBlockingDeque", "java/util/concurrent/BlockingDeque",
                "java/util/ArrayDeque"};
        for (String owner : queueOwners) {
            boolean itf = owner.equals("java/util/Queue") || owner.equals("java/util/Deque")
                    || owner.equals("java/util/concurrent/BlockingDeque");
            int op = itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            for (String name : new String[]{"remove", "element", "poll"}) {
                sb.append(instrument(op, owner, name, obj, itf));
            }
            sb.append(instrument(op, owner, "remove", "(Ljava/lang/Object;)Z", itf));
        }
        String[] dequeOwners = {"java/util/Deque", "java/util/concurrent/LinkedBlockingDeque",
                "java/util/concurrent/BlockingDeque", "java/util/ArrayDeque"};
        for (String owner : dequeOwners) {
            boolean itf = owner.equals("java/util/Deque") || owner.equals("java/util/concurrent/BlockingDeque");
            int op = itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            for (String name : new String[]{"getFirst", "getLast", "removeFirst", "removeLast", "pop", "peek"}) {
                sb.append(instrument(op, owner, name, obj, itf));
            }
        }
        for (String name : new String[]{"pop", "peek", "push"}) {
            sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/Stack", name,
                    name.equals("push") ? "(Ljava/lang/Object;)Ljava/lang/Object;" : obj, false));
        }
        for (String name : new String[]{"firstElement", "lastElement"}) {
            sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/Vector", name, obj, false));
        }
        sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/Vector", "elementAt", "(I)Ljava/lang/Object;", false));
        sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/Vector", "removeElementAt", "(I)V", false));
        sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/Vector", "elementAt",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        for (String name : new String[]{"getFirst", "getLast", "removeFirst", "removeLast", "element", "pop",
                "peek"}) {
            sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", name, obj, false));
        }
        sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "get", "(I)Ljava/lang/Object;", false));
        sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/LinkedList", "add", "(ILjava/lang/Object;)V", false));
        sb.append(instrument(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
        for (String name : new String[]{"first", "last", "size"}) {
            sb.append(instrument(Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashSet", name,
                    name.equals("size") ? "()I" : obj, false));
        }
        return sb.toString();
    }

    @Test
    public void emittedBytecodeIsUnchanged() throws IOException {
        String actual = dump();
        String update = System.getProperty("evosuite.updateGolden");
        if (update != null) {
            Files.write(Paths.get(update), actual.getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertNotNull(in, "missing golden file " + GOLDEN);
            String expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(expected, actual);
        }
    }
}
