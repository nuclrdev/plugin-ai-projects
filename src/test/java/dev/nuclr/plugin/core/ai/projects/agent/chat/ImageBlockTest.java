package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A picture in the conversation: drawn when Java can read it, offered when it cannot,
 * and what the save does afterwards.
 */
class ImageBlockTest {

	@TempDir
	Path folder;

	private static Container blocks(ConversationView view) {
		var scroll = (JScrollPane) view.getComponent(0);
		var holder = (Container) scroll.getViewport().getView();
		return (Container) holder.getComponent(0);
	}

	private static void onEdt(Runnable work) throws Exception {
		SwingUtilities.invokeAndWait(work);
	}

	private static ConversationView view() {
		return new ConversationView((requestId, option) -> {
			// Nothing answers a permission in this test.
		});
	}

	/** Everything of one type anywhere under a component. */
	private static <T> void collect(Container container, Class<T> type, java.util.List<T> found) {
		for (var child : container.getComponents()) {
			if (type.isInstance(child)) {
				found.add(type.cast(child));
			}
			if (child instanceof Container nested) {
				collect(nested, type, found);
			}
		}
	}

	private static <T> java.util.List<T> all(Container container, Class<T> type) {
		var found = new java.util.ArrayList<T>();
		collect(container, type, found);
		return found;
	}

	/** A real PNG, so ImageIO has something it genuinely reads. */
	private Path png(String name, int width, int height) throws IOException {
		var file = folder.resolve(name);
		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.createGraphics().fillRect(0, 0, width, height);
		ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private static AgentEvent.Image event(Path file) {
		return new AgentEvent.Image(file.toString(), null, "image/png", file.getFileName().toString());
	}

	@Test
	void anImageJavaCanReadIsDrawn() throws Exception {

		var file = png("chart.png", 40, 20);
		var view = view();

		onEdt(() -> view.accept(event(file), false));

		var labels = all(blocks(view), JLabel.class);
		assertTrue(labels.stream().anyMatch(label -> label.getIcon() != null), "no picture was drawn");
		assertTrue(labels.stream().anyMatch(label -> label.getText().contains("chart.png")),
				"the picture is not named");
		assertTrue(labels.stream().anyMatch(label -> label.getText().contains("40x20")),
				"the picture's size is not shown");
	}

	@Test
	void aTallImageIsScaledDownRatherThanFillingTheConversation() throws Exception {

		var file = png("tall.png", 100, 4_000);
		var view = view();

		onEdt(() -> view.accept(event(file), false));

		var drawn = all(blocks(view), JLabel.class).stream().filter(label -> label.getIcon() != null).findFirst();
		assertTrue(drawn.isPresent(), "no picture was drawn");
		assertTrue(drawn.get().getIcon().getIconHeight() <= 520,
				"the picture was " + drawn.get().getIcon().getIconHeight() + " tall");
	}

	@Test
	void anImageJavaCannotReadIsOfferedToTheSystemInstead() throws Exception {

		// An svg, which ImageIO has no reader for: there is nothing to draw, so the
		// window says what it is and offers to open it with whatever does.
		var file = folder.resolve("diagram.svg");
		Files.writeString(file, "<svg xmlns='http://www.w3.org/2000/svg'><rect width='10' height='10'/></svg>");
		var view = view();

		onEdt(() -> view.accept(event(file), false));

		var buttons = all(blocks(view), JButton.class);
		assertTrue(buttons.stream().anyMatch(java.awt.Component::isVisible), "nothing was offered");
		assertEquals(3, buttons.size(), "expected open, copy and save");
		assertTrue(all(blocks(view), JLabel.class).stream().allMatch(label -> label.getIcon() == null),
				"something was drawn for a picture Java cannot read");
	}

	@Test
	void anImageJavaCannotReadIsNeverConverted() throws Exception {

		// Nothing is re-encoded or rasterised to get something on screen: the file is
		// handed over as it is. Copying gives the file, which is all there is to give,
		// and a save is a copy of the same bytes.
		var file = folder.resolve("diagram.svg");
		var svg = "<svg xmlns='http://www.w3.org/2000/svg'><rect width='10' height='10'/></svg>";
		Files.writeString(file, svg);
		var view = view();
		var clipboard = new java.awt.datatransfer.Clipboard("test");
		view.clipboard(clipboard);
		onEdt(() -> view.accept(event(file), false));
		var block = blocks(view).getComponent(0);

		onEdt(() -> all((Container) block, JButton.class).stream()
				.filter(button -> "Copy".equals(button.getToolTipText()) || button.getToolTipText().contains("file"))
				.findFirst().orElseThrow().doClick());

		var flavors = List.of(clipboard.getAvailableDataFlavors());
		assertTrue(flavors.contains(java.awt.datatransfer.DataFlavor.javaFileListFlavor), flavors.toString());
		assertFalse(flavors.contains(java.awt.datatransfer.DataFlavor.imageFlavor),
				"an image was offered for something Java cannot read: " + flavors);
		assertEquals(List.of(file.toFile()),
				clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor));

		var target = folder.resolve("copied.svg");
		saveTo(block, target);
		assertEquals(svg, Files.readString(target), "the saved file is not the bytes that arrived");
	}

