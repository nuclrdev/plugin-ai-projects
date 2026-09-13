package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link TtyConnector} for a JediTerm widget that shows a replayed transcript
 * rather than a live process.
 *
 * <p>The widget still needs <em>some</em> connector - its panel writes typed
 * keys and resize events straight to it - so this is what stands in: reading
 * returns end-of-stream at once, writing and resizing are silently accepted,
 * and {@link #isConnected()} is always {@code false} so the widget renders as
 * disconnected (no blinking cursor) the moment it is shown.
 */
final class NoOpTtyConnector implements TtyConnector {

	static final NoOpTtyConnector INSTANCE = new NoOpTtyConnector();

	private NoOpTtyConnector() {
	}

	@Override
	public int read(char[] buf, int offset, int length) {
		return -1;
	}

	@Override
	public void write(byte[] bytes) {
		// A replay is read-only; whatever was typed at it goes nowhere.
	}

	@Override
	public void write(String string) {
		// See write(byte[]).
	}

	@Override
	public boolean isConnected() {
		return false;
	}

	@Override
	public void resize(@NotNull TermSize termSize) {
		// Nothing on the other end to tell.
	}

	@Override
	public int waitFor() {
		return 0;
	}

	@Override
	public boolean ready() {
		return false;
	}

	@Override
	public String getName() {
		return "Transcript";
	}

	@Override
	public void close() {
		// Nothing owned.
	}
}
