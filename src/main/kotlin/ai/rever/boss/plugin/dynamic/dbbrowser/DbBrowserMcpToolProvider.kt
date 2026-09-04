package ai.rever.boss.plugin.dynamic.dbbrowser

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.SecretDataProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * MCP tool provider for the Database Browser plugin.
 *
 * Exposes four tools to in-terminal agents (mcp__boss__db_*):
 *  - [db_connect]     — stores a connection string via BOSS Secret Manager; returns a connectionId
 *  - [db_list_tables] — lists tables in the connected database (requires db.read)
 *  - [db_query]       — executes a read-only SQL query (requires db.read)
 *  - [db_execute]     — executes a mutating SQL statement (requires db.write; OFF by default)
 *
 * **Credential safety**: raw connection strings are never returned to agents. The
 * [db_connect] tool stores the connection string as a BOSS secret keyed by
 * `db-browser/<connectionId>` and returns only the opaque `connectionId`. All
 * subsequent tools resolve the secret internally via [SecretDataProvider].
 *
 * **RBAC**: [db_execute] is gated by `db.write` (declared ungranted in plugin.json).
 * The host's McpToolRegistryImpl enforces this live — the tool is invisible to agents
 * whose signed-in user does not hold `db.write`. Admins bypass the check implicitly.
 *
 * **Blocking I/O**: every JDBC call is wrapped in [withContext(Dispatchers.IO)] to avoid
 * blocking the host's coroutine scheduler. The host wraps each handler in a 60 s
 * `withTimeout`; long-running queries are therefore bounded.
 *
 * Registered by [DbBrowserDynamicPlugin]; auto-cleaned up by TrackingPluginContext on unload.
 */
