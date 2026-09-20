package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading an image out of what a tool said it did.
 *
 * <p>The half of this feature that is a guess, so most of these are about what must
 * <em>not</em> be taken for a picture the agent produced.
 */
class ImageMentionsTest {

	@TempDir
	Path root;

	private Path workspace;

	@BeforeEach
	void setUp() throws IOException {
		workspace = Files.createDirectories(root.resolve("workspace"));
	}

	/** A file with something in it, since an empty one is not a picture. */
	private Path file(Path folder, String name) throws IOException {
		var file = folder.resolve(name);
		Files.createDirectories(file.getParent());
		Files.write(file, new byte[] { 1, 2, 3 });
		return file;
	}

	@Test
	void aPathATtoolSaysItWroteIsFound() throws IOException {

		var image = file(workspace, "diagram.png");

		assertEquals(List.of(image), ImageMentions.in("Saved to " + image, List.of(workspace)));
	}

	@Test
	void aPathRelativeToTheAgentsFolderIsFound() throws IOException {

		// Tools usually say what they wrote the way the user asked for it.
		var image = file(workspace, "out/chart.png");

		assertEquals(List.of(image), ImageMentions.in("Wrote out/chart.png", List.of(workspace)));
	}

	@Test
	void aPathThatNamesNothingIsNotAPicture() {
		assertEquals(List.of(), ImageMentions.in("Saving to missing.png failed", List.of(workspace)));
	}

	@Test
	void anEmptyFileIsNotAPicture() throws IOException {

		// A tool that created the file and then failed leaves one of these behind.
		Files.write(workspace.resolve("started.png"), new byte[0]);

		assertEquals(List.of(), ImageMentions.in("Wrote started.png", List.of(workspace)));
	}

	@Test
	void aFileOutsideTheAgentsFoldersIsNotShownHoweverRealItIs() throws IOException {

		// The guard that matters: a tool listing a folder full of icons, or printing a
		// path from somewhere else on the machine, must not fill the reply with pictures.
		var elsewhere = file(Files.createDirectories(root.resolve("elsewhere")), "logo.png");

		assertTrue(Files.isRegularFile(elsewhere));
		assertEquals(List.of(), ImageMentions.in("See " + elsewhere, List.of(workspace)));
	}

	@Test
	void theSentenceAroundAPathIsNotPartOfIt() throws IOException {

		var image = file(workspace, "shot.png");

		assertEquals(List.of(image), ImageMentions.in("Wrote " + image + ".", List.of(workspace)));
		assertEquals(List.of(image), ImageMentions.in("Wrote (" + image + "), done", List.of(workspace)));
		assertEquals(List.of(image), ImageMentions.in("Wrote \"" + image + "\"", List.of(workspace)));
	}

	@Test
	void thesameFileNamedTwiceIsOnePicture() throws IOException {

		var image = file(workspace, "once.png");

		assertEquals(List.of(image), ImageMentions.in("Wrote " + image + "\nThen read " + image, List.of(workspace)));
	}

	@Test
	void aResultFullOfImagesContributesOnlyAFew() throws IOException {

		// A directory listing is not a gallery.
		var output = new StringBuilder();
		for (var index = 0; index < 20; index++) {
			output.append(file(workspace, "icon" + index + ".png")).append('\n');
		}

		assertEquals(4, ImageMentions.in(output.toString(), List.of(workspace)).size());
	}

	@Test
	void somethingThatIsNotAnImageIsIgnoredEvenWhenItIsThere() throws IOException {

		file(workspace, "notes.txt");
		file(workspace, "archive.zip");

		assertEquals(List.of(), ImageMentions.in("Wrote notes.txt and archive.zip", List.of(workspace)));
	}

	@Test
	void nothingIsFoundWhenThereIsNowhereToLook() throws IOException {

		var image = file(workspace, "diagram.png");

		assertEquals(List.of(), ImageMentions.in("Saved to " + image, List.of()));
		assertEquals(List.of(), ImageMentions.in(null, List.of(workspace)));
	}
}
