package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;

/** What stops a profile from being saved, and the record model underneath. */
class ProfileValidatorTest {

	private static Profile named(String name) {
		var profile = new Profile();
		profile.setName(name);
		return profile;
	}

	private static List<String> messages(ProfileSection section, ProfileRecord record) {
		return ProfileValidator.validateRecord(section, record);
	}

	@Test
	void aNamedEmptyProfileIsValid() {
		assertTrue(ProfileValidator.validate(named("Empty"), List.of()).isEmpty());
	}

	@Test
	void theNameIsRequiredAndUniqueIgnoringCase() {
		assertFalse(ProfileValidator.validate(named("  "), List.of()).isEmpty());
		assertFalse(ProfileValidator.validate(named("Company"), List.of("company")).isEmpty());
		assertFalse(ProfileValidator.validate(named("x".repeat(ProfileValidator.MAX_NAME_LENGTH + 1)), List.of()).isEmpty());
	}

	@Test
	void aSectionOnlyAcceptsTheSourcesThatMakeSenseThere() {
		assertFalse(messages(ProfileSection.EXTRA_FOLDERS, ProfileRecord.git(null, "https://x/y.git", null, null)).isEmpty());
		assertFalse(messages(ProfileSection.EXTRA_FOLDERS, ProfileRecord.text(null, "/tmp")).isEmpty());
		assertTrue(messages(ProfileSection.EXTRA_FOLDERS, ProfileRecord.file(null, "/tmp")).isEmpty());
		assertTrue(messages(ProfileSection.SKILLS, ProfileRecord.git(null, "https://x/y.git", null, null)).isEmpty());
	}

	@Test
	void plainTextIsCheckedAgainstItsSectionsShape() {
		assertFalse(messages(ProfileSection.ENVIRONMENT, ProfileRecord.text("MY VAR", "x")).isEmpty());
		assertTrue(messages(ProfileSection.ENVIRONMENT, ProfileRecord.text("MY_VAR", "")).isEmpty());

		assertFalse(messages(ProfileSection.INSTRUCTIONS, ProfileRecord.text(null, "Do things")).isEmpty());
		assertFalse(messages(ProfileSection.INSTRUCTIONS, ProfileRecord.text("Style", " ")).isEmpty());
		assertTrue(messages(ProfileSection.INSTRUCTIONS, ProfileRecord.text("Style", "Line one\nLine two")).isEmpty());
	}

	@Test
	void gitLinksAcceptTheUsualRepositoryForms() {
		for (var url : List.of("https://github.com/org/repo.git", "ssh://git@host/org/repo", "git@github.com:org/repo.git",
				"file:///srv/repos/x.git")) {
			assertTrue(ProfileValidator.isRepository(url), url);
		}
		for (var url : List.of("github.com/org/repo", "not a url", "")) {
			assertFalse(ProfileValidator.isRepository(url), url);
		}
		assertFalse(messages(ProfileSection.SKILLS, ProfileRecord.git(null, "https://h/r.git", "my branch", null)).isEmpty());
		assertFalse(messages(ProfileSection.SKILLS, ProfileRecord.git(null, "https://h/r.git", null, "../x")).isEmpty());
		assertFalse(messages(ProfileSection.SKILLS, ProfileRecord.git(null, "https://h/r.git", null, "/etc")).isEmpty());
		assertTrue(messages(ProfileSection.SKILLS, ProfileRecord.git(null, "https://h/r.git", "v1.2", "skills/review")).isEmpty());
	}

	@Test
	void theSameEntryTwiceInOneSectionIsReportedAtTheSecond() {
		var profile = named("P");
		profile.getHarness().getEnvironment().add(ProfileRecord.text("TOKEN", "a"));
		profile.getHarness().getEnvironment().add(ProfileRecord.text("token", "b"));

		var problems = ProfileValidator.validate(profile, List.of());

		assertEquals(1, problems.size());
		assertEquals(ProfileSection.ENVIRONMENT, problems.getFirst().section());
		assertEquals(1, problems.getFirst().index());
	}

	@Test
	void mcpServerNamesAreChecked() {
		var profile = named("P");
		profile.getHarness().setMcpServers(List.of(McpServerSpec.of("files", "mcp-files", List.of()),
				McpServerSpec.of("Files", "other", List.of())));
		assertEquals(1, ProfileValidator.validate(profile, List.of()).size());
	}

	@Test
	void normalisingDropsWhatBelongsToAnotherSource() {
		var record = ProfileRecord.git("  Name ", "https://h/r.git", " main ", "docs");
		record.setText("left over");
		record.setKind(RecordKind.FILE);
		record.normalize();

		assertEquals("Name", record.getName());
		assertEquals("docs", record.getPath());
		assertNull(record.getText());
		assertNull(record.getRepository());
		assertNull(record.getRef());
	}

	@Test
	void anUnnamedRecordIsNamedAfterItsContent() {
		assertEquals("CONVENTIONS.md", ProfileRecord.file(null, "C:\\docs\\CONVENTIONS.md").displayName());
		assertEquals("repo/docs/A.md", ProfileRecord.git(null, "git@github.com:org/repo.git", null, "docs/A.md").displayName());
		assertEquals("first line", ProfileRecord.text(null, "  first line\nsecond").displayName());
		assertEquals("2 lines", ProfileRecord.text("Doc", "a\nb").detail());
		assertEquals("https://h/r.git @ main : x", ProfileRecord.git(null, "https://h/r.git", "main", "x").detail());
	}
}
