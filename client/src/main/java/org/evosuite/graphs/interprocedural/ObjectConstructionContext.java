package org.evosuite.graphs.interprocedural;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.interprocedural.var.DepVariable;

/**
 * Holder for the mutable static state of the EvoObj object-construction analysis and synthesis
 * (plan §E7). Consolidating the statics behind a single {@link #reset()} — wired into
 * {@link org.evosuite.TestGenerationContext#resetContext()} — keeps generation deterministic and
 * prevents state from leaking between runs or between system-test methods.
 *
 * <p>The maps use {@link LinkedHashMap} so iteration order (which can influence generation) is
 * deterministic across runs with the same seed.
 */
public class ObjectConstructionContext {

	/**
	 * What state variables each branch depends on: method signature -&gt; branch -&gt; dependent variables.
	 */
	public static Map<String, Map<Branch, Set<DepVariable>>> branchInterestedVarsMap = new LinkedHashMap<>();

	/**
	 * Concrete subtypes discovered via {@code INSTANCEOF} checks, keyed by target-method parameter
	 * position (used to instantiate the concrete subtype a branch checks for).
	 */
	public static Map<Integer, List<String>> recommendedClasses = new LinkedHashMap<>();

	private ObjectConstructionContext() {
		// static holder — not instantiable
	}

	/**
	 * Reset all EvoObj mutable static state. Invoked from
	 * {@link org.evosuite.TestGenerationContext#resetContext()}.
	 */
	public static void reset() {
		branchInterestedVarsMap = new LinkedHashMap<>();
		recommendedClasses = new LinkedHashMap<>();
		FBranchDefUseAnalyzer.analyzedMethods.clear();
		org.evosuite.testcase.factories.RandomLengthTestFactory.resetStatistics();
		org.evosuite.testcase.synthesizer.ObjectRuleBranchSelector.getInstance().reset();
	}
}
