package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import lombok.extern.slf4j.Slf4j;

/**
 * An internal frame holding one of the project's documents - a skill, an
 * instruction, or the project definition itself.
 *
 * <p>Editing happens inside the desktop rather than in a fullscreen editor
 * because the desktop <em>is</em> the project: sending the user away to another
 * screen to fix a typo in a skill, and back again, would take the agents off
 * screen for the duration.
 *
 * <p>Two things matter more here than in an ordinary editor. Agents write files
 * in this project while it is open, so a save checks whether the file changed
 * underneath it rather than overwriting blind. And the frame can be closed by
 * the project closing rather than by the user, so
 * {@link #settleBeforeForcedClose()} exists to ask before that happens -
 * {@link #dispose()} fires no vetoable change, so the close guard below would
 * never run on that path.
 *
 * <p>Document frames are not persisted in the desktop layout. They belong to the
 * moment, not to the project.
 */
@Slf4j
public final class DocumentFrame extends JInternalFrame {

	private static final long serialVersionUID = 1L;

	private final Path file;
	private final JTextArea editor = new JTextArea();
	private final JButton save = new JButton("Save");
	private final JLabel statusLabel = new JLabel();
	private final UndoManager undo = new UndoManager();
	private final Runnable onSaved;

	private boolean dirty;
	private FileTime lastKnownModified;

