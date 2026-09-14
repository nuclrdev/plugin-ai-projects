# Nuclr Commander — AI Projects

An AI project is a persistent desktop for coding agents. This plugin adds a
**file panel** that lists your projects, a **quick view** that summarises one,
and a **fullscreen screen** that is one project's desktop — a `JDesktopPane` with an
internal frame per agent, a foldable project sidebar, and an explicit view of
what every agent is actually run with and told.

The abstraction it is built around:

```
AI Project → Project Harness → Agent Definitions → Agent Sessions → Agent Windows
```

## The plugin entries

| Entry | Type | What it is |
|---|---|---|
| `…ai.projects.panel` | FilePanel | Lists projects: name, status, agents, harness, model, storage, last opened, root. Alt+F1 → **AI Projects**. |
| `…ai.projects.quickview` | QuickView | Ctrl+Q on a project row: its harness, model, agents and last session, without opening it. |
| `…ai.projects.screen` | Fullscreen (Editor) | One project's desktop. Opened by pressing Enter on a project row. |

A project row is deliberately **not** a folder and carries no local path, so
Enter opens the desktop rather than navigating into the directory.

### Getting a project into the list

- **F7** — New project, choosing a root folder and where its metadata lives.
- **F5 from the other panel** — copy a folder onto the AI Projects panel and it
  becomes a project. Commander is a file manager; being sent to a file chooser to
  find a folder the other pane is already showing would be the wrong gesture. A
  folder that already carries a definition is adopted rather than re-created.
- **Add existing project…** — for a folder that is not in either pane.

Columns sort: Name, Status, Last opened, Agents and Harness are declared as sort
descriptors, so clicking a header or pressing Ctrl+F3..F8 works the way it does
in every other panel. An empty list shows one row saying so, and Enter on it
creates a project.

## Harness and context

The **harness** says how an agent is run: executable, startup arguments,
provider, model, environment, permissions, MCP servers, allowed roots and shared
instructions. It is defined once on the project; a template may override parts of
it, and an agent may override parts again.

- `null` means *inherit*. An empty list or blank string means *explicitly
  nothing* — an agent can be given no MCP servers at all, which is not the same
  as failing to mention any.
- Environment merges by key and MCP servers merge by name, so an agent can change
  one variable or swap one server without restating the rest. Every other list
  replaces wholesale.
- **Harness…** shows the merged result with a `From` column naming the level that
  set each field, and edits it. The editor serves both levels: on the project it
  simply sets fields, on an agent every field carries an **Inherit** box, because
  the inherit / explicitly-empty distinction cannot be expressed by leaving a text
  box blank.

Changing a harness or a context tells you plainly when agents are already
running: they were launched with the previous configuration and only a restart
picks the change up.

The terminal provider applies the command, startup arguments, environment and
approved working directory directly, and delivers the context as a briefing (see
below). Provider-specific permissions, model selection and MCP wiring still
require support from the selected CLI, and the launch notice names whatever was
not applied.

The **context** says what an agent is told: instruction documents, skills,
injected files and context variables. Unlike the harness it is **additive** — the
project's context reaches every agent and each agent appends its own. That is
what makes several specialised agents in one project worth having.

**Resolved Context** shows the effective project configuration after merging,
including the environment variables the plugin itself sets at launch, with the
contributing level and a mark on any file that is referenced but missing.

### The briefing: how an agent is actually told

Resolving context only describes it. At every start the terminal provider builds
a **briefing** from the same resolved context - the content of every instruction,
skill and injected file, the context variables, and a list of anything referenced
but missing - writes it to `sessions/<agent>.briefing.md`, and hands it to the CLI
the way that CLI's own `--help` documents:

| CLI | Mechanism |
|---|---|
| Claude Code | `--append-system-prompt <briefing>` |
| Pi | `--append-system-prompt <briefing file>` |
| Codex | the initial `[PROMPT]`: the briefing opens the session, and Codex replies "Ready" |
| OpenCode | `OPENCODE_CONFIG_CONTENT` naming the briefing as an instructions file; `--prompt` if you already set that variable |

The CLI is recognised by the executable's name, not the window kind. Through a
`.cmd`/`.bat` shim, where `cmd.exe` splits arguments at line breaks, or for a
briefing too long for a Windows command line, only a one-line pointer to the file
is passed. A shell or unknown command gets no briefing, and the launch notice says
so. The transcript records how the briefing was delivered. A briefing that cannot
be written stops the start rather than launching an agent without its instructions.

### Allowed roots bound both halves

A project definition is a file, and a file can be hand-edited or arrive from a
colleague, so a reference that climbs out of the project with `..` is refused
rather than followed. **Allowed roots** are the deliberate way to widen that, and
they govern two things at once: where an agent may run, and what it may be
pointed at. One instruction document shared across several checkouts works by
declaring its directory in the harness.

