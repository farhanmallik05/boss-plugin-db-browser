# boss-plugin-db-browser

> A BOSS plugin that brings a **visual database browser and SQL query editor** directly into your BOSS workspace — with full MCP tool exposure and fine-grained RBAC permission gating.

---

## What it does

**Database Browser** adds a left-sidebar panel to BOSS that lets you:

- **Connect** to PostgreSQL or SQLite databases with credentials stored securely in BOSS's Secret Manager (no plaintext ever touches disk or tool output)
- **Browse tables** — click a connection to list all user tables with schema/type metadata
- **Run read queries** — a built-in SQL editor with a Run button that calls `db_query` and renders results in a paginated grid
- **Execute mutations** — `db_execute` for INSERT / UPDATE / DELETE / DDL, gated behind `db.write` which is **off by default**

All four operations are also exposed as **MCP tools** so any AI agent attached to your terminal can query your DB directly.

---

## MCP Tool List

| Tool | Description | Required Permission |
|------|-------------|-------------------|
| `db_connect` | Store a DB connection securely via Secret Manager, returns opaque `connectionId` | *(none — any authenticated user)* |
| `db_list_tables` | List all user tables in the connected DB | `db.read` |
| `db_query` | Execute a read-only SELECT (rejects INSERT/UPDATE/DELETE/DDL) | `db.read` |
| `db_execute` | Execute mutating SQL (INSERT, UPDATE, DELETE, DDL) | `db.write` |

Tools appear in Toolbox → MCP as `mcp__boss__db_connect`, `mcp__boss__db_list_tables`, etc.

### Input / Output shapes

**`db_connect`**
```json
// Input
{ "connectionString": "jdbc:sqlite:/path/to/db.sqlite", "driver": "sqlite" }
// Output (credentials are NEVER echoed back)
{ "connectionId": "abc123", "status": "connected", "driver": "sqlite" }
```

**`db_list_tables`**
```json
// Input
{ "connectionId": "abc123" }
// Output
{ "connectionId": "abc123", "tables": [{ "schema": "main", "table": "users", "type": "TABLE" }] }
```

**`db_query`**
```json
// Input
{ "connectionId": "abc123", "sql": "SELECT * FROM users LIMIT 10" }
// Output
{ "connectionId": "abc123", "rowCount": 3, "truncated": false,
  "rows": [{ "id": "1", "name": "Alice" }] }
```

**`db_execute`**
```json
// Input
{ "connectionId": "abc123", "sql": "INSERT INTO users (name) VALUES ('Bob')" }
// Output
{ "connectionId": "abc123", "rowsAffected": 1 }
```

---

## RBAC Model

This plugin introduces two new fine-grained permissions to the BOSS RBAC catalog:

| Permission | Effect | Default |
|-----------|--------|---------|
| `db.read` | Grants access to `db_list_tables` and `db_query`. Required to even see the plugin panel. | **Must be granted by an admin** |
| `db.write` | Unlocks `db_execute` in both the UI and MCP registry. | **OFF by default** |

### How it works

Permissions are declared in `plugin.json` under `definedPermissions` and auto-registered (as ungranted) when the plugin is first published to the store. An admin then grants them to the appropriate roles via the **role-creation** plugin.

```json
// plugin.json (excerpt)
"requiredPermissions": ["db.read"],
"definedPermissions": [
  { "name": "db.read",  "description": "Read and query database schemas and tables" },
  { "name": "db.write", "description": "Execute database mutations and write operations" }
]
```

The enforcement is **host-side**, not just UI-hidden:

- `db_list_tables` and `db_query` are registered with `McpToolDefinition.withRbac(requiredPermissions = ["db.read"])` — the MCP registry drops them from the tool list if the user lacks `db.read`.
- `db_execute` requires `db.write`. When that permission is absent, the tool is **completely absent** from the MCP registry, not just greyed-out. Toggling it off mid-session causes the next tool-list refresh to omit it.

### Credential safety

`db_connect` stores the raw connection string in BOSS's **Secret Manager plugin** (encrypted at rest, never returned in tool output). All subsequent tool calls reference only the opaque `connectionId`. The connection string never appears in:
- MCP tool responses
- Logs
- The sidebar UI

---

## Demo — Hot-reload + Permission Gating

### Prerequisites
- BOSS desktop app running in dev mode (`./gradlew run` from `BossConsole`)
- A local SQLite or Postgres instance
- `db.read` granted to your user role

### Steps

**1. Install the plugin (hot-reload, no restart)**
```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-db-browser-*.jar ~/.boss_debug/plugins/
```
Open Toolbox → the **Database Browser** panel appears immediately in the left sidebar.

**2. Connect to a SQLite DB**

Click **+ Add Connection** in the sidebar, paste `jdbc:sqlite:/tmp/mydb.sqlite`, select `sqlite`, click Connect.  
The sidebar shows the connection. Click it → tables appear in the list below.

**3. Run a read query with `db.write` disabled**

Type `SELECT * FROM my_table` in the query editor, click **Run** — results appear in the grid.  
Open Toolbox → MCP: you see `db_connect`, `db_list_tables`, `db_query` — **`db_execute` is absent**.

**4. Enable `db.write` and confirm `db_execute` appears**

Admin: open role-creation plugin → grant `db.write` to your role → save.  
Your session picks it up on next permission refresh.  
Open Toolbox → MCP: **`db_execute` now appears**.  
Run `INSERT INTO my_table (col) VALUES ('test')` via the MCP tool — `rowsAffected: 1`.

**5. Uninstall — tools vanish instantly**

Toolbox → Database Browser → Disable (or Remove).  
All four `mcp__boss__db_*` tools disappear from the MCP registry immediately — no agent restart needed.

---

## Project Structure

```
boss-plugin-db-browser/
├── build.gradle.kts
├── settings.gradle.kts
├── plugin.manifest.json                         # top-level manifest (source of truth)
├── src/main/
│   ├── kotlin/ai/rever/boss/plugin/dynamic/dbbrowser/
│   │   ├── DbBrowserDynamicPlugin.kt            # plugin entry point
│   │   ├── DbBrowserComponent.kt                # Compose UI panel
│   │   ├── DbBrowserInfo.kt                     # PanelInfo definition
│   │   └── DbBrowserMcpToolProvider.kt          # 4 MCP tools + RBAC
│   └── resources/META-INF/boss-plugin/
│       └── plugin.json                          # embedded manifest (synced at build)
├── src/test/kotlin/.../
│   ├── DbBrowserPluginTest.kt                   # 40+ unit tests (SQLite in-memory)
│   └── DbBrowserUITest.kt                       # Compose UI smoke test
└── .github/workflows/
    ├── build.yml                                # delegates to shared plugin-release workflow
    └── claude-code-review.yml                   # AI code review on PRs
```

---

## Building

```bash
# Build the plugin JAR
./gradlew buildPluginJar

# Run the test suite (uses in-memory SQLite, no live DB needed)
./gradlew test

# Install locally for development
cp build/libs/boss-plugin-db-browser-*.jar ~/.boss_debug/plugins/
```

---

## Requirements

- BOSS desktop app ≥ 9.2.20
- boss-plugin-api ≥ 1.0.51
- `db.read` permission granted to the user's role
- (Optional) `db.write` for mutation tools

---

## License

MIT
