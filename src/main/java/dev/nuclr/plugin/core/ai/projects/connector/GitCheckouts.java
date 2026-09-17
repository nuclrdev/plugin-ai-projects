package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Local copies of the repositories profiles name, kept in a cache folder and
 * brought up to date each time an agent starts.
 *
 * <p>One folder per repository and ref. Each update fetches only the one commit
 * wanted - {@code git fetch --depth 1 origin <ref>} - and checks it out detached, so
 * a branch, a tag and a commit hash are all handled the same way and nothing is
 * cloned in full. When the fetch fails - offline, or the credentials are not there -
 * the copy from last time is used and said to be; with no copy at all, the source
 * cannot be used.
 *
 * <p>git runs with no terminal to ask for credentials on, so a private repository
 * needs a credential helper or an SSH key already set up, and a prompt never hangs a
 * launch.
 */
@Slf4j
public final class GitCheckouts implements GitSources {

	/** How long one git command may take. */
	private static final long TIMEOUT_SECONDS = 120;

	/** Most output kept from a failed command, for its message. */
	private static final int OUTPUT_LIMIT = 2_000;

	private final Path cache;
	private final String git;
	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

	/**
	 * Checkouts in a cache folder.
	 *
	 * @param cache the folder that holds every copy
	 * @param git   the git command
	 */
	public GitCheckouts(Path cache, String git) {
		this.cache = cache;
		this.git = git;
	}

	/**
	 * The cache under a Commander home.
	 *
	 * @param commanderHome the Commander configuration folder
	 * @return the checkouts
	 */
	public static GitCheckouts inCommanderHome(Path commanderHome) {
		return new GitCheckouts(commanderHome.resolve("ai-profiles-git"), "git");
	}

	@Override
	public Checkout checkout(String repository, String ref) throws IOException {
		var wanted = ref == null || ref.isBlank() ? "HEAD" : ref.strip();
		var key = key(repository.strip(), wanted);
		// Two agents starting from the same profile share one copy; updating it twice at once would break it.
		synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
			var copy = cache.resolve(key);
			var existing = Files.isDirectory(copy.resolve(".git"));
			try {
				if (!existing) {
					Files.createDirectories(copy);
					run(copy, "init", "--quiet");
					run(copy, "remote", "add", "origin", repository.strip());
				}
				run(copy, "fetch", "--quiet", "--depth", "1", "--no-tags", "origin", wanted);
				run(copy, "checkout", "--quiet", "--force", "--detach", "FETCH_HEAD");
				return new Checkout(copy, false, null);
			} catch (IOException e) {
				if (existing && Files.isRegularFile(copy.resolve(".git").resolve("HEAD"))
						&& hasCheckout(copy)) {
					log.info("Using the cached copy of {} ({}): {}", repository, wanted, e.getMessage());
					return new Checkout(copy, true, e.getMessage());
				}
				if (!existing) {
					deleteQuietly(copy);
				}
				throw e;
			}
		}
	}

	/**
	 * These checkouts without fetching anything: the copy from the last start when there is
	 * one, for showing what a profile holds without reaching the network.
	 *
	 * @return sources that never run git
	 */
	public GitSources cachedOnly() {
		return (repository, ref) -> {
			var copy = cache.resolve(key(repository.strip(), ref == null || ref.isBlank() ? "HEAD" : ref.strip()));
			if (!Files.isRegularFile(copy.resolve(".git").resolve("HEAD"))) {
				throw new IOException("it is fetched when an agent starts");
			}
			return new Checkout(copy, false, null);
		};
	}

	/** Whether a folder has had a commit checked out, rather than only been initialised. */
	private boolean hasCheckout(Path copy) {
		try {
			run(copy, "rev-parse", "--verify", "--quiet", "HEAD");
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void run(Path directory, String... arguments) throws IOException {
		var command = new ArrayList<String>();
		command.add(git);
		// No transport that runs a command, and no pager. A copy made here has no hooks to run.
		command.addAll(List.of("-c", "protocol.ext.allow=never", "--no-pager"));
		command.addAll(List.of(arguments));
		var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
		builder.environment().put("GIT_TERMINAL_PROMPT", "0");
		builder.environment().put("GCM_INTERACTIVE", "never");
		final Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw new IOException("git is not installed or not on PATH, so git sources cannot be fetched.", e);
		}
		var output = new StringBuilder();
		var reader = Thread.ofVirtual().start(() -> {
			try (var in = process.getInputStream()) {
				var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				synchronized (output) {
					output.append(text, 0, Math.min(text.length(), OUTPUT_LIMIT));
				}
			} catch (IOException ignored) {
				// The process is gone; its exit code says enough.
			}
		});
		try {
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("git " + arguments[0] + " took longer than " + TIMEOUT_SECONDS + " seconds.");
			}
			reader.join(5_000);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while running git " + arguments[0] + ".", e);
		}
		if (process.exitValue() != 0) {
			String message;
			synchronized (output) {
				message = output.toString().strip();
			}
			throw new IOException("git " + arguments[0] + " failed"
					+ (message.isEmpty() ? " with exit code " + process.exitValue() : ": " + message));
		}
	}

	/** A folder name for a repository and ref: short, safe on every file system, and the same every time. */
	static String key(String repository, String ref) {
		try {
			var digest = MessageDigest.getInstance("SHA-256")
					.digest((repository + "\n" + ref).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 12);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void deleteQuietly(Path folder) {
		try (var walk = Files.walk(folder)) {
			for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
				// A cloned repository marks its objects read-only on Windows.
				path.toFile().setWritable(true);
				Files.deleteIfExists(path);
			}
		} catch (IOException | RuntimeException e) {
			log.debug("Could not remove the unfinished copy {}: {}", folder, e.getMessage());
		}
	}
}