Both questions go through a single containment check, so the launcher and the
Resolved Context view cannot disagree about a path. Symlinks are resolved on both
sides, so a link cannot be used to step outside and a project that itself lives
under one is not wrongly refused.

A reference to a legal place that holds no file still resolves, and shows as
missing. Dropping it would hide the most common configuration mistake there is.

### Linking instructions and skills from elsewhere

Instruction and skill documents are worth sharing: a house style kept in one
repository, a skill written once and used in every project. An **absolute path
(or `~/…`) to a Markdown file** in an instruction or skill list is a deliberate
link, and resolves without declaring its folder as an allowed root — which would
also let agents *run* there, a far wider grant than reading one document.

- **Link instruction…** / **Link skill…** in the sidebar pick files and add them
  to the project context; the Context editor has a **Link Markdown file…** button
  on the same two tabs for agent- and template-level links.
- Linked documents appear in the Instructions and Skills sections and are marked
  *linked* in Resolved Context. Right-click offers **Unlink** instead of Rename and
  Delete, because the file belongs to the project it came from.
- The link is narrow on purpose, since a definition can arrive from someone else:
  Markdown only (`.md`, `.markdown`), absolute paths only — a `..` climb is still
  refused — and a `.md` symlink whose real target is not Markdown is refused too.
  Injected files are not linkable this way; they still need an allowed root.

## Agent window kinds

`AgentWindowProvider` is the extension point. "Terminal" is one implementation of
it, not a built-in assumption: a log viewer, a task board, an embedded browser or
a diff/review agent are the same shape — a stable `kind()` recorded in
`project.json`, a display name, and a factory.

Shipped kinds: `terminal.codex`, `terminal.claude-code`, `terminal.pi`,
`terminal.opencode`, `terminal.shell`. An agent naming a kind nothing provides
still gets a frame, which says what is missing; its definition is left untouched.

Register another with `AgentWindowRegistry.register(...)`.

## Just a terminal

A plain shell is one of the window kinds, so a terminal in the project folder is
an agent like any other - it just does not need the form filled in:

- the **Terminal** button on the project toolbar,
- **Ctrl+O**, the same key Commander uses for a console in a panel (its own
  handler is guarded on the file panels being visible, so inside this screen the
  key is free),
- **Shift+F2** on the function-key bar,
- **New terminal** in the sidebar's Agents section,
- **Open terminal here** on any folder in Files / Repositories.

It starts immediately, is named `Terminal` (or `Terminal - <folder>`), and gets
the platform shell as an explicit agent-level override so it does not inherit the
project's agent CLI. Its working directory is stored relative to the project root
when it is inside, because `project.json` is the committable half and an absolute
path from one machine is no use in it.

Because it is an ordinary agent it persists with the desktop and can be renamed,
duplicated or deleted like any other - a terminal that vanished on reopen would
be the odd one out on a desktop whose whole point is that it does not.

A folder outside every allowed root would silently fall back to the project root
at launch, so choosing one offers to add it to the allowed roots instead.

## Templates

A project starts with **Coder**, **Reviewer**, **Researcher** and **Architect**.
A template is a window kind plus harness overrides plus context, and its
instruction document is written into the project as a file — templates and their
instructions are the part a team may want to commit. New agent → Coder is one
click on the toolbar or in the sidebar.

## Storage, and what belongs in git

Two layouts, chosen per project when it is created:

- **Project-local** — `<root>/.nuclr/ai-project/`
- **Commander-private** — `~/.nuclr/commander/ai-projects/<id>/`, for a
  repository nothing should be written into.

```
.nuclr/ai-project/
├── project.json      committable — harness, agents, templates, shared context
├── instructions/     committable
├── skills/           committable
├── .gitignore        generated, and ignores the three below
├── desktop.json      runtime — window geometry, sidebar state
├── sessions/         runtime — pid, exit code, command line, per agent
└── transcripts/      runtime — terminal output, per agent
```

Persistence is **continuous**, not tied to a clean shutdown: changes are marked
and a background writer coalesces them a few hundred milliseconds later, writing
atomically through a temporary file. Killing Commander outright costs the last
gesture, not the session.

## Restoration, and being honest about it

Reopening a project restores the exact desktop: the same frames, in the same
places, folded the same way. What it cannot restore is the processes — they were
children of a JVM that has exited, and none of these CLIs can be reattached.

So they are not pretended into life. Every session record is stamped with the
Commander run that wrote it; a record claiming `RUNNING` from an earlier run is
corrected to stopped on sight, and the window says *"Session stopped — the
process did not survive the Commander restart"* above the transcript of what that
session printed, with a Start button. The transcript is the continuity.

## Status and notifications

Each frame's title carries its status and a coloured dot: stopped, starting,
running, waiting for input, finished, failed.

`WAITING_INPUT` is the one inferred value — no CLI reports that it is waiting, so
it is derived from quiet output ending in something prompt-shaped. An idle prompt
is not worth interrupting anyone for, so it only sets the status. A *confirmation*
("(y/n)", "Do you want…", "Allow…") also raises an attention flag on the frame,
in the sidebar, and on the project's row in the file panel.

