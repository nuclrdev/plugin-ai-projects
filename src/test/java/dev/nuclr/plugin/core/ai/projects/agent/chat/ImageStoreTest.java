package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Putting an inline image somewhere it can be shown, copied and saved. */
class ImageStoreTest {

	@TempDir
	Path runtime;

	private static final byte[] BYTES = { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4 };

	private static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	@Test
	void theBytesLandInTheAgentsRuntimeFolder() throws IOException {

		var file = ImageStore.store(runtime, base64(BYTES), "image/png");

		assertTrue(file.startsWith(runtime.resolve(ImageStore.FOLDER)), file.toString());
		assertEquals("png", file.getFileName().toString().replaceAll(".*\\.", ""));
		assertArrayEquals(BYTES, Files.readAllBytes(file));
	}

	@Test
	void thesamePictureTwiceIsStoredOnce() throws IOException {

		// An agent that keeps referring to one diagram sends it again each time.
		var first = ImageStore.store(runtime, base64(BYTES), "image/png");
		var second = ImageStore.store(runtime, base64(BYTES), "image/png");

		assertEquals(first, second);
		try (var files = Files.list(runtime.resolve(ImageStore.FOLDER))) {
			assertEquals(1, files.count());
		}
	}

	@Test
	void differentPicturesGetDifferentFiles() throws IOException {

		var first = ImageStore.store(runtime, base64(BYTES), "image/png");
		var second = ImageStore.store(runtime, base64(new byte[] { 9, 9, 9 }), "image/png");

		assertTrue(!first.equals(second), "two pictures shared one file");
	}

	@Test
	void theMediaTypeDecidesTheExtension() {
		assertEquals("png", ImageStore.extensionOf("image/png"));
		assertEquals("jpg", ImageStore.extensionOf("image/jpeg"));
		assertEquals("svg", ImageStore.extensionOf("image/svg+xml"));
		assertEquals("webp", ImageStore.extensionOf("image/webp"));
	}

	@Test
	void aMediaTypeNobodyListedStillGetsAPlausibleExtension() {

		// Better a name that says what it is than everything called .png.
		assertEquals("avif", ImageStore.extensionOf("image/avif"));
		assertEquals("png", ImageStore.extensionOf(null));
		assertEquals("png", ImageStore.extensionOf("   "));
	}

	@Test
	void theParametersOnAMediaTypeAreNotPartOfIt() {
		assertEquals("png", ImageStore.extensionOf("image/png; charset=binary"));
	}

	@Test
	void somethingThatIsNotAnImageIsRefusedRatherThanWritten() {

		// The window turns this into a notice; what must not happen is an empty file
		// sitting in the folder pretending to be a picture.
		assertThrows(IOException.class, () -> ImageStore.store(runtime, "not base64 at all!!", "image/png"));
		assertThrows(IOException.class, () -> ImageStore.store(runtime, "", "image/png"));
	}
}
