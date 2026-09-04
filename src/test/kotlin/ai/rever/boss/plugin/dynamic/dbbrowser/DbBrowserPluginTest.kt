package ai.rever.boss.plugin.dynamic.dbbrowser

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Unit tests for [DbBrowserMcpToolProvider] and [DbBrowserDynamicPlugin].
 *
 * These tests run against an in-memory SQLite database so no real credentials
 * or running database are required. The SQLite driver (org.xerial:sqlite-jdbc)
 * must be on the test runtime classpath (declared in build.gradle.kts).
 *
 * Tests are organized by tool:
 *  - Metadata and tool registration
 *  - db_connect: validation, driver checks, in-memory fallback
 *  - db_list_tables: metadata query
 *  - db_query: read-only enforcement, result serialization, row limit
 *  - db_execute: mutating statements, RBAC annotation
 *  - Error handling: unknown connection, malformed SQL
 */
class DbBrowserPluginTest {

    // ── Plugin metadata ────────────────────────────────────────────────────────

    @Test
    fun testPluginMetadata() {
        val plugin = DbBrowserDynamicPlugin()
        assertEquals("ai.rever.boss.plugin.dynamic.dbbrowser", plugin.pluginId)
        assertEquals("Database Browser", plugin.displayName)
        assertEquals("1.0.0", plugin.version)
        assertEquals("Risa Labs", plugin.author)
        assertTrue(plugin.url.startsWith("https://"), "plugin.url must start with https://")
    }

    // ── Tool registration ──────────────────────────────────────────────────────

    @Test
    fun testFourToolsRegistered() {
        val provider = DbBrowserMcpToolProvider()
        val tools = provider.tools()
        assertEquals(4, tools.size, "Expected exactly 4 MCP tools")
    }

    @Test
    fun testToolNames() {
        val provider = DbBrowserMcpToolProvider()
        val names = provider.tools().map { it.name }.toSet()
        assertTrue("db_connect" in names)
        assertTrue("db_list_tables" in names)
        assertTrue("db_query" in names)
        assertTrue("db_execute" in names)
    }

    @Test
    fun testNoReservedNamesUsed() {
        val reservedNames = setOf(
            "list_tabs", "get_active_tab", "list_panes", "read_scrollback",
            "search_output", "get_last_command", "read_debug_console",
            "send_input", "send_signal", "run_in_panel", "run_command",
            "show_image", "manage_tools", "run_in_sidebar", "cli",
        )
        val names = DbBrowserMcpToolProvider().tools().map { it.name }.toSet()
        val conflicts = names intersect reservedNames
        assertTrue(conflicts.isEmpty(), "Tool names conflict with BossTerm reserved names: $conflicts")
    }

    @Test
    fun testToolNamesFollowSnakeCaseConvention() {
        for (tool in DbBrowserMcpToolProvider().tools()) {
            assertTrue(
                tool.name.matches(Regex("[a-z][a-z0-9_]+")),
                "Tool '${tool.name}' must be snake_case",
            )
        }
    }

    @Test
    fun testInputSchemasPresent() {
        for (tool in DbBrowserMcpToolProvider().tools()) {
            val schema = tool.inputSchema
            assertNotNull(schema, "Tool '${tool.name}' must have an inputSchema")
            assertTrue(schema.contains("\"type\""), "'${tool.name}' schema must declare 'type'")
            assertTrue(schema.contains("\"required\""), "'${tool.name}' schema must declare 'required'")
        }
    }

    @Test
    fun testReadOnlyFlags() {
        val tools = DbBrowserMcpToolProvider().tools().associateBy { it.name }
        assertFalse(tools["db_connect"]!!.readOnly, "db_connect stores a secret — not read-only")
        assertTrue(tools["db_list_tables"]!!.readOnly, "db_list_tables is read-only")
        assertTrue(tools["db_query"]!!.readOnly, "db_query is read-only")
        assertFalse(tools["db_execute"]!!.readOnly, "db_execute mutates — not read-only")
    }

    @Test
    fun testDbExecuteDescriptionMentionsDbWrite() {
        val tool = DbBrowserMcpToolProvider().tools().first { it.name == "db_execute" }
        assertTrue(tool.description.contains("db.write"), "db_execute description must mention db.write permission")
        assertTrue(tool.description.contains("OFF by default"), "db_execute must state it is OFF by default")
    }