A flag nobody is looking at is not a notification, so an agent asking for a
decision also flashes Commander's taskbar entry and marks the window title until
it is answered. The desktop's status bar carries the running total, and its
tooltip is the colour legend — including the plain statement that "waiting" is a
good guess rather than something the agent reported.

## Commands

**Per window** — Start/Stop, Restart, Send instruction…, Duplicate, Folder,
Context…, and behind **More** (or a right-click anywhere on the frame): Edit
agent, Harness, Copy all output, Open transcript file, Clear screen, Clear
transcript, text size, Delete agent. Closing a frame does not delete the agent.

**Per project** — New agent (by template), Start all, Stop all, Windows, Tile,
Cascade, Save layout, Reset layout, Broadcast…, Context…, Harness…, Sidebar,
Close project. On the toolbar, on the F-key bar and on the keyboard alike; all
three route through the same methods.

The **Windows** menu lists every agent window, minimised or not, with Minimise
all and Restore all — a `JDesktopPane` drops minimised frames into its
bottom-left corner and offers no way back to one you cannot see.

Keyboard, all on Ctrl+Shift so a terminal keeps what it needs: `N` new agent,
`W` close window, `T` tile, `D` cascade, `S` save layout, `B` broadcast,
`M` minimise all, `K` sidebar, `PageUp`/`PageDown` previous/next window. Every
one is also a button; the keyboard is an accelerator, never the only way in.

**Sidebar** — Agents, Context, Instructions, Skills, Harness, Files/Repositories,
each foldable and each remembering whether it was open, with a filter box that
narrows all six at once. Double-clicking an agent focuses its window (reopening
it if it was closed); double-clicking a skill or instruction opens it in a
document frame. Right-click for rename and delete, for **Save as template**, and
for the harness and context editors.

**Documents** — the skill and instruction editor has Ctrl+S, undo and redo, and
a Reload. Before saving it checks whether the file changed on disk since it was
opened, because agents in this project write files in it; an external change is
put to the user rather than overwritten. Closing the project asks about unsaved
edits instead of discarding them, which is what `JInternalFrame.dispose()` would
otherwise do in silence.

## Before anything irreversible

Stopping agents cannot be undone, so **Stop all** and **Close project** confirm
first, saying how many are running and what survives. Close is on the plugin's
own action (Ctrl+F4) rather than a host shortcut precisely so it *can* confirm —
Commander's teardown cannot be vetoed once it starts. Bare F10 is deliberately
left alone: that is Commander's own "quit the application".

## Desktop backgrounds

The toolbar background picker offers None, Neon Network, Synthwave Grid and
Arcade Starfield. The selected effect is saved with the project's desktop
layout. Effects are isolated behind a paint-only background interface, so new
visuals can be added without coupling them to agent windows or project logic.

## Relationship to workspaces

An AI project is a domain object that owns its own persistent desktop; a
Commander workspace is general UI state. They stay separate. The panel keeps only
which project row it was showing in its workspace state — the desktop belongs to
the project and travels with it.

## Icons

Agents, statuses, sections, commands and context kinds all carry a glyph:
✅ finished, 🟢 running, 🟡 waiting, 🔴 failed, 🤖 agents, 📄 instructions,
🎓 skills, 🔌 tools, ⚙️ harness, and so on.

Getting an emoji onto a Swing label is not as simple as typing one. Java2D draws
a character the font lacks as an empty box rather than falling back, and the
theme font Commander uses on Windows — Segoe UI — contains none of these. So
`Glyphs` declares every glyph with a plainer stand-in and:

- **this plugin'''s own widgets** get the emoji wrapped in an HTML span naming a
  font that can draw it, so the picture comes from the emoji font while the words
  keep the theme'''s;
- **anything handed to the host** — file-panel column values, function-key labels
  — stays plain text and falls back to a symbol, because how the host draws its
  own widgets is the host'''s business.

Force one or the other with `-Dnuclr.ai.glyphs=emoji` or `=symbols`.

One detail worth knowing: the Status column shows a glyph, but the value the
sort comparator reads does not. Sorting by status orders by the word rather than
by whichever codepoint happens to sit in front of it.

## Build

```bash
mvn clean package     # runs the tests, produces target/plugin-ai-projects-<version>.zip
```

Java 25 and Maven 3.9+. The SDK, Jackson, JediTerm and pty4j are all `provided`:
the Commander host supplies them at runtime and none are bundled — a second copy
of pty4j would mean a second extraction of its native helpers. JediTerm resolves
from JetBrains' Maven repository, declared in the POM as the host declares it.

If a host is ever built without the terminal libraries, `TerminalStack` notices
and the terminal kinds report themselves unavailable, rather than the plugin
failing to load.

## Licence

Apache-2.0. See `LICENSE`.
