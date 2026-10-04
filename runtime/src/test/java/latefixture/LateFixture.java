package latefixture;

import org.evosuite.runtime.EvoRunner;
import org.evosuite.runtime.EvoRunnerParameters;
import org.evosuite.runtime.mock.java.io.MockFile;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Run by LoadedBeforeAgentTest in a fresh JVM through JUnitCore, which initializes (and so
 * links) this class before EvoRunner gets control. Linking verifies the call in
 * {@link #passesSubclass()}, which loads SutSub to check it is a SutBase, before the agent
 * is active.
 */
@RunWith(EvoRunner.class)
@EvoRunnerParameters(useVFS = true, resetStaticState = true, mockJVMNonDeterminism = true)
public class LateFixture extends LateFixtureScaffolding {

    static void take(SutBase base) {
    }

    @Test
    public void writesThroughTheVirtualFileSystem() throws Throwable {
        new SutSub().touch(new MockFile("late_fixture_output.txt"));
    }

    @Test
    public void passesSubclass() {
        take(new SutSub());
    }
}
