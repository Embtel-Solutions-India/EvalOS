package com.ie.evalos.job;

/**
 * One background sweep, addressable by name.
 *
 * <p><strong>An interface with four implementations, and it earns its keep by exactly one
 * thing:</strong> the GM's "Run now" arrives as a job type in a URL, and something has to turn
 * that string into a sweep. The alternative is a switch in the controller that a fifth sweep
 * forgets to join — which is the same failure Unit 38's hardcoded nav list produced. Injecting
 * {@code List<Sweep>} means a new sweep is registered by existing.
 *
 * <p>The sweeps share no behaviour through this interface. What they share is
 * {@link SweepRunner}, which they hold rather than extend.
 */
public interface Sweep {

	/** The lock key and the ledger key. Stable: two instances agree on this string. */
	String jobType();

	/**
	 * Runs the sweep now, in the calling thread, under the same lock the schedule uses.
	 *
	 * @return true if it ran, false if another instance holds the lock
	 */
	boolean run();
}
