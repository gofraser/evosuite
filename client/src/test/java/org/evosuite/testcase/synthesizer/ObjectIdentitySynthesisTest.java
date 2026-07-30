package org.evosuite.testcase.synthesizer;

import com.examples.with.different.packagename.objectconstruction.tuning.AliasedArrayAccessor;
import com.examples.with.different.packagename.objectconstruction.tuning.MembershipGroup;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.runtime.LoopCounter;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.testcase.synthesizer.var.DepVariableWrapper;
import org.evosuite.utils.MethodUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectIdentitySynthesisTest {

    @AfterEach
    void tearDown() {
        Properties.TARGET_CLASS = "";
        Properties.TARGET_METHOD = "";
        TargetContext.clearActive();
        org.evosuite.TestGenerationContext.getInstance().resetContext();
    }

    @Test
    void joinsPrivateAccessorResultToReturnedArrayElement() throws Exception {
        analyze(AliasedArrayAccessor.class, "accepts", int.class);
        Branch branch = stateBranch();
        PartialGraph graph = new ConstructionPathSynthesizer()
                .constructPartialComputationGraph(branch);

        DepVariableWrapper call = graph.getNodes().stream()
                .filter(node -> node.var.isMethodCall()
                        && node.var.getInstruction().getASMNode() instanceof MethodInsnNode
                        && "getCell".equals(((MethodInsnNode)
                                node.var.getInstruction().getASMNode()).name))
                .findFirst().orElse(null);
        assertNotNull(call, "the private accessor call must remain in the graph");
        assertTrue(call.parents.stream().anyMatch(
                        parent -> parent.var.isLoadArrayElement()),
                "the accessor result must alias its returned array element");
    }

    @Test
    void identifiesExactMembershipTargetArgument() throws Exception {
        analyze(MembershipGroup.class, "accepts", MembershipGroup.Member.class);
        Field members = MembershipGroup.class.getDeclaredField("members");
        assertEquals(0, DataDependencyUtil.membershipTargetParameter(members));
    }

    private static void analyze(Class<?> target, String methodName,
            Class<?>... parameterTypes) throws Exception {
        Method method = target.getDeclaredMethod(methodName, parameterTypes);
        Properties.CLIENT_ON_THREAD = true;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.APPLY_OBJECT_RULE = true;
        Properties.TARGET_CLASS = target.getName();
        Properties.TARGET_METHOD = method.getName() + MethodUtil.getSignature(method);
        String classPath = System.getProperty("user.dir") + "/target/test-classes";
        ClassPathHandler.getInstance().addElementToTargetProjectClassPath(classPath);
        DependencyAnalysis.analyzeClass(target.getName(),
                Collections.singletonList(classPath));
        LoopCounter.getInstance().setActive(false);
        TargetContext.setActive(new TargetContext(
                target.getName(), Properties.TARGET_METHOD, null));
    }

    private static Branch stateBranch() {
        Map<Branch, Set<DepVariable>> branches =
                ObjectConstructionContext.branchInterestedVarsMap
                        .get(Properties.TARGET_METHOD);
        assertNotNull(branches);
        return branches.entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst().orElseThrow(AssertionError::new);
    }
}
