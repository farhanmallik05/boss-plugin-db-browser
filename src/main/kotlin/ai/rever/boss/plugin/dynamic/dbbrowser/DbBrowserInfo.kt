package ai.rever.boss.plugin.dynamic.dbbrowser

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.Panel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector

object DbBrowserInfo : PanelInfo {
    override val id = PanelId("db-browser", defaultOrder = 60, pluginId = "ai.rever.boss.plugin.dynamic.dbbrowser")
    override val displayName = "Database Browser"
    override val icon: ImageVector = Icons.Outlined.Storage
    override val defaultSlotPosition = Panel.bottom
}

data class DbConnectionInfo(
    val id: String,
    val driver: String,
    val description: String,
)
