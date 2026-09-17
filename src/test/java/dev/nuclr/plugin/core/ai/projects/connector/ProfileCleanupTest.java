package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/** The sections no provider could use are gone; what they held goes where it can be used. */
class ProfileCleanupTest {

	@Test
	void onlyCodexNeedsAFlagForSandboxedCommandsToReachTheNetwork() {
		var codex = AgentConnectors.of(AgentProvider.CODEX);
		var flag = List.of("-c", "sandbox_workspace_write.network_access=true");
		assertEquals(flag, codex.sandboxNetworkArguments(AccessMode.ASK));
		assertEquals(flag, codex.sandboxNetworkArguments(AccessMode.AUTO));
		assertEquals(List.of(), codex.sandboxNetworkArguments(AccessMode.READ_ONLY));
		assertEquals(List.of(), codex.sandboxNetworkArguments(AccessMode.FULL_ACCESS));
		for (var provider : List.of(AgentProvider.CLAUDE_CODE, AgentProvider.PI)) {
			for (var mode : AccessMode.values()) {
				assertEquals(List.of(), provider.connector().sandboxNetworkArguments(mode), provider + " " + mode);
			}
		}
	}

	@Test
	void filesAndVariablesFromOlderProfilesBecomeInstructions() throws IOException {
		var json = """
				{"name": "Old",
				 "harness": {"sandbox": "docker", "maxTurns": 5, "timeoutMinutes": 10, "maxBudgetUsd": 1.5,
				             "network": [{"kind": "TEXT", "text": "github.com"}]},
				 "context": {
				   "files": [{"kind": "FILE", "name": "Style", "path": "/srv/style.md"}],
				   "variables": [{"kind": "TEXT", "name": "TICKET", "text": "AI-42"},
				                 {"kind": "TEXT", "name": "OFF", "text": "x", "enabled": false}],
				   "loadingRules": [{"kind": "TEXT", "text": "exclude: target/**"}],
				   "instructions": [{"kind": "TEXT", "name": "Variables", "text": "Mine"}]}}""";

		var profile = Json.fromJson(json, Profile.class);
		var instructions = profile.getContext().getInstructions();

		assertEquals(3, instructions.size(), instructions.toString());
		assertEquals("Mine", instructions.get(0).getText(), "the profile's own instructions come first");
		assertEquals(RecordKind.FILE, instructions.get(1).getKind());
		assertEquals("/srv/style.md", instructions.get(1).getPath());
		assertEquals("Variables (2)", instructions.get(2).getName(), "not clashing with an existing name");
		assertEquals("TICKET: AI-42", instructions.get(2).getText(), "switched-off variables are left out");
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());

		var saved = Json.toJson(profile);
		for (var gone : List.of("\"files\"", "\"variables\"", "loadingRules", "sandbox\"", "maxTurns", "network\"")) {
			assertFalse(saved.contains(gone), gone + " in " + saved);
		}
		assertEquals(3, Json.fromJson(saved, Profile.class).getContext().getInstructions().size(), "and it stays put");
	}

	@Test
	void aCopyKeepsCarriedOverInstructions() {
		var profile = new Profile();
		profile.getContext().getInstructions().add(ProfileRecord.text("Style", "Be brief."));
		assertEquals(profile.getContext().getInstructions(), profile.copy().getContext().getInstructions());
	}
}
