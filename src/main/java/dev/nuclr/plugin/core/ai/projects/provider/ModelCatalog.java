package dev.nuclr.plugin.core.ai.projects.provider;

import java.util.List;
import java.util.Optional;

/**
 * The models a provider offers on this machine, and where the list came from.
 *
 * @param provider the provider
 * @param models   the models, in the order the provider gives them
 * @param live     whether the list was read from the installed CLI rather than built in
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
	 * @param efforts       the reasoning efforts it accepts; empty when unknown, meaning
	 *                      whatever the provider accepts
	 * @param defaultEffort the effort it uses when none is given, or {@code null}
	 */
	public record Model(String id, String label, List<String> efforts, String defaultEffort) {

		/** Defensive copy. */
		public Model {
			efforts = efforts == null ? List.of() : List.copyOf(efforts);
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
	 * @return the efforts, lowest first
	 */
	public List<String> effortsFor(String modelId) {
		return model(modelId).map(Model::efforts).filter(efforts -> !efforts.isEmpty())
				.orElse(provider.efforts());
	}
}
