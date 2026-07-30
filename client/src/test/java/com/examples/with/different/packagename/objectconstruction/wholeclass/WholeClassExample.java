package com.examples.with.different.packagename.objectconstruction.wholeclass;

/**
 * Fixture for EvoObj whole-class mode. It has a public entry method and a private one,
 * both containing a branch that reads the private field {@code state}. In whole-class mode the
 * object rule may target the branch in {@code publicEntry} (a valid entry) but not the one in
 * {@code hiddenEntry} (private, hence ineligible without call-graph search).
 */
public class WholeClassExample {

	private int state;

	public WholeClassExample() {
		// default constructor retained for existing whole-class tests
	}

	public WholeClassExample(int x) {
		if (state == x) {
			state = 1;
		}
	}

	public boolean publicEntry(int x) {
		if (state == x) {
			return true;
		}
		return false;
	}

	boolean packageEntry(int x) {
		if (state == x) {
			return true;
		}
		return false;
	}

	public boolean publicCallsHidden(int x) {
		return hiddenEntry(x);
	}

	private boolean hiddenEntry(int x) {
		if (state == x) {
			return true;
		}
		return false;
	}

	public void setState(int s) {
		this.state = s;
	}
}
