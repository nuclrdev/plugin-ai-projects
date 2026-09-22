package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.TransferHandler;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;

/**
 * What the composer does with a paste or a drop: text goes into the message, pictures
 * and files are attached.
 *
 * <p>Swing's own handler for a text area knows only text, and cannot be extended - it
 * is private to Swing - so this one does its part too: copy, cut and paste of plain
 * text, as the area had them before. It is installed on the area and on the panels
 * around it, so a file dropped a little off the box still lands.
 *
 * <p>What the clipboard holds decides what happens, in this order:
 * <ol>
 * <li>Files - copied in a file manager, or dragged in - are handed to the {@link Sink},
 * which attaches the pictures and writes the paths of the rest into the message.</li>
 * <li>Plain text is text, even when a picture comes with it. Word and Excel put a
 * picture of what was copied beside its text, and it is the text that was meant.</li>
 * <li>A picture alone - a screenshot, "Copy image" in a browser - is attached.</li>
 * </ol>
 * A long paste is offered to the sink first, which attaches it rather than filling the
 * box with it.
 *
 * <p>Whatever comes from outside is taken as a copy, never a move: a file dragged from
 * Explorer must not be deleted there because it was dropped here.
 */
final class ComposerTransfer extends TransferHandler {

	private static final long serialVersionUID = 1L;

	/** Where a paste or drop that is not plain typing goes. */
	interface Sink {

		/**
		 * Files arrived; the caret is where a drop put it.
		 *
		 * @param files the files, in the order given
		 */
		void files(List<File> files);

		/**
		 * A picture arrived.
		 *
		 * @param image the picture
		 */
		void image(Image image);

		/**
		 * Text arrived that may be too long for the box.
		 *
		 * @param text the text, line breaks as {@code \n}
		 * @return whether it was attached; {@code false} puts it in the box
		 */
		boolean longText(String text);
	}

	/** A list of URIs, which is how a Linux file manager hands over files. */
	private static final DataFlavor URI_LIST = uriListFlavor();

	private final JTextArea input;
	private final Sink sink;
	/** The text being cut, held as positions so edits elsewhere cannot move it. */
	private Position cutStart;
	private Position cutEnd;

	/**
	 * @param input the message box
	 * @param sink  where files, pictures and long pastes go
	 */
	ComposerTransfer(JTextArea input, Sink sink) {
		this.input = input;
		this.sink = sink;
	}

	private static DataFlavor uriListFlavor() {
		try {
			return new DataFlavor("text/uri-list;class=java.lang.String");
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------ out: copy and cut

	@Override
	public int getSourceActions(JComponent component) {
		if (component != input) {
			return NONE;
		}
		return input.isEditable() && input.isEnabled() ? COPY_OR_MOVE : COPY;
	}

	@Override
	protected Transferable createTransferable(JComponent component) {
		if (component != input) {
			return null;
		}
		var start = input.getSelectionStart();
		var end = input.getSelectionEnd();
		if (start == end) {
			return null;
		}
		try {
			cutStart = input.getDocument().createPosition(start);
			cutEnd = input.getDocument().createPosition(end);
		} catch (BadLocationException e) {
			cutStart = null;
			cutEnd = null;
		}
		return new StringSelection(input.getSelectedText());
	}

	@Override
	protected void exportDone(JComponent source, Transferable data, int action) {
		try {
			if (action == MOVE && data != null && cutStart != null && input.isEditable()) {
				var start = cutStart.getOffset();
				var end = cutEnd.getOffset();
				if (end > start) {
					input.getDocument().remove(start, end - start);
				}
			}
		} catch (BadLocationException e) {
			// The text changed under the cut; leave it as it is.
		} finally {
			cutStart = null;
			cutEnd = null;
		}
	}

	// ------------------------------------------------------------------ in: paste and drop

	@Override
	public boolean canImport(TransferSupport support) {
		if (!input.isEditable() || !input.isEnabled() || !importable(support.getDataFlavors())) {
			return false;
		}
		if (support.isDrop()) {
			if ((support.getSourceDropActions() & COPY) == 0) {
				// Only a move or a link is on offer; taking it could delete the original.
				return false;
			}
			support.setDropAction(COPY);
		}
		return true;
	}

	@Override
	public boolean canImport(JComponent component, DataFlavor[] flavors) {
		return input.isEditable() && input.isEnabled() && importable(flavors);
	}

	/** Whether any of the flavors is one this handler takes. */
	static boolean importable(DataFlavor[] flavors) {
		for (var flavor : flavors) {
			if (DataFlavor.javaFileListFlavor.equals(flavor) || DataFlavor.imageFlavor.equals(flavor)
					|| DataFlavor.stringFlavor.equals(flavor) || URI_LIST.equals(flavor)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean importData(TransferSupport support) {
		if (!canImport(support)) {
			return false;
		}
		var transferable = support.getTransferable();
		if (support.isDrop() && support.getComponent() == input
				&& support.getDropLocation() instanceof JTextComponent.DropLocation location) {
			input.setCaretPosition(Math.clamp(location.getIndex(), 0, input.getDocument().getLength()));
		}
		try {
			var files = files(transferable);
			if (!files.isEmpty()) {
				sink.files(files);
				return true;
			}
			if (hasPlainText(transferable) && transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
				return text((String) transferable.getTransferData(DataFlavor.stringFlavor));
			}
			if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)
					&& transferable.getTransferData(DataFlavor.imageFlavor) instanceof Image image) {
				sink.image(image);
				return true;
			}
			if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
				return text((String) transferable.getTransferData(DataFlavor.stringFlavor));
			}
		} catch (UnsupportedFlavorException | IOException | ClassCastException | IllegalStateException e) {
			// The source changed its mind, or another application holds the clipboard.
		}
		return false;
	}

	/**
	 * Put text into the box where the caret is, unless the sink attaches it.
	 *
	 * <p>Line breaks are made {@code \n} as Swing's own paste makes them, since a box
	 * holding carriage returns counts and draws its lines wrongly.
	 */
	private boolean text(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		var normalised = text.replace("\r\n", "\n").replace('\r', '\n');
		if (sink.longText(normalised)) {
			return true;
		}
		input.replaceSelection(normalised);
		return true;
	}

	/** The files a transfer holds, from a file list or a list of {@code file:} URIs; empty when it holds none. */
	private static List<File> files(Transferable transferable) throws UnsupportedFlavorException, IOException {
		if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			var files = new ArrayList<File>();
			for (var item : (List<?>) transferable.getTransferData(DataFlavor.javaFileListFlavor)) {
				if (item instanceof File file) {
					files.add(file);
				}
			}
			return files;
		}
		if (transferable.isDataFlavorSupported(URI_LIST)) {
			var files = new ArrayList<File>();
			for (var line : ((String) transferable.getTransferData(URI_LIST)).split("\r?\n")) {
				var entry = line.strip();
				// Comments start with '#'; only local files are files.
				if (!entry.startsWith("file:")) {
					continue;
				}
				try {
					files.add(new File(URI.create(entry)));
				} catch (IllegalArgumentException e) {
					// Not a URI this system can name a file by.
				}
			}
			return files;
		}
		return List.of();
	}

	/**
	 * Whether a transfer carries real plain text, rather than only a string Java could
	 * make of something else.
	 */
	static boolean hasPlainText(Transferable transferable) {
		for (var flavor : transferable.getTransferDataFlavors()) {
			if (flavor.isMimeTypeEqual("text/plain")) {
				return true;
			}
		}
		return false;
	}
}
