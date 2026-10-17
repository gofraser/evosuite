package latefixture;

import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.EvoSuiteExtension;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Run by LoadedBeforeAgentTest in a fresh JVM through the JUnit Platform launcher. Jupiter
 * initializes (and so links) this class to read the extension field, before the extension
 * gets control. Linking verifies the call in {@link #staticInitializerRanMocked()}, which
 * loads StaticInitSut to check it is a SutBase, before the agent is active.
 */
@EvoRunnerParameters(useVFS = true, resetStaticState = true, mockJVMNonDeterminism = true)
public class LateJupiterFixture {

    private static final String[] EVO_INIT_ORDER = {"latefixture.SutBase", "latefixture.StaticInitSut"};

    @RegisterExtension
    static EvoSuiteExtension runner = new EvoSuiteExtension(LateJupiterFixture.class, EVO_INIT_ORDER);

    static void take(SutBase base) {
    }

    @Test
    public void staticInitializerRanMocked() {
        assertEquals("written", StaticInitSut.STATE);
        take(new StaticInitSut());
    }

    @Test
    public void writesThroughTheVirtualFileSystem() throws Throwable {
        new StaticInitSut().touch(new MockFile("late_jupiter_output.txt"));
    }
}
