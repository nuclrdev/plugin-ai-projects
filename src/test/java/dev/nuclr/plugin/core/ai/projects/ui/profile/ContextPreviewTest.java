package dev.nuclr.plugin.core.ai.projects.ui.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.ContextDelivery;
import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/** The Context tab: what it accepts, what older profiles become, and what its preview says. */
class ContextPreviewTest {

	@TempDir
	Path folder;

	private static Profile named(String provider) {
		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().setProvider(provider);
		return profile;
	}

	@Test
	void textSkillsAndKnowledgeFromOlderProfilesBecomeInstructions() throws IOException {
		var profile = Json.fromJson("""
				{"schemaVersion": 2, "name": "Old", "context": {
				  "skills": [{"kind": "TEXT", "name": "Review", "text": "Check everything."},
				             {"kind": "FILE", "path": "/srv/skills/deploy"}],
				  "knowledge": [{"kind": "TEXT", "name": "Glossary", "text": "SLA: service level."}],
				  "instructions": [{"kind": "TEXT", "name": "Style", "text": "Be brief."}]}}""", Profile.class);

		var context = profile.getContext();
		assertEquals(List.of("Style", "Review", "Glossary"),
				context.getInstructions().stream().map(ProfileRecord::getName).toList());
		assertEquals(1, context.getSkills().size());
		assertEquals(RecordKind.FILE, context.getSkills().getFirst().getKind());
		assertTrue(context.getKnowledge().isEmpty());
		assertTrue(ProfileValidator.validate(profile, List.of()).isEmpty());
		assertEquals(3, Json.fromJson(Json.toJson(profile), Profile.class).getContext().getInstructions().size());
	}

	@Test
	void skillsAndKnowledgeTakeNoTextAndGitSourcesAreChecked() {
		var profile = named("claude-code");
		profile.getContext().getSkills().add(ProfileRecord.text("Review", "Check everything."));
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("not available here"));

		var dashed = named("claude-code");
		dashed.getContext().getSkills().add(ProfileRecord.git(null, "https://git.example.com/s.git", "--upload-pack=x", null));
		assertTrue(ProfileValidator.validate(dashed, List.of()).getFirst().message().contains("cannot start with"));

		var wholeRepository = named("claude-code");
		wholeRepository.getContext().getInstructions().add(ProfileRecord.git("Rules", "https://git.example.com/r.git", null, null));
		assertTrue(ProfileValidator.validate(wholeRepository, List.of()).getFirst().message().contains("name the file"));
	}

	@Test
	void thePreviewSaysHowTheBriefingAndSkillsReachTheProvider() throws IOException {
		var skill = Files.createDirectories(folder.resolve("review"));
		Files.writeString(skill.resolve("SKILL.md"), "---\nname: review\ndescription: Reviews\n---\n");
		var profile = named("claude-code");
		profile.getContext().getInstructions().add(ProfileRecord.text("Style", "Be brief."));
		profile.getContext().getSkills().add(ProfileRecord.file(null, skill.toString()));
		profile.getContext().getInstructions().add(ProfileRecord.git("Shared", "https://git.example.com/r.git", null, "R.md"));

		var preview = ContextPreviewPanel.build(profile, GitSources.NONE);

		assertTrue(preview.briefing().contains("Be brief."), preview.briefing());
		assertTrue(preview.summary().contains("--append-system-prompt"), preview.summary());
		assertTrue(preview.summary().contains("--plugin-dir <runtime folder>"), preview.summary());
		assertTrue(preview.summary().contains("fetched when an agent starts"), preview.summary());
		assertNull(preview.warning());
	}

	@Test
	void aBriefingTooLongForTheCommandLineIsWarnedAbout() {
		var profile = named("codex");
		profile.getContext().getInstructions().add(ProfileRecord.text("Huge", "x".repeat(ContextDelivery.INLINE_LIMIT + 1)));

		var preview = ContextPreviewPanel.build(profile, GitSources.NONE);

		assertTrue(preview.warning().contains("longer than a command line"), preview.warning());
		assertTrue(preview.summary().contains("pointer"), preview.summary());
	}

	@Test
	void anIncompleteProfileSaysWhatIsMissingInsteadOfAPreview() {
		var preview = ContextPreviewPanel.build(new Profile(), GitSources.NONE);

		assertFalse(preview.summary().isBlank());
		assertEquals("", preview.briefing());
	}
}
