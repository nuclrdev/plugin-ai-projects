package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

/**
 * The bridge between an agent's pty and its terminal widget, with a tap on the
 * way past.
 *
 * <p>Everything the agent prints is handed to a listener as well as to the
 * screen. That is what makes the transcript possible: none of these CLIs can be
 * reattached after Commander exits, so the output is the only part of a session
 * that can survive, and it has to be captured while it is being displayed.
 *
 * <p>The tap must stay cheap. It runs on JediTerm's reader thread, and anything
 * slow here shows up as a terminal that lags behind its process.
 *
 * <p>A process ending is not the same as its output having been read: the last of what
 * it printed is still in the pty when it exits, and whoever reports the exit has to wait
 * for {@link #awaitDrained(long)} first or it will report it over the top of the output.
 */
final class AgentTtyConnector extends ProcessTtyConnector {

	private final PtyProcess process;
	private final String name;
	private final Consumer<String> outputListener;
	private final CountDownLatch drained = new CountDownLatch(1);

	/**
	 * Wrap a pty process.
	 *
	 * @param process        the running pty
	 * @param charset        the encoding it speaks
	 * @param commandLine    the command it was started with, for the widget
	 * @param name           short label shown by the terminal widget
	 * @param outputListener receives every chunk read; must be quick
	 */
	AgentTtyConnector(PtyProcess process, Charset charset, List<String> commandLine,
			String name, Consumer<String> outputListener) {
		super(process, charset, commandLine);
		this.process = process;
		this.name = name;
		this.outputListener = outputListener;
	}

	@Override
	public int read(char[] buffer, int offset, int length) throws IOException {
		int read;
		try {
			read = super.read(buffer, offset, length);
		} catch (IOException | RuntimeException e) {
			// The reader is done, however it ended; nobody is waiting for more.
			drained.countDown();
			throw e;
		}
		if (read < 0) {
			drained.countDown();
		}
		if (read > 0 && outputListener != null) {
			try {
				outputListener.accept(new String(buffer, offset, read));
			} catch (RuntimeException e) {
				// A failing tap must never break the terminal it is tapping.
				return read;
			}
		}
		return read;
	}

	@Override
	public void resize(TermSize termSize) {
		if (isConnected()) {
			process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
		}
	}

	@Override
	public boolean isConnected() {
		return process.isAlive();
	}

	@Override
	public String getName() {
		return name;
	}

	/**
	 * Wait until the pty has been read to its end.
	 *
	 * <p>Called off the event thread by whoever is about to write the session's last word,
	 * so that what the agent printed is in the transcript before the line saying it exited.
	 * Gives up after the timeout rather than holding the session open on a reader that has
	 * stopped without reaching the end.
	 *
	 * @param millis how long to wait at most
	 * @return whether the end was actually reached
	 */
	boolean awaitDrained(long millis) {
		try {
			return drained.await(millis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** The underlying process, so the window can report its pid and destroy it. */
	PtyProcess ptyProcess() {
		return process;
	}
}
