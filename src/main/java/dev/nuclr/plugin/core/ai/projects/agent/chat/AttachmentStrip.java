package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
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
 * What the next message will carry besides its words: a chip for each picture, each
 * long paste and each attached file, above the box it is typed in.
 *
 * <p>A picture's chip shows it small; a paste's shows how long it is and, on hover, how
 * it begins; a file's shows its type and size. Pastes and files show, once the host has
 * drawn one, a thumbnail of what they hold. Each has a button that takes it off again.
 * Clicking a chip opens its file; right-clicking offers the same, removal, and - for a
 * paste - putting it back into the box as text, for the paste that was attached but was
 * meant to be edited, or - for a file - putting its path there instead.
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
	/** Pastes whose thumbnail has been asked for, answered or not, so each is asked for once. */
	private final Set<String> requested = new HashSet<>();
	/** Requests still out, by path, so a chip taken off stops its picture being drawn. */
	private final Map<String, AtomicBoolean> pending = new HashMap<>();
	/** The picture label of each chip on show, by path, so an answer lands without a rebuild. */
	private final Map<String, JLabel> pictures = new HashMap<>();
	private final Thumbnails source;
	private final Consumer<Attachment> insertAsText;
	private final Consumer<String> say;
	private Runnable changed = () -> {
	};

	/**
	 * Where a paste's thumbnail comes from: the host, drawing it with whichever Quick View
	 * plugin would preview the file. See {@code AgentWindowHost#thumbnail}.
	 */
	@FunctionalInterface
	interface Thumbnails {

		/**
		 * @param file      the file to draw
		 * @param maxWidth  the widest the picture may be
		 * @param maxHeight the tallest it may be
		 * @param cancelled set when the picture is no longer wanted
		 * @param answer    given the picture or {@code null}, at most once, on the EDT
		 */
		void request(Path file, int maxWidth, int maxHeight, AtomicBoolean cancelled, Consumer<BufferedImage> answer);
	}

	/**
	 * @param source       draws a paste's thumbnail; one that never answers leaves the text glyph
	 * @param insertAsText puts a paste back into the box as text, or a file's path; the chip is removed by the strip
	 * @param say          reports something about an attachment, such as a file that would not open
	 */
	AttachmentStrip(Thumbnails source, Consumer<Attachment> insertAsText, Consumer<String> say) {
		super(new WrapLayout(6, 4));
		this.source = source;
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
		var present = attachments.stream().map(Attachment::path).toList();
		thumbnails.keySet().retainAll(present);
		requested.retainAll(present);
		pending.entrySet().removeIf(request -> {
			if (present.contains(request.getKey())) {
				return false;
			}
			request.getValue().set(true);
			return true;
		});
		pictures.clear();
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
		} else if (attachment.kind() == Kind.FILE) {
			showThumbnail(attachment, picture, Glyphs.FILE);
			detail.setText(Attachments.fileDetail(file));
			chip.setToolTipText("<html><b>" + MiniMarkdown.escape(Attachments.displayName(attachment))
					+ "</b> - the agent is given its path and reads it itself<br>" + MiniMarkdown.escape(attachment.path())
					+ "<br>Click to open it; right-click to put its path in the box instead</html>");
		} else {
			showThumbnail(attachment, picture, Glyphs.TEXT);
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

	/** A drawn thumbnail when there is one, the glyph meanwhile - and the thumbnail asked for. */
	private void showThumbnail(Attachment attachment, JLabel picture, String glyph) {
		var thumbnail = thumbnails.get(attachment.path());
		if (thumbnail != null) {
			picture.setIcon(thumbnail);
		} else {
			Glyphs.decorate(picture, glyph, null);
			requestThumbnail(attachment);
		}
		pictures.put(attachment.path(), picture);
	}

	/**
	 * Ask the host for a paste's or a file's thumbnail, once. The chip shows the text glyph meanwhile,
	 * and keeps it when the answer is {@code null} or never comes.
	 */
	private void requestThumbnail(Attachment attachment) {
		var path = attachment.path();
		if (!requested.add(path)) {
			return;
		}
		var cancelled = new AtomicBoolean();
		pending.put(path, cancelled);
		source.request(attachment.file(), THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, cancelled, image -> {
			// Only this request's entry: the paste may have been taken off and added again since.
			pending.remove(path, cancelled);
			if (image == null || cancelled.get() || !requested.contains(path)) {
				return;
			}
			var icon = new ImageIcon(image);
			thumbnails.put(path, icon);
			var picture = pictures.get(path);
			if (picture != null) {
				picture.setIcon(icon);
				picture.setDisabledIcon(null);
				revalidate();
				repaint();
			}
		});
	}

	private JPopupMenu menu(Attachment attachment) {
		var menu = new JPopupMenu();
		menu.add(item("Open", Glyphs.LINK, () -> open(attachment)));
		if (attachment.kind() != Kind.IMAGE) {
			var label = attachment.kind() == Kind.TEXT ? "Put back in the box as text" : "Put its path in the box instead";
			menu.add(item(label, Glyphs.EDIT, () -> {
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
