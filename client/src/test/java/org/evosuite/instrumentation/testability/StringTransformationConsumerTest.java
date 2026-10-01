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
package org.evosuite.instrumentation.testability;

import org.evosuite.Properties;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link StringHelper} and {@link ContainerHelper} do not return booleans. They return a signed
 * branch distance: a positive value means "true", a value {@code <= 0} means "false" and says how
 * far from true it was (see {@link BooleanHelper#K}). {@link StringHelperTest} already covers that
 * contract thoroughly, and it holds.
 *
 * <p>What the existing tests never check is whether the <em>call sites</em> that
 * {@link StringTransformation} and {@link ContainerTransformation} rewrite still interpret that
 * contract. {@code StringTransformation} does take care of this itself, and mostly gets it right:
 * {@code transformMethod} runs an ASM dataflow analysis with {@link StringBooleanInterpreter},
 * finds the jumps that consume a tagged value and rewrites them to be sign-aware
 * ({@code IFEQ -> IFLE}, {@code IFNE -> IFGT}). So most shapes are fine, which is why most of the
 * cases below pass. They are here to keep it that way: a change that disabled the transformation
 * rather than fixing it would leave them passing while the interesting case below regressed.
 *
 * <p>The defect this class was written for is narrower. The interpreter tags the result of a
 * {@code StringHelper.String*} call but, before the fix, never overrode {@code merge}, and ASM's
 * {@code BasicInterpreter.merge} collapses two unequal values to {@code UNINITIALIZED_VALUE}. So
 * wherever a tagged value reached a branch through a control-flow merge, the tag was lost, the
 * jump stayed {@code IFEQ}, and the signed distance was read with plain boolean semantics -- where
 * any nonzero value is true, including the large negative "false" distances
 * ({@code StringEquals("SW", "SH")} is -30561911). The canonical shape is a boolean local
 * initialised to {@code false} and assigned a comparison result only on some paths, which is
 * exactly Apache {@code Metaphone.regionMatch}.
 *
 * <p>These tests pin the observable consequence, not the sign convention: each fixture method
 * returns 1 from the true branch and 2 from the false branch, so an instrumented class that still
 * computes the right answer returns what plain Java returns.
 */
public class StringTransformationConsumerTest {

    private static final String VICTIM = "com.examples.with.different.packagename.StringEqualsBranchVictim";

    private Class<?> instrumented;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        // The transformation applies to the class under test, so the fixture has to be one. Both
        // the prefix (which decides whether the pass runs at all) and the target class (which
        // decides whether StringTransformation is applied) are needed.
        Properties.TARGET_CLASS = VICTIM;
        Properties.PROJECT_PREFIX = "com.examples.with.different.packagename";
        // Match what the SF110 and Defects4J runs use. The default criterion set includes
        // WEAKMUTATION, whose ReplaceComparisonOperator instrumentation rewrites the branch around
        // the comparison and masks the defect for some shapes.
        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE, Properties.Criterion.BRANCH};
        // Left at their defaults deliberately: this is the shipped configuration, and the whole
        // point is that TT is off while STRING_REPLACEMENT is on.
        assertEquals(false, Properties.TT, "TT is expected to default to false");
        assertEquals(true, Properties.STRING_REPLACEMENT,
                "STRING_REPLACEMENT is expected to default to true");
        instrumented = instrument(VICTIM);
    }

    @AfterEach
    public void tearDown() {
        Properties.getInstance().resetToDefaults();
    }

    @Test
    public void equalsOnDifferentStringsIsFalse() throws Exception {
        assertEquals(1, invoke("equalsBranch", "same", "same"));
        assertEquals(2, invoke("equalsBranch", "left", "right"));
    }

    @Test
    public void equalsStoredInALocalIsFalseForDifferentStrings() throws Exception {
        assertEquals(1, invoke("equalsViaLocal", "same", "same"));
        assertEquals(2, invoke("equalsViaLocal", "left", "right"));
    }

    /**
     * The concrete case from the Defects4J Codec-1 run: {@code Metaphone.regionMatch} compares a
     * substring against a literal, and the corrupted answer made the SUT take the {@code SH}
     * branch for the text {@code SW}, so the generated assertions recorded {@code X} where the
     * real algorithm produces {@code S}.
     */
    @Test
    public void regionMatchIsFalseWhenTheSubstringDiffers() throws Exception {
        assertEquals(1, invoke("regionMatchIdiom", "SHOUT", 0, "SH"));
        assertEquals(2, invoke("regionMatchIdiom", "SWPVY", 0, "SH"));
    }

    @Test
    public void equalsIgnoreCaseOnDifferentStringsIsFalse() throws Exception {
        assertEquals(1, invoke("equalsIgnoreCaseBranch", "Same", "sAME"));
        assertEquals(2, invoke("equalsIgnoreCaseBranch", "left", "right"));
    }

    @Test
    public void startsWithIsFalseForAWrongPrefix() throws Exception {
        assertEquals(1, invoke("startsWithBranch", "prefixed", "pre"));
        assertEquals(2, invoke("startsWithBranch", "prefixed", "xyz"));
    }

    @Test
    public void endsWithIsFalseForAWrongSuffix() throws Exception {
        assertEquals(1, invoke("endsWithBranch", "suffixed", "xed"));
        assertEquals(2, invoke("endsWithBranch", "suffixed", "xyz"));
    }

    @Test
    public void orChainIsFalseWhenBothSidesDiffer() throws Exception {
        assertEquals(1, invoke("orChainBranch", "same", "same", "x", "y"));
        assertEquals(2, invoke("orChainBranch", "left", "right", "x", "y"));
    }

    @Test
    public void loopAssignedMatchIsFalseWhenNoElementMatches() throws Exception {
        assertEquals(1, invoke("loopAssignedBranch",
                (Object) new String[]{"a", "b", "needle"}, "needle"));
        assertEquals(2, invoke("loopAssignedBranch",
                (Object) new String[]{"a", "b", "c"}, "needle"));
    }

    @Test
    public void ternaryArmIsFalseWhenTheChosenComparisonDiffers() throws Exception {
        assertEquals(1, invoke("ternaryBranch", true, "same", "same", "left", "right"));
        assertEquals(2, invoke("ternaryBranch", true, "left", "right", "same", "same"));
        assertEquals(1, invoke("ternaryBranch", false, "left", "right", "same", "same"));
        assertEquals(2, invoke("ternaryBranch", false, "same", "same", "left", "right"));
    }

    /**
     * {@link ContainerTransformation} rewrites {@code isEmpty}, {@code contains} and
     * {@code containsKey} to the same signed-int convention, from the same ungated block, so it
     * shares the defect.
     */
    @Test
    public void collectionIsEmptyIsFalseForANonEmptyList() throws Exception {
        assertEquals(1, invoke("collectionIsEmptyBranch", false));
        assertEquals(2, invoke("collectionIsEmptyBranch", true));
    }

    @Test
    public void collectionContainsIsFalseForAnAbsentElement() throws Exception {
        assertEquals(1, invoke("collectionContainsBranch", "present"));
        assertEquals(2, invoke("collectionContainsBranch", "absent"));
    }

    @Test
    public void mapContainsKeyIsFalseForAnAbsentKey() throws Exception {
        assertEquals(1, invoke("mapContainsKeyBranch", "present"));
        assertEquals(2, invoke("mapContainsKeyBranch", "absent"));
    }

    // ---------------------------------------------------------------- helpers

    private int invoke(String name, Object... args) throws Exception {
        Method target = null;
        for (Method candidate : instrumented.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == args.length) {
                target = candidate;
                break;
            }
        }
        assertNotNull(target, "No method " + name + " with " + args.length + " parameters");
        return (Integer) target.invoke(null, args);
    }

    /**
     * Runs the fixture through the real client-side pipeline, which is what
     * {@code InstrumentingClassLoader} does for the class under test.
     */
    private static Class<?> instrument(String className) {
        String internalName = className.replace('.', '/');
        byte[] transformed;
        try (InputStream is = StringTransformationConsumerTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(is, "Could not find " + className + " on the test classpath");
            transformed = new BytecodeInstrumentation()
                    .transformBytes(StringTransformationConsumerTest.class.getClassLoader(),
                            internalName, new ClassReader(is));
        } catch (Exception e) {
            throw new IllegalStateException("Could not instrument " + className, e);
        }
        ByteClassLoader loader =
                new ByteClassLoader(StringTransformationConsumerTest.class.getClassLoader());
        return loader.define(className, transformed);
    }

    private static final class ByteClassLoader extends ClassLoader {
        private ByteClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
