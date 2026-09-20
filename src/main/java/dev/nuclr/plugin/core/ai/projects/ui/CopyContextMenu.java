package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

/**
 * The right-click menu text you can only read gets: Copy, Copy All and Select
 * All, and - where the text on screen was rendered from Markdown - Copy as
 * Markdown, which copies the source rather than what the renderer made of it.
 *
 * <p>{@link TextContextMenu} is the menu for a field being edited, with undo,
 * cut and paste. Offering those on text nobody can change would be three
 * greyed-out entries, so read-only text gets this shorter menu instead.
 */
public final class CopyContextMenu {

	private CopyContextMenu() {
	}

	/**
	 * Give read-only text the menu.
	 *
	 * @param text      the component
	 * @param clipboard where copies go; {@code null}, or returning {@code null}, means
	 *                  the display's own
	 */
	public static void install(JTextComponent text, Supplier<Clipboard> clipboard) {
		install(text, clipboard, null);
	}

	/**
	 * Give read-only text the menu, with the source it was rendered from.
	 *
	 * @param text      the component
	 * @param clipboard where copies go; {@code null}, or returning {@code null}, means
	 *                  the display's own
	 * @param markdown  the Markdown the text was rendered from, read when the entry is
	 *                  chosen; {@code null} leaves Copy as Markdown out
	 */
	public static void install(JTextComponent text, Supplier<Clipboard> clipboard, Supplier<String> markdown) {

		if (text == null) {
			return;
		}
		var shortcut = Dialogs.menuShortcutMask();
		var copy = item("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut), text::copy);
		var copyAll = item("Copy All", null, () -> put(clipboard, text, new StringSelection(plainText(text))));
		var selectAll = item("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut), () -> {
			text.requestFocusInWindow();
			text.selectAll();
		});

		var menu = new JPopupMenu();
		menu.add(copy);
		menu.add(copyAll);
		if (markdown != null) {
			menu.add(item("Copy as Markdown", null,
					() -> put(clipboard, text, new StringSelection(markdown.get()))));
		}
		menu.addSeparator();
		menu.add(selectAll);

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
				copy.setEnabled(text.getSelectionStart() != text.getSelectionEnd());
				var hasText = text.getDocument().getLength() > 0;
				copyAll.setEnabled(hasText);
				selectAll.setEnabled(hasText);
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
				// Nothing to put back.
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent event) {
				// Nothing to put back.
			}
		});
		text.setComponentPopupMenu(menu);
	}

	/**
	 * What the component shows, as text: a pane rendering HTML would otherwise hand
	 * over its markup, which is not what "Copy All" is being asked for.
	 *
	 * @param text the component
	 * @return its text content
	 */
	private static String plainText(JTextComponent text) {
		var document = text.getDocument();
		try {
			return document.getText(0, document.getLength());
		} catch (javax.swing.text.BadLocationException e) {
			// The document changed underneath; whatever the component reports will do.
			return text.getText();
		}
	}

	/** Copy, quietly doing nothing when there is no clipboard to be had. */
	private static void put(Supplier<Clipboard> clipboard, JTextComponent text, Transferable content) {
		try {
			var target = clipboard == null ? null : clipboard.get();
			(target != null ? target : text.getToolkit().getSystemClipboard()).setContents(content, null);
		} catch (IllegalStateException | java.awt.HeadlessException e) {
			// Another application holds the clipboard, or there is no display to have one.
		}
	}

	private static JMenuItem item(String label, KeyStroke shortcut, Runnable action) {
		var entry = new JMenuItem(label);
		// Shown for reference; the component's own key bindings do the work.
		entry.setAccelerator(shortcut);
		entry.addActionListener(event -> action.run());
		return entry;
	}
}
