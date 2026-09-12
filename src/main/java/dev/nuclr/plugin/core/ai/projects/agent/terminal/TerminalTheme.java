package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.List;

import javax.swing.UIManager;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

/**
 * JediTerm colours and font for an agent terminal.
 *
 * <p>Colours are taken from the active Commander theme, so an agent terminal on
 * the project desktop matches the rest of the window instead of being a black
 * rectangle in the middle of it. They are read from {@code UIManager} on every
 * call, so a terminal created after a theme change reflects it.
 *
 * <p>The font family is <em>not</em> themed - a terminal must stay monospaced
 * while the theme's UI font may be proportional - but it is not left to JediTerm
 * either. See {@link #monospacedFamily()}.
 */
final class TerminalTheme {

	/**
	 * The block-drawing range agent CLIs draw their banners and progress bars
	 * from: half blocks, shades, the full block, and the quadrant blocks.
	 */
	private static final int BLOCK_GLYPHS_FIRST = 0x2580;

	/** @see #BLOCK_GLYPHS_FIRST */
	private static final int BLOCK_GLYPHS_LAST = 0x259F;

	/**
	 * Monospaced families tried in order, the platform's usual terminal font
	 * first so a machine that needs no help is left looking as it did.
	 */
	private static final List<String> CANDIDATE_FAMILIES = List.of(
			"Menlo",
			"SF Mono",
			"Cascadia Mono",
			"Cascadia Code",
			"JetBrains Mono",
			"DejaVu Sans Mono",
			"Noto Sans Mono",
			"Liberation Mono",
			"Ubuntu Mono",
			"Consolas");

	/** Resolved once: enumerating and measuring fonts is far too slow to repeat while painting. */
	private static final String FAMILY = firstFamilyThatDrawsBlockGlyphs();

	private TerminalTheme() {
	}

	/** A settings provider whose colours follow the theme and whose font can draw a banner. */
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

			@Override
			public Font getTerminalFont() {
				return FAMILY == null
						? super.getTerminalFont()
						: new Font(FAMILY, Font.PLAIN, Math.round(getTerminalFontSize()));
			}
		};
	}

	/**
	 * The monospaced family agent output is drawn in, at any size.
	 *
	 * <p>Not {@link Font#MONOSPACED}. The logical monospaced font resolves to
	 * whatever the platform nominates and is only guaranteed uniform across the
	 * characters that font itself covers - on Windows its full block is half again
	 * as wide as its {@code M}, which knocks every column of a banner out of line.
	 *
	 * @return a concrete family name, or {@link Font#MONOSPACED} when no better
	 *         one is installed
	 */
	static String monospacedFamily() {
		return FAMILY == null ? Font.MONOSPACED : FAMILY;
	}

	/**
	 * Whether a font can actually draw the block glyphs, all at the width of an
	 * ordinary character.
	 *
	 * <p>This is what rules out the platform defaults. Consolas - JediTerm's own
	 * choice on Windows, and so what an agent terminal used to get - has the half
	 * blocks but none of the ten quadrant blocks, which is most of what a Claude
	 * Code or Codex banner is built from. Swing then substitutes another physical
	 * font for those glyphs alone, at its own advance width, while the terminal
	 * goes on laying text out on a grid measured from Consolas: the words come out
	 * right and the picture between them does not.
	 *
	 * @param font the font to measure
	 * @return whether every block glyph is present and cell-width
	 */
	static boolean drawsBlockGlyphs(Font font) {

		if (font == null) {
			return false;
		}
		var context = new FontRenderContext(new AffineTransform(), true, true);
		var cell = font.getStringBounds("M", context).getWidth();
		if (cell <= 0) {
			return false;
		}
		for (var glyph = BLOCK_GLYPHS_FIRST; glyph <= BLOCK_GLYPHS_LAST; glyph++) {
			if (!font.canDisplay(glyph)) {
				return false;
			}
			var width = font.getStringBounds(Character.toString(glyph), context).getWidth();
			if (Math.abs(width - cell) > 0.01) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The first candidate family that is installed and can draw a banner.
	 *
	 * @return the family name, or {@code null} to leave the choice to JediTerm
	 */
	private static String firstFamilyThatDrawsBlockGlyphs() {
		for (var candidate : CANDIDATE_FAMILIES) {
			var font = new Font(candidate, Font.PLAIN, 14);
			// An absent family silently becomes Dialog, so the name has to be confirmed
			// rather than assumed from the request.
			if (candidate.equalsIgnoreCase(font.getFamily()) && drawsBlockGlyphs(font)) {
				return candidate;
			}
		}
		return null;
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
