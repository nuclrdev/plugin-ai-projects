package dev.nuclr.plugin.core.ai.projects.agent.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What a conversational agent tells its window, whichever CLI it is.
 *
 * <p>Shaped after the Agent Client Protocol's {@code session/update} kinds -
 * message and thought chunks, tool calls and their updates, permission requests,
 * the end of a turn - so a backend for an ACP agent maps onto it one to one, and
 * a backend for a CLI's own protocol (Claude Code's stream-JSON, Codex's
 * app-server, Pi's RPC) translates into the same vocabulary. The window renders
 * only these; it never sees a CLI's wire format.
 *
 * <p>Events are also the conversation's record: they are written to disk as they
 * arrive and replayed into the window when the project is reopened, so every
 * type here must survive a round trip through JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = AgentEvent.SessionStarted.class, name = "session"),
		@JsonSubTypes.Type(value = AgentEvent.UserMessage.class, name = "user"),
		@JsonSubTypes.Type(value = AgentEvent.MessageChunk.class, name = "message"),
		@JsonSubTypes.Type(value = AgentEvent.ThoughtChunk.class, name = "thought"),
		@JsonSubTypes.Type(value = AgentEvent.ToolCall.class, name = "tool"),
		@JsonSubTypes.Type(value = AgentEvent.ToolResult.class, name = "toolResult"),
		@JsonSubTypes.Type(value = AgentEvent.ToolOutput.class, name = "toolOutput"),
		@JsonSubTypes.Type(value = AgentEvent.PermissionRequest.class, name = "permission"),
		@JsonSubTypes.Type(value = AgentEvent.PermissionResolved.class, name = "permissionResolved"),
		@JsonSubTypes.Type(value = AgentEvent.TurnEnded.class, name = "turnEnded"),
		@JsonSubTypes.Type(value = AgentEvent.Notice.class, name = "notice"),
		@JsonSubTypes.Type(value = AgentEvent.CommandsAvailable.class, name = "commands"),
		@JsonSubTypes.Type(value = AgentEvent.Suggestion.class, name = "suggestion"),
		@JsonSubTypes.Type(value = AgentEvent.Image.class, name = "image"),
		@JsonSubTypes.Type(value = AgentEvent.GeneratedFile.class, name = "generatedFile") })
public sealed interface AgentEvent {

	/**
	 * The agent is ready and says who it is.
	 *
	 * @param sessionId      the CLI's own session id, which resumes the conversation later
	 * @param model          the model it is using, or {@code null}
	 * @param permissionMode its permission mode, or {@code null}
	 */
	record SessionStarted(String sessionId, String model, String permissionMode) implements AgentEvent {
	}

	/**
	 * What the user sent.
	 *
	 * <p>A record written before messages could carry attachments has none, and one with
	 * none is written without the field, so the transcript reads the same as it did.
	 *
	 * @param text        the prompt as typed, possibly empty when only attachments were sent
	 * @param attachments the pictures and long pastes sent with it, in the order they were added
	 */
	record UserMessage(String text,
			@JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.List<Attachment> attachments) implements AgentEvent {

		/** A missing text read as empty, a missing list as none, and the list not shared. */
		public UserMessage {
			text = text == null ? "" : text;
			attachments = attachments == null ? java.util.List.of() : java.util.List.copyOf(attachments);
		}

		/**
		 * A message of words alone.
		 *
		 * @param text the prompt
		 */
		public UserMessage(String text) {
			this(text, java.util.List.of());
		}
	}

	/**
	 * Something sent with a message besides its words, kept as a file - in the agent's
	 * runtime folder for a picture or a paste, where it already is for a file - so the
	 * transcript holds a path rather than the content.
	 *
	 * @param kind      what it is, which decides how it reaches the agent
	 * @param path      the file, absolute
	 * @param mediaType its media type, such as {@code image/png} or {@code text/plain}
	 * @param name      what to call it on screen
	 */
	record Attachment(Kind kind, String path, String mediaType, String name) {

