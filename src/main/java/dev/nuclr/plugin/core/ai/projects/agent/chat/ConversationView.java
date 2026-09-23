package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import dev.nuclr.plugin.core.ai.projects.ui.CopyContextMenu;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.Reveal;
import dev.nuclr.plugin.core.ai.projects.ui.screen.WrapLayout;

/**
 * A conversation, drawn as Swing components rather than as a terminal screen.
 *
 * <p>One block per thing that happened: the user's messages, the agent's replies
 * rendered from Markdown, its reasoning folded away, each tool call with its result
 * one click from view, permission requests with the buttons that answer them, and a
 * line at the end of every turn. Blocks are only ever appended, and consecutive
 * chunks of a reply grow the block they belong to, so a streaming answer costs a
 * re-render of one message, not of the conversation.
 *
 * <p>Event dispatch thread only.
 */
final class ConversationView extends JPanel {

	private static final long serialVersionUID = 1L;

	/** How long a streaming reply waits before its Markdown is rendered again. */
	private static final int RENDER_DELAY_MS = 80;

	/** How far a subagent's tool calls are indented under the call that started it. */
	private static final int NESTED_INDENT = 22;

	/**
	 * The scheme of the link that copies a code block, followed by the block's place in
	 * the reply. Not a URL, so the pane hands it over as a description and no browser is
	 * ever asked to open it.
	 */
	private static final String COPY_LINK = "nuclr-copy:";

	/** How tall a picture is allowed to be before it is scaled down to fit a screenful. */
	private static final int MAX_IMAGE_HEIGHT = 520;

	/** How long a prompt stays lit after the index has taken the page to it. */
	private static final int FLASH_MS = 900;

	/** How long a copied block says so before going back to offering the copy. */
	private static final int COPIED_SHOWN_MS = 1_400;

	/**
	 * How many blocks are kept on screen. Every block is a live component tree, and a
	 * conversation that runs for hours produces them without end; past a few hundred the
	 * layout costs more than the oldest ones are worth. Nothing is lost by dropping them:
	 * the transcript holds the whole conversation and is what a rebuilt window replays.
	 */
	private static final int MAX_BLOCKS = 600;

	/** How many blocks go at once, so trimming happens seldom rather than on every message. */
	private static final int TRIM_BATCH = 100;

	/** What stands at the top of a conversation whose beginning has been dropped. */
	private static final String TRIMMED = "Earlier messages are no longer shown here; "
			+ "the whole conversation is in the transcript.";

	private final Column column = new Column();
	private final JScrollPane scroll;
	private final BiConsumer<String, AgentEvent.PermissionOption> permissionAnswer;
	private final Map<String, ToolBlock> tools = new HashMap<>();
	private final Map<String, PermissionBlock> permissions = new HashMap<>();
	private JComponent last;
	/** The newest thing the user sent, which {@link #pin} stands in for once it scrolls away. */
	private UserBlock lastPrompt;
	private final PinnedPrompt pin = new PinnedPrompt();
	/** Every prompt still on screen, oldest first, as the index lists them. */
	private final DefaultListModel<UserBlock> prompts = new DefaultListModel<>();
	private final PromptIndex index = new PromptIndex();
	/** How many prompts this conversation has had, counting any dropped from the top, so each keeps its number. */
	private int sent;
	/** The folder the agent runs in, shown on the line that closes a turn; {@code null} until it is known. */
	private String folder;
	/** The model the profile asks for, used when the CLI has not said which it is using. */
	private String launchModel;
	/** The reasoning effort the profile asks for, used when the CLI has not reported one. */
	private String launchEffort;
	/** The model the running session reported, which beats the profile's; {@code null} until it says. */
	private String sessionModel;
	/** The thinking level the running session reported, if it reports one. */
	private String sessionEffort;
	/** Points added to every font, as the user has zoomed; see {@link #zoom(int)}. */
	private int fontScale;
	/** Where copied code goes; {@code null} means the display's own, which is the only case that ships. */
	private Clipboard clipboard;
	/** Draws a sent file's thumbnail; by default nothing does, and the file keeps its glyph. */
	private AttachmentStrip.Thumbnails thumbnails = (file, maxWidth, maxHeight, cancelled, answer) -> {
	};

	/**
	 * @param permissionAnswer called with a request id and the option the user chose
	 */
	/**
	 * Where sent files' thumbnails come from: the host, drawing them with the Quick View
	 * plugins. Set before messages are shown; a block already on screen keeps its glyph.
	 *
	 * @param source draws a file's thumbnail
	 */
	void setThumbnails(AttachmentStrip.Thumbnails source) {
		this.thumbnails = source;
	}

	ConversationView(BiConsumer<String, AgentEvent.PermissionOption> permissionAnswer) {
		super(new BorderLayout());
		this.permissionAnswer = permissionAnswer;
		column.setLayout(new BoxLayout(column, BoxLayout.PAGE_AXIS));
		column.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
		// The column sits at the top of a panel that fills the viewport, so a short
		// conversation keeps its blocks at their own heights instead of stretching them.
		var holder = new Column();
		holder.setLayout(new BorderLayout());
		holder.add(column, BorderLayout.NORTH);
		scroll = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(24);
		// A column header rather than a panel over the page: it takes its own row above the
		// viewport, so it never covers a block or the scroll bar.
		scroll.setColumnHeaderView(pin);
		scroll.getViewport().addChangeListener(event -> {
			updatePin();
			index.follow();
		});
		add(scroll, BorderLayout.CENTER);
		// Beside the page rather than over it, and hidden until asked for.
		index.setVisible(false);
		add(index, BorderLayout.LINE_START);
		updateTheme();
	}

	/**
	 * Show one event.
	 *
	 * @param event the event
	 * @param live  whether it is happening now; a replayed permission request gets no buttons
	 */
	void accept(AgentEvent event, boolean live) {
		var follow = atBottom();
		switch (event) {
			case AgentEvent.MessageChunk(var text) -> {
				if (last instanceof MessageBlock message) {
					message.append(text);
				} else {
					add(new MessageBlock(text));
				}
			}
			case AgentEvent.ThoughtChunk(var text) -> {
				if (last instanceof ThoughtBlock thought) {
					thought.append(text);
				} else {
					add(new ThoughtBlock(text));
				}
			}
			case AgentEvent.UserMessage(var text, var attachments) -> {
				lastPrompt = new UserBlock(text, attachments);
				add(lastPrompt);
				lastPrompt.number = ++sent;
				prompts.addElement(lastPrompt);
			}
			case AgentEvent.ToolCall call -> {
				var known = call.id() == null ? null : tools.get(call.id());
				if (known != null) {
					known.update(call);
				} else {
					var block = new ToolBlock(call);
					if (call.id() != null) {
						tools.put(call.id(), block);
					}
					add(block);
				}
			}
			case AgentEvent.ToolOutput progress -> {
				var block = tools.get(progress.id());
				if (block != null) {
					block.progress(progress.output());
				}
			}
			case AgentEvent.ToolResult result -> {
				var block = tools.get(result.id());
				if (block != null) {
					block.finish(result.error(), result.output());
				}
			}
			case AgentEvent.PermissionRequest request -> {
				var block = new PermissionBlock(request, live);
				permissions.put(request.requestId(), block);
				add(block);
			}
			case AgentEvent.PermissionResolved resolved -> {
				var block = permissions.remove(resolved.requestId());
				if (block != null) {
					block.resolve(resolved);
				}
			}
			case AgentEvent.TurnEnded turn -> add(footer(turn));
			case AgentEvent.SessionStarted started -> {
				sessionModel = blankToNull(started.model());
				sessionEffort = thinkingLevel(started.permissionMode());
				add(note(sessionLine(started), false));
			}
			case AgentEvent.Notice notice -> add(note(notice.text(), notice.error()));
			// What the agent offers in the composer, not something that happened in the
			// conversation: the window keeps it, and the page says nothing about it.
			case AgentEvent.CommandsAvailable ignored -> {
			}
			// Offered in the composer, like the commands.
			case AgentEvent.Suggestion ignored -> {
			}
			case AgentEvent.Image picture -> {
				// Only the stored shape can be shown. The window writes an inline one to disk
				// and hands it back, so one arriving here with only its bytes was never stored
				// and has no file to point at.
				if (picture.path() == null) {
					add(note("An image arrived that could not be stored.", true));
				} else {
					add(new ImageBlock(Path.of(picture.path()), picture.name()));
				}
			}
		}
		column.revalidate();
		column.repaint();
		if (follow) {
			scrollToEnd();
		}
		SwingUtilities.invokeLater(this::updatePin);
	}

	/**
	 * Hand the view a clipboard to copy into instead of the display's.
	 *
	 * <p>For tests. A headless one has no system clipboard at all, and a test that ran
	 * against the real one would take the developer's with it.
	 *
	 * @param replacement the clipboard to use
	 */
	void clipboard(Clipboard replacement) {
		this.clipboard = replacement;
	}

