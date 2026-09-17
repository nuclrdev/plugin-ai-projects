package dev.nuclr.plugin.core.ai.projects.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.profile.ExtraFolders;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/** Folders agents may also work in, besides the project folder. */
class ExtraFoldersTest {

	private static final String HOME = File.separatorChar == '\\' ? "C:\\Users\\dev" : "/home/dev";

	private static String underHome(String rest) {
		return HOME + File.separator + rest.replace('/', File.separatorChar);
	}

	@Test
	void aLeadingTildeIsTheHomeFolderWithEitherSeparator() {
		assertEquals(HOME, ExtraFolders.expandHome("~", HOME));
		assertEquals(underHome(".m2"), ExtraFolders.expandHome("~/.m2", HOME));
		assertEquals(underHome(".gradle/caches"), ExtraFolders.expandHome(" ~\\.gradle\\caches ", HOME));
		assertEquals("~other/x", ExtraFolders.expandHome("~other/x", HOME), "only the user's own home");
	}

	@Test
	void aChosenFolderUnderTheHomeFolderIsWrittenPortably() {
		assertEquals("~/.m2", ExtraFolders.collapseHome(underHome(".m2"), HOME));
		assertEquals("~/work/libs", ExtraFolders.collapseHome(underHome("work/libs"), HOME));
		assertEquals("~", ExtraFolders.collapseHome(HOME, HOME));
		assertEquals(HOME + "2", ExtraFolders.collapseHome(HOME + "2", HOME), "a sibling is not under it");
	}

	@Test
	void relativeFoldersAreRefusedButOtherSystemsAbsolutePathsAreNot() {
		for (var folder : List.of("~", "~/.m2", "/opt/libs", "C:\\libs", "d:/libs", "\\\\server\\share")) {
			assertTrue(ExtraFolders.isUsable(folder), folder);
		}
		for (var folder : List.of("libs", "../libs", ".m2")) {
			assertFalse(ExtraFolders.isUsable(folder), folder);
		}

		var profile = new Profile();
		profile.setName("P");
		profile.getHarness().getExtraFolders().add(ProfileRecord.file(null, "libs"));
		assertTrue(ProfileValidator.validate(profile, List.of()).getFirst().message().contains("relative"));
	}

	@Test
	void switchedOnFoldersArePassedWithAddDirToClaudeAndCodexButNotPi() {
		var harness = new Profile.Harness();
		harness.getExtraFolders().add(ProfileRecord.file(null, "~/.m2"));
		var off = ProfileRecord.file(null, "/opt/off");
		off.setEnabled(false);
		harness.getExtraFolders().add(off);
		harness.getExtraFolders().add(ProfileRecord.file(null, "/opt/my libs"));
		harness.getExtraFolders().add(ProfileRecord.file(null, "~/.m2"));

		var folders = ExtraFolders.resolve(harness, HOME);
		assertEquals(List.of(underHome(".m2"), "/opt/my libs"), folders, "switched on, expanded, once each");

		var expected = List.of("--add-dir", underHome(".m2"), "--add-dir", "/opt/my libs");
		assertEquals(expected, AgentConnectors.of(AgentProvider.CLAUDE_CODE).extraFolderArguments(folders));
		assertEquals(expected, AgentConnectors.of(AgentProvider.CODEX).extraFolderArguments(folders));
		assertEquals(List.of(), AgentConnectors.of(AgentProvider.PI).extraFolderArguments(folders));
		assertTrue(AgentConnectors.of(AgentProvider.CODEX).extraFoldersMeaning(AccessMode.FULL_ACCESS)
				.contains("no difference"));
	}

	@Test
	void profilesSavedWithAllowedRootsKeepTheirFolders() throws IOException {
		var profile = Json.fromJson("""
				{"name": "Old", "harness": {"allowedRoots": [{"kind": "FILE", "path": "/srv/shared"}]}}""",
				Profile.class);
		assertEquals("/srv/shared", profile.getHarness().getExtraFolders().getFirst().getPath());
		assertTrue(Json.toJson(profile).contains("extraFolders"));
	}
}
