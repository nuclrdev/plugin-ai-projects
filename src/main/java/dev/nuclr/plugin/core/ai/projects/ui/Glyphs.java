package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
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
 * and is only chosen when something on the machine can draw it; and on this
 * plugin's own widgets it is not text at all. {@link #decorate} puts it in the
 * icon slot of a button, menu item or label as a {@link GlyphIcon} drawn from
 * whichever font has it, so the words beside it stay plain, in the theme's font,
 * and the picture sits where a picture is expected.
 *
 * <p>Strings that leave for the host - file-panel column values, function-key
 * labels, context-menu entries - go through {@link #label} and stay plain text,
 * falling back to the stand-in. The host builds those widgets itself, so there
 * is no icon slot to reach, and handing it markup it might render literally is
 * not worth the risk. {@link #span} serves HTML documents such as the quick
 * view, which have no icon slot either.
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
	/**
	 * A permission. The stand-in is a ballot box rather than the squared key: the
	 * key is missing from DejaVu Sans, so where the emoji font holds only colour
	 * bitmaps (Noto Color Emoji on a stock Linux desktop) the icon had nothing to
	 * trace at all.
	 */
	public static final String PERMISSION = pick("🔐", "☑");
	/** An allowed root. */
	public static final String ROOT = pick("📂", "▸");
	/** A context variable. */
	public static final String VARIABLE = pick("🔤", "§");
	/** The harness. */
	public static final String HARNESS = pick("⚙️", "⚙");
	/** The shared context. */
	public static final String CONTEXT = pick("💡", "◇");
	/** A built-in tool the harness grants. */
	public static final String TOOLS = pick("🔧", "⚙");
	/** Software the agent may drive. */
	public static final String SOFTWARE = pick("📦", "▣");
	/** Hardware the agent may reach. */
	public static final String HARDWARE = pick("🖥️", "▭");
	/** Network access. */
	public static final String NETWORK = pick("🌐", "◎");
	/** An execution limit or the sandbox. */
	public static final String LIMIT = pick("⏱️", "◷");
	/** Project knowledge. */
	public static final String KNOWLEDGE = pick("📚", "¶");
	/** A context-loading rule. */
	public static final String RULE = pick("📏", "※");
	/** The project configuration. */
	public static final String CONFIGURE = pick("🛠️", "⚒");
	/** A shared profile. */
	public static final String PROFILE = pick("🧩", "❖");
	/** Content written in place. */
	public static final String TEXT = pick("📝", "✍");
	/** A file attached by reference. */
	public static final String FILE = pick("📄", "▤");
	/** A link to a file or folder. */
	public static final String LINK = pick("🔗", "↗");
	/** A git repository. */
	public static final String GIT = pick("🔀", "⎇");
	/** Bring something in from a file. */
	public static final String IMPORT = pick("📥", "⇩");
	/** Write something out to a file. */
	public static final String EXPORT = pick("📤", "⇧");
	/** Move up. */
	public static final String UP = pick("⬆️", "↑");
	/** Move down. */
	public static final String DOWN = pick("⬇️", "↓");

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
	public static final String COPY = pick("⧉", "❐");
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
	/** Attach a file or a picture. */
	public static final String ATTACH = pick("📎", "+");
	/** A picture. */
	public static final String IMAGE = pick("🖼️", "▣");
	// The icon form's colours. Where an emoji's own colour carries meaning -
	// status, a destructive command, a folder - the icon keeps one; everything
	// else follows the component's foreground, so a toolbar is not a box of
	// crayons. Mid-tones, so they read on the dark theme and the light one.
	private static final Color GREEN = new Color(0x4C, 0xAF, 0x50);
	private static final Color BLUE = new Color(0x42, 0xA5, 0xF5);
	private static final Color SKY = new Color(0x64, 0xB5, 0xF6);
	private static final Color AMBER = new Color(0xFF, 0xB3, 0x00);
	private static final Color ORANGE = new Color(0xFB, 0x8C, 0x00);
	private static final Color RED = new Color(0xE5, 0x39, 0x35);
	private static final Color GOLD = new Color(0xE0, 0xA5, 0x26);
	private static final Color PURPLE = new Color(0xAB, 0x47, 0xBC);
	private static final Color PINK = new Color(0xEC, 0x40, 0x7A);
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
	private static final Color SLATE = new Color(0x78, 0x90, 0x9C);
	private static final Color GREY = new Color(0x88, 0x88, 0x88);

	/**
	 * Each glyph's tint; one not listed follows the component's foreground.
	 * Declared after the glyphs, which it reads.
	 */
	private static final Map<String, Color> TINTS = tints();

	private static final Map<String, GlyphIcon> ICONS = new ConcurrentHashMap<>();

	private Glyphs() {
	}

	private static Map<String, Color> tints() {
		// Keyed by the glyph, so two constants sharing one - the open folder is both
		// FOLDER and ROOT - share a colour too.
		var tints = new HashMap<String, Color>();
		tints.put(STOPPED, GREY);
		tints.put(FINISHED, SLATE);
		tints.put(STARTING, SKY);
		tints.put(RUNNING, GREEN);
		tints.put(WAITING, AMBER);
		tints.put(FAILED, RED);
		tints.put(ATTENTION, ORANGE);
		tints.put(MISSING, AMBER);
		tints.put(AGENT, SKY);
		tints.put(PROJECT, GOLD);
		tints.put(ROOT, GOLD);
		tints.put(FOLDER, GOLD);
		tints.put(SKILL, PURPLE);
		tints.put(CONTEXT, GOLD);
		tints.put(TEMPLATE, GOLD);
		tints.put(ENVIRONMENT, GREEN);
		tints.put(NEW, GREEN);
		tints.put(START, GREEN);
		tints.put(STOP, RED);
		tints.put(RESTART, BLUE);
		tints.put(REFRESH, BLUE);
		tints.put(RELOAD, BLUE);
		tints.put(RESET, BLUE);
		tints.put(SEND, BLUE);
		tints.put(SAVE, BLUE);
		tints.put(BROADCAST, TEAL);
		tints.put(EDIT, ORANGE);
		tints.put(RENAME, ORANGE);
		tints.put(FOCUS, RED);
		tints.put(CLEAR, PURPLE);
		tints.put(BACKGROUND, PINK);
		tints.put(DELETE, RED);
		tints.put(CLOSE, RED);
		return Map.copyOf(tints);
	}

	/**
	 * The glyph an agent wears: its status, or the flag when it is asking the
	 * user for a decision, which outranks any status.
	 *
	 * @param status    the status, possibly {@code null}
	 * @param attention whether the agent is waiting on the user
	 * @return the glyph
	 */
	public static String statusGlyph(AgentStatus status, boolean attention) {
		return attention ? ATTENTION : forStatus(status);
	}

	/**
	 * The side of the square a glyph icon is drawn in: somewhat larger than the
	 * interface font, the way a 16-pixel icon sits beside 12-point text, and
	 * following it when the theme's font is scaled.
	 *
	 * @return the size in pixels
	 */
	public static int iconSize() {
		return Math.max(14, Math.round(uiFont().getSize2D() * 4f / 3f));
	}

	/**
	 * A glyph as an icon, at the size that suits the interface font.
	 *
	 * @param glyph the glyph, possibly {@code null}
	 * @return the icon, or {@code null} when there is no glyph
	 */
	public static GlyphIcon icon(String glyph) {
		return icon(glyph, iconSize());
	}

	/**
	 * Glyphs whose icon is traced from a different character. Declared after the
	 * glyphs, which it reads; see {@link #iconForm}.
	 */
	private static final Map<String, String> ICON_FORMS = iconForms();

	private static Map<String, String> iconForms() {
		var forms = new HashMap<String, String>();
		// The coloured circles' monochrome outlines stand for their colour with
		// hatching - stripes for blue, cross-hatch for green, dots for yellow - which
		// at sixteen pixels is noise. The icon has a real colour, so a plain dot says it.
		var dot = "●";
		forms.put(RUNNING, dot);
		forms.put(STARTING, dot);
		forms.put(WAITING, dot);
		forms.put(FAILED, dot);
		forms.put(STOPPED, "○");
		// The emoji pencil lies flat and reads as a dash; the card-index box is a blob.
		forms.put(EDIT, "✎");
		forms.put(WINDOWS, "🗔");
		return Map.copyOf(forms);
	}

	/**
	 * The character a glyph's icon is traced from: usually the glyph itself, but
	 * for a few emoji whose one-colour outline does not survive being small, a
	 * plainer shape that does. Text keeps the glyph; only the icon changes.
	 *
	 * @param glyph the glyph
	 * @return what its icon draws
	 */
	public static String iconForm(String glyph) {
		return glyph == null ? null : ICON_FORMS.getOrDefault(glyph, glyph);
	}

	/**
	 * A glyph as an icon.
	 *
	 * <p>Traced from its {@link #iconForm}, from whichever font has it; failing
	 * that from the glyph itself, and failing that from its plain stand-in, so it
	 * is never a box. Icons are immutable and cached.
	 *
	 * @param glyph the glyph, possibly {@code null}
	 * @param size  the side of the square, in pixels
	 * @return the icon, or {@code null} when there is no glyph
	 */
	public static GlyphIcon icon(String glyph, int size) {
		if (glyph == null || glyph.isEmpty()) {
			return null;
		}
		return ICONS.computeIfAbsent(glyph + SIDEBAR_SEPARATOR + size, key -> {
			// Coloured by what the glyph means, whichever character ends up drawing it.
			var tint = TINTS.get(glyph);
			var form = iconForm(glyph);
			var icon = new GlyphIcon(form, iconFont(form), size, tint);
			if (icon.isBlank() && !form.equals(glyph)) {
				icon = new GlyphIcon(glyph, iconFont(glyph), size, tint);
			}
			var fallback = FALLBACKS.getOrDefault(glyph, glyph);
			if (icon.isBlank() && !fallback.equals(glyph)) {
				// Nothing here has the glyph, or the font claiming it holds only a colour
				// bitmap that Java2D cannot trace: the stand-in beats an empty square.
				icon = new GlyphIcon(fallback, iconFont(fallback), size, tint);
			}
			return icon;
		});
	}

	/**
	 * Give a button, menu item or menu its words and, in its icon slot, a glyph.
	 *
	 * <p>The disabled icon is set alongside, faded, because a look and feel will
	 * only fade an {@code ImageIcon} for itself; FlatLaf shows a disabled menu
	 * item with any other icon as no icon at all.
	 *
	 * @param <T>    the kind of button
	 * @param button the button
	 * @param glyph  the glyph; {@code null} or empty removes the icon
	 * @param text   the words, as plain text
	 * @return the same button, for chaining
	 */
	public static <T extends AbstractButton> T decorate(T button, String glyph, String text) {
		var icon = icon(glyph);
		button.setText(text == null ? "" : text);
		button.setIcon(icon);
		button.setDisabledIcon(icon == null ? null : icon.disabled());
		return button;
	}

	/**
	 * Give a label its words and, in its icon slot, a glyph.
	 *
	 * @param label the label, which may be a renderer
	 * @param glyph the glyph; {@code null} or empty removes the icon
	 * @param text  the words; plain text, or HTML the caller has escaped
	 * @return the same label, for chaining
	 */
	public static JLabel decorate(JLabel label, String glyph, String text) {
		var icon = icon(glyph);
		label.setText(text == null ? "" : text);
		label.setIcon(icon);
		label.setDisabledIcon(icon == null ? null : icon.disabled());
		return label;
	}

	/** A font that can draw a glyph, preferring the interface font; {@code null} when none can. */
	private static Font iconFont(String glyph) {
		var ui = uiFont();
		if (drawable(ui, glyph)) {
			return ui;
		}
		var family = emojiFamily(glyph);
		return family == null ? null : new Font(family, Font.PLAIN, ui.getSize());
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
			case KNOWLEDGE -> KNOWLEDGE;
			case LOADING_RULE -> RULE;
			case TOOL -> TOOLS;
			case SOFTWARE -> SOFTWARE;
			case HARDWARE -> HARDWARE;
			case NETWORK -> NETWORK;
			case LIMIT -> LIMIT;
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
	 * here, so the sidebar's renderer can put the glyph in the icon slot and the
	 * words, escaped, into HTML with the dimmed detail suffix it adds.
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