	/** Mark every unanswered permission request as no longer answerable. */
	void expirePermissions() {
		permissions.values().forEach(PermissionBlock::expire);
		permissions.clear();
	}

	/** Remove every block. */
	void clear() {
		column.removeAll();
		tools.clear();
		permissions.clear();
		last = null;
		lastPrompt = null;
		prompts.clear();
		sent = 0;
		column.revalidate();
		column.repaint();
		updatePin();
	}

	/** Re-read colours and fonts from the look and feel. */
	void updateTheme() {
		var background = UIManager.getColor("TextArea.background");
		column.setBackground(background);
		column.getParent().setBackground(background);
		scroll.getViewport().setBackground(background);
		pin.theme();
		index.theme();
		for (var component : column.getComponents()) {
			if (component instanceof Themed themed) {
				themed.theme();
			}
		}
	}

	/**
	 * Grow or shrink the text in every block.
	 *
	 * <p>Sizes are re-read from the look and feel on a theme change, so the zoom is kept
	 * as points to add rather than as sizes: a conversation zoomed in stays zoomed in when
	 * the theme changes under it.
	 *
	 * @param steps points to add, positive to enlarge and negative to shrink
	 */
	void zoom(int steps) {
		var next = Math.clamp(fontScale + steps, -4, 12);
		if (next != fontScale) {
			fontScale = next;
			updateTheme();
		}
	}

	/** Return the text to the size the look and feel asks for. */
	void resetZoom() {
		if (fontScale != 0) {
			fontScale = 0;
			updateTheme();
		}
	}

	/** Scroll to the newest block. */
	void scrollToEnd() {
		SwingUtilities.invokeLater(() -> {
			var bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
		});
	}

	/**
	 * Show the last prompt above the page while it is scrolled out of sight, and not otherwise.
	 *
	 * <p>The bar takes a row from the viewport as it appears. That row comes off the bottom,
	 * so a page that was following the agent's output would stop following it; it is put back
	 * at the end.
	 */
	void updatePin() {
		var prompt = lastPrompt != null && lastPrompt.getParent() == column ? lastPrompt : null;
		var hidden = prompt != null && prompt.getY() + prompt.getHeight() <= scroll.getViewport().getViewPosition().y;
		if (prompt != null) {
			pin.show(prompt.message());
		}
		if (pin.isVisible() == hidden) {
			return;
		}
		var follow = atBottom();
		pin.setVisible(hidden);
		scroll.revalidate();
		if (follow) {
			scrollToEnd();
		}
	}

	/** Whether the last prompt is pinned above the page, for tests. */
	boolean promptPinned() {
		return pin.isVisible();
	}

	/** Bring the last prompt back into view, as clicking the pinned one does. */
	void scrollToPrompt() {
		scrollTo(lastPrompt);
	}

	/** Bring a prompt to the top of the page, where it can be read from its first line. */
	private void scrollTo(UserBlock prompt) {
		if (prompt == null || prompt.getParent() != column) {
			return;
		}
		// The prompt at the top of the page, with the gap above it that the column leaves.
		var viewport = scroll.getViewport();
		var top = SwingUtilities.convertPoint(column, prompt.getLocation(), viewport.getView()).y - 8;
		var furthest = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
		viewport.setViewPosition(new java.awt.Point(0, Math.clamp(top, 0, furthest)));
	}

	/**
	 * Whether the page is scrolled to its end, read off the viewport rather than the scroll
	 * bar: the bar is told of a move by a listener of its own, which may not have run yet
	 * when {@link #updatePin()} asks.
	 */
	private boolean atBottom() {
		var viewport = scroll.getViewport();
		var view = viewport.getView();
		return view == null
				|| viewport.getViewPosition().y + viewport.getExtentSize().height >= view.getHeight() - 24;
	}

	private void add(JComponent block) {
		block.setAlignmentX(LEFT_ALIGNMENT);
		if (column.getComponentCount() > 0) {
			var gap = Box.createVerticalStrut(6);
			((JComponent) gap).setAlignmentX(LEFT_ALIGNMENT);
			column.add(gap);
		}
		column.add(block);
		last = block;
		trim();
	}

	/**
	 * Drop the oldest blocks once there are more than {@link #MAX_BLOCKS} of them.
	 *
	 * <p>Blocks and the struts between them alternate, so they go in pairs from the front.
	 * A tool call or permission request that goes with them is forgotten as well, which is
	 * what should happen: a result arriving for a call that has scrolled out of the window's
	 * memory has nowhere to be shown, and is dropped rather than resurrecting the block.
	 */
	private void trim() {
		if (column.getComponentCount() <= MAX_BLOCKS * 2) {
			return;
		}
		for (var dropped = 0; dropped < TRIM_BATCH * 2 && column.getComponentCount() > 0; dropped++) {
			column.remove(0);
		}
		tools.values().removeIf(block -> block.getParent() == null);
		for (var i = prompts.size() - 1; i >= 0; i--) {
			if (prompts.get(i).getParent() == null) {
				prompts.remove(i);
			}
		}
		permissions.values().removeIf(block -> block.getParent() == null);
		var marker = note(TRIMMED, false);
		marker.setAlignmentX(LEFT_ALIGNMENT);
		column.add(marker, 0);
	}

	private static String sessionLine(AgentEvent.SessionStarted started) {
		var line = new StringBuilder("Session started");
		if (started.model() != null) {
			line.append(" · ").append(started.model());
		}
		if (started.permissionMode() != null) {
			line.append(" · ").append(started.permissionMode());
		}
		return line.toString();
	}

	/**
	 * What the agent is running as, for the line that closes a turn: the model, the
	 * thinking level and the folder it works in.
	 *
	 * <p>The session's own word beats the profile's where it gives one - a CLI may
	 * resolve an alias, or have been told otherwise - and each part is left out when
	 * neither knows it.
	 *
	 * @param workingDirectory where the agent runs, or {@code null} when not yet known
	 * @param model            the model the profile asks for, or {@code null}
	 * @param effort           the reasoning effort the profile asks for, or {@code null}
	 */
	void setLaunchFacts(Path workingDirectory, String model, String effort) {
		folder = workingDirectory == null ? null : workingDirectory.toAbsolutePath().normalize().toString();
		launchModel = blankToNull(model);
		launchEffort = blankToNull(effort);
	}

	/** Forget what the last session reported, so the next one's facts are its own. */
	void forgetSessionFacts() {
		sessionModel = null;
		sessionEffort = null;
	}

	private JComponent footer(AgentEvent.TurnEnded turn) {
		var parts = new StringBuilder();
		if (turn.error()) {
			parts.append(turn.message() == null ? "The turn failed" : turn.message());
		} else {
			parts.append("Done");
		}
		if (turn.durationMs() != null) {
			parts.append("  ·  ").append(String.format(Locale.ROOT, "%.1f s", turn.durationMs() / 1000.0));
		}
		if (turn.costUsd() != null && turn.costUsd() > 0) {
			parts.append("  ·  ").append(String.format(Locale.ROOT, "$%.4f total", turn.costUsd()));
		}
		var model = sessionModel != null ? sessionModel : launchModel;
		if (model != null) {
			parts.append("  ·  ").append(model);
		}
		var effort = sessionEffort != null ? sessionEffort : launchEffort;
		if (effort != null) {
			parts.append("  ·  thinking ").append(effort);
		}
		if (folder != null) {
			parts.append("  ·  ").append(folder);
		}
		var label = note(parts.toString(), turn.error());
		label.setHorizontalAlignment(SwingConstants.TRAILING);
		return label;
	}

	/** A value worth showing, or {@code null} when it is empty. */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	/**
	 * The thinking level a session reported, where it reports one at all: Pi says
	 * {@code thinking medium} in the field the others use for a permission mode.
	 *
	 * @param mode what the session called its mode, or {@code null}
	 * @return the level alone, or {@code null} when the field is something else
	 */
	private static String thinkingLevel(String mode) {
		var text = blankToNull(mode);
		return text != null && text.regionMatches(true, 0, "thinking ", 0, 9) ? blankToNull(text.substring(9)) : null;
	}


	private NoteLabel note(String text, boolean error) {
		return new NoteLabel(text, error);
	}

	// ------------------------------------------------------------------ colours

	private static Color foreground() {
		return UIManager.getColor("TextArea.foreground");
	}

	private static Color muted() {
		var color = UIManager.getColor("Label.disabledForeground");
		return color != null ? color : Color.GRAY;
	}

	/** A red that reads as text on the page: a theme's error border colour is too dark for that on a dark theme. */
	private static Color errorColor() {
		var background = UIManager.getColor("TextArea.background");
		var dark = background != null
				&& (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114) / 1000 < 128;
		return dark ? new Color(0xFF, 0x6B, 0x68) : new Color(0xC6, 0x28, 0x28);
	}

