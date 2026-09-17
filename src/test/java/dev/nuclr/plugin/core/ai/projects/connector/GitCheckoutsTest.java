package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;

/** Local copies of a real repository, served from a folder, through the git command line. */
class GitCheckoutsTest {

	@TempDir
	Path root;

	private Path origin;
	private String url;
	private GitCheckouts checkouts;

	@BeforeEach
	void setUp() throws Exception {
		Assumptions.assumeTrue(AgentCli.isInstalled("git"), "git is not installed");
		origin = Files.createDirectories(root.resolve("origin"));
		git(origin, "init", "--quiet", "--initial-branch=main");
		commit("RULES.md", "Version one.", "first");
		git(origin, "tag", "v1");
		commit("RULES.md", "Version two.", "second");
		url = origin.toUri().toString();
		checkouts = new GitCheckouts(root.resolve("cache"), "git");
	}

	private void commit(String file, String text, String message) throws Exception {
		Files.writeString(origin.resolve(file), text);
		git(origin, "add", file);
		git(origin, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--quiet", "-m", message);
	}

	private static void git(Path directory, String... arguments) throws Exception {
		var command = new java.util.ArrayList<>(List.of("git"));
		command.addAll(List.of(arguments));
		var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		var output = new String(process.getInputStream().readAllBytes());
		assertTrue(process.waitFor(60, TimeUnit.SECONDS));
		assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
	}

	@Test
	void aBranchATagAndTheDefaultBranchEachGetTheirOwnCopy() throws IOException {
		var tagged = checkouts.checkout(url, "v1");
		var branch = checkouts.checkout(url, "main");
		var defaultBranch = checkouts.checkout(url, "");

		assertEquals("Version one.", Files.readString(tagged.copy().resolve("RULES.md")));
		assertEquals("Version two.", Files.readString(branch.copy().resolve("RULES.md")));
		assertEquals("Version two.", Files.readString(defaultBranch.copy().resolve("RULES.md")));
		assertFalse(tagged.cached());
		assertTrue(tagged.copy().startsWith(root.resolve("cache")));
	}

	@Test
	void theNextStartBringsACopyUpToDate() throws Exception {
		var first = checkouts.checkout(url, "main");
		commit("RULES.md", "Version three.", "third");

		var second = checkouts.checkout(url, "main");

		assertEquals(first.copy(), second.copy());
		assertEquals("Version three.", Files.readString(second.copy().resolve("RULES.md")));
	}

	@Test
	void whenTheRepositoryCannotBeReachedTheLastCopyIsUsedAndSaidToBe() throws Exception {
		checkouts.checkout(url, "main");
		// The origin disappears, as it would offline.
		Files.move(origin, root.resolve("moved"));

		var stale = checkouts.checkout(url, "main");

		assertTrue(stale.cached());
		assertFalse(stale.reason().isBlank());
		assertEquals("Version two.", Files.readString(stale.copy().resolve("RULES.md")));
	}

	@Test
	void withNoCopyAtAllTheSourceCannotBeUsedAndNothingIsLeftBehind() {
		var missing = root.resolve("nowhere").toUri().toString();

		var refused = assertThrows(IOException.class, () -> checkouts.checkout(missing, "main"));

		assertTrue(refused.getMessage().startsWith("git fetch failed"), refused.getMessage());
		assertThrows(IOException.class, () -> checkouts.cachedOnly().checkout(missing, "main"));
	}

	@Test
	void aPreviewUsesTheCopyFromTheLastStartWithoutFetching() throws Exception {
		assertThrows(IOException.class, () -> checkouts.cachedOnly().checkout(url, "v1"), "nothing fetched yet");
		checkouts.checkout(url, "v1");
		Files.move(origin, root.resolve("moved"));

		var copy = checkouts.cachedOnly().checkout(url, "v1");

		assertEquals("Version one.", Files.readString(copy.copy().resolve("RULES.md")));
	}
}
