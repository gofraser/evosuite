package latefixture;

import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.Runtime;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.classhandling.ClassStateSupport;
import org.evosuite.runtime.sandbox.Sandbox;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

/** The shape of a generated JUnit 4 scaffolding, reduced to what this fixture needs. */
public class LateFixtureScaffolding {

    @BeforeClass
    public static void initEvoSuiteFramework() {
        RuntimeSettings.className = "latefixture.SutSub";
        RuntimeSettings.sandboxMode = Sandbox.SandboxMode.RECOMMENDED;
        Sandbox.initializeSecurityManagerForSUT();
        ClassStateSupport.initializeClasses(LateFixtureScaffolding.class.getClassLoader(),
                "latefixture.SutBase", "latefixture.SutSub");
        Runtime.getInstance().resetRuntime();
    }

    @AfterClass
    public static void clearEvoSuiteFramework() {
        Sandbox.resetDefaultSecurityManager();
    }

    @Before
    public void initTestCase() {
        Sandbox.goingToExecuteSUTCode();
        Runtime.getInstance().resetRuntime();
        InstrumentingAgent.activate();
    }

    @After
    public void doneWithTestCase() {
        Sandbox.doneWithExecutingSUTCode();
        InstrumentingAgent.deactivate();
    }
}
