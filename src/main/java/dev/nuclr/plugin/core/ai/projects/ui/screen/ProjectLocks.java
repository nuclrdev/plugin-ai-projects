package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which screen has each project open, across every Commander workspace.
 *
 * <p>A project can be open in one workspace at a time. Each desktop opens its own
 * {@code ProjectStore} and its own agent processes, so a second desktop on the same project
 * would write the same files and could start the same agents - resuming the same CLI
 * sessions - twice. Until a desktop can be a second view onto one shared session, the second
 * workspace is told where the project is open instead.
 *
 * <p>The plugin's classes are loaded once per Commander process and every workspace creates
 * its screen instances from them, so {@link #shared()} is visible to all of them. It says
 * nothing about another Commander process.
 */
public final class ProjectLocks {

	/**
	 * Who holds a project.
	 *
	 * @param ownerId       the holding screen instance's uuid
	 * @param workspaceName the holder's workspace name when it is known, else {@code null}
	 */
	public record Holder(String ownerId, String workspaceName) {
	}

	private static final ProjectLocks SHARED = new ProjectLocks();

	private final Map<String, Holder> held = new HashMap<>();

	/** The process-wide registry every screen uses. */
	public static ProjectLocks shared() {
		return SHARED;
	}

	/**
	 * Take a project for a screen. Taking a project the same screen already holds succeeds,
	 * and records the workspace name if one is now known.
	 *
	 * @param projectId     the project
	 * @param ownerId       the screen instance's uuid
	 * @param workspaceName its workspace name, or {@code null}
	 * @return empty when the screen now holds the project; otherwise who does
	 */
	public synchronized Optional<Holder> tryAcquire(String projectId, String ownerId, String workspaceName) {
		var current = held.get(projectId);
		if (current != null && !current.ownerId().equals(ownerId)) {
			return Optional.of(current);
		}
		var name = workspaceName != null ? workspaceName : current == null ? null : current.workspaceName();
		held.put(projectId, new Holder(ownerId, name));
		return Optional.empty();
	}

	/** Give a project back. Ignored unless {@code ownerId} holds it. */
	public synchronized void release(String projectId, String ownerId) {
		if (projectId == null) {
			return;
		}
		var current = held.get(projectId);
		if (current != null && current.ownerId().equals(ownerId)) {
			held.remove(projectId);
		}
	}

	/** Who holds a project, if anyone. */
	public synchronized Optional<Holder> holder(String projectId) {
		return Optional.ofNullable(projectId == null ? null : held.get(projectId));
	}
}
