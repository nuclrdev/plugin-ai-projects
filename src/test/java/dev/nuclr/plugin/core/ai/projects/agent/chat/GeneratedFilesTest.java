package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The files a reply names, and which of them the turn made. */
class GeneratedFilesTest {

	@TempDir
	Path folder;

	private final Instant turn = Instant.now().minus(Duration.ofMinutes(1));

	private Path written(String name) throws IOException {
		var file = folder.resolve(name);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, "x").toAbsolutePath().normalize();
	}

	private Path old(String name) throws IOException {
		var file = written(name);
		Files.setLastModifiedTime(file, FileTime.from(turn.minus(Duration.ofHours(1))));
		return file;
	}

	@Test
	void linksAndCodeSpansNameFilesInTheOrderWritten() throws IOException {
		var model = written("out/bridge.obj");
		var material = written("out/bridge.mtl");
		var readme = written("README.md");
		var reply = "Made [OBJ](" + model + ") and [material](out/bridge.mtl), see `README.md`.";
		assertEquals(List.of(model, material, readme), GeneratedFiles.in(reply, folder, turn, List.of()));
	}

	@Test
	void aFileFromBeforeTheTurnIsOnlyAReference() throws IOException {
		var made = written("made.stl");
		old("Existing.java");
		assertEquals(List.of(made), GeneratedFiles.in("Wrote `made.stl`, like `Existing.java`.", folder, turn, List.of()));
	}

	@Test
	void whatIsAlreadyShownIsLeftOut() throws IOException {
		var picture = written("chart.png");
		var model = written("chart.glb");
		assertEquals(List.of(model),
				GeneratedFiles.in("`chart.png` and `chart.glb`", folder, turn, List.of(folder.resolve("./chart.png"))));
	}

	@Test
	void aLineAfterThePathIsNotPartOfIt() throws IOException {
		var file = written("src/Main.java");
		assertEquals(List.of(file), GeneratedFiles.in("[Main](src/Main.java:12) and `src/Main.java#L3`", folder, turn,
				List.of()));
		assertEquals(List.of(file), GeneratedFiles.in("[Main](" + file + ":12:4)", folder, turn, List.of()));
	}

	@Test
	void urlsFoldersWordsAndMissingFilesAreNotFiles() throws IOException {
		written("sub/a.txt");
		assertEquals(List.of(), GeneratedFiles.in("[site](https://x.dev/a.txt) [mail](mailto:a@b.c) `sub` `npm test` "
				+ "`gone.stl` [x](<nowhere.obj>)", folder, turn, List.of()));
		assertNull(GeneratedFiles.resolve("relative.txt", null));
	}

	@Test
	void aFileUriAndAnglesAroundAPathWithSpacesAreRead() throws IOException {
		var spaced = written("My Models/bridge model.stl");
		assertEquals(List.of(spaced), GeneratedFiles.in("[a](<My Models/bridge model.stl>)", folder, turn, List.of()));
		assertEquals(List.of(spaced), GeneratedFiles.in("[a](" + spaced.toUri() + ")", folder, turn, List.of()));
	}

	@Test
	void codexFileCitationsAreLinksAndNameTheFilesTheyCite() throws IOException {
		assertEquals("Download it here: [sample.pdf](C:\\out\\pdf\\sample.pdf)", GeneratedFiles.citationsAsLinks(
				"Download it here: :codex-file-citation{path=\"C:\\out\\pdf\\sample.pdf\" purpose=\"output\"}"));
		assertEquals("[the report](/tmp/r.pdf) and `out/r.csv`", GeneratedFiles.citationsAsLinks(
				":codex-file-citation[the report]{path='/tmp/r.pdf'} and :codex-file-citation{purpose=\"output\" path=\"out/r.csv\"}"));
		// Nothing to point at: left for the reader to see as it was.
		assertEquals(":codex-file-citation{purpose=\"output\"}",
				GeneratedFiles.citationsAsLinks(":codex-file-citation{purpose=\"output\"}"));

		var pdf = written("output/pdf/sample.pdf");
		var csv = written("output/data.csv");
		assertEquals(List.of(pdf, csv), GeneratedFiles.in("Here: :codex-file-citation{path=\"" + pdf
				+ "\" purpose=\"output\"} and :codex-file-citation{path=\"output/data.csv\"}", folder, turn, List.of()));
	}

	@Test
	void oneTurnAddsAtMostSoManyCards() throws IOException {
		var reply = new StringBuilder();
		for (var index = 0; index < GeneratedFiles.MOST_PER_TURN + 3; index++) {
			written("f" + index + ".txt");
			reply.append('`').append("f").append(index).append(".txt` ");
		}
		assertEquals(GeneratedFiles.MOST_PER_TURN, GeneratedFiles.in(reply.toString(), folder, turn, List.of()).size());
	}

	@Test
	void aLinkedFileIsFoundWithoutItsLineOrScheme() throws IOException {
		var file = written("bridge model.obj");
		assertEquals(file, GeneratedFiles.linkedFile(file.toString()));
		assertEquals(file, GeneratedFiles.linkedFile(file + ":12"));
		assertEquals(file, GeneratedFiles.linkedFile(file + "#L3-L9"));
		assertEquals(file, GeneratedFiles.linkedFile(file.toUri().toString()));
		// Gone, but still a path: opening it says so rather than doing nothing.
		assertEquals(folder.resolve("gone.stl"), GeneratedFiles.linkedFile(folder.resolve("gone.stl").toString()));
	}
}
