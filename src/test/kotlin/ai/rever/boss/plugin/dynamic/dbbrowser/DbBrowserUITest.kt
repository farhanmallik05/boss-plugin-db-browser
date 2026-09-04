package ai.rever.boss.plugin.dynamic.dbbrowser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry

class DbBrowserUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        DbBrowserMcpToolProvider.inMemoryConnections.clear()
    }

    @Test
    fun testTableBrowserDisplaysEmptyStateInitially() {
        val provider = DbBrowserMcpToolProvider(secretDataProvider = null)
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle)

        composeTestRule.setContent {
            val component = DbBrowserComponent(ctx, DbBrowserInfo, provider)
            component.Content()
        }

        // We expect it to prompt the user to select or add a connection
        composeTestRule.onNodeWithText("No active connection").assertIsDisplayed()
    }
}
