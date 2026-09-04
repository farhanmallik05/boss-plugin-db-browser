# CLAUDE.md — boss-plugin-db-browser

Plugin conventions for Claude Code reviewing or modifying this repo.

## Plugin id

`ai.rever.boss.plugin.dynamic.dbbrowser`

## boss-plugin-api contract rules

- **Declare `compileOnly`**, never bundle: `boss-plugin-api`, Compose, coroutines,
  serialization, Decompose. They come from the host classloader at runtime.
- **Resolve all `PluginContext` providers lazily** — they may be `null` (older host
  or missing feature). Always handle `null` with fallback UI; never crash.
- **Use `context.pluginScope`** for coroutines. It is cancelled on plugin dispose.
- **`tools()` is a snapshot**, queried once at registration. Gate runtime-varying
  availability inside the handler, not by mutating the returned list.
- **Do not call `unregisterMcpToolProvider` in `dispose()`** — `TrackingPluginContext`
  auto-unregisters everything.

## MCP tool naming

Tool names must be `db_<verb>` snake_case (e.g. `db_list_tables`, `db_query`).
The MCP server prefixes them automatically as `mcp__boss__db_<verb>`.
Never use the `boss_` prefix.

## Permissions

`db.read` — required to see the panel (in `requiredPermissions`).
`db.write` — write-side capability, enforced per-tool via `.withRbac()`.
Database calls are NEVER security boundaries; Supabase RLS is the real gate.

## Version

`build.gradle.kts` is the single source of truth. The `processResources` block
rewrites `plugin.json`'s `version` and `apiVersion` at build time. Never hand-edit
the version in `plugin.json`.
