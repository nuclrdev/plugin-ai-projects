package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;

import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * Find in the conversation: a line above the page with a field, the count of what it
 * found, and the arrows that step through it.
 *
 * <p>Every piece of text the page holds is searched, whether it is showing or not - a
 * folded thought, a tool call's output - because what the user remembers reading is
 * as likely to be in there as in a reply. Stepping onto a match in something folded
 * asks the page to open it. Every match is shaded, and the one stepped to more strongly.
 *
 * <p>The page keeps changing under the search while an agent writes: a reply is drawn
 * again with every chunk, which moves or wipes the shading. The page says when it has
 * changed, and the search runs again a moment later, keeping its place and leaving the
 * scroll where it is.
 *
 * <p>Event dispatch thread only.
 */
final class FindBar extends JPanel {

	private static final long serialVersionUID = 1L;

	/** How long the search waits after the page changed before running again; longer than a reply takes to redraw. */
	private static final int REFRESH_DELAY_MS = 200;

	/** What the bar needs from the page it searches. */
	interface Page {

		/** Everything that is searched: text components anywhere under it, in the order they appear. */
		Container content();

		/** Open whatever holds a match that is folded away, so it can be shown. */
		void reveal(JTextComponent where);

		/** Bring part of a text component into view. */
		void show(JTextComponent where, Rectangle area);

		/** The top of what is in view, in the content's coordinates. */
		int viewTop();
	}

	/** One match: where it is, and which characters. */
	record Match(JTextComponent area, int start, int end) {
	}

	private final Page page;
	private final JLabel glyph = new JLabel();
	private final JTextField field = new JTextField(22);
	private final JToggleButton matchCase = new JToggleButton("Aa");
	private final JLabel count = new JLabel();
	private final JButton previous = Glyphs.decorate(new JButton(), Glyphs.UP, null);
	private final JButton next = Glyphs.decorate(new JButton(), Glyphs.DOWN, null);
	private final JButton close = Glyphs.decorate(new JButton(), Glyphs.CLOSE, null);
	private final List<Match> matches = new ArrayList<>();
	/** The shading drawn for the matches, by the component it is drawn in, so it can be taken away again. */
	private final Map<JTextComponent, List<Object>> shading = new IdentityHashMap<>();
	private final Timer refresh = new Timer(REFRESH_DELAY_MS, event -> search(false));
	private int current = -1;
	/** Where focus was before the bar took it, to go back to when it closes. */
	private Component returnFocus;
	private DefaultHighlighter.DefaultHighlightPainter all;
	private DefaultHighlighter.DefaultHighlightPainter chosen;
	/** The locale the count is written in; Commander's, once the window has said what it is. */
	private Supplier<Locale> locale = Locale::getDefault;

