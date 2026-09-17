package dev.nuclr.plugin.core.ai.projects.ui.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;

/** Editing a secret in a dialog that is then cancelled changes nothing. */
class SecretFieldTest {

	private static final class MemoryStore implements NuclrCredentialStore {

		final Map<String, String> entries = new HashMap<>();

		@Override
		public Optional<String> get(String key) {
			return Optional.ofNullable(entries.get(key));
		}

		@Override
		public void set(String key, String secret) {
			entries.put(key, secret);
		}

		@Override
		public void delete(String key) {
			entries.remove(key);
		}
	}

	@Test
	void clearingASecretInADialogThatIsThenCancelledKeepsItStaged() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		var session = new SecretSession(new ProfileSecrets(new MemoryStore()), profile);
		var key = session.stage("ghp_entered_earlier");

		SwingUtilities.invokeAndWait(() -> {
			var field = new SecretField(session, McpSecret.stored(key));
			clickButton(field, "Clear");
			// The dialog holding the field is cancelled: value() is never called.
		});

		assertTrue(session.isStaged(key), "the server as it was still refers to this secret");

		var server = McpServerSpec.remote("github", McpServerSpec.HTTP, "https://x.example/mcp");
		server.setAuth(McpServerSpec.AUTH_BEARER);
		server.setBearerToken(McpSecret.stored(key));
		profile.getHarness().getMcpServers().add(server);
		assertEquals(List.of(key), session.writeStaged(profile), "so saving still writes it");
	}

	@Test
	void replacingASecretInADialogThatIsThenCancelledKeepsTheOriginalStaged() throws Exception {
		var profile = new Profile();
		profile.setName("P");
		var session = new SecretSession(new ProfileSecrets(new MemoryStore()), profile);
		var key = session.stage("ghp_original");

		SwingUtilities.invokeAndWait(() -> {
			var field = new SecretField(session, McpSecret.stored(key));
			clickButton(field, "Replace");
			// The dialog holding the field is cancelled: value() is never called.
		});

		assertTrue(session.isStaged(key));
	}

	private static void clickButton(java.awt.Container root, String text) {
		for (var child : root.getComponents()) {
			if (child instanceof AbstractButton button && button.getText() != null && button.getText().contains(text)) {
				button.doClick();
				return;
			}
			if (child instanceof java.awt.Container container) {
				clickButton(container, text);
			}
		}
	}
}
