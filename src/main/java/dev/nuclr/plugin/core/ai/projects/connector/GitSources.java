package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where a profile's git sources - instructions, skills, knowledge - are read from:
 * a local copy of the repository at the branch, tag or commit the profile names.
 */
public interface GitSources {

	/**
	 * A local copy of a repository.
	 *
	 * @param copy      the folder holding it
	 * @param cached    whether it could not be brought up to date, so an earlier copy is used
	 * @param reason    why it could not be, when {@code cached}; otherwise {@code null}
	 */
	record Checkout(Path copy, boolean cached, String reason) {
	}

	/**
	 * Get a local copy. May reach the network, so never on the event thread.
	 *
	 * @param repository the repository URL, already validated
	 * @param ref        a branch, tag or commit, or blank for the default branch
	 * @return the copy
	 * @throws IOException with a message for the user when there is no copy to use
	 */
	Checkout checkout(String repository, String ref) throws IOException;

	/** Sources for when nothing may be fetched: every git source is left for the agent's start. */
	GitSources NONE = (repository, ref) -> {
		throw new IOException("git sources are fetched when an agent starts");
	};
}