		/** What an attachment is. */
		public enum Kind {
			/** A picture, handed to the model as an image. */
			IMAGE,
			/** A long paste, handed to the model as text in front of the message. */
			TEXT,
			/**
			 * A file of the user's, left where it is: the model is given its path and reads it
			 * with its own tools. Attached only when a Quick View plugin can show it, so its
			 * chip has a picture of what it holds.
			 */
			FILE
		}

		/** The file it is kept in. */
		public java.nio.file.Path file() {
			return java.nio.file.Path.of(path);
		}
	}

	/**
	 * A piece of the agent's reply, to be appended to the one before it.
	 *
	 * @param text the piece
	 */
	record MessageChunk(String text) implements AgentEvent {
	}

	/**
	 * A piece of the agent's visible reasoning.
	 *
	 * @param text the piece
	 */
	record ThoughtChunk(String text) implements AgentEvent {
	}

	/**
	 * The agent called a tool.
	 *
	 * @param id       the call's id, which its result names
	 * @param parentId the call this one was made inside - a subagent's - or {@code null}
	 * @param name     the tool, e.g. {@code Bash}
	 * @param title    one line saying what it does, e.g. the command
	 * @param detail   the rest of its input, readable, or empty
	 */
	record ToolCall(String id, String parentId, String name, String title, String detail) implements AgentEvent {
	}

	/**
	 * A tool call finished.
	 *
	 * @param id     the call
	 * @param error  whether it failed
	 * @param output what it returned, possibly cut short
	 */
	record ToolResult(String id, boolean error, String output) implements AgentEvent {
	}

	/**
	 * What a tool call has printed so far, replacing what was shown before. Not kept:
	 * the {@link ToolResult} that follows is the record.
	 *
	 * @param id     the call
	 * @param output everything so far, possibly cut short
	 */
	record ToolOutput(String id, String output) implements AgentEvent {
	}

	/**
	 * An image the agent produced, or one a tool it ran wrote to disk.
	 *
	 * <p>Two shapes, and only one of them is ever written down. A backend that receives
	 * an image inline - a base64 content block - emits it with {@code data} and no
	 * {@code path}; the window writes the bytes into the agent's runtime folder and keeps
	 * the event again with {@code path} and no {@code data}. A megabyte of base64 on
	 * every line of the transcript would be re-read in full each time the window is
	 * rebuilt, for a picture already sitting in a file.
	 *
	 * @param path      where the image is, once it is on disk; {@code null} until then
	 * @param data      the image as base64, before it has been stored; {@code null} after
	 * @param mediaType its media type, such as {@code image/png}, or {@code null}
	 * @param name      what to call it, for the block's header and the save dialog
	 */
	record Image(String path, String data, String mediaType, String name) implements AgentEvent {

		/** Whether this is the unstored shape, still carrying its bytes. */
		public boolean isInline() {
			return path == null && data != null && !data.isBlank();
		}
	}

	/**
	 * A file the agent made during a turn and named in its reply, shown as a card with a
	 * picture of what it holds. Not from the CLI: the window finds these when the turn ends.
	 *
	 * @param path the file, absolute
	 */
	record GeneratedFile(String path) implements AgentEvent {

		/** The file. */
		public java.nio.file.Path file() {
			return java.nio.file.Path.of(path);
		}
	}

	/**
	 * The agent needs the user's permission before it goes on.
	 *
	 * @param requestId what the answer must name
	 * @param toolName  the tool it wants
	 * @param title     one line saying what it wants to do
	 * @param detail    the rest, readable, or empty
	 * @param options   the answers it accepts, in the order to offer them; {@code null}
	 *                  in a record written before there were options, meaning allow or deny
	 */
	record PermissionRequest(String requestId, String toolName, String title, String detail,
			java.util.List<PermissionOption> options) implements AgentEvent {

		/** The options, or allow and deny for a request that named none. */
		public java.util.List<PermissionOption> choices() {
			return options == null || options.isEmpty() ? PermissionOption.ALLOW_OR_DENY : options;
		}
	}