	FindBar(Page page) {
		super(new BorderLayout(6, 0));
		this.page = page;
		refresh.setRepeats(false);

		glyph.setIcon(Glyphs.icon(Glyphs.ZOOM));
		field.putClientProperty("JTextField.placeholderText", "Find in the conversation");
		TextContextMenu.install(field);
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				typed();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				typed();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				typed();
			}
		});
		key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nuclr-find-next", this::next);
		key(field, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nuclr-find-next", this::next);
		key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "nuclr-find-previous",
				this::previous);
		key(field, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "nuclr-find-previous", this::previous);
		key(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nuclr-find-close", this::close);

		matchCase.setToolTipText("Match case");
		matchCase.addActionListener(event -> typed());
		previous.setToolTipText("Previous match (Shift+Enter, Shift+F3)");
		previous.addActionListener(event -> previous());
		next.setToolTipText("Next match (Enter, F3)");
		next.addActionListener(event -> next());
		close.setToolTipText("Close (Escape)");
		close.addActionListener(event -> close());
		for (var button : new javax.swing.AbstractButton[] { matchCase, previous, next, close }) {
			button.putClientProperty("JButton.buttonType", "toolBarButton");
			button.setFocusable(false);
		}

		var left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);
		left.add(glyph, BorderLayout.WEST);
		left.add(field, BorderLayout.CENTER);
		var right = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
		right.setOpaque(false);
		right.add(matchCase);
		right.add(count);
		right.add(previous);
		right.add(next);
		right.add(close);
		add(left, BorderLayout.CENTER);
		add(right, BorderLayout.EAST);
		setVisible(false);
		theme(null);
	}

	/**
	 * Show the bar and put the cursor in its field, with the text selected so typing replaces it.
	 *
	 * @param seed what to look for instead of what was looked for last, or {@code null} to keep that
	 */
	void open(String seed) {
		if (!isVisible()) {
			var focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
			returnFocus = focused != null && !SwingUtilities.isDescendingFrom(focused, this) ? focused : null;
			setVisible(true);
			revalidateParent();
		}
		if (seed != null && !seed.isEmpty() && !seed.equals(field.getText())) {
			// Replacing the text runs the search, which goes to the first match from the top of the page.
			field.setText(seed);
		} else if (matches.isEmpty() && !field.getText().isEmpty()) {
			search(true);
		} else {
			paint();
		}
		field.selectAll();
		field.requestFocusInWindow();
	}

	/** Hide the bar and take the shading away, and put focus back where it was. */
	void close() {
		if (!isVisible()) {
			return;
		}
		refresh.stop();
		unpaint();
		matches.clear();
		current = -1;
		setVisible(false);
		revalidateParent();
		if (returnFocus != null && returnFocus.isShowing()) {
			returnFocus.requestFocusInWindow();
		}
		returnFocus = null;
	}

	/** Step to the next match, round to the first after the last. */
	void next() {
		step(1);
	}

	/** Step to the previous match, round to the last before the first. */
	void previous() {
		step(-1);
	}

	/**
	 * The page has changed: run the search again once it has settled. Nothing happens
	 * while the bar is closed.
	 */
	void contentChanged() {
		if (isVisible() && !field.getText().isEmpty()) {
			refresh.restart();
		}
	}

	/** Run a pending search now rather than when it is due, for tests. */
	void flush() {
		if (refresh.isRunning()) {
			refresh.stop();
			search(false);
		}
	}

	/** Every match, in the order of the page, for tests. */
	List<Match> matches() {
		return List.copyOf(matches);
	}

	/** Which match is the one stepped to, from 0, or -1 for none; for tests. */
	int current() {
		return current;
	}

	/** What the count says, for tests. */
	String countText() {
		return count.getText();
	}

	/**
	 * The locale the count is written in.
	 *
	 * @param source asked each time the count is shown, since the user can change it
	 */
	void setNumberLocale(Supplier<Locale> source) {
		locale = source;
		updateCount();
	}

	/** The field that is typed into, for tests and for the window's shortcut. */
	JTextField field() {
		return field;
	}

	/** The button that makes the search care about case, for tests. */
	JToggleButton matchCaseButton() {
		return matchCase;
	}

	/**
	 * Re-read colours from the look and feel.
	 *
	 * @param font the font the bar's labels are drawn in, or {@code null} for the look and feel's
	 */
	void theme(Font font) {
		var background = UIManager.getColor("TextArea.background");
		var foreground = UIManager.getColor("TextArea.foreground");
		var shade = tint(background, foreground, 0.06f);
		setOpaque(true);
		setBackground(shade);
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, tint(background, foreground, 0.18f)),
				BorderFactory.createEmptyBorder(4, 10, 4, 6)));
		if (font != null) {
			count.setFont(font);
		}
		var dark = background != null && luminance(background) < 128;
		var amber = new Color(0xFF, 0xC1, 0x07);
		var orange = new Color(0xFF, 0x8F, 0x00);
		var base = background != null ? background : Color.WHITE;
		all = new DefaultHighlighter.DefaultHighlightPainter(blend(base, amber, dark ? 0.35f : 0.45f));
		chosen = new DefaultHighlighter.DefaultHighlightPainter(blend(base, orange, dark ? 0.70f : 0.75f));
		updateCount();
		if (isVisible()) {
			paint();
		}
	}

	// ------------------------------------------------------------------ searching

	/** The query changed: search again, going to the first match from where the page is. */
	private void typed() {
		refresh.stop();
		search(true);
	}

	/**
	 * Find every match and shade them.
	 *
	 * @param jump whether to go to the nearest match from the top of the page, as typing
	 *             does, rather than keep the one stepped to and leave the page alone
	 */
	private void search(boolean jump) {
		var kept = current >= 0 && current < matches.size() ? matches.get(current) : null;
		unpaint();
		matches.clear();
		current = -1;
		var query = field.getText();
		if (!query.isEmpty()) {
			collect(page.content(), query, matchCase.isSelected());
		}
		if (!matches.isEmpty()) {
			current = jump ? fromTop() : keep(kept);
		}
		paint();
		updateCount();
		if (jump && current >= 0) {
			bringIntoView(matches.get(current));
		}
	}

	private void collect(Container container, String query, boolean caseSensitive) {
		for (var child : container.getComponents()) {
			if (child instanceof JTextComponent area) {
				find(area, query, caseSensitive);
			} else if (child instanceof Container nested) {
				collect(nested, query, caseSensitive);
			}
		}
	}

	private void find(JTextComponent area, String query, boolean caseSensitive) {
		String text;
		try {
			var document = area.getDocument();
			text = document.getText(0, document.getLength());
		} catch (BadLocationException e) {
			return;
		}
		var length = query.length();
		for (var at = 0; at + length <= text.length(); at++) {
			if (text.regionMatches(!caseSensitive, at, query, 0, length)) {
				matches.add(new Match(area, at, at + length));
				at += length - 1;
			}
		}
	}

	/** The first match at or below the top of the page, or the last one when all are above it. */
	private int fromTop() {
		var top = page.viewTop();
		for (var i = 0; i < matches.size(); i++) {
			if (y(matches.get(i)) >= top) {
				return i;
			}
		}
		return matches.size() - 1;
	}

	/** The match where the one stepped to was before the page changed, or the next one after it. */
	private int keep(Match kept) {
		if (kept == null) {
			return 0;
		}
		var seen = false;
		for (var i = 0; i < matches.size(); i++) {
			var match = matches.get(i);
			if (match.area() == kept.area()) {
				seen = true;
				if (match.start() >= kept.start()) {
					return i;
				}
			} else if (seen) {
				return i;
			}
		}
		// Gone from the page, or it was the last: stay near the end rather than go back to the start.
		return seen ? matches.size() - 1 : 0;
	}

	/** How far down the page a match is, in the content's coordinates, as near as what is showing tells. */
	private int y(Match match) {
		Component shown = match.area();
		var content = page.content();
		while (shown != null && shown != content && !shown.isVisible()) {
			shown = shown.getParent();
		}
		if (shown == null || !SwingUtilities.isDescendingFrom(shown, content)) {
			return 0;
		}
		var y = SwingUtilities.convertPoint(shown, 0, 0, content).y;
		if (shown == match.area()) {
			var at = rectangle(match);
			if (at != null) {
				y += at.y;
			}
		}
		return y;
	}

	private void step(int direction) {
		if (!isVisible()) {
			return;
		}
		refresh.stop();
		if (matches.isEmpty()) {
			search(true);
			return;
		}
		current = Math.floorMod(current + direction, matches.size());
		paint();
		updateCount();
		bringIntoView(matches.get(current));
	}

	private void bringIntoView(Match match) {
		page.reveal(match.area());
		var at = rectangle(match);
		if (at != null) {
			page.show(match.area(), at);
		}
	}

	/** Where a match is drawn in its component, or {@code null} while it has not been laid out. */
	private static Rectangle rectangle(Match match) {
		try {
			var from = match.area().modelToView2D(match.start());
			var to = match.area().modelToView2D(match.end());
			if (from == null || to == null) {
				return null;
			}
			return from.getBounds().union(to.getBounds());
		} catch (BadLocationException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ drawing

	private void paint() {
		unpaint();
		for (var i = 0; i < matches.size(); i++) {
			var match = matches.get(i);
			try {
				var tag = match.area().getHighlighter().addHighlight(match.start(), match.end(),
						i == current ? chosen : all);
				shading.computeIfAbsent(match.area(), area -> new ArrayList<>()).add(tag);
			} catch (BadLocationException e) {
				// The text changed since the search; the next refresh finds it again.
			}
		}
	}

	private void unpaint() {
		shading.forEach((area, tags) -> tags.forEach(area.getHighlighter()::removeHighlight));
		shading.clear();
	}

	private void updateCount() {
		if (field.getText().isEmpty()) {
			count.setText("");
		} else if (matches.isEmpty()) {
			count.setText("No results");
		} else {
			var in = locale.get();
			var numbers = java.text.NumberFormat.getIntegerInstance(in != null ? in : Locale.getDefault());
			count.setText(numbers.format(current + 1) + " of " + numbers.format(matches.size()));
		}
		var muted = UIManager.getColor("Label.disabledForeground");
		count.setForeground(matches.isEmpty() && !field.getText().isEmpty() ? errorColor()
				: muted != null ? muted : Color.GRAY);
		field.putClientProperty("JComponent.outline",
				matches.isEmpty() && !field.getText().isEmpty() ? "error" : null);
	}

	private void revalidateParent() {
		var parent = getParent();
		if (parent instanceof JComponent component) {
			component.revalidate();
			component.repaint();
		}
	}

	private static void key(JComponent component, KeyStroke stroke, String name, Runnable action) {
		component.getInputMap(JComponent.WHEN_FOCUSED).put(stroke, name);
		component.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				action.run();
			}
		});
	}

	private static int luminance(Color color) {
		return (color.getRed() * 299 + color.getGreen() * 587 + color.getBlue() * 114) / 1000;
	}

	private static Color errorColor() {
		var background = UIManager.getColor("TextArea.background");
		return background != null && luminance(background) < 128 ? new Color(0xFF, 0x6B, 0x68)
				: new Color(0xC6, 0x28, 0x28);
	}

	private static Color tint(Color from, Color to, float amount) {
		if (from == null || to == null) {
			return Color.LIGHT_GRAY;
		}
		return blend(from, to, amount);
	}

	private static Color blend(Color from, Color to, float amount) {
		return new Color(
				Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}
}
