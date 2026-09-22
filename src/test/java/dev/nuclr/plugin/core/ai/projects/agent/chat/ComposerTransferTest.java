package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JTextArea;
import javax.swing.TransferHandler;

import org.junit.jupiter.api.Test;

/**
 * What the composer makes of what is pasted into it: text into the box, pictures and
 * files to the attachments, and copy and cut as the box always had them.
 */
class ComposerTransferTest {

	private final JTextArea input = new JTextArea();
	private final List<File> files = new ArrayList<>();
	private final List<Image> images = new ArrayList<>();
	private final List<String> longTexts = new ArrayList<>();
	private final ComposerTransfer transfer = new ComposerTransfer(input, new ComposerTransfer.Sink() {
		@Override
		public void files(List<File> dropped) {
			files.addAll(dropped);
		}

		@Override
		public void image(Image image) {
			images.add(image);
		}

		@Override
		public boolean longText(String text) {
			if (Attachments.isLongPaste(text)) {
				longTexts.add(text);
				return true;
			}
			return false;
		}
	});

	/** A transfer of whatever flavors are given, as another application might offer them. */
	private record Offer(Object... pairs) implements Transferable {

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			var flavors = new DataFlavor[pairs.length / 2];
			for (var i = 0; i < flavors.length; i++) {
				flavors[i] = (DataFlavor) pairs[i * 2];
			}
			return flavors;
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			for (var offered : getTransferDataFlavors()) {
				if (offered.equals(flavor)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			for (var i = 0; i < pairs.length; i += 2) {
				if (pairs[i].equals(flavor)) {
					return pairs[i + 1];
				}
			}
			throw new UnsupportedFlavorException(flavor);
		}
	}

	private boolean paste(Transferable transferable) {
		return transfer.importData(new TransferHandler.TransferSupport(input, transferable));
	}

	private static DataFlavor plainText() throws ClassNotFoundException {
		return new DataFlavor("text/plain;charset=utf-8;class=java.io.InputStream");
	}

	@Test
	void textGoesWhereTheCaretIsWithItsLineBreaksMadePlain() {
		input.setText("ab");
		input.setCaretPosition(1);

		assertTrue(paste(new StringSelection("one\r\ntwo")));

		assertEquals("aone\ntwob", input.getText());
		assertTrue(longTexts.isEmpty());
	}

	@Test
	void aLongPasteIsAttachedAndNotTyped() {
		var log = "line\n".repeat(Attachments.LONG_PASTE_LINES);

		assertTrue(paste(new StringSelection(log)));

		assertEquals("", input.getText());
		assertEquals(List.of(log), longTexts);
	}

	@Test
	void filesAreHandedOverWhateverElseComesWithThem() {
		var file = new File("C:/work/a.txt");

		assertTrue(paste(new Offer(DataFlavor.javaFileListFlavor, List.of(file), DataFlavor.stringFlavor, "a.txt")));

		assertEquals(List.of(file), files);
		assertEquals("", input.getText());
	}

	@Test
	void aFileManagerOnLinuxHandsOverFilesAsUris() throws Exception {
		var uris = new DataFlavor("text/uri-list;class=java.lang.String");
		var file = new File(System.getProperty("java.io.tmpdir"), "shot.png").getAbsoluteFile();

		assertTrue(paste(new Offer(uris, "# a comment\r\n" + file.toURI() + "\r\nhttps://example.com/x\r\n")));

		assertEquals(List.of(file), files);
	}

	@Test
	void aPictureAloneIsAttached() {
		var picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

		assertTrue(paste(new Offer(DataFlavor.imageFlavor, picture)));

		assertEquals(List.of(picture), images);
		assertEquals("", input.getText());
	}

	@Test
	void textWithAPictureOfItIsText() throws Exception {
		// What Word and Excel put on the clipboard: the text, and a picture of the text.
		var picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

		assertTrue(paste(new Offer(DataFlavor.imageFlavor, picture, DataFlavor.stringFlavor, "cell",
				plainText(), new ByteArrayInputStream("cell".getBytes(StandardCharsets.UTF_8)))));

		assertEquals("cell", input.getText());
		assertTrue(images.isEmpty());
	}

	@Test
	void aPictureWithOnlyAStringMadeOfItsHtmlIsAPicture() {
		// "Copy image" in a browser: a bitmap, and markup Java may offer as a string.
		var picture = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);

		assertTrue(paste(new Offer(DataFlavor.imageFlavor, picture, DataFlavor.stringFlavor, "<img src=x>")));

		assertEquals(List.of(picture), images);
		assertEquals("", input.getText());
	}

	@Test
	void nothingIsTakenIntoABoxThatCannotBeEdited() {
		input.setEditable(false);

		assertFalse(paste(new StringSelection("hello")));
		assertFalse(transfer.canImport(input, new DataFlavor[] { DataFlavor.stringFlavor }));
		assertEquals("", input.getText());
	}

	@Test
	void onlyWhatCanBeUsedIsOffered() {
		assertTrue(transfer.canImport(input, new DataFlavor[] { DataFlavor.imageFlavor }));
		assertTrue(transfer.canImport(input, new DataFlavor[] { DataFlavor.javaFileListFlavor }));
		assertFalse(transfer.canImport(input, new DataFlavor[] { DataFlavor.allHtmlFlavor }));
	}

	@Test
	void copyAndCutWorkAsTheyAlwaysDid() throws Exception {
		var clipboard = new Clipboard("test");
		input.setText("hello world");
		input.select(0, 5);

		transfer.exportToClipboard(input, clipboard, TransferHandler.COPY);
		assertEquals("hello", clipboard.getData(DataFlavor.stringFlavor));
		assertEquals("hello world", input.getText());

		input.select(5, 11);
		transfer.exportToClipboard(input, clipboard, TransferHandler.MOVE);
		assertEquals(" world", clipboard.getData(DataFlavor.stringFlavor));
		assertEquals("hello", input.getText());
	}
}
