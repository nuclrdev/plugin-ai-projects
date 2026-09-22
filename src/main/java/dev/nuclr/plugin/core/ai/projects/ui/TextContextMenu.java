package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * The right-click menu every editable text field in this plugin has:
 * Undo, Redo, then Copy, Cut, Paste, then Select All.
 *
 * <p>Swing gives a text field none of this - not the menu, and not undo at all -
 * so it is installed here once rather than by hand in every dialog. A field gets
 * its own undo history, bound to Ctrl+Z and Ctrl+Y (and Ctrl+Shift+Z), unless it
 * already keeps one, in which case that one is shared.
 *
 * <p>Items are enabled for what can actually be done at the moment the menu
 * opens: Cut and Paste only in an editable field, Copy and Cut only with a
 * selection, Paste only with something on the clipboard the field takes.
 */
public final class TextContextMenu {

	private static final String INSTALLED = "nuclr.textContextMenu.undo";

	private TextContextMenu() {
	}

	/**
	 * Give one field the menu and an undo history of its own.
	 *
	 * <p>Call it once the field's initial text is in place, so undo cannot
	 * empty a field back past the value it was opened with. Installing twice is
	 * harmless.
	 *
	 * @param field the field
	 */
	public static void install(JTextComponent field) {
		install(field, null);
	}

	/**
	 * Give one field the menu, sharing an undo history the field already keeps.
	 *
	 * @param field the field
	 * @param undo  the field's existing undo manager, already listening to its
	 *              document; {@code null} to create and attach one
	 */
	public static void install(JTextComponent field, UndoManager undo) {

		if (field == null || field.getClientProperty(INSTALLED) != null) {
			return;
		}
		var owned = undo == null;
		var history = owned ? new UndoManager() : undo;
		if (owned) {
			field.getDocument().addUndoableEditListener(history);
			// A field whose document is swapped keeps its menu, and its undo follows the new text.
			field.addPropertyChangeListener("document", (PropertyChangeListener) event -> {
				if (event.getOldValue() instanceof Document old) {
					old.removeUndoableEditListener(history);
				}
				if (event.getNewValue() instanceof Document replacement) {
					replacement.addUndoableEditListener(history);
				}
				history.discardAllEdits();
			});
			bindUndoKeys(field, history);
		}
		field.putClientProperty(INSTALLED, history);
		field.setComponentPopupMenu(menu(field, history));
	}

	/**
	 * Install the menu on every editable text field inside a component, however
	 * deeply nested - a dialog's content pane, a form, a tab.
	 *
	 * @param root the component to search
	 */
	public static void installTree(Component root) {
		if (root instanceof JTextComponent field) {
			if (field.isEditable()) {
				install(field);
			}
			return;
		}
		if (root instanceof Container container) {
			for (var child : container.getComponents()) {
				installTree(child);
			}
		}
	}

	/**
	 * The undo history a field was given, for code that changes its text in a way
	 * that should not be undoable.
	 *
	 * @param field the field
	 * @return its undo manager, or {@code null} when none was installed
	 */
	public static UndoManager undoManager(JTextComponent field) {
		return field.getClientProperty(INSTALLED) instanceof UndoManager undo ? undo : null;
	}

	private static JPopupMenu menu(JTextComponent field, UndoManager history) {

		var shortcut = Dialogs.menuShortcutMask();
		var undo = item("Undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), () -> undo(history));
		var redo = item("Redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcut), () -> redo(history));
		var copy = item("Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut), field::copy);
		var cut = item("Cut", KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcut), field::cut);
		var paste = item("Paste", KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcut), field::paste);
		var selectAll = item("Select All", KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut), () -> {
			field.requestFocusInWindow();
			field.selectAll();
		});

		var menu = new JPopupMenu();
		menu.add(undo);
		menu.add(redo);
		menu.addSeparator();
		menu.add(copy);
		menu.add(cut);
		menu.add(paste);
		menu.addSeparator();
		menu.add(selectAll);

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
				var editable = field.isEditable() && field.isEnabled();
				var selection = field.getSelectionStart() != field.getSelectionEnd();
				// A password field must never put its content on the clipboard.
				var secret = field instanceof JPasswordField;
				undo.setEnabled(editable && history.canUndo());
				redo.setEnabled(editable && history.canRedo());
				copy.setEnabled(selection && !secret);
				cut.setEnabled(editable && selection && !secret);
				paste.setEnabled(editable && clipboardHasPasteable(field));
				selectAll.setEnabled(field.isEnabled() && field.getDocument().getLength() > 0);
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
				// Nothing to undo.
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent event) {
				// Nothing to undo.
			}
		});
		return menu;
	}

	private static void bindUndoKeys(JTextComponent field, UndoManager history) {
		var shortcut = Dialogs.menuShortcutMask();
		var input = field.getInputMap(JComponent.WHEN_FOCUSED);
		input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "nuclr.text.undo");
		input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcut), "nuclr.text.redo");
		input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | InputEvent.SHIFT_DOWN_MASK), "nuclr.text.redo");
		field.getActionMap().put("nuclr.text.undo", action(() -> {
			if (field.isEditable()) {
				undo(history);
			}
		}));
		field.getActionMap().put("nuclr.text.redo", action(() -> {
			if (field.isEditable()) {
				redo(history);
			}
		}));
	}

	private static void undo(UndoManager history) {
		try {
			if (history.canUndo()) {
				history.undo();
			}
		} catch (CannotUndoException e) {
			// The history no longer matches the text; there is nothing sensible to undo.
		}
	}

	private static void redo(UndoManager history) {
		try {
			if (history.canRedo()) {
				history.redo();
			}
		} catch (CannotRedoException e) {
			// As for undo.
		}
	}

	/**
	 * Whether the clipboard holds something the field would take: text, or whatever else
	 * its own transfer handler accepts - a message box that attaches pictures takes a
	 * screenshot, and its Paste should say so.
	 */
	private static boolean clipboardHasPasteable(JTextComponent field) {
		try {
			var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
				return true;
			}
			var handler = field.getTransferHandler();
			return handler != null && handler.canImport(field, clipboard.getAvailableDataFlavors());
		} catch (IllegalStateException | java.awt.HeadlessException e) {
			// Another application holds the clipboard; offer Paste and let it fail quietly.
			return true;
		}
	}

	private static JMenuItem item(String label, KeyStroke shortcut, Runnable action) {
		var item = new JMenuItem(label);
		// Shown for reference; the field's own key bindings do the work.
		item.setAccelerator(shortcut);
		item.addActionListener(event -> action.run());
		return item;
	}

	private static AbstractAction action(Runnable work) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				work.run();
			}
		};
	}
}