	/**
	 * Open a document.
	 *
	 * @param file     the file to edit
	 * @param readOnly whether editing should be disabled
	 * @param onSaved  run after a successful save; may be {@code null}
	 */
	public DocumentFrame(Path file, boolean readOnly, Runnable onSaved) {

		super(displayName(file), true, true, true, true);
		this.file = file;
		this.onSaved = onSaved;

		editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize()));
		editor.setEditable(!readOnly);
		editor.setText(read(file));
		editor.setCaretPosition(0);
		this.lastKnownModified = modifiedTime(file);

		// A JTextArea has no undo of its own, and Ctrl+Z in a text editor is not
		// optional. The manager is attached before the dirty listener so an undo that
		// returns the text to what was on disk still marks the document dirty, which
		// is the conservative side to be on.
		editor.getDocument().addUndoableEditListener(undo);
		editor.getDocument().addDocumentListener(new DocumentListener() {

			@Override
			public void insertUpdate(DocumentEvent event) {
				markDirty();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				markDirty();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				markDirty();
			}
		});

		var bar = new JToolBar();
		bar.setFloatable(false);
		bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		save.setEnabled(false);
		save.setMnemonic(KeyEvent.VK_S);
		save.setText(Glyphs.rich(Glyphs.SAVE, "Save"));
		save.setToolTipText("Save (Ctrl+S)");
		save.addActionListener(event -> save());
		if (!readOnly) {
			bar.add(save);
			bar.add(action(Glyphs.rich(Glyphs.UNDO, "Undo"), "Undo (Ctrl+Z)", this::undo));
			bar.add(action(Glyphs.rich(Glyphs.REDO, "Redo"), "Redo (Ctrl+Y)", this::redo));
			bar.add(action(Glyphs.rich(Glyphs.RELOAD, "Reload"),
					"Discard edits and re-read the file from disk", this::reload));
		}
		bar.add(javax.swing.Box.createHorizontalGlue());
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
		statusLabel.setToolTipText(file.toString());
		bar.add(statusLabel);
		refreshStatusLabel();

		bindKeys(readOnly);

		// DISPOSE_ON_CLOSE, not DO_NOTHING_ON_CLOSE: the close button has to actually
		// call setClosed(true), because that is what fires the vetoable change the
		// listener below uses to hold the frame open over unsaved edits.
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(bar, BorderLayout.NORTH);
		getContentPane().add(new JScrollPane(editor), BorderLayout.CENTER);
		setSize(720, 520);

		addVetoableChangeListener(event -> {
			if (IS_CLOSED_PROPERTY.equals(event.getPropertyName())
					&& Boolean.TRUE.equals(event.getNewValue())
					&& !confirmClose()) {
				throw new java.beans.PropertyVetoException("Close cancelled", event);
			}
		});
	}

	private void bindKeys(boolean readOnly) {

		var shortcut = dev.nuclr.plugin.core.ai.projects.ui.Dialogs.menuShortcutMask();
		var input = editor.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
		var actions = editor.getActionMap();

		if (!readOnly) {
			input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), "document.save");
			actions.put("document.save", new AbstractAction() {

				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(java.awt.event.ActionEvent event) {
					save();
				}
			});
			input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "document.undo");
			actions.put("document.undo", new AbstractAction() {

				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(java.awt.event.ActionEvent event) {
					undo();
				}
			});
			input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, shortcut), "document.redo");
			// Shift+Ctrl+Z is the other half of the world's redo binding.
			input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | InputEvent.SHIFT_DOWN_MASK), "document.redo");
			actions.put("document.redo", new AbstractAction() {

				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(java.awt.event.ActionEvent event) {
					redo();
				}
			});
		}
		input.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcut), "document.close");
		actions.put("document.close", new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				try {
					setClosed(true);
				} catch (java.beans.PropertyVetoException vetoed) {
					// The user cancelled at the unsaved-changes prompt; stay open.
				}
			}
		});
	}

	private static JButton action(String label, String tip, Runnable work) {
		var button = new JButton(label);
		button.setToolTipText(tip);
		button.addActionListener(event -> work.run());
		return button;
	}

	/** The file this frame is editing. */
	public Path file() {
		return file;
	}

	/** Whether there are edits that have not been written. */
	public boolean isDirty() {
		return dirty;
	}

	private void undo() {
		try {
			if (undo.canUndo()) {
				undo.undo();
			}
		} catch (CannotUndoException e) {
			log.debug("Nothing to undo in {}", file);
		}
	}

	private void redo() {
		try {
			if (undo.canRedo()) {
				undo.redo();
			}
		} catch (CannotRedoException e) {
			log.debug("Nothing to redo in {}", file);
		}
	}

	/** Re-read the file, after asking if that would throw away edits. */
	private void reload() {
		if (dirty && !Dialogs.confirm(this, "Reload",
				"Discard your edits and re-read " + displayName(file) + " from disk?")) {
			return;
		}
		editor.setText(read(file));
		editor.setCaretPosition(0);
		undo.discardAllEdits();
		lastKnownModified = modifiedTime(file);
		clearDirty();
	}

	private void markDirty() {
		if (dirty) {
			return;
		}
		dirty = true;
		save.setEnabled(true);
		setTitle("*" + displayName(file));
		refreshStatusLabel();
	}

	private void clearDirty() {
		dirty = false;
		save.setEnabled(false);
		setTitle(displayName(file));
		refreshStatusLabel();
	}

	private void refreshStatusLabel() {
		statusLabel.setText(dirty
				? Glyphs.rich(Glyphs.MISSING, "unsaved changes")
				: Glyphs.rich(Glyphs.FINISHED, "saved"));
	}

	/**
	 * Write the document, first checking that nothing else has.
	 *
	 * <p>Agents in this project write files in it. If one has rewritten this
	 * document since it was opened, saving over it silently would destroy work
	 * that the user cannot even see, so the conflict is put to them instead.
	 *
	 * @return whether the file now holds this frame's content
	 */
	private boolean save() {

		if (hasChangedOnDisk() && !resolveConflict()) {
			return false;
		}
		try {
			var parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(file, editor.getText(), StandardCharsets.UTF_8);
			lastKnownModified = modifiedTime(file);
		} catch (IOException e) {
			log.warn("Could not save {}: {}", file, e.getMessage(), e);
			Dialogs.error(this, "Save", "Could not save " + file + ": " + e.getMessage());
			return false;
		}
		clearDirty();
		if (onSaved != null) {
			onSaved.run();
		}
		return true;
	}

	/** Whether the file's timestamp differs from the one seen when it was read. */
	private boolean hasChangedOnDisk() {
		var current = modifiedTime(file);
		if (current == null || lastKnownModified == null) {
			return false;
		}
		return !current.equals(lastKnownModified);
	}

	/**
	 * Put an external change to the user: overwrite it, or reload and lose the
	 * edits in this frame.
	 *
	 * @return whether saving should go ahead
	 */
	private boolean resolveConflict() {
		if (Dialogs.isHeadless()) {
			// Nobody to ask, and the caller is midway through a save it asked for.
			return true;
		}
		var options = new Object[] { "Overwrite", "Reload from disk", "Cancel" };
		var choice = JOptionPane.showOptionDialog(this,
				displayName(file) + " has changed on disk since it was opened,\n"
						+ "probably because an agent in this project wrote it.\n\n"
						+ "Overwrite it with what is in this window, or discard these edits and reload?",
				"Changed on disk", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
				null, options, options[2]);
		if (choice == 0) {
			return true;
		}
		if (choice == 1) {
			editor.setText(read(file));
			editor.setCaretPosition(0);
			undo.discardAllEdits();
			lastKnownModified = modifiedTime(file);
			clearDirty();
		}
		return false;
	}

	/**
	 * Ask before discarding unsaved edits. Cancelling keeps the frame open, which
	 * is what the veto listener enforces.
	 */
	private boolean confirmClose() {
		if (!dirty) {
			return true;
		}
		if (Dialogs.isHeadless()) {
			return save();
		}
		var choice = JOptionPane.showConfirmDialog(this,
				"Save changes to " + displayName(file) + "?", "Unsaved changes",
				JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
			return false;
		}
		if (choice == JOptionPane.YES_OPTION) {
			return save();
		}
		return true;
	}

	/**
	 * Settle this document when the close cannot be stopped.
	 *
	 * <p>Save or Discard, with no Cancel: by the time the project is tearing its
	 * frames down there is nothing left to cancel into. The alternative is
	 * {@link #dispose()} throwing the edits away in silence, which is the defect
	 * this exists to prevent.
	 */
	public void settleBeforeForcedClose() {
		if (!dirty) {
			return;
		}
		try {
			setSelected(true);
		} catch (java.beans.PropertyVetoException e) {
			log.debug("Could not bring {} forward before closing", file);
		}
		// Save is the default, and the only answer when there is nobody to ask:
		// losing an edit is worse than writing one the user might have discarded.
		if (Dialogs.choose(this, "Unsaved changes",
				"The project is closing and " + displayName(file) + " has unsaved changes.",
				"Save", "Discard")) {
			save();
		}
	}

	private static String displayName(Path file) {
		var name = file.getFileName();
		return name == null ? file.toString() : name.toString();
	}

	private static FileTime modifiedTime(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.getLastModifiedTime(file) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String read(Path file) {
		try {
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
		} catch (IOException e) {
			return "Could not read " + file + ": " + e.getMessage();
		}
	}

	private static int fontSize() {
		var base = UIManager.getFont("TextArea.font");
		return base == null ? 12 : base.getSize();
	}
}