	@Test
	void anImageJavaCanReadDoesNotOfferToOpenItElsewhere() throws Exception {

		var file = png("shown.png", 10, 10);
		var view = view();
		onEdt(() -> view.accept(event(file), false));

		// Three buttons exist either way; the Open one is only shown when it is needed.
		var visible = all(blocks(view), JButton.class).stream().filter(java.awt.Component::isVisible).count();
		assertEquals(2, visible, "expected only copy and save to be offered");
	}

	@Test
	void savingPutsTheFileWhereItWasAskedForAndSaysSo() throws Exception {

		var file = png("chart.png", 8, 8);
		var view = view();
		onEdt(() -> view.accept(event(file), false));
		var block = blocks(view).getComponent(0);
		var target = folder.resolve("saved").resolve("copy.png");

		var saved = saveTo(block, target);

		assertTrue(Files.isRegularFile(target), "nothing was saved");
		assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(target));
		assertEquals(target.toAbsolutePath().normalize(), saved);
	}

	@Test
	void savingOntoAFolderIsRefusedRatherThanDeletingIt() throws Exception {

		// Copying with REPLACE_EXISTING onto an empty folder removes the folder, which is
		// not what anyone means by "save the picture here".
		var file = png("chart.png", 8, 8);
		var view = view();
		onEdt(() -> view.accept(event(file), false));
		var block = blocks(view).getComponent(0);
		var target = Files.createDirectories(folder.resolve("in-the-way"));

		var failed = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> saveTo(block, target));

		assertInstanceOf(IOException.class, failed.getCause());
		assertTrue(Files.isDirectory(target), "the folder was removed");
	}

	@Test
	void anImageThatWasNeverStoredSaysSoInsteadOfShowingNothing() throws Exception {

		var view = view();

		onEdt(() -> view.accept(new AgentEvent.Image(null, "Zm9v", "image/png", null), false));

		var labels = all(blocks(view), JLabel.class);
		assertTrue(labels.stream().anyMatch(label -> label.getText().contains("could not be stored")),
				"an unstored image was passed over in silence");
		assertFalse(labels.stream().anyMatch(label -> label.getIcon() != null));
	}

	/**
	 * Call the block's own save, which is what the chooser calls once the user has chosen.
	 *
	 * <p>The save alone: showing the file in the file manager is the dialog's doing, and a
	 * test that reached it would open a window on the machine running the suite.
	 */
	private static Path saveTo(java.awt.Component block, Path target) throws Exception {
		var method = block.getClass().getDeclaredMethod("saveTo", Path.class);
		method.setAccessible(true);
		var saved = new Path[1];
		var thrown = new Exception[1];
		onEdt(() -> {
			try {
				saved[0] = (Path) method.invoke(block, target);
			} catch (ReflectiveOperationException e) {
				thrown[0] = e;
			}
		});
		if (thrown[0] != null) {
			throw thrown[0];
		}
		assertNotNull(saved[0]);
		return saved[0];
	}
}
