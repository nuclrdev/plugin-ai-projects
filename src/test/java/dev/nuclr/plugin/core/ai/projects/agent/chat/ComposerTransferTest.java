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

	@Test
	void thirtyNineLinesGoIntoTheBoxAndFortyAreAttached() {
		var almost = "l\n".repeat(Attachments.LONG_PASTE_LINES - 1);

		assertTrue(paste(new StringSelection(almost)));
		assertEquals(almost, input.getText());
		assertTrue(longTexts.isEmpty());

		input.setText("");
		var enough = "l\n".repeat(Attachments.LONG_PASTE_LINES);
		assertTrue(paste(new StringSelection(enough)));
		assertEquals("", input.getText());
		assertEquals(List.of(enough), longTexts);
	}

	@Test
	void oneVeryLongLineIsAttachedToo() {
		var line = "x".repeat(Attachments.LONG_PASTE_CHARS);

		assertTrue(paste(new StringSelection(line)));

		assertEquals("", input.getText());
		assertEquals(List.of(line), longTexts);
	}

	@Test
	void aLongPasteIsJudgedWithItsLineBreaksAlreadyMadePlain() {
		// Notepad's CRLF would count double against the length if it were judged raw.
		var log = "line\r\n".repeat(Attachments.LONG_PASTE_LINES);

		assertTrue(paste(new StringSelection(log)));

		assertEquals(List.of("line\n".repeat(Attachments.LONG_PASTE_LINES)), longTexts);
	}

	@Test
	void oldStyleCarriageReturnsBecomeLineBreaks() {
		assertTrue(paste(new StringSelection("one\rtwo\r\nthree")));

		assertEquals("one\ntwo\nthree", input.getText());
	}

	@Test
	void aPasteReplacesWhatIsSelected() {
		input.setText("replace THIS here");
		input.select(8, 12);

		assertTrue(paste(new StringSelection("that")));

		assertEquals("replace that here", input.getText());
	}

	@Test
	void anEmptyPasteChangesNothing() {
		input.setText("kept");

		assertFalse(paste(new StringSelection("")));

		assertEquals("kept", input.getText());
		assertTrue(longTexts.isEmpty());
	}

	@Test
	void severalFilesArriveTogetherInTheirOrderAndAnythingElseInTheListIsIgnored() {
		var first = new File("C:/work/a.png");
		var second = new File("C:/work/b.txt");

		assertTrue(paste(new Offer(DataFlavor.javaFileListFlavor, List.of(first, "not a file", second))));

		assertEquals(List.of(first, second), files);
	}

	@Test
	void aFileManagersUriWithASpaceIsDecodedIntoTheFile() throws Exception {
		var uris = new DataFlavor("text/uri-list;class=java.lang.String");
		var file = new File(System.getProperty("java.io.tmpdir"), "my shot.png").getAbsoluteFile();
		assertTrue(file.toURI().toString().contains("%20"), "the URI does not encode the space");

		assertTrue(paste(new Offer(uris, file.toURI() + "\n")));

		assertEquals(List.of(file), files);
	}

	@Test
	void aUriListWithNoLocalFilesIsNotTakenAsFiles() throws Exception {
		var uris = new DataFlavor("text/uri-list;class=java.lang.String");

		// Nothing to hand over, and no text either: the paste is refused, not half-done.
		assertFalse(paste(new Offer(uris, "https://example.com/a.png\n")));

		assertTrue(files.isEmpty());
		assertEquals("", input.getText());
	}

	@Test
	void aBoxThatIsDisabledTakesNothing() {
		input.setEnabled(false);

		assertFalse(paste(new StringSelection("hello")));
		assertFalse(transfer.canImport(input, new DataFlavor[] { DataFlavor.imageFlavor }));
	}

	@Test
	void textIsOnlyEverCopiedOutOfTheBoxAndNeverMovedOutOfAReadOnlyOne() {
		assertEquals(TransferHandler.COPY_OR_MOVE, transfer.getSourceActions(input));
		input.setEditable(false);
		assertEquals(TransferHandler.COPY, transfer.getSourceActions(input));
		// The panels round the box accept drops but give nothing away.
		assertEquals(TransferHandler.NONE, transfer.getSourceActions(new javax.swing.JPanel()));
	}

	@Test
	void nothingIsCopiedWhenNothingIsSelected() throws Exception {
		var clipboard = new Clipboard("test");
		clipboard.setContents(new StringSelection("before"), null);
		input.setText("hello");
		input.select(2, 2);

		transfer.exportToClipboard(input, clipboard, TransferHandler.COPY);

		assertEquals("before", clipboard.getData(DataFlavor.stringFlavor));
	}

	@Test
	void theFlavorsThatCountAreRecognisedWhereverTheyAreInTheList() throws Exception {
		assertTrue(ComposerTransfer.importable(new DataFlavor[] { DataFlavor.allHtmlFlavor, DataFlavor.stringFlavor }));
		assertTrue(ComposerTransfer.importable(
				new DataFlavor[] { new DataFlavor("text/uri-list;class=java.lang.String") }));
		assertFalse(ComposerTransfer.importable(new DataFlavor[0]));
		assertTrue(ComposerTransfer.hasPlainText(new Offer(plainText(), "x")));
		assertFalse(ComposerTransfer.hasPlainText(new Offer(DataFlavor.imageFlavor, new BufferedImage(1, 1, 1))));
	}
}
