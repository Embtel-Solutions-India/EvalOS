# Memory Maintenance

## Discovery Model

- Core principle: progressive discovery through references, building a graph of memories.
- Initially, agents are provided with the list of all memories (names only).
- Agents should read `mem:core` as the top-level entry point (graph root).
  This memory should contain references to other memories covering major project domains.
  The referenced memories shall, in turn, shall contain references to even more specific memories, and so on.
  The depth of the graph shall depend on the project complexity.
- Use topics/folders to group related memories in order to make the content structure explicit.
  Folders can mirror project structure (e.g. modules like frontend/backend) or topics like debugging, architecture, etc.
- Memory references must use a mem: prefix inside backticks, e.g. `mem:frontend/core`.
  The surrounding text should clearly indicate when to read the memory/which content to expect.
  The text should provide more precise guidance than the memory name alone, 
  i.e. avoid a reference like "frontend debugging: `mem:frontend/debugging` and instead make clear which aspects of frontend debugging are covered.
- Memories themselves should not contain information about when to read them; this is the responsibility of the referring memory.

## Style

Dense agent notes, not prose docs. Prefer invariants, terse bullets. 
Avoid obvious context, rationale, and examples unless they prevent likely mistakes. 
Keep guidance durable and generalizable, not task-local.

## Add/update threshold

Add or update memories only with stable, non-obvious project conventions that avoid complex rediscovery in the future.
Do not add: quick-read facts; generic language/framework knowledge; one-off task notes; volatile line-level details; behavior likely to change soon.

## Update cadence (non-optional)

Every meaningful codebase change updates the memories it invalidated, in the same step as the code —
not "later". A memory that still describes replaced behavior is worse than no memory, because it is
trusted. Routing:

- backend domain/services → `mem:backend/core`; case lifecycle + transitions → `mem:backend/lifecycle`
- entities, migrations, repositories, converters → `mem:backend/persistence`
- auth, roles, scoping, encryption → `mem:backend/security`; GHL and Drive edges → `mem:backend/webhooks`
- frontend structure/conventions → `mem:frontend/core`
- stack or dependency change → `mem:tech_stack`; command change → `mem:suggested_commands`
- convention change → `mem:conventions`; verification-step change → `mem:task_completion`
- a new domain large enough to stand alone → new memory, linked from `mem:core`

**Changed decisions are edits, not additions.** Rewrite the statement that is now wrong; never append
a contradicting note beside it. The add/update threshold above still gates what earns a memory at all —
a change to task-local detail earns nothing.

## Maintenance Actions

- Renaming memories: References are updated automatically if handled via Serena's memory rename tool.
- Checking for stale memories (e.g. after deletion): Call `serena memories check` for a report.