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
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A SUT class whose members mention a type missing from the classpath makes reflection over it
 * throw NoClassDefFoundError. The DMoN promotion must treat that as "cannot promote", not let it
 * escape and end the client run (seen with SapNWPlugin and FrameStats in the SF110 corpus).
 */
public class DmonLinkageErrorTest {

    private static final String OWNER = "dmonlinkage.Holder";
    private static final String MISSING = "dmonlinkage/NotOnTheClasspath";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private boolean oldEnabled;
    private boolean oldPromote;
    private boolean oldReflection;

    @Before
    public void setUp() throws Exception {
        oldEnabled = Properties.DMON_ENABLED;
        oldPromote = Properties.DMON_PROMOTE_IN_PLACE;
        oldReflection = Properties.DMON_ALLOW_REFLECTION_FALLBACK;
        Properties.DMON_ENABLED = true;
        Properties.DMON_PROMOTE_IN_PLACE = true;
        Properties.DMON_ALLOW_REFLECTION_FALLBACK = true;

        // public class Holder { public static void setDep(NotOnTheClasspath x) {} }
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "dmonlinkage/Holder", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setDep",
                "(L" + MISSING + ";)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        Path dir = folder.newFolder("cp").toPath().resolve("dmonlinkage");
        Files.createDirectories(dir);
        Files.write(dir.resolve("Holder.class"), cw.toByteArray());

        TestGenerationContext.getInstance().resetContext();
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(
                dir.getParent().toString());
    }

    @After
    public void tearDown() {
        Properties.DMON_ENABLED = oldEnabled;
        Properties.DMON_PROMOTE_IN_PLACE = oldPromote;
        Properties.DMON_ALLOW_REFLECTION_FALLBACK = oldReflection;
        TestGenerationContext.getInstance().resetContext();
    }

    @Test
    public void unlinkableOwnerClassAbandonsThePromotionInsteadOfThrowing() {
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(new DefaultTestCase());
        chromosome.setChanged(false);

        ExecutionResult result = new ExecutionResult(chromosome.getTestCase(), null);
        result.setDmonPromotionPlan(new DmonPromotionPlan(
                new DmonFailureSite(OWNER, "<init>", 10, DmonFailureSiteKind.CONSTRUCTOR_INIT),
                Optional.of("this.dep"),
                Optional.of("this"),
                Optional.of("dep"),
                Optional.of(Runnable.class.getName())));

        DmonPromotionOperator.applyIfNeeded(chromosome, result);

        Assert.assertTrue(result.isDmonPromotionConsumed());
        Assert.assertFalse(chromosome.isChanged());
        Assert.assertEquals(0, chromosome.getTestCase().size());
    }
}
