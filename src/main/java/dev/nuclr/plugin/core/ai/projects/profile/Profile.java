package dev.nuclr.plugin.core.ai.projects.profile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
		return count + nullToEmpty(harness.getMcpServers()).size();
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

		/** Built-in tools agents may use. */
		private List<ProfileRecord> tools = new ArrayList<>();

		/** MCP servers and tool providers. */
		private List<McpServerSpec> mcpServers = new ArrayList<>();

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

		/** Creates an empty harness. */
		public Harness() {}
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
