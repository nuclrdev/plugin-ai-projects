package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
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
 */
final class AgentTtyConnector extends ProcessTtyConnector {

	private final PtyProcess process;
	private final String name;
	private final Consumer<String> outputListener;

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
		var read = super.read(buffer, offset, length);
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

	/** The underlying process, so the window can report its pid and destroy it. */
	PtyProcess ptyProcess() {
		return process;
	}
}
