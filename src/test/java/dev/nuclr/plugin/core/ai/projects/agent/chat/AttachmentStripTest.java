package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentStripTest {

	@TempDir
	Path runtime;

	/** One call to the host for a thumbnail, answerable later. */
	private record Request(Path file, int maxWidth, int maxHeight, AtomicBoolean cancelled,
			Consumer<BufferedImage> answer) {
	}

	private final List<Request> requests = new ArrayList<>();

	@Test
	void aPasteAsksForItsThumbnailOnceAndShowsItWhenItArrives() throws Exception {
		var paste = Attachments.text(runtime, "line\n".repeat(80), "Pasted text 1");
		var strip = onEdt(this::strip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			assertEquals(1, requests.size());
			assertEquals(paste.file(), requests.getFirst().file());
			assertEquals(0, pictures(strip), "the text glyph stands in meanwhile");

			// A rebuild while the answer is out must not ask again.
			strip.add(new AgentEvent.Attachment(AgentEvent.Attachment.Kind.TEXT, runtime.resolve("other.txt").toString(),
					"text/plain", "Pasted text 2"));
			assertEquals(2, requests.size());

			requests.getFirst().answer().accept(new BufferedImage(25, 36, BufferedImage.TYPE_INT_ARGB));
			assertEquals(1, pictures(strip));

			// Drawn once: rebuilding reuses the picture rather than asking again.
			strip.removeLast();
			assertEquals(1, pictures(strip));
			assertEquals(2, requests.size());
		});
	}

	@Test
	void aPasteTakenOffCancelsItsRequestAndIgnoresALateAnswer() throws Exception {
		var paste = Attachments.text(runtime, "line\n".repeat(80), "Pasted text 1");
		var strip = onEdt(this::strip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			var request = requests.getFirst();
			strip.clear();
			assertTrue(request.cancelled().get());

			request.answer().accept(new BufferedImage(25, 36, BufferedImage.TYPE_INT_ARGB));
			strip.add(paste);
			assertEquals(0, pictures(strip), "a cancelled answer is not shown");
			assertEquals(2, requests.size(), "added again, it is asked for afresh");
		});
	}

	@Test
	void aHostThatNeverAnswersLeavesTheGlyph() throws Exception {
		var paste = Attachments.text(runtime, "line\n".repeat(80), "Pasted text 1");
		var strip = onEdt(() -> new AttachmentStrip((file, w, h, cancelled, answer) -> {
		}, attachment -> {
		}, message -> {
		}));

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			assertEquals(0, pictures(strip));
			assertFalse(strip.isEmpty());
		});
	}

	private AttachmentStrip strip() {
		return new AttachmentStrip(
				(file, maxWidth, maxHeight, cancelled, answer) -> requests
						.add(new Request(file, maxWidth, maxHeight, cancelled, answer)),
				attachment -> {
				}, message -> {
				});
	}

	/** How many labels in the strip show a drawn picture rather than a glyph. */
	private static int pictures(Container container) {
		var count = 0;
		for (Component child : container.getComponents()) {
			if (child instanceof JLabel label && label.getIcon() instanceof ImageIcon) {
				count++;
			}
			if (child instanceof Container nested) {
				count += pictures(nested);
			}
		}
		return count;
	}

	private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
		var result = new ArrayList<T>(1);
		var failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				result.add(work.call());
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) {
			throw failure[0];
		}
		return result.getFirst();
	}

	// ------------------------------------------------------------------ adding, removing, order

	/** A strip that remembers what it was asked to put back in the box, and what it said. */
	private final List<AgentEvent.Attachment> putBack = new ArrayList<>();
	private final List<String> said = new ArrayList<>();

	private AttachmentStrip recordingStrip() {
		return new AttachmentStrip((file, maxWidth, maxHeight, cancelled, answer) -> {
		}, putBack::add, said::add);
	}

	private AgentEvent.Attachment picture(String name, int width, int height) throws Exception {
		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, name.hashCode());
		return Attachments.image(runtime, image, name);
	}

	@Test
	void theSameFileIsNeverAttachedTwice() throws Exception {
		var shot = picture("Pasted image 1", 30, 20);
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			assertTrue(strip.add(shot));
			assertTrue(strip.add(paste));
			// Pasted again, a picture or a text is stored under the same name - so it is the same attachment.
			assertFalse(strip.add(new AgentEvent.Attachment(shot.kind(), shot.path(), shot.mediaType(), "Pasted image 2")));
			assertFalse(strip.add(paste));
			assertEquals(2, strip.count());
			assertEquals(2, chips(strip).size());
		});
	}

	@Test
	void backspaceOrderTakesOffTheNewestFirstAndTheStripHidesWhenEmpty() throws Exception {
		var first = picture("Pasted image 1", 10, 10);
		var second = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);
		var changes = new int[1];

		SwingUtilities.invokeAndWait(() -> {
			strip.onChange(() -> changes[0]++);
			assertFalse(strip.isVisible(), "an empty strip takes room");
			strip.add(first);
			strip.add(second);
			assertTrue(strip.isVisible());

			strip.removeLast();
			assertEquals(List.of(first), strip.attachments());
			strip.removeLast();
			assertTrue(strip.isEmpty());
			assertFalse(strip.isVisible());
			// Nothing left: another Backspace is harmless.
			strip.removeLast();
			assertEquals(4, changes[0], "every change but the empty one is reported");
		});
	}

	@Test
	void settingTheSameAttachmentsAgainIsNotAChange() throws Exception {
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);
		var changes = new int[1];

		SwingUtilities.invokeAndWait(() -> {
			strip.set(List.of(paste));
			strip.onChange(() -> changes[0]++);
			strip.set(List.of(paste));
			assertEquals(0, changes[0]);
			strip.clear();
			assertEquals(1, changes[0]);
			assertTrue(strip.isEmpty());
		});
	}

	@Test
	void theRedCrossTakesOffItsOwnChipAndNoOther() throws Exception {
		var first = picture("Pasted image 1", 10, 10);
		var second = picture("Pasted image 2", 12, 10);
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(first);
			strip.add(second);
			removeButton(chips(strip).getFirst()).doClick();
			assertEquals(List.of(second), strip.attachments());
		});
	}

	// ------------------------------------------------------------------ what a chip shows

	@Test
	void aPictureChipShowsItsThumbnailAndItsSize() throws Exception {
		var shot = picture("Pasted image 1", 1280, 720);
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(shot);
			var chip = chips(strip).getFirst();
			assertEquals(1, pictures(chip));
			var words = texts(chip);
			assertTrue(words.contains("Pasted image 1"), words.toString());
			assertTrue(words.contains("1280x720"), words.toString());
		});
	}

	@Test
	void aPictureWhoseFileHasGoneStillHasAChipThatSaysWhatItWas() throws Exception {
		var shot = picture("Pasted image 1", 20, 10);
		java.nio.file.Files.delete(shot.file());
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(shot);
			var chip = chips(strip).getFirst();
			assertEquals(0, pictures(chip));
			assertTrue(texts(chip).contains("image"), texts(chip).toString());
		});
	}

	@Test
	void aTextChipShowsItsLengthAndPreviewsItsStartOnHover() throws Exception {
		var lines = new StringBuilder();
		for (var i = 1; i <= 50; i++) {
			lines.append("row ").append(i).append('\n');
		}
		var paste = Attachments.text(runtime, lines.toString(), "Pasted text 1");
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			var chip = chips(strip).getFirst();
			assertTrue(texts(chip).contains("50 lines · " + Attachments.size(lines.length())), texts(chip).toString());
			var tip = chip.getToolTipText();
			assertTrue(tip.contains("row 1\nrow 2"), tip);
			assertTrue(tip.contains("row 12"), tip);
			assertFalse(tip.contains("row 13"), "more than twelve lines previewed: " + tip);
		});
	}

	@Test
	void markupInAPasteIsShownAsTextInItsPreview() throws Exception {
		var paste = Attachments.text(runtime, "<b>not bold</b>\n".repeat(3), "Pasted text 1");
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			var tip = chips(strip).getFirst().getToolTipText();
			assertTrue(tip.contains("&lt;b&gt;not bold&lt;/b&gt;"), tip);
		});
	}

	// ------------------------------------------------------------------ the chip's menu

	@Test
	void aTextChipCanBePutBackInTheBoxAndTheChipGoes() throws Exception {
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			menuItem(chips(strip).getFirst(), "Put back in the box as text").doClick();
			assertTrue(strip.isEmpty());
			assertEquals(List.of(paste), putBack);
		});
	}

	@Test
	void aFileChipCanBeNamedInTheBoxInstead() throws Exception {
		var report = java.nio.file.Files.writeString(runtime.resolve("report.pdf"), "%PDF");
		var file = Attachments.file(report);
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(file);
			var chip = chips(strip).getFirst();
			assertTrue(texts(chip).contains("PDF · 4 B"), texts(chip).toString());
			menuItem(chip, "Put its path in the box instead").doClick();
			assertTrue(strip.isEmpty());
			assertEquals(List.of(file), putBack);
		});
	}

	@Test
	void aPictureChipHasNothingToPutBackAndItsMenuRemovesIt() throws Exception {
		var shot = picture("Pasted image 1", 10, 10);
		var strip = onEdt(this::recordingStrip);

		SwingUtilities.invokeAndWait(() -> {
			strip.add(shot);
			var chip = chips(strip).getFirst();
			var labels = menuLabels(chip);
			assertEquals(List.of("Open", "Remove"), labels);
			menuItem(chip, "Remove").doClick();
			assertTrue(strip.isEmpty());
			assertTrue(putBack.isEmpty());
		});
	}

	@Test
	void openingAnAttachmentWhoseFileHasGoneSaysSo() throws Exception {
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);
		java.nio.file.Files.delete(paste.file());

		SwingUtilities.invokeAndWait(() -> {
			strip.add(paste);
			var chip = chips(strip).getFirst();
			// A left click on the chip, as its own listener takes it.
			var click = new java.awt.event.MouseEvent(chip, java.awt.event.MouseEvent.MOUSE_CLICKED,
					System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false,
					java.awt.event.MouseEvent.BUTTON1);
			for (var listener : chip.getMouseListeners()) {
				listener.mouseClicked(click);
			}
			assertEquals(List.of("Pasted text 1: the file is gone"), said);
		});
	}

	@Test
	void aThemeChangeRedrawsTheChipsInItsColours() throws Exception {
		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var strip = onEdt(this::recordingStrip);
		var background = javax.swing.UIManager.getColor("TextArea.background");
		var foreground = javax.swing.UIManager.getColor("TextArea.foreground");

		SwingUtilities.invokeAndWait(() -> {
			try {
				javax.swing.UIManager.put("TextArea.background", java.awt.Color.WHITE);
				javax.swing.UIManager.put("TextArea.foreground", java.awt.Color.BLACK);
				strip.add(paste);
				var light = chips(strip).getFirst().getBackground();

				javax.swing.UIManager.put("TextArea.background", java.awt.Color.BLACK);
				javax.swing.UIManager.put("TextArea.foreground", java.awt.Color.WHITE);
				strip.updateUI();
				var dark = chips(strip).getFirst().getBackground();

				assertTrue(dark.getRed() < light.getRed(), light + " then " + dark);
				assertEquals(List.of(paste), strip.attachments(), "the theme change lost an attachment");
			} finally {
				javax.swing.UIManager.put("TextArea.background", background);
				javax.swing.UIManager.put("TextArea.foreground", foreground);
			}
		});
	}

	// ------------------------------------------------------------------ finding things in a strip

	/** The chips, in order: the strip's own children. */
	private static List<javax.swing.JPanel> chips(AttachmentStrip strip) {
		var chips = new ArrayList<javax.swing.JPanel>();
		for (var child : strip.getComponents()) {
			if (child instanceof javax.swing.JPanel chip) {
				chips.add(chip);
			}
		}
		return chips;
	}

	/** Every label's words under a component. */
	private static List<String> texts(Container container) {
		var texts = new ArrayList<String>();
		for (var child : container.getComponents()) {
			if (child instanceof JLabel label && label.getText() != null && !label.getText().isEmpty()) {
				texts.add(label.getText());
			}
			if (child instanceof Container nested) {
				texts.addAll(texts(nested));
			}
		}
		return texts;
	}

	private static javax.swing.JButton removeButton(Container container) {
		var found = findRemoveButton(container);
		if (found == null) {
			throw new AssertionError("no remove button");
		}
		return found;
	}

	private static javax.swing.JButton findRemoveButton(Container container) {
		for (var child : container.getComponents()) {
			if (child instanceof javax.swing.JButton button && "Remove".equals(button.getToolTipText())) {
				return button;
			}
			if (child instanceof Container nested) {
				var found = findRemoveButton(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static List<String> menuLabels(javax.swing.JComponent chip) {
		var labels = new ArrayList<String>();
		for (var child : chip.getComponentPopupMenu().getComponents()) {
			if (child instanceof javax.swing.JMenuItem item) {
				labels.add(item.getText());
			}
		}
		return labels;
	}

	private static javax.swing.JMenuItem menuItem(javax.swing.JComponent chip, String text) {
		for (var child : chip.getComponentPopupMenu().getComponents()) {
			if (child instanceof javax.swing.JMenuItem item && text.equals(item.getText())) {
				return item;
			}
		}
		throw new AssertionError("no \"" + text + "\" in " + menuLabels(chip));
	}
}
