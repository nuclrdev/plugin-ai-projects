package dev.nuclr.plugin.core.ai.projects.provider;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a provider offers on this machine - its models, and for each one the
 * reasoning efforts and access modes it accepts - and where that came from.
 *
 * @param provider the provider
 * @param models   the models, in the order the provider gives them
 * @param live     whether this was read from the installed CLI rather than built in
 * @param note     one line for the editor: the source, or why the CLI could not be asked
 */
public record ModelCatalog(AgentProvider provider, List<Model> models, boolean live, String note) {

	/** Defensive copy. */
	public ModelCatalog {
		models = List.copyOf(models);
	}

	/**
	 * One model.
	 *
	 * @param id            the value the CLI takes
	 * @param label         a readable name, possibly the id itself
	 * @param description   one line about it, or {@code null}
	 * @param efforts       the reasoning efforts it accepts: {@code null} when not known
	 *                      (the provider's whole vocabulary applies), empty when it has none
	 * @param defaultEffort the effort it uses when none is given, or {@code null}
	 * @param accessModes   the access modes it supports, or {@code null} when every mode the
	 *                      provider supports applies
	 */
	public record Model(String id, String label, String description, List<String> efforts, String defaultEffort,
			Set<AccessMode> accessModes) {

		/** Defensive copies, keeping "unknown" distinct from "none". */
		public Model {
			efforts = efforts == null ? null : List.copyOf(efforts);
			accessModes = accessModes == null ? null : Set.copyOf(accessModes);
		}

		/**
		 * A model known only by name.
		 *
		 * @param id    the value the CLI takes
		 * @param label a readable name
		 * @return the model, with nothing known about its efforts or access modes
		 */
		public static Model named(String id, String label) {
			return new Model(id, label, null, null, null, null);
		}
	}

	/**
	 * A model by id, ignoring case.
	 *
	 * @param id the id
	 * @return the model, or empty
	 */
	public Optional<Model> model(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		return models.stream().filter(model -> model.id().equalsIgnoreCase(id.trim())).findFirst();
	}

	/**
	 * The efforts to offer for a model: its own when known, otherwise the provider's.
	 *
	 * @param modelId the chosen model, possibly blank
	 * @return the efforts, lowest first; empty when the model has no effort setting
	 */
	public List<String> effortsFor(String modelId) {
		return model(modelId).map(Model::efforts).orElse(provider.efforts());
	}

	/**
	 * Whether an access mode can be used with a model.
	 *
	 * @param modelId the chosen model, possibly blank
	 * @param mode    the mode
	 * @return whether both the provider and the model support it
	 */
	public boolean supports(String modelId, AccessMode mode) {
		if (!provider.supports(mode)) {
			return false;
		}
		return model(modelId).map(Model::accessModes).map(modes -> modes.contains(mode)).orElse(true);
	}
}
