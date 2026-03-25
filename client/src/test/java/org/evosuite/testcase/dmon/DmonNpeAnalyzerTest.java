/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.testcase.dmon;

import org.evosuite.Properties;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.Optional;

public class DmonNpeAnalyzerTest {

    public static class AsmFallbackTarget {
        static Runnable dep;

        public AsmFallbackTarget() {
            dep.run();
        }
    }

    public static class HelpfulInvokeTarget {
        ConfigurationService configurationService;

        public HelpfulInvokeTarget() {
            configurationService.getI18nResourceBundle();
        }
    }

    public interface ConfigurationService {
        Object getI18nResourceBundle();
    }

    @Test
    public void asmFallbackInfersFieldAndTypeWhenHelpfulNpeParsingIsDisabled() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        boolean oldHelpful = Properties.DMON_HELPFUL_NPE_PARSE;
        boolean oldAsm = Properties.DMON_ASM_FALLBACK;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.DMON_HELPFUL_NPE_PARSE = false;
            Properties.DMON_ASM_FALLBACK = true;
            Properties.TARGET_CLASS = AsmFallbackTarget.class.getCanonicalName();

            AsmFallbackTarget.dep = null;
            NullPointerException npe = captureNpe();
            Assert.assertNotNull(npe);

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    AsmFallbackTarget.class.getDeclaredConstructor(), AsmFallbackTarget.class);
            ConstructorStatement statement = new ConstructorStatement(test, gc, Collections.emptyList());

            DmonNpeAnalyzer analyzer = new DmonNpeAnalyzer();
            Optional<DmonPromotionPlan> maybePlan = analyzer.analyzeConstructorNpe(statement, npe);

            Assert.assertTrue(maybePlan.isPresent());
            DmonPromotionPlan plan = maybePlan.get();
            Assert.assertTrue("ASM fallback should infer a member token", plan.getMemberToken().isPresent());
            Assert.assertTrue("ASM fallback should infer dependency type",
                    plan.getInferredMissingTypeName().isPresent());
            Assert.assertTrue("ASM fallback inferred type should be a reference type",
                    plan.getInferredMissingTypeName().get().contains("."));
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.DMON_HELPFUL_NPE_PARSE = oldHelpful;
            Properties.DMON_ASM_FALLBACK = oldAsm;
            Properties.TARGET_CLASS = oldTargetClass;
            AsmFallbackTarget.dep = null;
        }
    }

    @Test
    public void helpfulInvokeMessageInfersDeclaringTypeInsteadOfMethodToken() throws Exception {
        boolean oldEnabled = Properties.DMON_ENABLED;
        boolean oldOnlyTarget = Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR;
        boolean oldHelpful = Properties.DMON_HELPFUL_NPE_PARSE;
        String oldTargetClass = Properties.TARGET_CLASS;
        try {
            Properties.DMON_ENABLED = true;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = true;
            Properties.DMON_HELPFUL_NPE_PARSE = true;
            Properties.TARGET_CLASS = HelpfulInvokeTarget.class.getCanonicalName();

            NullPointerException npe = captureHelpfulInvokeNpe();
            Assert.assertNotNull(npe);

            DefaultTestCase test = new DefaultTestCase();
            GenericConstructor gc = new GenericConstructor(
                    HelpfulInvokeTarget.class.getDeclaredConstructor(), HelpfulInvokeTarget.class);
            ConstructorStatement statement = new ConstructorStatement(test, gc, Collections.emptyList());

            DmonNpeAnalyzer analyzer = new DmonNpeAnalyzer();
            Optional<DmonPromotionPlan> maybePlan = analyzer.analyzeConstructorNpe(statement, npe);

            Assert.assertTrue(maybePlan.isPresent());
            DmonPromotionPlan plan = maybePlan.get();
            Assert.assertTrue(plan.getInferredMissingTypeName().isPresent());
            Assert.assertEquals(ConfigurationService.class.getName(), plan.getInferredMissingTypeName().get());
        } finally {
            Properties.DMON_ENABLED = oldEnabled;
            Properties.DMON_ONLY_TARGET_CLASS_CONSTRUCTOR = oldOnlyTarget;
            Properties.DMON_HELPFUL_NPE_PARSE = oldHelpful;
            Properties.TARGET_CLASS = oldTargetClass;
        }
    }

    private static NullPointerException captureNpe() {
        try {
            new AsmFallbackTarget();
            return null;
        } catch (NullPointerException npe) {
            npe.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement(
                            AsmFallbackTarget.class.getName(),
                            "<init>",
                            "DmonNpeAnalyzerTest.java",
                            37)
            });
            return npe;
        }
    }

    private static NullPointerException captureHelpfulInvokeNpe() {
        try {
            new HelpfulInvokeTarget();
            return null;
        } catch (NullPointerException npe) {
            return npe;
        }
    }
}
