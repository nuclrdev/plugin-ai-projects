package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment.Kind;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.screen.WrapLayout;

/**
 * What the next message will carry besides its words: a chip for each picture and each
 * long paste, above the box it is typed in.
 *
 * <p>A picture's chip shows it small; a paste's shows how long it is and, on hover, how
 * it begins. Each has a button that takes it off again. Clicking a chip opens its file;
 * right-clicking offers the same, removal, and - for a paste - putting it back into the
 * box as text, for the paste that was attached but was meant to be edited.
 *
 * <p>The strip is hidden while it holds nothing. Event dispatch thread only.
 */
final class AttachmentStrip extends JPanel {

	private static final long serialVersionUID = 1L;

	/** How tall a picture's thumbnail is drawn in a chip. */
	private static final int THUMBNAIL_HEIGHT = 36;

	/** How wide it may be, so a panorama does not take the whole strip. */
	private static final int THUMBNAIL_WIDTH = 72;

	private final List<Attachment> attachments = new ArrayList<>();
	/** Thumbnails already drawn, by path, so a strip rebuilt after each change does not read every picture again. */
	private final Map<String, Icon> thumbnails = new HashMap<>();
	private final Consumer<Attachment> insertAsText;
	private final Consumer<String> say;
	private Runnable changed = () -> {
	};

	/**
	 * @param insertAsText puts a paste back into the box as text; the chip is removed by the strip
	 * @param say          reports something about an attachment, such as a file that would not open
	 */
	AttachmentStrip(Consumer<Attachment> insertAsText, Consumer<String> say) {
		super(new WrapLayout(6, 4));
		this.insertAsText = insertAsText;
		this.say = say;
		setOpaque(false);
		setVisible(false);
	}

	/**
	 * Be told whenever the attachments change.
	 *
	 * @param listener called after each change
	 */
	void onChange(Runnable listener) {
		changed = listener;
	}

	/** What is attached, in the order it was added. */
	List<Attachment> attachments() {
		return List.copyOf(attachments);
	}

	boolean isEmpty() {
		return attachments.isEmpty();
	}

	int count() {
		return attachments.size();
	}

	/**
	 * Attach one more.
	 *
	 * @param attachment what to attach
	 * @return whether it was added; {@code false} when the same file is attached already
	 */
	boolean add(Attachment attachment) {
		for (var present : attachments) {
			if (present.path().equals(attachment.path())) {
				return false;
			}
		}
		attachments.add(attachment);
		rebuild();
		return true;
	}

	/**
	 * Take one off.
	 *
	 * @param attachment what to remove
	 */
	void remove(Attachment attachment) {
		if (attachments.remove(attachment)) {
			rebuild();
		}
	}

	/** Take off the one added last, if there is one. */
	void removeLast() {
		if (!attachments.isEmpty()) {
			attachments.removeLast();
			rebuild();
		}
	}

	/**
	 * Replace everything attached.
	 *
	 * @param replacement what to attach instead
	 */
	void set(List<Attachment> replacement) {
		if (attachments.equals(replacement)) {
			return;
		}
		attachments.clear();
		attachments.addAll(replacement);
		rebuild();
	}

	/** Take everything off. */
	void clear() {
		set(List.of());
	}

	private void rebuild() {
		removeAll();
		thumbnails.keySet().retainAll(attachments.stream().map(Attachment::path).toList());
		for (var attachment : attachments) {
			add(chip(attachment));
		}
		setVisible(!attachments.isEmpty());
		revalidate();
		repaint();
		changed.run();
	}