	/**
	 * One answer to a permission request, as ACP's {@code PermissionOption} has it.
	 *
	 * @param id    what the answer sends back
	 * @param label the button's words
	 * @param kind  what it means, which decides how it is drawn
	 */
	record PermissionOption(String id, String label, Kind kind) {

		/** What an answer means. */
		public enum Kind {
			/** Allow this once. */
			ALLOW_ONCE,
			/** Allow this, and its like from now on. */
			ALLOW_ALWAYS,
			/** Refuse this once. */
			REJECT_ONCE,
			/** Refuse this, and its like from now on - or stop the turn. */
			REJECT_ALWAYS;

			/** Whether the answer lets the agent go ahead. */
			public boolean allows() {
				return this == ALLOW_ONCE || this == ALLOW_ALWAYS;
			}
		}

		/** Id of the plain allow, for a request that names no options. */
		public static final String ALLOW = "allow";

		/** Id of the plain deny, for a request that names no options. */
		public static final String DENY = "deny";

		/** The two answers every agent understands. */
		public static final java.util.List<PermissionOption> ALLOW_OR_DENY = java.util.List.of(
				new PermissionOption(ALLOW, "Allow", Kind.ALLOW_ONCE), new PermissionOption(DENY, "Deny", Kind.REJECT_ONCE));
	}

	/**
	 * A permission request was answered.
	 *
	 * @param requestId the request
	 * @param allowed   whether the answer lets the agent go ahead
	 * @param label     the answer's words, or {@code null} in an older record
	 */
	record PermissionResolved(String requestId, boolean allowed, String label) implements AgentEvent {
	}

	/**
	 * The agent finished its turn and is waiting for the user.
	 *
	 * @param error      whether the turn failed
	 * @param message    why, when it failed; otherwise {@code null}
	 * @param costUsd    what the session has cost so far, or {@code null}
	 * @param durationMs how long the turn took, or {@code null}
	 */
	record TurnEnded(boolean error, String message, Double costUsd, Long durationMs) implements AgentEvent {
	}

	/**
	 * The commands this agent answers, as it listed them when the session began.
	 *
	 * <p>Every CLI has slash commands of its own - {@code /compact}, {@code /context},
	 * whatever the project keeps in its commands folder - and the window cannot know them
	 * in advance. Those that it reports are offered in the composer beside the window's
	 * own, and typing one sends it to the agent to run.
	 *
	 * @param commands what it offers, in the order it named them
	 */
	record CommandsAvailable(java.util.List<Command> commands) implements AgentEvent {

		/** Defensive copy, and a missing list read as none. */
		public CommandsAvailable {
			commands = commands == null ? java.util.List.of() : java.util.List.copyOf(commands);
		}
	}

	/**
	 * One command an agent offers.
	 *
	 * @param name        what is typed after the slash
	 * @param description one line about it, or {@code null} when the agent gave none
	 */
	record Command(String name, String description) {
	}

	/**
	 * The message the agent expects the user to send next, offered in the empty composer.
	 *
	 * <p>Not something the agent said on the page: it is taken out of the end of its reply
	 * by {@link SuggestedPrompt}, and kept so a rebuilt window can offer it again.
	 *
	 * @param text the message, one line; empty withdraws an earlier suggestion, as a new
	 *             conversation does
	 */
	record Suggestion(String text) implements AgentEvent {

		/** No suggestion: what came before is no longer offered. */
		public static final Suggestion NONE = new Suggestion("");
	}

	/**
	 * Something the window says about the session rather than the agent saying it.
	 *
	 * @param text  the line
	 * @param error whether it reports a problem
	 */
	record Notice(String text, boolean error) implements AgentEvent {
	}
}
