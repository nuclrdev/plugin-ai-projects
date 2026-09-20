package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

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
	/** Points added to every font, as the user has zoomed; see {@link #zoom(int)}. */
	private int fontScale;
	/** Where copied code goes; {@code null} means the display's own, which is the only case that ships. */
	private Clipboard clipboard;

	/**
	 * @param permissionAnswer called with a request id and the option the user chose
	 */
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
		add(scroll, BorderLayout.CENTER);
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
			case AgentEvent.UserMessage(var text) -> add(new UserBlock(text));
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
			case AgentEvent.SessionStarted started -> add(note(sessionLine(started), false));
			case AgentEvent.Notice notice -> add(note(notice.text(), notice.error()));
		}
		column.revalidate();
		column.repaint();
		if (follow) {
			scrollToEnd();
		}
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
		column.revalidate();
		column.repaint();
	}

	/** Re-read colours and fonts from the look and feel. */
	void updateTheme() {
		var background = UIManager.getColor("TextArea.background");
		column.setBackground(background);
		column.getParent().setBackground(background);
		scroll.getViewport().setBackground(background);
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

	private boolean atBottom() {
		var bar = scroll.getVerticalScrollBar();
		return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 24;
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
		var label = note(parts.toString(), turn.error());
		label.setHorizontalAlignment(SwingConstants.TRAILING);
		return label;
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

	private static JTextArea textArea(String text, Font font) {
		var area = new JTextArea(text);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setFont(font);
		area.setBorder(BorderFactory.createEmptyBorder());
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

	/** What the user sent, on a shaded panel with an accent bar. */
	private final class UserBlock extends JPanel implements Themed {

		private static final long serialVersionUID = 1L;
		private final JTextArea text;

		UserBlock(String message) {
			super(new BorderLayout());
			text = textArea(message, textFont());
			add(text, BorderLayout.CENTER);
			theme();
		}

		@Override
		public void theme() {
			setBackground(tint(0.08f));
			setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, accent()),
					BorderFactory.createEmptyBorder(6, 8, 6, 8)));
			text.setForeground(foreground());
			text.setFont(textFont());
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
			header.append("</td><td bgcolor=\"").append(shade).append("\" align=\"right\"><a href=\"")
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
		private final JTextArea body;
		private String output = "";
		private String state = "running";
		private boolean open;

		ToolBlock(AgentEvent.ToolCall call) {
			super(new BorderLayout());
			this.call = call;
			setOpaque(false);
			body = textArea(call.detail(), monospace());
			body.setLineWrap(true);
			body.setWrapStyleWord(false);
			body.setOpaque(true);
			body.setVisible(false);
			body.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			header.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent event) {
					open = !open;
					body.setVisible(open && !body.getText().isEmpty());
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
			showBody();
			// A failure is worth seeing without a click.
			if (error) {
				open = true;
				body.setVisible(!body.getText().isEmpty());
			}
			theme();
			revalidate();
		}

		private void showBody() {
			var detail = call.detail() == null ? "" : call.detail();
			body.setText(detail.isEmpty() ? output : output.isEmpty() ? detail : detail + "\n\n" + output);
			body.setVisible(open && !body.getText().isEmpty());
		}

		private void updateHeader() {
			var glyph = switch (state) {
				case "done" -> "✓";
				case "error" -> "✖";
				default -> "◌";
			};
			var hasBody = !body.getText().isEmpty();
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
			body.setBackground(tint(0.06f));
			body.setForeground("error".equals(state) ? errorColor() : foreground());
			body.setFont(monospace());
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
			if (event instanceof AgentEvent.ToolOutput
					|| event instanceof AgentEvent.ToolCall call && call.id() != null && !tools.add(call.id())) {
				continue;
			}
			var continues = previous == event.getClass()
					&& (event instanceof AgentEvent.MessageChunk || event instanceof AgentEvent.ThoughtChunk);
			if (!continues && !text.isEmpty()) {
				text.append("\n\n");
			}
			switch (event) {
				case AgentEvent.UserMessage(var message) -> text.append("> ").append(message.replace("\n", "\n> "));
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
				case AgentEvent.TurnEnded turn -> text.append(turn.error() ? "--- turn failed: " + turn.message() : "---");
				case AgentEvent.SessionStarted started -> text.append("[nuclr] ").append(sessionLine(started));
				case AgentEvent.Notice notice -> text.append("[nuclr] ").append(notice.text());
			}
			previous = event.getClass();
		}
		return text.toString();
	}
}