	/** One attachment's chip: what it is, and the button that takes it off. */
	private JComponent chip(Attachment attachment) {
		var chip = new JPanel(new BorderLayout(6, 0));
		chip.setOpaque(true);
		chip.setBackground(tint());
		chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(outline(), 1, true),
				BorderFactory.createEmptyBorder(3, 4, 3, 2)));

		var picture = new JLabel();
		var name = new JLabel(Attachments.displayName(attachment));
		var detail = new JLabel();
		detail.setForeground(muted());
		var small = UIManager.getFont("Label.font");
		if (small != null) {
			detail.setFont(small.deriveFont(small.getSize2D() - 1f));
		}
		var file = attachment.file();
		if (attachment.kind() == Kind.IMAGE) {
			var thumbnail = thumbnails.computeIfAbsent(attachment.path(),
					path -> Attachments.thumbnail(file, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT));
			if (thumbnail != null) {
				picture.setIcon(thumbnail);
				detail.setText(thumbnail instanceof javax.swing.ImageIcon image && image.getDescription() != null
						? image.getDescription() : "image");
			} else {
				Glyphs.decorate(picture, Glyphs.IMAGE, null);
				detail.setText("image");
			}
			chip.setToolTipText(Attachments.displayName(attachment) + " - click to open it");
		} else {
			Glyphs.decorate(picture, Glyphs.TEXT, null);
			detail.setText(Attachments.textDetail(file));
			chip.setToolTipText("<html><b>" + MiniMarkdown.escape(Attachments.displayName(attachment))
					+ "</b> - sent as text before your message; click to open it, right-click to put it back in the box"
					+ "<pre>" + MiniMarkdown.escape(Attachments.preview(file, 12)) + "</pre></html>");
		}

		var words = new JPanel();
		words.setOpaque(false);
		words.setLayout(new BoxLayout(words, BoxLayout.PAGE_AXIS));
		words.add(Box.createVerticalGlue());
		words.add(name);
		words.add(detail);
		words.add(Box.createVerticalGlue());

		var remove = Glyphs.decorate(new JButton(), Glyphs.CLOSE, null);
		remove.putClientProperty("JButton.buttonType", "toolBarButton");
		remove.setFocusable(false);
		remove.setToolTipText("Remove");
		remove.addActionListener(event -> remove(attachment));
		var removeHolder = new JPanel(new BorderLayout());
		removeHolder.setOpaque(false);
		removeHolder.add(remove, BorderLayout.NORTH);

		chip.add(picture, BorderLayout.WEST);
		chip.add(words, BorderLayout.CENTER);
		chip.add(removeHolder, BorderLayout.EAST);
		chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		var menu = menu(attachment);
		chip.setComponentPopupMenu(menu);
		for (var child : new JComponent[] { picture, words, name, detail, removeHolder }) {
			child.setInheritsPopupMenu(true);
		}
		// On the chip alone: the labels inside have no listeners, so their clicks reach it, and the
		// chip's tooltip stays up while the mouse crosses them.
		chip.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (SwingUtilities.isLeftMouseButton(event) && !event.isPopupTrigger()) {
					open(attachment);
				}
			}
		});
		return chip;
	}

	private JPopupMenu menu(Attachment attachment) {
		var menu = new JPopupMenu();
		menu.add(item("Open", Glyphs.LINK, () -> open(attachment)));
		if (attachment.kind() == Kind.TEXT) {
			menu.add(item("Put back in the box as text", Glyphs.EDIT, () -> {
				remove(attachment);
				insertAsText.accept(attachment);
			}));
		}
		menu.addSeparator();
		menu.add(item("Remove", Glyphs.CLOSE, () -> remove(attachment)));
		return menu;
	}

	private static JMenuItem item(String label, String glyph, Runnable action) {
		var entry = Glyphs.decorate(new JMenuItem(), glyph, label);
		entry.addActionListener(event -> action.run());
		return entry;
	}

	private void open(Attachment attachment) {
		var problem = Attachments.open(attachment.file());
		if (problem != null) {
			say.accept(Attachments.displayName(attachment) + ": " + problem);
		}
	}

	@Override
	public void updateUI() {
		super.updateUI();
		// Chips are coloured from the theme when drawn; a new theme draws them again.
		if (attachments != null && !attachments.isEmpty()) {
			rebuild();
		}
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	private static Color tint() {
		var background = UIManager.getColor("TextArea.background");
		var foreground = UIManager.getColor("TextArea.foreground");
		if (background == null || foreground == null) {
			return Color.LIGHT_GRAY;
		}
		return blend(background, foreground, 0.08f);
	}

	private static Color outline() {
		var background = UIManager.getColor("TextArea.background");
		var foreground = UIManager.getColor("TextArea.foreground");
		if (background == null || foreground == null) {
			return Color.GRAY;
		}
		return blend(background, foreground, 0.25f);
	}

	private static Color muted() {
		var color = UIManager.getColor("Label.disabledForeground");
		return color != null ? color : Color.GRAY;
	}

	private static Color blend(Color from, Color to, float amount) {
		return new Color(Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}
}
