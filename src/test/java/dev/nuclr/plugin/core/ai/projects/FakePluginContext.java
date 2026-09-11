package dev.nuclr.plugin.core.ai.projects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;

/**
 * An in-memory {@link NuclrPluginContext} for tests: settings that are a map,
 * an event bus that records what was emitted, and no keyring at all.
 *
 * <p>The plugins are written against the SDK interfaces rather than against
 * Commander, so this is enough to drive them end to end without a host.
 */
public final class FakePluginContext implements NuclrPluginContext {

	/** One recorded emission. */
	public record Emission(String type, Map<String, Object> payload) {
	}

	/** Settings backed by a map, with a settable developer-mode flag. */
	public static final class FakeSettings implements NuclrSettings {

		private final Map<String, Object> values = new LinkedHashMap<>();

		@Override
		public void set(String namespace, String key, Object value) {
			values.put(namespace + "/" + key, value);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T get(String namespace, String key) {
			return (T) values.get(namespace + "/" + key);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T getOrDefault(String namespace, String key, T defaultValue) {
			var value = values.get(namespace + "/" + key);
			return value == null ? defaultValue : (T) value;
		}

		@Override
		public boolean isDeveloperModeOn() {
			return true;
		}
	}

	/** An event bus that records emissions and dispatches to subscribers. */
	public static final class FakeEventBus implements NuclrEventBus {

		private final List<Emission> emissions = new ArrayList<>();
		private final List<NuclrEventListener> listeners = new ArrayList<>();

		/** Everything emitted so far, in order. */
		public List<Emission> emissions() {
			return List.copyOf(emissions);
		}

		/**
		 * Emissions of one type.
		 *
		 * @param type the event type
		 * @return the matching emissions
		 */
		public List<Emission> of(String type) {
			return emissions.stream().filter(emission -> emission.type().equals(type)).toList();
		}

		/** Forget everything recorded. */
		public void clear() {
			emissions.clear();
		}

		@Override
		public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
			emissions.add(new Emission(type, event == null ? Map.of() : new LinkedHashMap<>(event)));
			for (var listener : List.copyOf(listeners)) {
				if (listener.isMessageSupported(type)) {
					listener.handleMessage(source, type, event, callback);
				}
			}
		}

		@Override
		public void emit(Object source, String type, Map<String, Object> event) {
			emit(source, type, event, null);
		}

		@Override
		public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) {
			emit(null, type, event, callback);
		}

		@Override
		public void emit(String type, NuclrPluginCallback callback) {
			emit(null, type, Map.of(), callback);
		}

		@Override
		public void emit(String type) {
			emit(null, type, Map.of(), null);
		}

		@Override
		public void subscribe(NuclrEventListener listener) {
			listeners.add(listener);
		}

		@Override
		public void unsubscribe(NuclrEventListener listener) {
			listeners.remove(listener);
		}

		/** How many listeners are currently registered. */
		public int listenerCount() {
			return listeners.size();
		}
	}

	private final FakeSettings settings = new FakeSettings();
	private final FakeEventBus eventBus = new FakeEventBus();
	private final NuclrThemeScheme theme = new NuclrThemeScheme("Test", Map.of());

	/** The settings this context hands out. */
	public FakeSettings settings() {
		return settings;
	}

	/** The event bus this context hands out. */
	public FakeEventBus bus() {
		return eventBus;
	}

	@Override
	public NuclrCredentialStore getCredentialStore() {
		return new NuclrCredentialStore() {

			@Override
			public Optional<String> get(String key) {
				return Optional.empty();
			}

			@Override
			public void set(String key, String secret) {
				throw new UnsupportedOperationException("No keyring in tests");
			}

			@Override
			public void delete(String key) {
				// Nothing is ever stored, so deleting is always a successful no-op.
			}
		};
	}

	@Override
	public NuclrEventBus getEventBus() {
		return eventBus;
	}

	@Override
	public NuclrThemeScheme getTheme() {
		return theme;
	}

	@Override
	public NuclrSettings getSettings() {
		return settings;
	}

	@Override
	public Locale getLocale() {
		return Locale.ROOT;
	}
}
