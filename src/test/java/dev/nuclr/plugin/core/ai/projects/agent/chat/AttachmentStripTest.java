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
}
