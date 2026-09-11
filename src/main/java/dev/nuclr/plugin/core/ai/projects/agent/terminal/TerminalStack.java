package dev.nuclr.plugin.core.ai.projects.agent.terminal;

/**
 * Whether the terminal libraries this plugin renders agents with are actually
 * present.
 *
 * <p>JediTerm and pty4j are supplied by the Commander host, the same way the SDK
 * and SLF4J are, rather than bundled: a second copy of pty4j would mean a second
 * extraction of its native helpers. That makes them a runtime assumption worth
 * checking rather than asserting. If a host ever ships without them, this class
 * is the only place that notices - the provider then reports itself unavailable
 * and the desktop shows a window explaining why, instead of the whole plugin
 * failing to load on a {@code NoClassDefFoundError}.
 *
 * <p>The check is done once, with {@link Class#forName} and without
 * initialisation, so it costs nothing after the first call.
 */
public final class TerminalStack {

	private static final boolean AVAILABLE;
	private static final String REASON;

	static {
		var available = false;
		String reason = null;
		try {
			var loader = TerminalStack.class.getClassLoader();
			Class.forName("com.jediterm.terminal.ui.JediTermWidget", false, loader);
			Class.forName("com.jediterm.terminal.ProcessTtyConnector", false, loader);
			Class.forName("com.pty4j.PtyProcessBuilder", false, loader);
			available = true;
		} catch (ClassNotFoundException | LinkageError e) {
			reason = "This Commander build does not provide the terminal libraries (" + e.getMessage() + ").";
		}
		AVAILABLE = available;
		REASON = reason;
	}

	private TerminalStack() {
	}

	/** Whether terminal-backed agent windows can be created. */
	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/**
	 * Why terminals are unavailable.
	 *
	 * @return one short line, or {@code null} when they are available
	 */
	public static String unavailableReason() {
		return REASON;
	}
}