	/**
	 * Text in the monospaced font at the label's own size, for a label's HTML. Swing's
	 * {@code <code>} would shrink it.
	 */
	private static String monospaceSpan(String text) {
		return "<font face=\"" + Font.MONOSPACED + "\">" + MiniMarkdown.escape(text) + "</font>";
	}

	private static Color accent() {
		var color = UIManager.getColor("Component.accentColor");
		return color != null ? color : new Color(0x42, 0xA5, 0xF5);
	}

	/** What blocks are drawn on, which decides whether code wants light or dark colours. */
	private static Color background() {
		return UIManager.getColor("TextArea.background");
	}

	/** A shade between the background and the foreground, for panels that sit on the page. */
	private static Color tint(float amount) {
		var from = UIManager.getColor("TextArea.background");
		var to = foreground();
		if (from == null || to == null) {
			return Color.LIGHT_GRAY;
		}
		return new Color(
				Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}

	/** A green that reads as text on the page, for what an edit added. */
	private static Color added() {
		var background = UIManager.getColor("TextArea.background");
		var dark = background != null
				&& (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114) / 1000 < 128;
		return dark ? new Color(0x6A, 0xC0, 0x6A) : new Color(0x2E, 0x7D, 0x32);
	}

	/** A colour part way from one to another. */
	private static Color blend(Color from, Color to, float amount) {
		return new Color(
				Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}

	/** The font blocks draw their text in, at the current zoom. */
	private Font textFont() {
		return scaled(UIManager.getFont("TextArea.font"));
	}

	/** The slightly smaller font the lines between blocks are drawn in, at the current zoom. */
	private Font labelFont() {
		var base = UIManager.getFont("Label.font");
		return base == null ? null : base.deriveFont(size(base.getSize2D() - 1f));
	}

	private Font monospace() {
		var base = UIManager.getFont("TextArea.font");
		return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size(base == null ? 12f : base.getSize2D())));
	}

	private Font scaled(Font base) {
		return base == null ? null : base.deriveFont(size(base.getSize2D()));
	}

	/** A size with the zoom applied, never so small as to be unreadable. */
	private float size(float base) {
		return Math.max(7f, base + fontScale);
	}

	/** Text of a conversation, which can be read, selected and copied but not changed. */
	private JTextArea textArea(String text, Font font) {
		var area = new JTextArea(text);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFont(font);
		area.setBorder(BorderFactory.createEmptyBorder());
		CopyContextMenu.install(area, () -> clipboard);
		return area;
	}

	// ------------------------------------------------------------------ blocks

	/** A block that draws with look-and-feel colours and must re-read them on a theme change. */
	private interface Themed {
		void theme();
	}

	/** The column of blocks: always as wide as the viewport, so text wraps instead of scrolling sideways. */
	private static final class Column extends JPanel implements Scrollable {

