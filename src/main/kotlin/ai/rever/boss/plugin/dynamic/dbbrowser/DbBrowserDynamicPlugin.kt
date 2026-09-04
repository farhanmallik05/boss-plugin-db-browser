package ai.rever.boss.plugin.dynamic.dbbrowser

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Entry point for the Database Browser dynamic plugin.
 *
 * Implements [DynamicPlugin] conforming to boss-plugin-api.
 *
 * On [register]:
 *  - Retrieves [PluginContext.secretDataProvider] (nullable — older hosts may not provide it;
 *    [DbBrowserMcpToolProvider] degrades to in-memory credential storage in that case).
 *  - Registers [DbBrowserMcpToolProvider] which exposes four MCP tools:
 *    db_connect, db_list_tables, db_query (db.read gated), db_execute (db.write gated).
 *  - Registers the [DbBrowserComponent] panel via the panel registry.
 *
 * [dispose] clears the in-memory connection fallback map so credentials do not
 * linger in memory after the plugin is unloaded or disabled.
 *
 * TrackingPluginContext auto-unregisters the McpToolProvider and panel on disable/unload —
 * do NOT call unregisterMcpToolProvider here.
 */
class DbBrowserDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.dbbrowser"
    override val displayName = "Database Browser"
    override val version = "1.0.0"
    override val description = "Database browser and SQL query inspector plugin for BOSS"
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-db-browser"

    override fun register(context: PluginContext) {
        // secretDataProvider is nullable — older hosts may not supply it.
        // DbBrowserMcpToolProvider handles the null case with an in-memory fallback.
        val secretDataProvider = context.secretDataProvider

        val provider = DbBrowserMcpToolProvider(
            providerId = pluginId,
            secretDataProvider = secretDataProvider,
        )
        context.registerMcpToolProvider(provider)

        // Register panel UI
        context.panelRegistry.registerPanel(DbBrowserInfo) { ctx, panelInfo ->
            DbBrowserComponent(ctx, panelInfo, provider)
        }
    }

    override fun dispose() {
        // Clear in-memory credential fallback map so connection strings do not
        // linger in memory after the plugin is unloaded or disabled.
        DbBrowserMcpToolProvider.inMemoryConnections.clear()
    }
}
