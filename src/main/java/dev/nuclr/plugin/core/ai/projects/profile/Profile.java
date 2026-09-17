package dev.nuclr.plugin.core.ai.projects.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import lombok.Data;

/**
 * A shared, reusable configuration: a harness (what agents can do) and a context
 * (what they know), kept outside any one project.
 *
 * <p>Profiles are managed on their own for now; nothing applies them to a
 * project or an agent yet.
 */
@Data
public class Profile {

	/** Schema version of a profile file; bumped when the shape changes incompatibly. */
	private int schemaVersion = 1;

	/** Stable identifier; also the file name. */
	private String id;

	/** Display name, unique among profiles regardless of case. */
	private String name;

	/** Optional one-line description. */
	private String description;

	/** When the profile was created. */
	private Instant createdAt;

	/** When the profile was last saved; used to notice a save from elsewhere. */
	private Instant updatedAt;

	/** What agents can do. */
	private Harness harness = new Harness();

	/** What agents know. */
	private Context context = new Context();

	/** Creates an empty profile. */
	public Profile() {}

	/** The name to show, falling back to the id. */
	public String displayName() {
		return name != null && !name.isBlank() ? name.trim() : id == null ? "(unnamed)" : id;
	}

	/** Every record in every section, enabled or not. */
	public int recordCount() {
		var count = 0;
		for (var section : ProfileSection.values()) {
			count += section.records(this).size();
		}
		return count + nullToEmpty(harness.getMcpServers()).size() + nullToEmpty(harness.getAllowedTools()).size()
				+ nullToEmpty(harness.getBlockedTools()).size();
	}

	/** A deep copy, through the same JSON form the profile is stored in. */
	public Profile copy() {
		try {
			return Json.fromJson(Json.toJson(this), Profile.class);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static <T> List<T> nullToEmpty(List<T> values) {
		return values == null ? List.of() : values;
	}

	/** The capabilities half. */
	@Data
	public static class Harness {

		/** Executable that starts the agent. */
		private String executable;

		/** The agent CLI, as an {@link dev.nuclr.plugin.core.ai.projects.provider.AgentProvider} id. */
		private String provider;

		/** Model, exactly as the provider's CLI takes it; blank for the CLI's default. */
		private String model;

		/** Reasoning effort in the provider's own vocabulary; blank for the model's default. */
		private String effort;

		/**
		 * How much agents may do unattended, as an
		 * {@link dev.nuclr.plugin.core.ai.projects.provider.AccessMode} id; blank for
		 * the provider's default.
		 */
		private String accessMode;

		/** Sandbox or runtime, e.g. {@code docker}. */
		private String sandbox;

		/** Arguments appended to the executable. */
		private List<String> startupArgs = new ArrayList<>();

		/**
		 * {@code only} when agents are limited to {@link #allowedTools}; blank when every
		 * tool is available. Explicit, so that "only these" with nothing chosen is caught
		 * rather than read as "all".
		 */
		private String toolAccess;

		/**
		 * The tools agents are limited to when {@link #toolAccess} is {@code only}; for a
		 * provider that cannot be limited (Codex), the optional tools switched on.
		 */
		private List<String> allowedTools = new ArrayList<>();

		/** Built-in tools, or tool patterns, agents may not use. */
		private List<String> blockedTools = new ArrayList<>();

		/** MCP servers the profile adds. */
		private List<McpServerSpec> mcpServers = new ArrayList<>();

		/** {@code only} when agents use the profile's MCP servers and none the user configured. */
		private String mcpAccess;

		/** MCP servers configured outside the profile to switch off, by name. */
		private List<String> switchedOffMcpServers = new ArrayList<>();

		/** Software agents may drive. */
		private List<ProfileRecord> software = new ArrayList<>();

		/** Hardware agents may reach. */
		private List<ProfileRecord> hardware = new ArrayList<>();

		/** Permission grants, or files holding them. */
		private List<ProfileRecord> permissions = new ArrayList<>();

		/** Folders agents may touch. */
		private List<ProfileRecord> allowedRoots = new ArrayList<>();

		/** Hosts or networks agents may reach. */
		private List<ProfileRecord> network = new ArrayList<>();

		/** Environment variables, or {@code .env} files. */
		private List<ProfileRecord> environment = new ArrayList<>();

		/** Most agentic turns per run. */
		private Integer maxTurns;

		/** Longest run, in minutes. */
		private Integer timeoutMinutes;

		/** Most a run may spend, in US dollars. */
		private Double maxBudgetUsd;

		/** The {@link #toolAccess} and {@link #mcpAccess} value that limits agents to what the profile lists. */
		public static final String TOOL_ACCESS_ONLY = "only";

		/** Creates an empty harness. */
		public Harness() {}

		/** Whether agents use only the profile's MCP servers. */
		public boolean restrictsMcpServers() {
			return TOOL_ACCESS_ONLY.equalsIgnoreCase(mcpAccess == null ? "" : mcpAccess.trim());
		}

		/** Whether agents are limited to the allowed tools. */
		public boolean restrictsTools() {
			return TOOL_ACCESS_ONLY.equalsIgnoreCase(toolAccess == null ? "" : toolAccess.trim());
		}

		/**
		 * Reads the free-text tool records profiles held before the allowed and blocked
		 * lists existed. They were meant as the tools agents may use, so the enabled
		 * ones become the allowed list rather than being silently dropped.
		 *
		 * @param records the old records
		 */
		@JsonProperty("tools")
		@SuppressWarnings("unused")
		private void readLegacyTools(List<ProfileRecord> records) {
			if (records == null || !allowedTools.isEmpty()) {
				return;
			}
			if (toolAccess == null) {
				toolAccess = TOOL_ACCESS_ONLY;
			}
			for (var record : records) {
				if (record != null && record.isEnabled() && record.getText() != null && !record.getText().isBlank()
						&& !allowedTools.contains(record.getText().strip())) {
					allowedTools.add(record.getText().strip());
				}
			}
		}
	}

	/** The information half. */
	@Data
	public static class Context {

		/** Instruction documents. */
		private List<ProfileRecord> instructions = new ArrayList<>();

		/** Skills. */
		private List<ProfileRecord> skills = new ArrayList<>();

		/** Project knowledge agents are pointed at. */
		private List<ProfileRecord> knowledge = new ArrayList<>();

		/** Files placed in the agent's context. */
		private List<ProfileRecord> files = new ArrayList<>();

		/** Rules for what agents load into their context. */
		private List<ProfileRecord> loadingRules = new ArrayList<>();

		/** Free-form context variables. */
		private List<ProfileRecord> variables = new ArrayList<>();

		/** Creates an empty context. */
		public Context() {}
	}
}