class DbBrowserMcpToolProvider(
    override val providerId: String = "ai.rever.boss.plugin.dynamic.dbbrowser",
    /** Nullable: older hosts may not provide the Secret Manager. Degrades gracefully. */
    private val secretDataProvider: SecretDataProvider? = null,
) : McpToolProvider {

    // ── JSON Schema constants ─────────────────────────────────────────────────

    private val connectInputSchema = """
        {
          "type": "object",
          "properties": {
            "connectionString": {
              "type": "string",
              "description": "JDBC connection string. Formats: 'jdbc:postgresql://host:5432/db?user=u&password=p' or 'jdbc:sqlite:/path/to/file.db'"
            },
            "driver": {
              "type": "string",
              "enum": ["postgres", "sqlite"],
              "description": "Database driver: 'postgres' for PostgreSQL, 'sqlite' for SQLite"
            }
          },
          "required": ["connectionString", "driver"]
        }
    """.trimIndent()

    private val connectionIdInputSchema = """
        {
          "type": "object",
          "properties": {
            "connectionId": {
              "type": "string",
              "description": "Connection ID returned by db_connect."
            }
          },
          "required": ["connectionId"]
        }
    """.trimIndent()

    private val queryInputSchema = """
        {
          "type": "object",
          "properties": {
            "connectionId": {
              "type": "string",
              "description": "Connection ID returned by db_connect."
            },
            "sql": {
              "type": "string",
              "description": "SQL SELECT statement to execute. Must be a read-only query."
            }
          },
          "required": ["connectionId", "sql"]
        }
    """.trimIndent()

    private val executeInputSchema = """
        {
          "type": "object",
          "properties": {
            "connectionId": {
              "type": "string",
              "description": "Connection ID returned by db_connect."
            },
            "sql": {
              "type": "string",
              "description": "SQL statement to execute (INSERT, UPDATE, DELETE, DDL). Requires db.write permission."
            }
          },
          "required": ["connectionId", "sql"]
        }
    """.trimIndent()

    // ── Tool list ─────────────────────────────────────────────────────────────

    /**
     * Snapshot of all four tools.
     *
     * Per PLUGIN_DEVELOPMENT.md §9: tools() is called once at registration and must
     * be a stable snapshot. Gate runtime-varying availability inside the handler.
     */
    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "db_connect",
            description = """
                Store a database connection string securely via BOSS Secret Manager.
                Returns an opaque connectionId for use by subsequent db_* tools.
                Raw credentials are never returned.
                Supported drivers: 'postgres', 'sqlite'.
            """.trimIndent(),
            inputSchema = connectInputSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                handleConnect(
                    connectionString = args.string("connectionString"),
                    driver = args.string("driver"),
                )
            },
        ),

        McpToolDefinition.withRbac(
            name = "db_list_tables",
            description = """
                List all user tables in the database identified by connectionId.
                Returns JSON: {"connectionId": "...", "tables": [{"schema": "", "table": "", "type": ""}]}.
                Requires db.read permission.
            """.trimIndent(),
            inputSchema = connectionIdInputSchema,
            readOnly = true,
            handler = McpToolHandler { args ->
                handleListTables(connectionId = args.string("connectionId"))
            },
            requiredPermissions = listOf("db.read")
        ),

        McpToolDefinition.withRbac(
            name = "db_query",
            description = """
                Execute a read-only SQL SELECT query on the database identified by connectionId.
                Returns JSON: {"connectionId": "...", "rowCount": N, "truncated": false, "rows": [...]}.
                Maximum 500 rows returned; add a LIMIT clause to control result size.
                Requires db.read permission. Mutating statements (INSERT/UPDATE/DELETE/DDL) are rejected.
            """.trimIndent(),
            inputSchema = queryInputSchema,
            readOnly = true,
            handler = McpToolHandler { args ->
                handleQuery(
                    connectionId = args.string("connectionId"),
                    sql = args.string("sql"),
                )
            },
            requiredPermissions = listOf("db.read")
        ),

        McpToolDefinition.withRbac(
            name = "db_execute",
            description = """
                Execute a mutating SQL statement (INSERT, UPDATE, DELETE, DDL) on the database.
                Returns JSON: {"connectionId": "...", "rowsAffected": N}.
                REQUIRES db.write permission — this permission is OFF by default and must
                be granted by an admin before this tool is visible to agents.
                Use db_query for read-only SELECT statements.
            """.trimIndent(),
            inputSchema = executeInputSchema,
            readOnly = false,
            handler = McpToolHandler { args ->
                handleExecute(
                    connectionId = args.string("connectionId"),
                    sql = args.string("sql"),
                )
            },
            requiredPermissions = listOf("db.write")
        ),
    )

    // ── Handlers ───────────────────────────────────────────────────────────────

    /**
     * Validates connectivity, generates an opaque connectionId, and stores the
     * connection string as a BOSS secret. Raw credentials are never returned.
     */
    private suspend fun handleConnect(
        connectionString: String?,
        driver: String?,
    ): McpToolResult {
        if (connectionString.isNullOrBlank()) {
            return err("Missing required argument: connectionString")
        }
        if (driver.isNullOrBlank()) {
            return err("Missing required argument: driver")
        }
        val normalizedDriver = driver.lowercase()
        if (normalizedDriver !in setOf("postgres", "sqlite")) {
            return err("Invalid driver '$driver'. Must be 'postgres' or 'sqlite'.")
        }

        // Load the JDBC driver class so DriverManager can instantiate it
        val driverLoadResult = runCatching {
            when (normalizedDriver) {
                "postgres" -> Class.forName("org.postgresql.Driver")
                "sqlite"   -> Class.forName("org.sqlite.JDBC")
                else       -> error("unreachable")
            }
        }
        if (driverLoadResult.isFailure) {
            return err(
                "JDBC driver for '$normalizedDriver' is not bundled with this plugin version. " +
                    "Error: ${driverLoadResult.exceptionOrNull()?.message}",
            )
        }

        // Validate prefix matches the declared driver
        val expectedPrefix = when (normalizedDriver) {
            "postgres" -> "jdbc:postgresql://"
            "sqlite"   -> "jdbc:sqlite:"
            else       -> error("unreachable")
        }
        if (!connectionString.startsWith(expectedPrefix)) {
            return err("connectionString must start with '$expectedPrefix' for driver '$normalizedDriver'.")
        }

        // Test connectivity before persisting anything
        val pingResult = withContext(Dispatchers.IO) {
            runCatching { DriverManager.getConnection(connectionString).use { /* ping */ } }
        }
        if (pingResult.isFailure) {
            val cause = pingResult.exceptionOrNull()
            val msg = when (cause) {
                is SQLException -> "Connection failed: ${cause.message} (SQLState=${cause.sqlState})"
                else            -> "Connection error: ${cause?.message ?: "unknown"}"
            }
            return err(msg)
        }

        // Assign an opaque ID and persist credentials via BOSS Secret Manager.
        // Fallback: in-memory map when secretDataProvider is null (older host).
        val connectionId = UUID.randomUUID().toString()

        if (secretDataProvider == null) {
            inMemoryConnections[connectionId] = connectionString
        } else {
            val storeResult = secretDataProvider.createSecret(
                CreateSecretRequestData(
                    // 'website' acts as the lookup key; 'password' holds the connection string.
                    website = secretKeyFor(connectionId),
                    username = normalizedDriver,
                    password = connectionString,
                    notes = "DB Browser connection ($normalizedDriver) — managed automatically by boss-plugin-db-browser.",
                    expirationDate = null,
                    tags = listOf("db-browser", normalizedDriver),
                    twofaEnabled = false,
                    twofaType = null,
                    recoveryCodes = emptyList(),
                ),
            )
            if (storeResult.isFailure) {
                return err("Failed to store connection securely: ${storeResult.exceptionOrNull()?.message}")
            }
        }

        return ok("""{"connectionId": "$connectionId", "driver": "$normalizedDriver", "status": "connected", "message": "Connection verified and stored. Use connectionId with db_list_tables, db_query, and db_execute."}""")
    }

    /**
     * Lists all user tables via JDBC DatabaseMetaData.
     */
    internal suspend fun handleListTables(connectionId: String?): McpToolResult {
        if (connectionId.isNullOrBlank()) return err("Missing required argument: connectionId")
        val connStr = resolveConnectionString(connectionId)
            ?: return err("Unknown connectionId '$connectionId'. Call db_connect first.")

        return withContext(Dispatchers.IO) {
            runCatching {
                DriverManager.getConnection(connStr).use { conn ->
                    val tables = mutableListOf<String>()
                    conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                        while (rs.next()) {
                            val schema = rs.getString("TABLE_SCHEM")?.escapeJson() ?: ""
                            val table  = rs.getString("TABLE_NAME")?.escapeJson() ?: ""
                            val type   = rs.getString("TABLE_TYPE")?.escapeJson() ?: "TABLE"
                            tables += """{"schema": "$schema", "table": "$table", "type": "$type"}"""
                        }
                    }
                    ok("""{"connectionId": "$connectionId", "tables": [${tables.joinToString(",")}]}""")
                }
            }.getOrElse { cause -> err(jdbcErrorMessage(cause)) }
        }
    }

    /**
     * Executes a read-only SELECT query. Rejects mutating statements before hitting the DB.
     */
    internal suspend fun handleQuery(connectionId: String?, sql: String?): McpToolResult {
        if (connectionId.isNullOrBlank()) return err("Missing required argument: connectionId")
        if (sql.isNullOrBlank()) return err("Missing required argument: sql")
        if (isMutatingStatement(sql)) {
            return err("db_query only accepts read-only SELECT statements. Use db_execute for mutating SQL (requires db.write permission).")
        }
        val connStr = resolveConnectionString(connectionId)
            ?: return err("Unknown connectionId '$connectionId'. Call db_connect first.")

        return withContext(Dispatchers.IO) {
            runCatching {
                DriverManager.getConnection(connStr).use { conn ->
                    runCatching { conn.isReadOnly = true }
                    conn.createStatement().use { stmt ->
                        stmt.maxRows = MAX_ROWS
                        stmt.executeQuery(sql).use { rs -> rs.toJsonResult(connectionId, sql) }
                    }
                }
            }.getOrElse { cause -> err(jdbcErrorMessage(cause)) }
        }
    }

    /**
     * Executes a mutating SQL statement. Gated by db.write RBAC at the host level.
     */
    private suspend fun handleExecute(connectionId: String?, sql: String?): McpToolResult {
        if (connectionId.isNullOrBlank()) return err("Missing required argument: connectionId")
        if (sql.isNullOrBlank()) return err("Missing required argument: sql")
        val connStr = resolveConnectionString(connectionId)
            ?: return err("Unknown connectionId '$connectionId'. Call db_connect first.")

        return withContext(Dispatchers.IO) {
            runCatching {
                DriverManager.getConnection(connStr).use { conn ->
                    conn.createStatement().use { stmt ->
                        val affected = runCatching { stmt.executeUpdate(sql) }
                            .getOrElse { cause ->
                                // DDL on some drivers returns -1 or throws with errorCode=0
                                if (cause is SQLException && cause.errorCode == 0) 0 else throw cause
                            }
                        ok("""{"connectionId": "$connectionId", "rowsAffected": $affected, "sql": "${sql.take(200).escapeJson()}"}""")
                    }
                }
            }.getOrElse { cause -> err(jdbcErrorMessage(cause)) }
        }
    }

    /**
     * Retrieves all saved connections from the secret store and in-memory fallback.
     */
    internal suspend fun getSavedConnections(): List<DbConnectionInfo> {
        val list = mutableListOf<DbConnectionInfo>()
        if (secretDataProvider != null) {
            val result = secretDataProvider.getUserSecrets(limit = 1000, offset = 0)
            if (result.isSuccess) {
                result.getOrNull()?.data?.filter { it.tags.contains("db-browser") }?.forEach {
                    val connId = it.website.removePrefix("db-browser/")
                    list.add(DbConnectionInfo(connId, it.username, it.notes ?: ""))
                }
            }
        }
        inMemoryConnections.forEach { (connId, url) ->
            if (list.none { it.id == connId }) {
                list.add(DbConnectionInfo(connId, "in-memory", url))
            }
        }
        return list
    }

    // ── Tool Handlers ─────────────────────────────────────────────────────────

    /**
     * Resolves a connectionId to its raw JDBC connection string.
     * Prefers BOSS Secret Manager (connection string stored in the 'password' field,
     * keyed by 'website' = "db-browser/<connectionId>"). Falls back to the
     * [inMemoryConnections] map when [secretDataProvider] is unavailable.
     */
    private suspend fun resolveConnectionString(connectionId: String): String? {
        if (secretDataProvider != null) {
            val key = secretKeyFor(connectionId)
            val result = secretDataProvider.searchSecrets(query = key, limit = 1, offset = 0)
            if (result.isSuccess) {
                val entry = result.getOrNull()?.data?.firstOrNull { it.website == key }
                if (entry != null) return entry.password
            }
        }
        return inMemoryConnections[connectionId]
    }

    private fun secretKeyFor(connectionId: String) = "db-browser/$connectionId"

    /** Returns true if the SQL starts with a mutating keyword (case-insensitive). */
    private fun isMutatingStatement(sql: String): Boolean {
        val upper = sql.trimStart().uppercase()
        return MUTATING_PREFIXES.any { upper.startsWith(it) }
    }

    /** Formats a JDBC/SQL exception into a user-friendly error string. */
    private fun jdbcErrorMessage(cause: Throwable): String = when (cause) {
        is SQLException -> "SQL error [${cause.sqlState ?: "?"}/${cause.errorCode}]: ${cause.message}"
        else            -> "${cause::class.simpleName}: ${cause.message}"
    }

    /** Serializes a [ResultSet] to the standard JSON query result shape. */
    private fun ResultSet.toJsonResult(connectionId: String, sql: String): McpToolResult {
        val meta = metaData
        val columnCount = meta.columnCount
        val columns = (1..columnCount).map { meta.getColumnLabel(it) }
        val rows = mutableListOf<String>()
        var truncated = false

        while (next()) {
            if (rows.size >= MAX_ROWS) { truncated = true; break }
            val fields = columns.mapIndexed { i, col ->
                val v = getObject(i + 1)
                val jsonValue = when (v) {
                    null       -> "null"
                    is Number  -> v.toString()
                    is Boolean -> v.toString()
                    else       -> "\"${v.toString().escapeJson()}\""
                }
                "\"${col.escapeJson()}\": $jsonValue"
            }
            rows += "{${fields.joinToString(", ")}}"
        }

        return ok(
            """{"connectionId": "$connectionId", "sql": "${sql.take(200).escapeJson()}", "rowCount": ${rows.size}, "truncated": $truncated, "rows": [${rows.joinToString(",")}]}""",
        )
    }

    private fun ok(text: String): McpToolResult = McpToolResult(text = text, isError = false)
    private fun err(message: String): McpToolResult =
        McpToolResult(text = """{"error": "${message.escapeJson()}"}""", isError = true)

    private fun String.escapeJson(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    companion object {
        /** Maximum rows returned by db_query to prevent agent context overflow. */
        internal const val MAX_ROWS = 500

        /**
         * Fallback in-memory credential store used when [SecretDataProvider] is unavailable
         * (older host). Cleared by [DbBrowserDynamicPlugin.dispose].
         *
         * Key = connectionId, value = raw JDBC connection string.
         * Not persisted across plugin reloads or restarts.
         */
        internal val inMemoryConnections: MutableMap<String, String> = mutableMapOf()

        /** SQL keywords that indicate a mutating statement. */
        private val MUTATING_PREFIXES = setOf(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
            "TRUNCATE", "REPLACE", "MERGE", "UPSERT", "GRANT", "REVOKE",
        )
    }
}