		private static final long serialVersionUID = 1L;

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
			return 24;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
			return Math.max(24, visible.height - 48);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return getParent() != null && getParent().getHeight() > getPreferredSize().height;
		}
	}

	/**
	 * A picture the agent produced: shown where Java can read it, offered where it cannot.
	 *
	 * <p>A real component rather than an image in a reply's HTML. The pane redraws a reply
	 * from its Markdown after every chunk, which would decode the picture again each time,
	 * and Swing's HTML would give it no way to be copied or saved. As its own block it is
	 * decoded once, scaled to the width there is, and carries its own actions.
	 */
	private final class ImageBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final Path file;
		private final String name;
		private final JLabel header = new JLabel();
		private final JLabel picture = new JLabel();
		private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		private final JButton copyButton = Glyphs.decorate(new JButton(), Glyphs.COPY, "Copy");
		private final JButton saveButton = Glyphs.decorate(new JButton(), Glyphs.SAVE, "Save as...");
		private final JButton openButton = Glyphs.decorate(new JButton(), Glyphs.LINK, "Open");
		private final BufferedImage image;
		private String note = "";

		ImageBlock(Path file, String name) {
			super(new BorderLayout());
			this.file = file;
			this.name = name == null || name.isBlank() ? file.getFileName().toString() : name;
			this.image = read(file);

			setOpaque(false);
			copyButton.setToolTipText(image != null ? "Copy the image" : "Copy the file");
			copyButton.addActionListener(event -> copy());
			saveButton.setToolTipText("Save a copy, and show it in the file manager");
			saveButton.addActionListener(event -> saveAs());
			openButton.setToolTipText("Open in the application this system uses for it");
			openButton.addActionListener(event -> open());
			// Java draws png, jpeg, gif and bmp; anything else - an svg, a webp on a JDK
			// without the reader - is a file the system knows better than this window does.
			openButton.setVisible(image == null);
			for (var button : new JButton[] { openButton, copyButton, saveButton }) {
				button.putClientProperty("JButton.buttonType", "toolBarButton");
				button.setFocusable(false);
				actions.add(button);
			}
			actions.setOpaque(false);

			var top = new JPanel(new BorderLayout());
			top.setOpaque(false);
			top.add(header, BorderLayout.CENTER);
			top.add(actions, BorderLayout.EAST);
			picture.setHorizontalAlignment(SwingConstants.LEADING);
			picture.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
			add(top, BorderLayout.NORTH);
			add(picture, BorderLayout.CENTER);
			// The same three things the buttons do, where the hand goes for them. Set on
			// the block and inherited by what is inside it, so a right-click anywhere over
			// the picture - or over the line naming it - finds the menu; Swing knows which
			// gesture opens one on this platform better than a mouse listener would.
			setComponentPopupMenu(menu());
			picture.setInheritsPopupMenu(true);
			top.setInheritsPopupMenu(true);
			header.setInheritsPopupMenu(true);
			// Re-scaled as the window is resized, so a picture uses the width it is given
			// without ever forcing the conversation wider than the frame.
			addComponentListener(new java.awt.event.ComponentAdapter() {
				@Override
				public void componentResized(java.awt.event.ComponentEvent event) {
					showPicture();
				}
			});
			theme();
		}

		/**
		 * The menu a right-click opens.
		 *
		 * <p>Open is offered whatever the format, unlike the button beside it: the button
		 * row stays as short as it can be and only shows Open where nothing else will do,
		 * while a menu that has been asked for should hold everything that can be done.
		 */
		private javax.swing.JPopupMenu menu() {
			var menu = new javax.swing.JPopupMenu();
			menu.add(item("Open", Glyphs.LINK, this::open));
			menu.add(item("Copy to clipboard", Glyphs.COPY, this::copy));
			menu.add(item("Save as...", Glyphs.SAVE, this::saveAs));
			return menu;
		}

		/** One entry, with its glyph where a menu expects an icon. */
		private static javax.swing.JMenuItem item(String label, String glyph, Runnable action) {
			var entry = Glyphs.decorate(new javax.swing.JMenuItem(), glyph, label);
			entry.addActionListener(event -> action.run());
			return entry;
		}

		/** The image, or {@code null} when Java has no reader for it. */
		private static BufferedImage read(Path file) {
			try {
				return javax.imageio.ImageIO.read(file.toFile());
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				// Unreadable, unknown to ImageIO, or too large to hold: it is still a file.
				return null;
			}
		}

		/** Whether Java could read the picture, and so whether it is drawn here at all. */
		boolean isDrawn() {
			return image != null;
		}

		/** Draw the image at the width there is, never enlarged and never past a screenful. */
		private void showPicture() {
			if (image == null) {
				picture.setIcon(null);
				return;
			}
			var insets = getInsets();
			var available = getWidth() - insets.left - insets.right;
			var width = Math.min(image.getWidth(), available > 0 ? available : image.getWidth());
			var height = Math.round(image.getHeight() * (width / (float) image.getWidth()));
			if (height > MAX_IMAGE_HEIGHT) {
				height = MAX_IMAGE_HEIGHT;
				width = Math.round(image.getWidth() * (height / (float) image.getHeight()));
			}
			if (width <= 0 || height <= 0) {
				return;
			}
			var shown = picture.getIcon();
			if (shown != null && shown.getIconWidth() == width && shown.getIconHeight() == height) {
				return;
			}
			picture.setIcon(new ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH)));
		}

		/** The image itself for anything that takes one, and the file for anything that does not. */
		private void copy() {
			try {
				var target = clipboard != null ? clipboard : getToolkit().getSystemClipboard();
				target.setContents(new ImageTransfer(image, file), null);
				say("copied");
			} catch (IllegalStateException | java.awt.HeadlessException e) {
				say("could not copy it");
			}
		}

		/** Save a copy wherever the user says, and then show it to them where it landed. */
		private void saveAs() {
			var chooser = new javax.swing.JFileChooser();
			chooser.setDialogTitle("Save image");
			chooser.setSelectedFile(new java.io.File(name));
			if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
				return;
			}
			var target = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
			// The chooser does not ask, and saving a picture is not worth losing a file
			// somebody already had under that name.
			if (Files.exists(target) && Dialogs.showConfirmDialog(this,
					target.getFileName() + " already exists. Replace it?", "Save image",
					javax.swing.JOptionPane.OK_CANCEL_OPTION,
					javax.swing.JOptionPane.WARNING_MESSAGE) != javax.swing.JOptionPane.OK_OPTION) {
				return;
			}
			try {
				var saved = saveTo(target);
				say("saved to " + saved);
				Reveal.show(saved);
			} catch (IOException | RuntimeException e) {
				say("could not save it: " + e.getMessage());
			}
		}

		/**
		 * Copy the picture to where the user chose.
		 *
		 * <p>Saves and nothing else: what to say about it, and showing it in the file
		 * manager afterwards, belong to whoever asked for the save.
		 *
		 * @param target where to put it
		 * @return where it landed
		 * @throws IOException when it could not be written there
		 */
		Path saveTo(Path target) throws IOException {
			var absolute = target.toAbsolutePath().normalize();
			if (Files.isDirectory(absolute)) {
				// Copying onto a folder would delete it when it happens to be empty.
				throw new IOException(absolute.getFileName() + " is a folder");
			}
			if (absolute.getParent() != null) {
				Files.createDirectories(absolute.getParent());
			}
			Files.copy(file, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return absolute;
		}

		/** Hand the file to whatever this system opens it with. */
		private void open() {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
					Desktop.getDesktop().open(file.toFile());
					return;
				}
				say("no application to open it with");
			} catch (IOException | RuntimeException e) {
				say("could not open it: " + e.getMessage());
			}
		}

		/** Say something about the picture, beside its name, until something else is said. */
		private void say(String said) {
			note = said;
			updateHeader();
		}

		/** What the header says now, for the tests and for anything that asks. */
		String headerText() {
			return header.getText();
		}

		private void updateHeader() {
			var parts = new StringBuilder(name);
			if (image != null) {
				parts.append("  ").append(image.getWidth()).append('x').append(image.getHeight());
			}
			if (!note.isEmpty()) {
				parts.append("  - ").append(note);
			}
			header.setText(parts.toString());
		}

		@Override
		public void theme() {
			header.setForeground(muted());
			var font = labelFont();
			if (font != null) {
				header.setFont(font);
			}
			updateHeader();
			showPicture();
		}
	}

	/**
	 * A picture on the clipboard, and the file it came from.
	 *
	 * <p>Both, because what is wanted depends on where it is going: an editor or a chat
	 * takes the image, a file manager or a mail client takes the file. An image Java
	 * could not read offers only the file, which is all there is to give.
	 */
	private static final class ImageTransfer implements java.awt.datatransfer.Transferable {

		private final Image image;
		private final Path file;

		ImageTransfer(Image image, Path file) {
			this.image = image;
			this.file = file;
		}

		@Override
		public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
			return image == null
					? new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.javaFileListFlavor }
					: new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.imageFlavor,
							java.awt.datatransfer.DataFlavor.javaFileListFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
			for (var supported : getTransferDataFlavors()) {
				if (supported.equals(flavor)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Object getTransferData(java.awt.datatransfer.DataFlavor flavor)
				throws java.awt.datatransfer.UnsupportedFlavorException {
			if (java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor) && image != null) {
				return image;
			}
			if (java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor)) {
				return List.of(file.toFile());
			}
			throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
		}
	}

	/** A line said about the conversation: a session start, the end of a turn, a problem. */
	private final class NoteLabel extends JLabel implements Themed {

		private static final long serialVersionUID = 1L;
		private final boolean error;

		NoteLabel(String text, boolean error) {
			super("<html>" + MiniMarkdown.escape(text).replace("\n", "<br>") + "</html>");
			this.error = error;
			setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
			theme();
		}

		@Override
		public void theme() {
			setForeground(error ? errorColor() : muted());
			var base = labelFont();
			if (base != null) {
				setFont(base);
			}
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/** How large a picture sent with a message is drawn in it; clicking it opens the whole picture. */
	private static final int SENT_THUMBNAIL_WIDTH = 240;
	private static final int SENT_THUMBNAIL_HEIGHT = 160;
	/** A sent file's thumbnail sits in a chip, beside its name, so it is kept small. */
	private static final int SENT_FILE_THUMBNAIL_SIZE = 56;

	/** The most of a pasted text shown when it is opened in the conversation; the file holds the rest. */
	private static final int SHOWN_PASTE_CHARS = 100_000;

	/** What the user sent, on a shaded panel with an accent bar, with whatever was attached to it. */
	private final class UserBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final String message;
		private final List<AgentEvent.Attachment> attachments;
		private final JTextArea text;
		private final JButton copyButton = Glyphs.decorate(new JButton(), Glyphs.COPY, null);
		/** The captions of the attachments, re-coloured with the theme. */
		private final List<JLabel> captions = new ArrayList<>();
		/** Pasted texts opened in place, re-fonted with the zoom. */
		private final List<JTextArea> opened = new ArrayList<>();
		/** Where it stands among the prompts of the conversation, from 1. */
		private int number;
		private final Timer flash = new Timer(FLASH_MS, event -> theme());

		UserBlock(String message, List<AgentEvent.Attachment> attachments) {
			super(new BorderLayout(6, 0));
			this.message = message;
			this.attachments = List.copyOf(attachments);
			text = textArea(message, textFont());
			var body = new JPanel(new BorderLayout(0, 6));
			body.setOpaque(false);
			if (!message.isBlank() || this.attachments.isEmpty()) {
				body.add(text, BorderLayout.CENTER);
			}
			if (!this.attachments.isEmpty()) {
				body.add(attached(), message.isBlank() ? BorderLayout.CENTER : BorderLayout.SOUTH);
			}
			add(body, BorderLayout.CENTER);
			copyButton.setToolTipText("Copy to clipboard");
			copyButton.putClientProperty("JButton.buttonType", "toolBarButton");
			copyButton.setFocusable(false);
			copyButton.addActionListener(event -> copy());
			var actions = new JPanel(new BorderLayout());
			actions.setOpaque(false);
			actions.add(copyButton, BorderLayout.NORTH);
			add(actions, BorderLayout.EAST);
			theme();
		}

		/** The row of attachments, and under it any pasted text that has been opened. */
		private JComponent attached() {
			var row = new JPanel(new WrapLayout(8, 4));
			row.setOpaque(false);
			var texts = new JPanel();
			texts.setOpaque(false);
			texts.setLayout(new BoxLayout(texts, BoxLayout.PAGE_AXIS));
			for (var attachment : attachments) {
				row.add(switch (attachment.kind()) {
					case IMAGE -> picture(attachment);
					case TEXT -> pasted(attachment, texts);
					case FILE -> file(attachment);
				});
			}
			var holder = new JPanel(new BorderLayout(0, 4));
			holder.setOpaque(false);
			holder.add(row, BorderLayout.NORTH);
			holder.add(texts, BorderLayout.CENTER);
			return holder;
		}

		/** A picture that was sent, small, opening in the system's viewer when clicked. */
		private JComponent picture(AgentEvent.Attachment attachment) {
			var file = attachment.file();
			var name = Attachments.displayName(attachment);
			var thumbnail = Attachments.thumbnail(file, SENT_THUMBNAIL_WIDTH, SENT_THUMBNAIL_HEIGHT);
			var label = new JLabel();
			label.setBorder(BorderFactory.createLineBorder(tint(0.25f), 1));
			if (thumbnail == null) {
				// Gone from the runtime folder, or never readable: say so rather than draw nothing.
				Glyphs.decorate(label, Glyphs.IMAGE, name + " (the file is gone)");
				captions.add(label);
				return label;
			}
			label.setIcon(thumbnail);
			label.setToolTipText(name + "  " + thumbnail.getDescription() + " - click to open it");
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event)) {
						open(label, attachment);
					}
				}
			});
			var menu = new javax.swing.JPopupMenu();
			menu.add(menuItem("Open", Glyphs.LINK, () -> open(label, attachment)));
			menu.add(menuItem("Copy to clipboard", Glyphs.COPY, () -> copyPicture(file)));
			label.setComponentPopupMenu(menu);
			return label;
		}

		/**
		 * A file that was sent by its path: its name, type and size, opening when clicked, and
		 * a thumbnail of what it holds once the host has drawn one.
		 */
		private JComponent file(AgentEvent.Attachment attachment) {
			var label = new JLabel();
			Glyphs.decorate(label, Glyphs.FILE,
					Attachments.displayName(attachment) + "  ·  " + Attachments.fileDetail(attachment.file()));
			label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tint(0.25f), 1, true),
					BorderFactory.createEmptyBorder(3, 6, 3, 6)));
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.setToolTipText(attachment.path() + " - given to the agent by its path; click to open it");
			captions.add(label);
			label.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					if (SwingUtilities.isLeftMouseButton(event)) {
						open(label, attachment);
					}
				}
			});
			var menu = new javax.swing.JPopupMenu();
			menu.add(menuItem("Open", Glyphs.LINK, () -> open(label, attachment)));
			label.setComponentPopupMenu(menu);
			thumbnails.request(attachment.file(), SENT_FILE_THUMBNAIL_SIZE, SENT_FILE_THUMBNAIL_SIZE,
					new java.util.concurrent.atomic.AtomicBoolean(), image -> {
						if (image != null) {
							var icon = new ImageIcon(image);
							label.setIcon(icon);
							label.setDisabledIcon(icon);
							label.revalidate();
						}
					});
			return label;
		}

		/** A pasted text that was sent: its name and length, shown in place when clicked. */
		private JComponent pasted(AgentEvent.Attachment attachment, JPanel texts) {
			var file = attachment.file();
			var label = new JLabel();
			Glyphs.decorate(label, Glyphs.TEXT,
					Attachments.displayName(attachment) + "  ·  " + Attachments.textDetail(file));
			label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tint(0.25f), 1, true),
					BorderFactory.createEmptyBorder(3, 6, 3, 6)));
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.setToolTipText("Sent as text in front of the message - click to show it here, again to hide it");
			captions.add(label);
			JTextArea[] shown = { null };
			label.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					if (!SwingUtilities.isLeftMouseButton(event)) {
						return;
					}
					if (shown[0] != null) {
						texts.remove(shown[0]);
						opened.remove(shown[0]);
						shown[0] = null;
					} else {
						shown[0] = textArea(pastedText(file), monospace());
						shown[0].setAlignmentX(LEFT_ALIGNMENT);
						shown[0].setForeground(foreground());
						opened.add(shown[0]);
						texts.add(shown[0]);
					}
					column.revalidate();
					column.repaint();
				}
			});
			var menu = new javax.swing.JPopupMenu();
			menu.add(menuItem("Open", Glyphs.LINK, () -> open(label, attachment)));
			label.setComponentPopupMenu(menu);
			return label;
		}

		/** A pasted text's content, as much of it as is worth drawing. */
		private static String pastedText(Path file) {
			try {
				var content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
				return content.length() > SHOWN_PASTE_CHARS
						? content.substring(0, SHOWN_PASTE_CHARS) + "\n... (the rest is in " + file + ")"
						: content.stripTrailing();
			} catch (IOException | RuntimeException e) {
				return "The pasted text could not be read: " + e.getMessage();
			}
		}

		/** Open an attachment's file, saying on the attachment itself when that fails. */
		private static void open(JLabel label, AgentEvent.Attachment attachment) {
			var problem = Attachments.open(attachment.file());
			if (problem != null) {
				label.setToolTipText(Attachments.displayName(attachment) + ": " + problem);
			}
		}

		private void copyPicture(Path file) {
			try {
				var target = clipboard != null ? clipboard : getToolkit().getSystemClipboard();
				target.setContents(new ImageTransfer(javax.imageio.ImageIO.read(file.toFile()), file), null);
			} catch (IOException | IllegalStateException | java.awt.HeadlessException e) {
				// Unreadable, or the clipboard is held by another application.
			}
		}

		private static javax.swing.JMenuItem menuItem(String label, String glyph, Runnable action) {
			var entry = Glyphs.decorate(new javax.swing.JMenuItem(), glyph, label);
			entry.addActionListener(event -> action.run());
			return entry;
		}

		/** The message as it went to the agent: pasted texts in front of the words. */
		private void copy() {
			String copied;
			try {
				copied = Attachments.wireText(message, attachments);
			} catch (IOException e) {
				copied = message;
			}
			if (copied.isBlank()) {
				copied = message();
			}
			try {
				var target = clipboard != null ? clipboard : getToolkit().getSystemClipboard();
				target.setContents(new StringSelection(copied), null);
			} catch (IllegalStateException | java.awt.HeadlessException e) {
				// The system clipboard may be unavailable or held by another application.
			}
		}

		@Override
		public void theme() {
			setBackground(tint(0.08f));
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent()),
					BorderFactory.createEmptyBorder(6, 8, 6, 8)));
			text.setForeground(foreground());
			text.setFont(textFont());
			for (var caption : captions) {
				caption.setForeground(muted());
				caption.setFont(labelFont());
			}
			for (var area : opened) {
				area.setForeground(foreground());
				area.setFont(monospace());
			}
		}

		/** The message on one line's worth: its words, or what it carried when it had none. */
		String message() {
			return Attachments.summary(message, attachments);
		}

		/** Light up for a moment, so the eye finds the prompt the page has just jumped to. */
		void flash() {
			setBackground(blend(tint(0.08f), accent(), 0.35f));
			flash.setRepeats(false);
			flash.restart();
		}
	}

	/**
	 * Say whether the agent is working on the last prompt, which the pinned prompt shows by
	 * pulsing.
	 *
	 * @param working whether a turn is under way
	 */
	void setWorking(boolean working) {
		pin.setWorking(working);
	}

	/** Whether the pinned prompt is pulsing, for tests. */
	boolean promptPulsing() {
		return pin.pulse.isRunning();
	}

	/**
	 * Show or hide the index of prompts beside the page.
	 *
	 * @param shown whether it is shown
	 */
	void setIndexShown(boolean shown) {
		if (index.isVisible() == shown) {
			return;
		}
		index.setVisible(shown);
		revalidate();
		repaint();
		if (shown) {
			SwingUtilities.invokeLater(index::follow);
		}
	}

	/** Whether the index of prompts is shown. */
	boolean indexShown() {
		return index.isVisible();
	}

	/** What the index lists, one line per prompt, for tests. */
	List<String> indexEntries() {
		var entries = new ArrayList<String>();
		for (var i = 0; i < prompts.size(); i++) {
			entries.add(prompts.get(i).number + ". " + oneLine(prompts.get(i).message()));
		}
		return entries;
	}

	/** Which prompt the index marks as the one being read, from 0, or -1 for none; for tests. */
	int indexSelection() {
		return index.list.getSelectedIndex();
	}

	/** A prompt's row in the index as the list draws it, for tests. */
	java.awt.Component indexRow(int position) {
		var list = index.list;
		return list.getCellRenderer().getListCellRendererComponent(list, prompts.get(position), position, false, false);
	}

	/** Mark the prompt being read in the index, as scrolling the page does; for tests. */
	void followInIndex() {
		index.follow();
	}

	/**
	 * Take the page to a prompt, as clicking it in the index does.
	 *
	 * @param position the prompt's place in the index, from 0
	 */
	void goToPrompt(int position) {
		if (position < 0 || position >= prompts.size()) {
			return;
		}
		var prompt = prompts.get(position);
		scrollTo(prompt);
		prompt.flash();
	}

	/** A message on one line: its line breaks and runs of spaces become single spaces. */
	private static String oneLine(String message) {
		return message.strip().replaceAll("\\s+", " ");
	}

	/**
	 * Every prompt of the conversation, one line each, down the side of the page: a table
	 * of contents for a session too long to scroll through by eye. Clicking one takes the
	 * page to it; scrolling the page moves the mark to the prompt being read.
	 */
	private final class PromptIndex extends JPanel {

		private static final long serialVersionUID = 1L;
		private static final int WIDTH = 240;
		/** More than a line holds at the index's width; the label cuts the rest with an ellipsis. */
		private static final int SHOWN_CHARS = 200;

		private final JLabel title = new JLabel();
		private final JLabel empty = new JLabel("Nothing sent yet");
		private final JList<UserBlock> list = new JList<>(prompts);
		private final JScrollPane listScroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		/** Set while the page moves the mark, so the mark moving does not move the page back. */
		private boolean following;

		PromptIndex() {
			super(new BorderLayout());
			title.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
			empty.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
			empty.setVerticalAlignment(SwingConstants.TOP);
			list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
			list.setCellRenderer(new Entry());
			list.addListSelectionListener(event -> {
				if (!following && !event.getValueIsAdjusting()) {
					goToPrompt(list.getSelectedIndex());
				}
			});
			// A click on the prompt already marked changes no selection, and should still go there.
			list.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					var at = list.locationToIndex(event.getPoint());
					if (at >= 0 && at == list.getSelectedIndex() && list.getCellBounds(at, at).contains(event.getPoint())) {
						goToPrompt(at);
					}
				}
			});
			prompts.addListDataListener(new javax.swing.event.ListDataListener() {
				@Override
				public void intervalAdded(javax.swing.event.ListDataEvent event) {
					count();
				}

				@Override
				public void intervalRemoved(javax.swing.event.ListDataEvent event) {
					count();
				}

				@Override
				public void contentsChanged(javax.swing.event.ListDataEvent event) {
					count();
				}
			});
			listScroll.setBorder(BorderFactory.createEmptyBorder());
			listScroll.getVerticalScrollBar().setUnitIncrement(16);
			add(title, BorderLayout.NORTH);
			add(empty, BorderLayout.CENTER);
			count();
			theme();
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(WIDTH + fontScale * 8, super.getPreferredSize().height);
		}

		/** Say how many prompts there are, and put the list or the empty line in the middle. */
		private void count() {
			title.setText(prompts.isEmpty() ? "Prompts" : "Prompts (" + prompts.size() + ")");
			JComponent shown = prompts.isEmpty() ? empty : listScroll;
			if (shown.getParent() != this) {
				remove(prompts.isEmpty() ? listScroll : empty);
				add(shown, BorderLayout.CENTER);
				revalidate();
				repaint();
			}
		}

		/** Mark the prompt whose part of the conversation is at the top of the page. */
		void follow() {
			if (!isVisible()) {
				return;
			}
			var viewport = scroll.getViewport();
			var top = viewport.getViewPosition().y + 16;
			var current = -1;
			for (var i = 0; i < prompts.size(); i++) {
				var prompt = prompts.get(i);
				if (prompt.getParent() != column) {
					continue;
				}
				if (SwingUtilities.convertPoint(column, prompt.getLocation(), viewport.getView()).y > top) {
					break;
				}
				current = i;
			}
			if (current == list.getSelectedIndex()) {
				return;
			}
			following = true;
			try {
				if (current < 0) {
					list.clearSelection();
				} else {
					list.setSelectedIndex(current);
					list.ensureIndexIsVisible(current);
				}
			} finally {
				following = false;
			}
		}

		void theme() {
			var shade = tint(0.04f);
			setBackground(shade);
			setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, tint(0.18f)));
			list.setBackground(shade);
			listScroll.getViewport().setBackground(shade);
			var base = labelFont();
			title.setFont(base == null ? null : base.deriveFont(Font.BOLD));
			title.setForeground(accent());
			empty.setFont(base);
			empty.setForeground(muted());
			list.setSelectionBackground(blend(shade, accent(), 0.30f));
			list.setSelectionForeground(foreground());
			// A new font changes every row's height, which the list measures again only when its renderer changes.
			list.setCellRenderer(new Entry());
			revalidate();
			repaint();
		}

		/** One prompt in the index: its number, muted, and its first words. */
		private final class Entry extends JPanel implements ListCellRenderer<UserBlock> {

			private static final long serialVersionUID = 1L;
			private final JLabel number = new JLabel();
			private final JLabel text = new JLabel();

			Entry() {
				super(new BorderLayout(6, 0));
				setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
				// The user's words, shown as typed: a prompt that opens with <html> is not markup.
				text.putClientProperty("html.disable", Boolean.TRUE);
				add(number, BorderLayout.WEST);
				add(text, BorderLayout.CENTER);
			}

			@Override
			public java.awt.Component getListCellRendererComponent(JList<? extends UserBlock> source, UserBlock prompt,
					int position, boolean selected, boolean focused) {
				var line = oneLine(prompt.message());
				text.setText(line.length() > SHOWN_CHARS ? line.substring(0, SHOWN_CHARS) : line);
				number.setText(Integer.toString(prompt.number));
				text.setFont(textFont());
				number.setFont(labelFont());
				text.setForeground(selected ? source.getSelectionForeground() : foreground());
				number.setForeground(selected ? accent() : muted());
				setBackground(selected ? source.getSelectionBackground() : source.getBackground());
				var tip = prompt.message().strip();
				setToolTipText("<html>" + MiniMarkdown.escape(tip.length() > 600 ? tip.substring(0, 600) + "..." : tip)
						.replace("\n", "<br>") + "</html>");
				return this;
			}
		}
	}

	/**
	 * The last prompt, on one line above the page, while the prompt itself has scrolled
	 * out of sight - so what the agent is working on stays in view however far its output
	 * runs. Clicking it goes back to the prompt.
	 *
	 * <p>It is meant to be seen: shaded with the accent colour rather than the page's grey,
	 * with a thicker accent bar and a label saying what it is. While the agent works the
	 * shading breathes, slowly - motion that says the agent is still on it, and stops the
	 * moment it is not, so a bar that has gone still is a turn that has ended. The timer
	 * behind it runs only while the bar is on screen and the agent is working.
	 */
	private final class PinnedPrompt extends JPanel {

		private static final long serialVersionUID = 1L;
		/** More than a line holds at any width; the label cuts the rest with an ellipsis. */
		private static final int SHOWN_CHARS = 400;
		private static final int PULSE_FRAME_MS = 50;
		private static final double PULSE_PERIOD_MS = 1_800;
		/** How much accent the shading holds at rest, and how much more at the top of a breath. */
		private static final float ACCENT_REST = 0.16f;
		private static final float ACCENT_SWING = 0.18f;
		private static final int BAR_WIDTH = 4;

		private final JLabel tag = new JLabel();
		private final JLabel label = new JLabel();
		private final Timer pulse = new Timer(PULSE_FRAME_MS, event -> breathe());
		private boolean working;
		private float glow;

		PinnedPrompt() {
			super(new BorderLayout(8, 0));
			tag.setIcon(Glyphs.icon(Glyphs.UP));
			tag.setIconTextGap(6);
			// The user's words, shown as typed: a prompt that opens with <html> is not markup.
			label.putClientProperty("html.disable", Boolean.TRUE);
			add(tag, BorderLayout.WEST);
			add(label, BorderLayout.CENTER);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					scrollToPrompt();
				}
			});
			setVisible(false);
			updateTag();
			theme();
		}

		void show(String message) {
			// One line, so the page loses as little as it can: line breaks become spaces.
			var line = message.strip().replaceAll("\\s+", " ");
			label.setText(line.length() > SHOWN_CHARS ? line.substring(0, SHOWN_CHARS) : line);
			var tip = message.strip();
			setToolTipText("<html><b>Your last message</b> - click to go back to it<br>"
					+ MiniMarkdown.escape(tip.length() > 600 ? tip.substring(0, 600) + "..." : tip)
							.replace("\n", "<br>")
					+ "</html>");
		}

		void setWorking(boolean now) {
			if (working == now) {
				return;
			}
			working = now;
			updateTag();
			updatePulse();
		}

		@Override
		public void setVisible(boolean visible) {
			super.setVisible(visible);
			updatePulse();
		}

		@Override
		public void addNotify() {
			super.addNotify();
			updatePulse();
		}

		@Override
		public void removeNotify() {
			// A closed window must not leave a timer running for a bar nobody can see.
			pulse.stop();
			super.removeNotify();
		}

		private void updatePulse() {
			if (working && isVisible()) {
				pulse.start();
			} else {
				pulse.stop();
				glow = 0;
				repaint();
			}
		}

		private void breathe() {
			if (!isShowing()) {
				return;
			}
			// A sine eased into 0..1: slow at either end, the way a breath is.
			var phase = (System.currentTimeMillis() % (long) PULSE_PERIOD_MS) / PULSE_PERIOD_MS;
			glow = (float) (0.5 - 0.5 * Math.cos(phase * 2 * Math.PI));
			repaint();
		}

		private void updateTag() {
			tag.setText(working ? "WORKING ON" : "YOUR LAST MESSAGE");
			tag.setToolTipText(null);
		}

		void theme() {
			setOpaque(true);
			setBorder(BorderFactory.createEmptyBorder(6, BAR_WIDTH + 8, 7, 8));
			var accent = accent();
			tag.setForeground(accent);
			var base = labelFont();
			tag.setFont(base == null ? null : base.deriveFont(Font.BOLD));
			label.setForeground(foreground());
			var text = textFont();
			label.setFont(text == null ? null : text.deriveFont(Font.BOLD));
			repaint();
		}

		@Override
		protected void paintComponent(java.awt.Graphics graphics) {
			var accent = accent();
			var width = getWidth();
			var height = getHeight();
			graphics.setColor(blend(tint(0.08f), accent, ACCENT_REST + ACCENT_SWING * glow));
			graphics.fillRect(0, 0, width, height);
			graphics.setColor(accent);
			graphics.fillRect(0, 0, BAR_WIDTH, height);
			// A hairline in the accent along the bottom, and a soft shadow under it, so the
			// bar reads as lying over the page rather than as the first line of it.
			graphics.setColor(blend(tint(0.08f), accent, 0.55f));
			graphics.fillRect(0, height - 2, width, 1);
			graphics.setColor(blend(background() == null ? java.awt.Color.DARK_GRAY : background(),
					java.awt.Color.BLACK, 0.25f));
			graphics.fillRect(0, height - 1, width, 1);
		}
	}

	/** The agent's reply, rendered from Markdown, re-rendered a moment after each chunk. */
	private final class MessageBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final StringBuilder markdown = new StringBuilder();
		private final JEditorPane pane = new JEditorPane();
		private final Timer render;
		private CodeHighlighter highlighter = new CodeHighlighter(background());
		/** Each fenced block's code, in the order they appear, as the copy links index them. */
		private final List<String> fenced = new ArrayList<>();
		private final Timer copiedShown;
		private int copied = -1;

		MessageBlock(String text) {
			super(new BorderLayout());
			setOpaque(false);
			markdown.append(text);
			pane.setEditable(false);
			pane.setOpaque(false);
			pane.setBorder(BorderFactory.createEmptyBorder());
			pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
			pane.setEditorKit(new HTMLEditorKit());
			pane.addHyperlinkListener(event -> {
				if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
					return;
				}
				var description = event.getDescription();
				if (description != null && description.startsWith(COPY_LINK)) {
					copy(description.substring(COPY_LINK.length()));
					return;
				}
				if (event.getURL() != null) {
					try {
						Desktop.getDesktop().browse(event.getURL().toURI());
					} catch (Exception e) {
						// No browser; the link is still there to copy.
					}
				}
			});
			// The reply is the one block with a source behind what is drawn, so it is the
			// one that can offer the Markdown it was written in - rather than the pane's
			// own document, which is full of the copy links and the shading that make it
			// readable here and nowhere else.
			CopyContextMenu.install(pane, () -> clipboard, markdown::toString);
			add(pane, BorderLayout.CENTER);
			render = new Timer(RENDER_DELAY_MS, event -> render());
			render.setRepeats(false);
			copiedShown = new Timer(COPIED_SHOWN_MS, event -> {
				copied = -1;
				render();
			});
			copiedShown.setRepeats(false);
			theme();
		}

		void append(String text) {
			markdown.append(text);
			render.restart();
		}

		private void render() {
			// Rebuilt from the top every time, so the copy links are renumbered along with
			// the blocks they point at and cannot end up naming code from an earlier draft.
			fenced.clear();
			pane.setText("<html><body>" + MiniMarkdown.toHtml(markdown.toString(), this::codeBlock)
					+ "</body></html>");
			revalidate();
		}

		/**
		 * One fenced block: its language and a copy link on a line above the code.
		 *
		 * <p>A link rather than a button, because a reply is one editor pane and a real
		 * button would have to be positioned over it and moved whenever the text reflows.
		 * The pane already reports clicks on links, already draws a hand cursor over them,
		 * and re-renders without anything to keep in step.
		 */
		private String codeBlock(String code, String language) {
			var index = fenced.size();
			fenced.add(code);
			var muted = String.format("#%06x", muted().getRGB() & 0xFFFFFF);
			// The same shade the code sits on, so the header reads as the top of the block
			// rather than as a line of its own floating above it.
			var shade = String.format("#%06x", tint(0.10f).getRGB() & 0xFFFFFF);
			var header = new StringBuilder("<table width=\"100%\" border=\"0\" cellspacing=\"0\" cellpadding=\"2\">"
					+ "<tr><td bgcolor=\"" + shade + "\">");
			if (language != null) {
				header.append("<font color=\"").append(muted).append("\">")
						.append(MiniMarkdown.escape(language)).append("</font>");
			}
			header.append("</td><td bgcolor=\"").append(shade)
					.append("\" align=\"right\"><a style=\"text-decoration:none\" href=\"")
					.append(COPY_LINK).append(index).append("\">");
			header.append(copied == index ? "copied" : Glyphs.span(Glyphs.COPY));
			header.append("</a></td></tr></table>");
			return header + "<pre>" + highlighter.toHtml(code, language) + "</pre>";
		}

		/** Put a block on the clipboard, and say on the link itself that it went. */
		private void copy(String which) {
			int index;
			try {
				index = Integer.parseInt(which);
			} catch (NumberFormatException e) {
				return;
			}
			if (index < 0 || index >= fenced.size()) {
				// The reply was re-rendered out from under the click; nothing to copy.
				return;
			}
			try {
				var target = clipboard != null ? clipboard : getToolkit().getSystemClipboard();
				target.setContents(new StringSelection(fenced.get(index)), null);
			} catch (IllegalStateException | java.awt.HeadlessException e) {
				// Another application is holding the clipboard, or there is no display to
				// have one. Either way saying "copied" would be a lie.
				return;
			}
			copied = index;
			render();
			copiedShown.restart();
		}

		@Override
		public void theme() {
			// Re-read before the render below: the palette follows the background the code
			// is drawn on, which is the thing a theme change moves.
			highlighter = new CodeHighlighter(background());
			pane.setFont(textFont());
			pane.setForeground(foreground());
			var styles = ((HTMLDocument) pane.getDocument()).getStyleSheet();
			var code = String.format("#%06x", tint(0.10f).getRGB() & 0xFFFFFF);
			var link = String.format("#%06x", accent().getRGB() & 0xFFFFFF);
			var mono = monospace();
			// Sizes stated, because Swing's own style sheet draws code a size smaller than the text around it.
			var size = "font-size: " + mono.getSize() + "pt; ";
			styles.addRule("p { margin-top: 0; margin-bottom: 6px; }");
			styles.addRule("pre { font-family: " + mono.getFamily() + "; " + size + "background-color: " + code
					+ "; padding: 6px; margin-top: 2px; margin-bottom: 6px; }");
			styles.addRule("code { font-family: " + mono.getFamily() + "; " + size + "background-color: " + code + "; }");
			styles.addRule("a { color: " + link + "; }");
			styles.addRule("ul, ol { margin-top: 0; margin-bottom: 6px; margin-left-ltr: 18px; }");
			styles.addRule("blockquote { margin-left: 8px; border-left: 3px solid " + code + "; padding-left: 6px; }");
			styles.addRule("th { background-color: " + code + "; }");
			render();
		}
	}

	/** The agent's reasoning: folded to one line, opened by a click. */
	private final class ThoughtBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final StringBuilder thought = new StringBuilder();
		private final JLabel header = new JLabel();
		private final JTextArea body;
		private boolean open;

		ThoughtBlock(String text) {
			super(new BorderLayout());
			setOpaque(false);
			thought.append(text);
			body = textArea(text, textFont());
			body.setVisible(false);
			body.setBorder(BorderFactory.createEmptyBorder(2, 16, 2, 0));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					open = !open;
					body.setVisible(open);
					updateHeader();
					revalidate();
				}
			});
			add(header, BorderLayout.NORTH);
			add(body, BorderLayout.CENTER);
			theme();
		}

		void append(String text) {
			thought.append(text);
			body.append(text);
			updateHeader();
		}

		private void updateHeader() {
			var preview = thought.toString().strip().replace('\n', ' ');
			if (preview.length() > 90) {
				preview = preview.substring(0, 90) + "...";
			}
			header.setText((open ? "▾ " : "▸ ") + "Thinking" + (open || preview.isEmpty() ? "" : " - " + preview));
		}

		@Override
		public void theme() {
			header.setForeground(muted());
			body.setForeground(muted());
			var font = textFont();
			if (font != null) {
				body.setFont(font.deriveFont(Font.ITALIC));
				header.setFont(font.deriveFont(Font.ITALIC));
			}
			updateHeader();
		}
	}

	/**
	 * One tool call: a header saying what it does and how it went - with the last line
	 * it printed while it runs - and its input and output a click away.
	 */
	private final class ToolBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private AgentEvent.ToolCall call;
		private final JLabel header = new JLabel();
		/** Styled rather than plain, so an edit reads as a diff and its code in the file's colours. */
		private final JTextPane body = new JTextPane();
		private CodeHighlighter highlighter = new CodeHighlighter(background());
		private String output = "";
		private String state = "running";
		private boolean open;

		ToolBlock(AgentEvent.ToolCall call) {
			super(new BorderLayout());
			this.call = call;
			setOpaque(false);
			body.setEditable(false);
			body.setOpaque(true);
			CopyContextMenu.install(body, () -> clipboard);
			body.setVisible(false);
			body.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					open = !open;
					body.setVisible(open && body.getDocument().getLength() > 0);
					updateHeader();
					revalidate();
				}
			});
			var indent = call.parentId() == null ? 0 : NESTED_INDENT;
			setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
			add(header, BorderLayout.NORTH);
			add(body, BorderLayout.CENTER);
			theme();
		}

		/** The agent said again what the call is, now knowing better - an ACP agent names a call before its input. */
		void update(AgentEvent.ToolCall again) {
			call = again;
			showBody();
			updateHeader();
			revalidate();
		}

		/** What the call has printed so far, replacing what was shown. */
		void progress(String so) {
			output = so == null ? "" : so.stripTrailing();
			showBody();
			updateHeader();
			revalidate();
		}

		void finish(boolean error, String result) {
			state = error ? "error" : "done";
			if (result != null && !result.isBlank()) {
				output = result.stripTrailing();
			}
			// A failure is worth seeing without a click.
			open |= error;
			// Redraws the body too, in the colours the new state wants.
			theme();
			revalidate();
		}

		private void showBody() {
			var document = body.getStyledDocument();
			try {
				document.remove(0, document.getLength());
				for (var run : ToolText.runs(call.name(), call.title(), call.detail(), output, highlighter)) {
					document.insertString(document.getLength(), run.getText(), style(run));
				}
			} catch (BadLocationException e) {
				// Offsets taken from the document itself; cannot happen.
			}
			body.setCaretPosition(0);
			body.setVisible(open && document.getLength() > 0);
		}

		/** One run as drawn: the block's font, the token's colour, and the shade of an added or removed line. */
		private SimpleAttributeSet style(ToolText.Run run) {
			var style = new SimpleAttributeSet();
			var font = monospace();
			StyleConstants.setFontFamily(style, font.getFamily());
			StyleConstants.setFontSize(style, font.getSize());
			var error = "error".equals(state);
			Color colour = switch (run.getLine()) {
				case ADDED -> run.isMarker() ? added() : null;
				case REMOVED -> run.isMarker() ? errorColor() : null;
				case HEADER -> muted();
				case PLAIN -> null;
			};
			if (colour == null && run.getColour() != null && !error) {
				colour = Color.decode(run.getColour());
			}
			colour = colour != null ? colour : error ? errorColor() : foreground();
			if (colour != null) {
				StyleConstants.setForeground(style, colour);
			}
			StyleConstants.setBold(style, run.isMarker());
			switch (run.getLine()) {
				case ADDED -> StyleConstants.setBackground(style, blend(tint(0.06f), added(), 0.16f));
				case REMOVED -> StyleConstants.setBackground(style, blend(tint(0.06f), errorColor(), 0.16f));
				default -> {
					// The block's own shade shows through.
				}
			}
			return style;
		}

		private void updateHeader() {
			var glyph = switch (state) {
				case "done" -> "✓";
				case "error" -> "✖";
				default -> "◌";
			};
			var hasBody = body.getDocument().getLength() > 0;
			var last = "running".equals(state) && !open ? lastLine(output) : "";
			header.setText("<html>" + glyph + "&nbsp;&nbsp;<b>" + MiniMarkdown.escape(call.name()) + "</b>"
					+ (call.title().isEmpty() ? "" : "&nbsp;&nbsp;" + monospaceSpan(call.title()))
					+ (hasBody ? (open ? "&nbsp;&nbsp;▾" : "&nbsp;&nbsp;▸") : "")
					+ (last.isEmpty() ? "" : "&nbsp;&nbsp;<i>" + MiniMarkdown.escape(last) + "</i>") + "</html>");
		}

		private static String lastLine(String text) {
			var lines = text.strip().lines().toList();
			var line = lines.isEmpty() ? "" : lines.getLast().strip();
			return line.length() > 100 ? line.substring(0, 100) + "..." : line;
		}

		@Override
		public void theme() {
			header.setForeground("error".equals(state) ? errorColor() : muted());
			highlighter = new CodeHighlighter(background());
			body.setBackground(tint(0.06f));
			body.setForeground("error".equals(state) ? errorColor() : foreground());
			body.setFont(monospace());
			// The runs carry their colours and font, so a theme or zoom change redraws them.
			showBody();
			updateHeader();
		}
	}

	/** A request for permission, with the buttons that answer it until it is answered. */
	private final class PermissionBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final AgentEvent.PermissionRequest request;
		private final JLabel title = new JLabel();
		private final JTextArea detail;
		private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		private final JLabel outcome = new JLabel();

		PermissionBlock(AgentEvent.PermissionRequest request, boolean live) {
			super(new BorderLayout(0, 4));
			this.request = request;
			title.setText("<html><b>" + MiniMarkdown.escape("Allow " + request.toolName() + "?") + "</b>"
					+ (request.title().isEmpty() ? "" : "&nbsp;&nbsp;" + monospaceSpan(request.title()))
					+ "</html>");
			Glyphs.decorate(title, Glyphs.PERMISSION, title.getText());
			detail = textArea(request.detail(), monospace());
			detail.setVisible(!request.detail().isEmpty());

			actions.setOpaque(false);
			for (var option : request.choices()) {
				var button = new JButton(option.label());
				button.addActionListener(event -> permissionAnswer.accept(request.requestId(), option));
				if (option.kind() == AgentEvent.PermissionOption.Kind.ALLOW_ONCE && actions.getComponentCount() == 0) {
					// The first plain allow is what Enter presses, as in a dialog.
					button.putClientProperty("JButton.buttonType", "default");
				}
				actions.add(button);
				actions.add(Box.createHorizontalStrut(6));
			}
			actions.add(Box.createHorizontalStrut(4));
			actions.add(outcome);

			add(title, BorderLayout.NORTH);
			add(detail, BorderLayout.CENTER);
			add(actions, BorderLayout.SOUTH);
			theme();
			if (!live) {
				expire();
			}
		}

		void resolve(AgentEvent.PermissionResolved resolved) {
			settle(resolved.label() != null ? "Answered: " + resolved.label() : resolved.allowed() ? "Allowed" : "Denied");
		}

		void expire() {
			if (actions.getComponentCount() > 1) {
				settle("Not answered - the session ended");
			}
		}

		private void settle(String text) {
			actions.removeAll();
			outcome.setText(text);
			actions.add(outcome);
			actions.revalidate();
			actions.repaint();
		}

		@Override
		public void theme() {
			setBackground(tint(0.06f));
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(0xFF, 0xB3, 0x00)),
					BorderFactory.createEmptyBorder(6, 8, 6, 8)));
			title.setForeground(foreground());
			detail.setForeground(foreground());
			detail.setFont(monospace());
			outcome.setForeground(muted());
		}

		@Override
		public Dimension getMaximumSize() {
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}
	}

	/**
	 * A conversation as plain text, for the clipboard and for a transcript file.
	 *
	 * @param events the conversation
	 * @return Markdown-flavoured text
	 */
	static String plainText(List<AgentEvent> events) {
		var text = new StringBuilder();
		Class<?> previous = null;
		var tools = new java.util.HashSet<String>();
		for (var event : events) {
			// Progress is not history, and a call named again is the same call.
			if (event instanceof AgentEvent.ToolOutput || event instanceof AgentEvent.Suggestion
					|| event instanceof AgentEvent.ToolCall call && call.id() != null && !tools.add(call.id())) {
				continue;
			}
			var continues = previous == event.getClass()
					&& (event instanceof AgentEvent.MessageChunk || event instanceof AgentEvent.ThoughtChunk);
			if (!continues && !text.isEmpty()) {
				text.append("\n\n");
			}
			switch (event) {
				case AgentEvent.UserMessage(var message, var attachments) -> {
					var lines = new ArrayList<String>();
					if (!message.isBlank() || attachments.isEmpty()) {
						lines.add(message);
					}
					attachments.forEach(attachment -> lines.add(Attachments.plainLine(attachment)));
					text.append("> ").append(String.join("\n", lines).replace("\n", "\n> "));
				}
				case AgentEvent.MessageChunk(var chunk) -> text.append(chunk);
				case AgentEvent.ThoughtChunk(var chunk) -> text.append(continues ? "" : "(thinking) ").append(chunk);
				case AgentEvent.ToolCall call -> text.append("[").append(call.name()).append("] ").append(call.title());
				case AgentEvent.ToolResult result -> text.append(result.error() ? "[failed] " : "[result] ")
						.append(ClaudeStreamTranslator.limit(result.output().strip(), 2_000));
				case AgentEvent.PermissionRequest request -> text.append("[permission] ").append(request.toolName())
						.append(' ').append(request.title());
				case AgentEvent.PermissionResolved resolved -> text.append(resolved.allowed() ? "[allowed" : "[denied")
						.append(resolved.label() == null ? "" : ": " + resolved.label()).append(']');
				case AgentEvent.ToolOutput progress -> {
					// skipped above
				}
				case AgentEvent.Suggestion suggestion -> {
					// skipped above
				}
				case AgentEvent.TurnEnded turn -> text.append(turn.error() ? "--- turn failed: " + turn.message() : "---");
				case AgentEvent.SessionStarted started -> text.append("[nuclr] ").append(sessionLine(started));
				case AgentEvent.CommandsAvailable ignored -> {
					// Not part of the conversation.
				}
				case AgentEvent.Notice notice -> text.append("[nuclr] ").append(notice.text());
				case AgentEvent.Image picture -> text.append("[image] ")
						.append(picture.name() == null ? picture.path() : picture.name());
			}
			previous = event.getClass();
		}
		return text.toString();
	}
}
