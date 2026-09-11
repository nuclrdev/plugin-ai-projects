package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.ui.screen.DocumentFrame;

/**
 * The document editor's safety net: undo, keyboard save, and not throwing away
 * work when the project closes underneath it.
 */
class DocumentFrameTest {

	@TempDir
	Path workspace;

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	private Path write(String name, String content) throws IOException {
		var file = workspace.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	/** The editor inside the frame, which the frame does not expose deliberately. */
	private static JTextArea editorOf(DocumentFrame frame) {
		return findTextArea(frame.getContentPane());
	}

	private static JTextArea findTextArea(java.awt.Container container) {
		for (var child : container.getComponents()) {
			if (child instanceof JScrollPane scroll
					&& scroll.getViewport().getView() instanceof JTextArea area) {
				return area;
			}
			if (child instanceof java.awt.Container nested) {
				var found = findTextArea(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	@Test
	void aFreshlyOpenedDocumentIsNotDirty() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(file, false, null));

		assertFalse(frames[0].isDirty());
		assertEquals(file, frames[0].file());
		onEdt(frames[0]::dispose);
	}

	@Test
	void typingMarksTheDocumentDirtyAndTheTitleShowsIt() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> {
			frames[0] = new DocumentFrame(file, false, null);
			editorOf(frames[0]).setText("edited");
		});

		assertTrue(frames[0].isDirty());
		assertTrue(frames[0].getTitle().startsWith("*"), frames[0].getTitle());
		onEdt(frames[0]::dispose);
	}

	@Test
	void ctrlSIsBoundSoSavingDoesNotNeedTheMouse() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(file, false, null));

		var shortcut = dev.nuclr.plugin.core.ai.projects.ui.Dialogs.menuShortcutMask();
		var editor = editorOf(frames[0]);
		var binding = editor.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, shortcut));

		assertEquals("document.save", binding);
		onEdt(frames[0]::dispose);
	}

	@Test
	void undoIsAvailableBecauseATextAreaHasNoneOfItsOwn() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(file, false, null));

		var shortcut = dev.nuclr.plugin.core.ai.projects.ui.Dialogs.menuShortcutMask();
		var input = editorOf(frames[0]).getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

		assertEquals("document.undo",
				input.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, shortcut)));
		assertEquals("document.redo",
				input.get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, shortcut)));
		onEdt(frames[0]::dispose);
	}

	@Test
	void undoingRestoresWhatWasThere() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> {
			frames[0] = new DocumentFrame(file, false, null);
			var editor = editorOf(frames[0]);
			editor.append(" plus more");
			editor.getActionMap().get("document.undo")
					.actionPerformed(new java.awt.event.ActionEvent(editor, 0, "undo"));
		});

		assertEquals("original", editorOf(frames[0]).getText());
		onEdt(frames[0]::dispose);
	}

	@Test
	void aForcedCloseSavesRatherThanDiscardingSilently() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> {
			frames[0] = new DocumentFrame(file, false, null);
			editorOf(frames[0]).setText("work that must not vanish");
		});

		// This is the path a closing project takes. dispose() fires no vetoable
		// change, so without this the edit would be lost without a word.
		onEdt(frames[0]::settleBeforeForcedClose);

		assertEquals("work that must not vanish", Files.readString(file, StandardCharsets.UTF_8));
		assertFalse(frames[0].isDirty());
		onEdt(frames[0]::dispose);
	}

	@Test
	void aForcedCloseOnACleanDocumentWritesNothing() throws Exception {

		var file = write("skill.md", "original");
		var before = Files.getLastModifiedTime(file);
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(file, false, null));
		onEdt(frames[0]::settleBeforeForcedClose);

		assertEquals(before, Files.getLastModifiedTime(file));
		onEdt(frames[0]::dispose);
	}

	@Test
	void aFileThatChangedOnDiskIsNoticedRatherThanOverwrittenBlind() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> {
			frames[0] = new DocumentFrame(file, false, null);
			editorOf(frames[0]).setText("my edit");
		});

		// An agent in this project rewrites the same skill.
		Files.writeString(file, "written by an agent", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

		// Headless, the conflict dialog answers "overwrite", so the save goes ahead -
		// what matters here is that the frame looked before writing and settled the
		// document rather than throwing.
		onEdt(frames[0]::settleBeforeForcedClose);

		assertFalse(frames[0].isDirty());
		onEdt(frames[0]::dispose);
	}

	@Test
	void aReadOnlyDocumentOffersNoSave() throws Exception {

		var file = write("skill.md", "original");
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(file, true, null));

		assertFalse(editorOf(frames[0]).isEditable());
		onEdt(frames[0]::dispose);
	}

	@Test
	void aMissingFileOpensEmptyRatherThanFailing() throws Exception {

		var missing = workspace.resolve("not-there.md");
		var frames = new DocumentFrame[1];
		onEdt(() -> frames[0] = new DocumentFrame(missing, false, null));

		assertEquals("", editorOf(frames[0]).getText());
		onEdt(frames[0]::dispose);
	}
}
