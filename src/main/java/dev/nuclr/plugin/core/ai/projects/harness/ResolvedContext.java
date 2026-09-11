package dev.nuclr.plugin.core.ai.projects.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one agent receives, after inheritance and merging.
 *
 * <p>This is the answer to "what does this agent actually see?", and the whole
 * reason the plugin resolves harness and context rather than just running a
 * command line. It is a snapshot: built on demand, never cached, so it cannot
 * describe a configuration that has since changed.
 *
 * @param agentId the agent this was resolved for, or {@code null} for the project itself
 * @param items   every resolved item, in category order
 */
public record ResolvedContext(String agentId, List<ContextItem> items) {

	/** Defensive copy. */
	public ResolvedContext {
		items = List.copyOf(items);
	}

	/**
	 * The items of one category.
	 *
	 * @param kind the category
	 * @return its items, in resolution order
	 */
	public List<ContextItem> of(ContextItem.Kind kind) {
		return items.stream().filter(item -> item.kind() == kind).toList();
	}

	/**
	 * Items grouped by category, categories in declaration order and empty ones
	 * omitted.
	 *
	 * @return an ordered map of category to items
	 */
	public Map<ContextItem.Kind, List<ContextItem>> grouped() {
		var grouped = new LinkedHashMap<ContextItem.Kind, List<ContextItem>>();
		for (var kind : ContextItem.Kind.values()) {
			var group = of(kind);
			if (!group.isEmpty()) {
				grouped.put(kind, group);
			}
		}
		return grouped;
	}

	/** Items that name a file which is not there. */
	public List<ContextItem> missing() {
		return items.stream().filter(item -> item.path() != null && !item.available()).toList();
	}

	/** Total number of resolved items. */
	public int size() {
		return items.size();
	}
}