    @Test
    fun testDbExecuteRequiresDbWritePermission() {
        val tool = DbBrowserMcpToolProvider().tools().first { it.name == "db_execute" }
        assertTrue(tool.requiredPermissions?.contains("db.write") == true, "db_execute must enforce db.write via RBAC")
    }

    @Test
    fun testDbQueryDescriptionMentionsDbRead() {
        val tool = DbBrowserMcpToolProvider().tools().first { it.name == "db_query" }
        assertTrue(tool.description.contains("db.read"))
    }

    @Test
    fun testProviderIdMatchesPluginId() {
        val plugin = DbBrowserDynamicPlugin()
        val provider = DbBrowserMcpToolProvider()
        assertEquals(plugin.pluginId, provider.providerId)
    }

    // ── Setup / teardown ───────────────────────────────────────────────────────

    @BeforeTest
    fun clearInMemoryConnections() {
        DbBrowserMcpToolProvider.inMemoryConnections.clear()
    }

    // ── db_connect ─────────────────────────────────────────────────────────────

    @Test
    fun testConnectMissingConnectionString() = runBlocking {
        val result = findTool("db_connect").invoke(args())
        assertTrue(result.isError)
        assertTrue(result.text.contains("connectionString"))
    }

    @Test
    fun testConnectMissingDriver() = runBlocking {
        val result = findTool("db_connect").invoke(args("connectionString" to "jdbc:sqlite::memory:"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("driver"))
    }

    @Test
    fun testConnectInvalidDriver() = runBlocking {
        val result = findTool("db_connect").invoke(
            args("connectionString" to "jdbc:sqlite::memory:", "driver" to "oracle"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("oracle"))
    }

    @Test
    fun testConnectWrongPrefixForDriver() = runBlocking {
        val result = findTool("db_connect").invoke(
            args("connectionString" to "jdbc:postgresql://localhost/db", "driver" to "sqlite"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("jdbc:sqlite:"))
    }

    @Test
    fun testConnectSqliteInMemorySucceeds() = runBlocking {
        val result = findTool("db_connect").invoke(
            args("connectionString" to "jdbc:sqlite::memory:", "driver" to "sqlite"),
        )
        assertFalse(result.isError, "SQLite in-memory connect must succeed: ${result.text}")
        assertTrue(result.text.contains("connectionId"))
        assertTrue(result.text.contains("connected"))
        assertEquals(1, DbBrowserMcpToolProvider.inMemoryConnections.size)
    }

    @Test
    fun testConnectReturnsOpaqueIdNotConnectionString() = runBlocking {
        val connStr = "jdbc:sqlite::memory:"
        val result = findTool("db_connect").invoke(
            args("connectionString" to connStr, "driver" to "sqlite"),
        )
        assertFalse(result.isError)
        // Raw connection string must NOT appear in the response (credential safety)
        assertFalse(result.text.contains(connStr), "Raw connection string must not leak in response")
    }

    @Test
    fun testConnectPostgresFailsWithBadCredentials() = runBlocking {
        val result = findTool("db_connect").invoke(
            args(
                "connectionString" to "jdbc:postgresql://127.0.0.1:5433/nonexistent?user=test&password=bad",
                "driver" to "postgres",
            ),
        )
        // Fails either at driver load (no PG driver in test classpath) or at connection test
        assertTrue(result.isError, "Connecting to a non-existent Postgres must fail")
        assertTrue(result.text.contains("error"))
    }

    // ── db_list_tables ─────────────────────────────────────────────────────────

    @Test
    fun testListTablesMissingConnectionId() = runBlocking {
        val result = findTool("db_list_tables").invoke(args())
        assertTrue(result.isError)
        assertTrue(result.text.contains("connectionId"))
    }

    @Test
    fun testListTablesUnknownConnectionId() = runBlocking {
        val result = findTool("db_list_tables").invoke(args("connectionId" to "ghost"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown connectionId"))
    }

    @Test
    fun testListTablesAfterConnect() = runBlocking {
        val connectionId = connectSqliteInMemory()

        // Create a table so there is something to list
        findTool("db_execute").invoke(
            args(
                "connectionId" to connectionId,
                "sql" to "CREATE TABLE test_table (id INTEGER PRIMARY KEY, name TEXT)",
            ),
        )

        val result = findTool("db_list_tables").invoke(args("connectionId" to connectionId))
        assertFalse(result.isError, "List tables must succeed: ${result.text}")
        assertTrue(result.text.contains("tables"))
    }

    // ── db_query ───────────────────────────────────────────────────────────────

    @Test
    fun testQueryMissingConnectionId() = runBlocking {
        val result = findTool("db_query").invoke(args("sql" to "SELECT 1"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("connectionId"))
    }

    @Test
    fun testQueryMissingSql() = runBlocking {
        val result = findTool("db_query").invoke(args("connectionId" to "x"))
        assertTrue(result.isError)
        assertTrue(result.text.contains("sql"))
    }

    @Test
    fun testQueryRejectsInsert() = runBlocking {
        val connectionId = connectSqliteInMemory()
        val result = findTool("db_query").invoke(
            args("connectionId" to connectionId, "sql" to "INSERT INTO t VALUES (1)"),
        )
        assertTrue(result.isError, "db_query must reject INSERT")
        assertTrue(result.text.contains("db_execute"))
    }

    @Test
    fun testQueryRejectsUpdate() = runBlocking {
        val result = findTool("db_query").invoke(
            args("connectionId" to connectSqliteInMemory(), "sql" to "UPDATE t SET x=1"),
        )
        assertTrue(result.isError, "db_query must reject UPDATE")
    }

    @Test
    fun testQueryRejectsDrop() = runBlocking {
        val result = findTool("db_query").invoke(
            args("connectionId" to connectSqliteInMemory(), "sql" to "DROP TABLE t"),
        )
        assertTrue(result.isError, "db_query must reject DROP")
    }

    @Test
    fun testQueryRejectsDelete() = runBlocking {
        val result = findTool("db_query").invoke(
            args("connectionId" to connectSqliteInMemory(), "sql" to "DELETE FROM t"),
        )
        assertTrue(result.isError, "db_query must reject DELETE")
    }

    @Test
    fun testQueryRejectsCreate() = runBlocking {
        val result = findTool("db_query").invoke(
            args("connectionId" to connectSqliteInMemory(), "sql" to "CREATE TABLE t (id INT)"),
        )
        assertTrue(result.isError, "db_query must reject CREATE TABLE")
    }

    @Test
    fun testQuerySelect1() = runBlocking {
        val connectionId = connectSqliteInMemory()
        val result = findTool("db_query").invoke(
            args("connectionId" to connectionId, "sql" to "SELECT 1 AS answer"),
        )
        assertFalse(result.isError, "SELECT 1 must succeed: ${result.text}")
        assertTrue(result.text.contains("rows"))
        assertTrue(result.text.contains("answer"))
        assertTrue(result.text.contains("rowCount"))
    }

    @Test
    fun testQueryResultEchoesConnectionId() = runBlocking {
        val connectionId = connectSqliteInMemory()
        val result = findTool("db_query").invoke(
            args("connectionId" to connectionId, "sql" to "SELECT 42 AS val"),
        )
        assertFalse(result.isError)
        assertTrue(result.text.contains(connectionId))
    }

    @Test
    fun testQueryUnknownConnectionId() = runBlocking {
        val result = findTool("db_query").invoke(
            args("connectionId" to "not-a-real-id", "sql" to "SELECT 1"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown connectionId"))
    }

    // ── db_execute ─────────────────────────────────────────────────────────────

    @Test
    fun testExecuteMissingArgs() = runBlocking {
        val noConnId = findTool("db_execute").invoke(args("sql" to "CREATE TABLE t (id INT)"))
        assertTrue(noConnId.isError)

        val noSql = findTool("db_execute").invoke(args("connectionId" to "x"))
        assertTrue(noSql.isError)
    }

    @Test
    fun testExecuteCreateAndInsert() = runBlocking {
        val connectionId = connectSqliteInMemory()

        val create = findTool("db_execute").invoke(
            args("connectionId" to connectionId, "sql" to "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)"),
        )
        assertFalse(create.isError, "CREATE TABLE must succeed: ${create.text}")

        val insert = findTool("db_execute").invoke(
            args("connectionId" to connectionId, "sql" to "INSERT INTO items (name) VALUES ('alpha')"),
        )
        assertFalse(insert.isError, "INSERT must succeed: ${insert.text}")
        assertTrue(insert.text.contains("rowsAffected"))
    }

    @Test
    fun testExecuteThenQueryRoundTrip() = runBlocking {
        val connectionId = connectSqliteInMemory()
        val execute = findTool("db_execute")
        val query = findTool("db_query")

        execute.invoke(args("connectionId" to connectionId, "sql" to "CREATE TABLE nums (val INTEGER)"))
        execute.invoke(args("connectionId" to connectionId, "sql" to "INSERT INTO nums VALUES (7)"))
        execute.invoke(args("connectionId" to connectionId, "sql" to "INSERT INTO nums VALUES (42)"))

        val result = query.invoke(
            args("connectionId" to connectionId, "sql" to "SELECT val FROM nums ORDER BY val"),
        )
        assertFalse(result.isError, "Query after execute: ${result.text}")
        assertTrue(result.text.contains("7"))
        assertTrue(result.text.contains("42"))
        assertTrue(result.text.contains("\"rowCount\": 2"))
    }

    @Test
    fun testExecuteUnknownConnectionId() = runBlocking {
        val result = findTool("db_execute").invoke(
            args("connectionId" to "ghost", "sql" to "SELECT 1"),
        )
        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown connectionId"))
    }

    // ── Credential safety ──────────────────────────────────────────────────────

    @Test
    fun testConnectionStringNotStoredInToolListResponse() {
        // tools() snapshot must not contain connection strings
        val tools = DbBrowserMcpToolProvider().tools()
        val allText = tools.joinToString { it.name + it.description }
        assertFalse(allText.contains("jdbc:"), "No JDBC URLs must appear in tool metadata")
    }

    // ── dispose clears in-memory map ───────────────────────────────────────────

    @Test
    fun testDisposesClearsInMemoryConnections() = runBlocking {
        connectSqliteInMemory()
        assertTrue(DbBrowserMcpToolProvider.inMemoryConnections.isNotEmpty())

        DbBrowserDynamicPlugin().dispose()

        assertTrue(
            DbBrowserMcpToolProvider.inMemoryConnections.isEmpty(),
            "dispose() must clear in-memory connection map",
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private val sharedProvider = DbBrowserMcpToolProvider(secretDataProvider = null)

    private fun findTool(name: String): McpToolDefinition =
        sharedProvider.tools().first { it.name == name }

    /**
     * Build a [McpToolArgs] from a vararg of key-value string pairs.
     *
     * McpToolArgs is a data class constructed as McpToolArgs(map, raw) per
     * McpToolRegistryImpl.parseArgs. We build the raw JSON and the map together.
     */
    private fun args(vararg pairs: Pair<String, String>): McpToolArgs {
        val map: Map<String, Any?> = mapOf(*pairs)
        val raw = buildString {
            append("{")
            pairs.joinTo(this, ",") { (k, v) -> "\"$k\":\"$v\"" }
            append("}")
        }
        return McpToolArgs(map, raw)
    }

    /**
     * Invoke a tool's handler and return the result.
     */
    private suspend fun McpToolDefinition.invoke(toolArgs: McpToolArgs) =
        handler.call(toolArgs)

    /**
     * Connects to an in-memory SQLite database and returns the connectionId.
     */
    private suspend fun connectSqliteInMemory(): String {
        val tempDb = java.io.File.createTempFile("testdb", ".sqlite")
        tempDb.deleteOnExit()
        val result = findTool("db_connect").invoke(
            args("connectionString" to "jdbc:sqlite:${tempDb.absolutePath}", "driver" to "sqlite"),
        )
        assertFalse(result.isError, "SQLite connect: ${result.text}")
        val match = Regex(""""connectionId"\s*:\s*"([^"]+)"""").find(result.text)
        return checkNotNull(match?.groupValues?.get(1)) { "No connectionId in: ${result.text}" }
    }
}
