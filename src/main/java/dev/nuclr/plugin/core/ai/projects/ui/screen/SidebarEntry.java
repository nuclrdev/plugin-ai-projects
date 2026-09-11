package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.nio.file.Path;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * One line in a sidebar section.
 *
 * <p>The same shape serves every section, so an agent, a skill and an allowed
 * root are rendered by one renderer and activated by one double-click handler.
 * What a line <em>does</em> is carried in {@link #agentId()} and {@link #path()}
 * rather than in a subclass, because the sections differ only in what they list.
 *
 * @param label     the text shown
 * @param detail    the greyed-out suffix, e.g. a status or a provenance
 * @param agentId   the agent this line names, or {@code null}
 * @param path      the file or folder this line names, or {@code null}
 * @param status    the status dot to draw, or {@code null} for none
 * @param attention whether to mark the line as needing the user
 * @param action    a section-level command such as "New skill", or {@code null}
 */
public record SidebarEntry(String label, String detail, String agentId, Path path,
		AgentStatus status, boolean attention, Runnable action) {

	/**
	 * A line naming an agent.
	 *
	 * @param label     the agent's name
	 * @param detail    its status text
	 * @param agentId   its id
	 * @param status    its status
	 * @param attention whether it is asking for a decision
	 * @return the entry
	 */
	public static SidebarEntry agent(String label, String detail, String agentId,
			AgentStatus status, boolean attention) {
		return new SidebarEntry(label, detail, agentId, null, status, attention, null);
	}

	/**
	 * A line naming a file or folder.
	 *
	 * @param label  display name
	 * @param detail the greyed-out suffix
	 * @param path   the file or folder
	 * @return the entry
	 */
	public static SidebarEntry file(String label, String detail, Path path) {
		return new SidebarEntry(label, detail, null, path, null, false, null);
	}

	/**
	 * A line that only shows something.
	 *
	 * @param label  display name
	 * @param detail the greyed-out suffix
	 * @return the entry
	 */
	public static SidebarEntry text(String label, String detail) {
		return new SidebarEntry(label, detail, null, null, null, false, null);
	}

	/**
	 * A line that runs a command when activated, such as "New skill...".
	 *
	 * @param label  display name
	 * @param action what to run
	 * @return the entry
	 */
	public static SidebarEntry command(String label, Runnable action) {
		return new SidebarEntry(label, "", null, null, null, false, action);
	}
}
