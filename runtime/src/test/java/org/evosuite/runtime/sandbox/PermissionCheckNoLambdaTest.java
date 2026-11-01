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
package org.evosuite.runtime.sandbox;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statistics the security manager records while it checks a permission must not use lambdas or
 * method references: bootstrapping one checks permissions again, and the recursion overflows the stack
 * while the class under test is initialised.
 */
public class PermissionCheckNoLambdaTest {

    private static List<String> invokeDynamicSites(Class<?> clazz) throws IOException {
        List<String> sites = new ArrayList<>();
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrap,
                                                           Object... bootstrapArguments) {
                            sites.add(name + descriptor);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        return sites;
    }

    @Test
    public void permissionStatisticsUsesNoLambdas() throws IOException {
        List<String> sites = invokeDynamicSites(PermissionStatistics.class);
        assertTrue(sites.isEmpty(), "invokedynamic in " + sites);
    }
}
