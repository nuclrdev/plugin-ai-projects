package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.awt.Color;

import javax.swing.UIManager;

import com.jediterm.terminal.TextStyle;
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
			public TextStyle getDefaultStyle() {
				return defaultStyle();
			}

			@Override
			public TextStyle getSelectionColor() {
				return selectionStyle();
			}
		};
	}

	/** Foreground and background from the theme's text-area colours. */
	static TextStyle defaultStyle() {
		return style(uiColor("TextArea.foreground", Color.WHITE), uiColor("TextArea.background", Color.BLACK));
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
				com.jediterm.terminal.TerminalColor.fromColor(toTerminalColor(foreground)),
				com.jediterm.terminal.TerminalColor.fromColor(toTerminalColor(background)));
	}

	private static Color uiColor(String key, Color fallback) {
		var color = UIManager.getColor(key);
		return color != null ? color : fallback;
	}

	private static com.jediterm.core.Color toTerminalColor(Color color) {
		return new com.jediterm.core.Color(color.getRed(), color.getGreen(), color.getBlue());
	}
}
