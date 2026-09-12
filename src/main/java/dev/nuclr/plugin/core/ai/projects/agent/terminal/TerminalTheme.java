package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.awt.Color;

import javax.swing.UIManager;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

/**
 * JediTerm colours taken from the active Commander theme, so an agent terminal
 * on the project desktop matches the rest of the window instead of being a
 * black rectangle in the middle of it.
 */
final class TerminalTheme {

	private TerminalTheme() {
	}

	/** A settings provider whose default and selection colours follow the theme. */
	static DefaultSettingsProvider settingsProvider() {
		return new DefaultSettingsProvider() {

			@Override
			public TerminalColor getDefaultForeground() {
				return terminalColor(uiColor("TextArea.foreground", Color.WHITE));
			}

			@Override
			public TerminalColor getDefaultBackground() {
				return terminalColor(uiColor("TextArea.background", Color.BLACK));
			}

			@Override
			public TextStyle getSelectionColor() {
				return selectionStyle();
			}
		};
	}

	/** Selection colours from the theme. */
	static TextStyle selectionStyle() {
		return style(uiColor("TextArea.selectionForeground", Color.WHITE),
				uiColor("TextArea.selectionBackground", new Color(0x33, 0x66, 0x99)));
	}

	/** The terminal background, used for the panel behind the widget. */
	static Color backgroundColor() {
		return uiColor("TextArea.background", Color.BLACK);
	}

	private static TextStyle style(Color foreground, Color background) {
		return new TextStyle(
				terminalColor(foreground), terminalColor(background));
	}

	private static Color uiColor(String key, Color fallback) {
		var color = UIManager.getColor(key);
		return color != null ? color : fallback;
	}

	private static TerminalColor terminalColor(Color color) {
		return TerminalColor.rgb(color.getRed(), color.getGreen(), color.getBlue());
	}
}
