package latefixture;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs the JUnit 5 test classes named on the command line, as a build tool would. */
public class JupiterMain {

    public static void main(String[] args) {
        LauncherDiscoveryRequestBuilder request = LauncherDiscoveryRequestBuilder.request();
        for (String className : args) {
            request.selectors(selectClass(className));
        }
        LauncherDiscoveryRequest discoveryRequest = request.build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(discoveryRequest, listener);
        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        summary.printTo(out);
        summary.printFailuresTo(out, 20);
        out.flush();
        System.out.println("SUCCEEDED " + summary.getTestsSucceededCount() + " FAILED "
                + summary.getTotalFailureCount());
        System.exit(summary.getTotalFailureCount() == 0 && summary.getTestsFoundCount() > 0 ? 0 : 1);
    }
}
