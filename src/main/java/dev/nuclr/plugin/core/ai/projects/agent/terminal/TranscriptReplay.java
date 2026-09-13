package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.io.IOException;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.emulator.JediEmulator;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.SettingsProvider;

/**
 * Turns a captured transcript back into a terminal screen.
 *
 * <p>The transcript is the raw bytes a CLI printed, escape sequences and all -
 * the same thing a live terminal would have received. Rather than inventing a
 * second way to interpret that (stripping codes into flat text, which throws
 * away colour and, worse, turns every carriage-return redraw of a progress
 * line into a duplicate line of its own) this drives the same emulator the
 * live view uses, over a widget that was never connected to a process.
 *
 * <p>Nothing here is started or connected: {@link JediEmulator} is run to
 * completion synchronously, against the widget's own {@code Terminal}, and the
 * widget is left showing whatever screen that produced - cursor hidden, as
 * {@link com.jediterm.terminal.Terminal#disconnected()} leaves it.
 */
final class TranscriptReplay {

	private TranscriptReplay() {
	}

	/**
	 * Build a widget that shows the final screen a transcript would have left on
	 * a real terminal.
	 *
	 * @param transcript raw terminal output, escape sequences included
	 * @param columns    terminal width in character cells
	 * @param rows       terminal height in character cells
	 * @param settings   colours and font for the widget
	 * @return the widget, disconnected and ready to display
	 */
	static JediTermWidget render(String transcript, int columns, int rows, SettingsProvider settings) {

		var widget = new JediTermWidget(columns, rows, settings);
		widget.setBackground(TerminalTheme.backgroundColor());
		widget.setTtyConnector(NoOpTtyConnector.INSTANCE);

		var emulator = new JediEmulator(new ArrayTerminalDataStream(transcript.toCharArray()), widget.getTerminal());
		try {
			while (emulator.hasNext()) {
				emulator.next();
			}
		} catch (IOException e) {
			// The tail handed in can start mid-escape-sequence, and a malformed one is
			// no reason to lose the rest of an otherwise good replay: what got through
			// before the failure is still shown.
		}
		widget.getTerminal().disconnected();
		return widget;
	}
}
