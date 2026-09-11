package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Font;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.UIManager;

import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * The little pictures.
 *
 * <p>Getting an emoji onto a Swing label is not as simple as typing one. Java2D
 * draws a character its font lacks as an empty box rather than falling back, and
 * the font Commander's theme uses - Segoe UI on Windows - contains none of these
 * emoji at all. Typed straight into a button label they would be a row of
 * rectangles on exactly the machines this plugin is built for.
 *
 * <p>So two things happen here. Every glyph is declared with a plainer stand-in
 * and is only chosen when something on the machine can draw it; and for this
 * plugin's own widgets {@link #rich} wraps it in an HTML span naming a font that
 * can, so the picture comes from the emoji font while the words keep the
 * theme's.
 *
 * <p>Strings that leave for the host - file-panel column values, function-key
 * labels - go through {@link #label} and stay plain text, falling back to the
 * stand-in. How the host draws its own widgets is the host's business, and
 * handing it markup it might render literally is not worth the risk.
 *
 * <p>The choice can be forced with {@code -Dnuclr.ai.glyphs=emoji} or
 * {@code =symbols} when the automatic answer is wrong for a particular setup.
 */
public final class Glyphs {

	/** System property forcing {@code emoji}, {@code symbols} or {@code auto}. */
	public static final String MODE_PROPERTY = "nuclr.ai.glyphs";

	/**
	 * Joins a glyph to its text in a sidebar label. A unit separator, so it cannot
	 * occur in a file name, an agent name or anything else a user types.
	 */
	public static final String SIDEBAR_SEPARATOR = "";

	/** Cache marker for "nothing here can draw it", so a negative answer is kept. */
	private static final String NONE = "";

	/**
	 * Families tried for a glyph the interface font lacks, best first.
	 *
	 * <p>The logical name comes first because it is a composite - it covers most of
	 * what the platform has and is portable to name in HTML. The rest are the usual
	 * emoji fonts, for the supplemental blocks the composite misses.
	 */
	private static final String[] CANDIDATE_FAMILIES = {
			Font.SANS_SERIF, "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji",
			"Noto Emoji", "Segoe UI Symbol" };

	private static final Map<String, Boolean> DRAWABLE = new ConcurrentHashMap<>();

	private static final Map<String, String> EMOJI_FAMILY = new ConcurrentHashMap<>();

	/** Each chosen glyph's plain-text stand-in, recorded by {@link #pick}. */
	private static final Map<String, String> FALLBACKS = new ConcurrentHashMap<>();

	// Status.
	/** An agent with a live process. */
	public static final String RUNNING = pick("🟢", "●");
	/** An agent being launched. */
	public static final String STARTING = pick("🔵", "◌");
	/** An agent sitting at a prompt. */
	public static final String WAITING = pick("🟡", "◔");
	/** An agent that is not running. */
	public static final String STOPPED = pick("⚪", "○");
	/** An agent that ended cleanly. */
	public static final String FINISHED = pick("✅", "✓");
	/** An agent that ended badly. */
	public static final String FAILED = pick("🔴", "✖");
	/** An agent asking for a decision. */
	public static final String ATTENTION = pick("❗", "!");

	// Things.
	/** An agent. */
	public static final String AGENT = pick("🤖", "◆");
	/** A project. */
	public static final String PROJECT = pick("📁", "■");
	/** A plain shell. */
	public static final String TERMINAL = pick("💻", ">_");
	/** An instruction document. */
	public static final String INSTRUCTION = pick("📄", "≡");
	/** A skill. */
	public static final String SKILL = pick("🎓", "✶");
	/** A file whose content is injected. */
	public static final String INJECTED = pick("📎", "⁂");
	/** An MCP server or tool. */
	public static final String TOOL = pick("🔌", "⚙");
	/** An environment variable. */
	public static final String ENVIRONMENT = pick("🌱", "±");
	/** A permission. */
	public static final String PERMISSION = pick("🔐", "⚿");
	/** An allowed root. */
	public static final String ROOT = pick("📂", "▸");
	/** A context variable. */
	public static final String VARIABLE = pick("🔤", "§");
	/** The harness. */
	public static final String HARNESS = pick("⚙️", "⚙");
	/** The shared context. */
	public static final String CONTEXT = pick("💡", "◇");

	// Commands.
	/** Create something. */
	public static final String NEW = pick("➕", "+");
	/** Start. */
	public static final String START = pick("▶️", "▶");
	/** Stop. */
	public static final String STOP = pick("⏹️", "■");
	/** Restart. */
	public static final String RESTART = pick("🔄", "↻");
	/** Send an instruction. */
	public static final String SEND = pick("✉️", "→");
	/** Broadcast to several agents. */
	public static final String BROADCAST = pick("📣", "≫");
	/** Duplicate. */
	public static final String DUPLICATE = pick("⧉", "❐");
	/** Open a folder. */
	public static final String FOLDER = pick("📂", "▸");
	/** Save. */
	public static final String SAVE = pick("💾", "↓");
	/** Undo. */
	public static final String UNDO = pick("↩️", "←");
	/** Redo. */
	public static final String REDO = pick("↪️", "→");
	/** Reload from disk. */
	public static final String RELOAD = pick("🔃", "↺");
	/** Reset. */
	public static final String RESET = pick("↺", "↺");
	/** Tile the windows. */
	public static final String TILE = pick("▦", "#");
	/** Cascade the windows. */
	public static final String CASCADE = pick("❐", "▧");
	/** The window list. */
	public static final String WINDOWS = pick("🗂️", "☰");
	/** Show or hide the sidebar. */
	public static final String SIDEBAR = pick("☰", "|");
	/** The animated desktop background. */
	public static final String BACKGROUND = pick("🎨", "▦");
	/** Close. */
	public static final String CLOSE = pick("✖️", "✖");
	/** Delete. */
	public static final String DELETE = pick("🗑️", "✗");
	/** Edit. */
	public static final String EDIT = pick("✏️", "✎");
	/** Rename. */
	public static final String RENAME = pick("🏷️", "✎");
	/** Refresh. */
	public static final String REFRESH = pick("🔄", "↻");
	/** Focus a window. */
	public static final String FOCUS = pick("🎯", "◎");
	/** Copy. */
	public static final String COPY = pick("📋", "❐");
	/** Clear. */
	public static final String CLEAR = pick("✨", "⌧");
	/** Larger or smaller text. */
	public static final String ZOOM = pick("🔍", "⌕");
	/** More commands. */
	public static final String MORE = pick("⋯", "...");
	/** A template. */
	public static final String TEMPLATE = pick("⭐", "✧");
	/** A transcript. */
	public static final String TRANSCRIPT = pick("📜", "≣");
	/** Something missing or wrong. */
	public static final String MISSING = pick("⚠️", "⚠");

	private Glyphs() {
	}

	/**
	 * The glyph for an agent status.
	 *
	 * @param status the status, possibly {@code null}
	 * @return its glyph
	 */
	public static String forStatus(AgentStatus status) {
		if (status == null) {
			return STOPPED;
		}
		return switch (status) {
			case RUNNING -> RUNNING;
			case STARTING -> STARTING;
			case WAITING_INPUT -> WAITING;
			case FINISHED -> FINISHED;
			case FAILED -> FAILED;
			case STOPPED -> STOPPED;
		};
	}

	/**
	 * The glyph for a kind of context item.
	 *
	 * @param kind the category, possibly {@code null}
	 * @return its glyph
	 */
	public static String forContextKind(ContextItem.Kind kind) {
		if (kind == null) {
			return CONTEXT;
		}
		return switch (kind) {
			case INSTRUCTION -> INSTRUCTION;
			case SKILL -> SKILL;
			case INJECTED_FILE -> INJECTED;
			case MCP_TOOL -> TOOL;
			case ENVIRONMENT -> ENVIRONMENT;
			case PERMISSION -> PERMISSION;
			case ALLOWED_ROOT -> ROOT;
			case VARIABLE -> VARIABLE;
		};
	}

	/**
	 * A glyph and a label as plain text, for a string the host will draw.
	 *
	 * <p>Plain text cannot name a font, so a glyph the interface font lacks would
	 * be a box, and the stand-in is used instead.
	 *
	 * @param glyph the glyph
	 * @param text  the label
	 * @return the decorated label, with one space between the halves
	 */
	public static String label(String glyph, String text) {
		if (glyph == null || glyph.isEmpty()) {
			return text == null ? "" : text;
		}
		var shown = drawable(uiFont(), glyph) ? glyph : FALLBACKS.getOrDefault(glyph, glyph);
		if (shown.isEmpty()) {
			return text == null ? "" : text;
		}
		return text == null || text.isEmpty() ? shown : shown + " " + text;
	}

	/**
	 * A glyph and a label for the sidebar, which assembles its own HTML.
	 *
	 * <p>The halves are joined by {@link #SIDEBAR_SEPARATOR} rather than rendered
	 * here, so the sidebar's renderer can put the glyph in its own span and escape
	 * the words - it has a dimmed detail suffix to add as well, and nesting one
	 * HTML document inside another does not work.
	 *
	 * @param glyph the glyph
	 * @param text  the label
	 * @return the two halves, separated
	 */
	public static String sidebar(String glyph, String text) {
		if (glyph == null || glyph.isEmpty()) {
			return text == null ? "" : text;
		}
		return glyph + SIDEBAR_SEPARATOR + (text == null ? "" : text);
	}

	/**
	 * Split a {@link #sidebar} label back into glyph and text.
	 *
	 * @param label the joined label, possibly {@code null}
	 * @return glyph and text; the glyph is empty when there was none
	 */
	public static String[] splitSidebar(String label) {
		if (label == null) {
			return new String[] { "", "" };
		}
		var at = label.indexOf(SIDEBAR_SEPARATOR);
		return at < 0
				? new String[] { "", label }
				: new String[] { label.substring(0, at), label.substring(at + SIDEBAR_SEPARATOR.length()) };
	}

	/**
	 * A glyph and a label for one of this plugin's own widgets, as HTML.
	 *
	 * <p>The glyph goes in a span naming a font that can draw it whenever the
	 * interface font cannot, which is the normal case for emoji under Segoe UI.
	 * The words inherit the component's own font, so a button here still matches
	 * every other button in Commander.
	 *
	 * @param glyph the glyph
	 * @param text  the label
	 * @return HTML for a Swing label, button, menu item or table cell
	 */
	public static String rich(String glyph, String text) {
		var body = text == null ? "" : escape(text);
		if (glyph == null || glyph.isEmpty()) {
			return body.isEmpty() ? "" : "<html>" + body + "</html>";
		}
		return "<html>" + span(glyph) + (body.isEmpty() ? "" : " " + body) + "</html>";
	}

	/**
	 * A glyph as an HTML fragment, for a caller assembling its own markup.
	 *
	 * @param glyph the glyph
	 * @return the fragment, naming a font only when it has to
	 */
	public static String span(String glyph) {
		if (glyph == null || glyph.isEmpty()) {
			return "";
		}
		if (drawable(uiFont(), glyph)) {
			return escape(glyph);
		}
		var family = emojiFamily(glyph);
		return family == null
				? escape(glyph)
				: "<span style=\"font-family:'" + family + "'\">" + escape(glyph) + "</span>";
	}

	/**
	 * Choose between an emoji and a plainer stand-in.
	 *
	 * <p>The emoji is accepted when anything on this machine can draw it, because
	 * {@link #span} can name that font. {@link #label}, which cannot, falls back to
	 * the stand-in recorded here.
	 *
	 * @param emoji    the preferred glyph
	 * @param fallback the stand-in
	 * @return whichever can be shown
	 */
	public static String pick(String emoji, String fallback) {
		return switch (mode()) {
			case "emoji" -> emoji;
			case "symbols" -> fallback;
			default -> drawableAnywhere(emoji) ? remember(emoji, fallback) : fallback;
		};
	}

	private static String remember(String emoji, String fallback) {
		FALLBACKS.put(emoji, fallback);
		return emoji;
	}

	private static String mode() {
		try {
			var mode = System.getProperty(MODE_PROPERTY, "auto");
			return mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
		} catch (SecurityException e) {
			return "auto";
		}
	}

	/** Whether the interface font, the logical composite or an emoji font can draw this. */
	private static boolean drawableAnywhere(String glyph) {
		return DRAWABLE.computeIfAbsent(glyph,
				candidate -> drawable(uiFont(), candidate) || emojiFamily(candidate) != null);
	}

	/**
	 * A font family that can draw a glyph, or {@code null} when none can.
	 *
	 * @param glyph the glyph
	 * @return the family name, or {@code null}
	 */
	private static String emojiFamily(String glyph) {
		var family = EMOJI_FAMILY.computeIfAbsent(glyph, candidate -> {
			for (var name : CANDIDATE_FAMILIES) {
				try {
					if (drawable(new Font(name, Font.PLAIN, 12), candidate)) {
						return name;
					}
				} catch (RuntimeException | Error e) {
					continue;
				}
			}
			return NONE;
		});
		return NONE.equals(family) ? null : family;
	}

	/** Whether a font can draw every character of a glyph. */
	private static boolean drawable(Font font, String glyph) {
		try {
			// A variation selector is a formatting instruction rather than something to
			// draw, and fonts routinely report it as missing; the glyph before it is
			// what matters.
			return font.canDisplayUpTo(glyph.replace("️", "")) == -1;
		} catch (RuntimeException | Error e) {
			return false;
		}
	}

	private static Font uiFont() {
		var font = UIManager.getFont("Label.font");
		return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
