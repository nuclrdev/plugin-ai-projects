package dev.nuclr.plugin.core.ai.projects.runtime;

import java.util.UUID;

/**
 * Identifies this run of Commander.
 *
 * <p>Session records are written while agents run and are still on disk the next
 * time the project opens. A record saying {@code RUNNING} is only believable if
 * the run that wrote it is the run reading it - the process was a child of a JVM
 * that has since exited. Stamping every record makes that check trivial, and is
 * what lets a restored window say "stopped" instead of inheriting a status that
 * cannot be true.
 */
public final class RuntimeStamp {

	/** The stamp for this JVM run of the plugin. */
	public static final String CURRENT = UUID.randomUUID().toString();

	private RuntimeStamp() {
	}
}
