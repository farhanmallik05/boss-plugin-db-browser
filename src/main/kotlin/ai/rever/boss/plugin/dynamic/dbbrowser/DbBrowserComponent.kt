package ai.rever.boss.plugin.dynamic.dbbrowser

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*


object ThemeTokens {
    val panel = Color(0xFF080B11)
    val raised = Color(0xFF0E141E)
    val line = Color(0xFF1C2432)
    val lineStrong = Color(0xFF2E3B4F)
    val textPrimary = Color(0xFFE7EDFA)
    val textSecondary = Color(0xFF9AA7BB)
    val textMuted = Color(0xFF69768B)
    val signal = Color(0xFF0F5BFF)
    val onSignal = Color(0xFFFFFFFF)
    val ok = Color(0xFF2FD98A)
    val alert = Color(0xFFFF5D5D)
    val ink = Color(0xFF05070B)
    val signalText = Color(0xFF88A9FF)
    val data = Color(0xFF88A9FF)
    
    val sm = 8.dp
    val md = 12.dp
    
    val spaceSm = 8.dp
    val spaceMd = 12.dp
    val spaceLg = 16.dp
    
    val radiusCard = 5.dp
    val radiusInput = 3.dp
}

class DbBrowserComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val provider: DbBrowserMcpToolProvider
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        
        var connections by remember { mutableStateOf<List<DbConnectionInfo>>(emptyList()) }
        var selectedConnection by remember { mutableStateOf<DbConnectionInfo?>(null) }
        
        var tables by remember { mutableStateOf<List<String>>(emptyList()) }
        var queryText by remember { mutableStateOf("") }
        var queryResult by remember { mutableStateOf<QueryResult?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        // Load connections on mount
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                connections = provider.getSavedConnections()
            }
        }

        fun runQuery(sql: String = queryText) {
            val conn = selectedConnection ?: return
            if (sql.isBlank()) return
            
            queryText = sql
            error = null
            queryResult = null
            
            scope.launch(Dispatchers.IO) {
                val result = provider.handleQuery(conn.id, sql)
                if (result.isError) {
                        error = "Query failed: ${result.text}"
                    } else {
                        try {
                            val parsed = Json.parseToJsonElement(result.text).jsonObject
                            val err = parsed["error"]?.jsonPrimitive?.content
                            if (err != null) {
                                error = err
                            } else {
                                val rowsArray = parsed["rows"]?.jsonArray ?: JsonArray(emptyList())
                                val cols = mutableSetOf<String>()
                                val rowsList = mutableListOf<Map<String, String>>()
                                
                                for (rowElem in rowsArray) {
                                    val rowObj = rowElem.jsonObject
                                    val rowMap = mutableMapOf<String, String>()
                                    for ((k, v) in rowObj) {
                                        cols.add(k)
                                        rowMap[k] = v.jsonPrimitive.content
                                    }
                                    rowsList.add(rowMap)
                                }
                                queryResult = QueryResult(cols.toList(), rowsList)
                            }
                        } catch (e: Exception) {
                            error = "Failed to parse query result: ${e.message}"
                        }
                    }
            }
        }

        fun selectConnection(conn: DbConnectionInfo) {
            selectedConnection = conn
            tables = emptyList()
            queryResult = null
            error = null
            
            scope.launch(Dispatchers.IO) {
                val result = provider.handleListTables(conn.id)
                if (result.isError) {
                        error = "Failed to list tables: ${result.text}"
                    } else {
                        try {
                            val parsed = Json.parseToJsonElement(result.text).jsonObject
                            val err = parsed["error"]?.jsonPrimitive?.content
                            if (err != null) {
                                error = err
                            } else {
                                val tableArray = parsed["tables"]?.jsonArray
                                tables = tableArray?.mapNotNull { it.jsonObject["table"]?.jsonPrimitive?.content } ?: emptyList()
                            }
                        } catch (e: Exception) {
                            error = "Failed to parse tables: ${e.message}"
                        }
                    }
            }
        }

        Row(modifier = Modifier.fillMaxSize().background(ThemeTokens.ink)) {
            // Left Sidebar
            Column(
                modifier = Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(ThemeTokens.panel)
                    .border(width = 1.dp, color = ThemeTokens.line)
            ) {
                Text(
                    text = "CONNECTIONS",
                    color = ThemeTokens.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(connections) { conn ->
                        val isSelected = selectedConnection?.id == conn.id
                        val bgColor = if (isSelected) ThemeTokens.raised else Color.Transparent
                        val textColor = if (isSelected) ThemeTokens.signalText else ThemeTokens.textPrimary
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor)
                                .clickable { selectConnection(conn) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = conn.driver, color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (conn.description.isNotBlank()) {
                                    Text(text = conn.description, color = ThemeTokens.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            
            // Main Content
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selectedConnection == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Select a connection to begin", color = ThemeTokens.textSecondary)
                    }
                } else {
                    // Query Editor
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.3f)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Query Editor", color = ThemeTokens.textPrimary, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { runQuery() },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = ThemeTokens.signal,
                                    contentColor = ThemeTokens.onSignal
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run")
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ThemeTokens.panel, RoundedCornerShape(ThemeTokens.md))
                                .border(1.dp, ThemeTokens.lineStrong, RoundedCornerShape(ThemeTokens.md))
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = queryText,
                                onValueChange = { queryText = it },
                                textStyle = TextStyle(color = ThemeTokens.textPrimary, fontSize = 14.sp),
                                cursorBrush = SolidColor(ThemeTokens.signal),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    
                    // Error Message
                    if (error != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .background(ThemeTokens.alert.copy(alpha = 0.1f), RoundedCornerShape(ThemeTokens.md))
                                .border(1.dp, ThemeTokens.alert, RoundedCornerShape(ThemeTokens.md))
                                .padding(12.dp)
                        ) {
                            Text(text = error!!, color = ThemeTokens.alert, fontSize = 13.sp)
                        }
                    }
                    
                    // Tables
                    if (tables.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(text = "Tables", color = ThemeTokens.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(tables) { table ->
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(ThemeTokens.sm))
                                            .background(ThemeTokens.raised)
                                            .border(1.dp, ThemeTokens.line, RoundedCornerShape(ThemeTokens.sm))
                                            .clickable { runQuery("SELECT * FROM $table LIMIT 100") }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.TableChart, contentDescription = null, tint = ThemeTokens.data, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = table, color = ThemeTokens.textPrimary, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Results
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.7f)
                            .padding(16.dp)
                    ) {
                        Text(text = "Results", color = ThemeTokens.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ThemeTokens.panel, RoundedCornerShape(ThemeTokens.md))
                                .border(1.dp, ThemeTokens.line, RoundedCornerShape(ThemeTokens.md))
                        ) {
                            if (queryResult != null) {
                                val res = queryResult!!
                                if (res.columns.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No rows returned", color = ThemeTokens.textSecondary)
                                    }
                                } else {
                                    val horizontalScroll = rememberScrollState()
                                    val verticalScroll = rememberScrollState()
                                    
                                    Column(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                                        // Header row
                                        Row(
                                            modifier = Modifier
                                                .background(ThemeTokens.raised)
                                                .border(width = 1.dp, color = ThemeTokens.line)
                                        ) {
                                            for (col in res.columns) {
                                                Box(modifier = Modifier.width(150.dp).padding(12.dp)) {
                                                    Text(text = col, color = ThemeTokens.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                        
                                        // Data rows
                                        Column(modifier = Modifier.verticalScroll(verticalScroll)) {
                                            for ((index, row) in res.rows.withIndex()) {
                                                Row(
                                                    modifier = Modifier.background(
                                                        if (index % 2 == 0) ThemeTokens.panel else ThemeTokens.raised.copy(alpha = 0.3f)
                                                    )
                                                ) {
                                                    for (col in res.columns) {
                                                        Box(
                                                            modifier = Modifier
                                                                .width(150.dp)
                                                                .padding(12.dp)
                                                        ) {
                                                            Text(
                                                                text = row[col] ?: "null", 
                                                                color = if (row[col] == null) ThemeTokens.textMuted else ThemeTokens.textPrimary,
                                                                fontSize = 13.sp
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Run a query or click a table to see results", color = ThemeTokens.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class QueryResult(
    val columns: List<String>,
    val rows: List<Map<String, String>>
)
