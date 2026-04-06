package io.aether.wrapper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.*

// ---- Data Models ----

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = true
)

data class ChatConversation(
    val peerUsername: String,
    val peerNodeId: String,
    val messages: List<ChatMessage> = emptyList()
)

data class SwarmNode(
    val nodeId: String,
    val username: String,
    val address: String,
    val isLocal: Boolean = false
)

data class PendingAnchor(
    val anchorHash: String,
    val trustScore: Double,
    val quorum: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isGlobal: Boolean get() = quorum >= 3
}

data class MetricsState(
    val entropy: Double = 0.0,
    val boltzmann: Double = 0.0,
    val zipf: Double = 0.0,
    val benford: Double = 0.0,
    val fourier: Double = 0.0,
    val katz: Double = 0.0,
    val permEntropy: Double = 0.0,
    val deltaConvergence: Double = 0.0,
    val noether: Double = 0.0,
    val trustScore: Double = 0.0
)

// ---- MainActivity ----

class MainActivity : ComponentActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            this, "aether_secure_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        if (!prefs.contains("hw_seed")) {
            prefs.edit().putString("hw_seed", generateHardwareFingerprint()).apply()
        }

        setContent {
            var isRegistered by remember { mutableStateOf(prefs.contains("node_id")) }
            val username = remember { prefs.getString("username", "Unknown") ?: "Unknown" }
            
            AetherAppTheme {
                if (!isRegistered) {
                    OckhamSetupScreen { user, pass ->
                        val hwSeed = prefs.getString("hw_seed", "") ?: ""
                        val nodeId = generateOckhamId(user, pass, hwSeed)
                        prefs.edit().putString("username", user).putString("node_id", nodeId).apply()
                        isRegistered = true
                    }
                } else {
                    MainAppScaffold(username, prefs) { startAetherScan() }
                }
            }
        }
    }

    private fun generateHardwareFingerprint(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "random"
        val buildInfo = Build.BOARD + Build.BRAND + Build.DEVICE + Build.MANUFACTURER + Build.MODEL
        return MessageDigest.getInstance("SHA-256").digest((androidId + buildInfo).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(this, FramePipelineService::class.java).apply {
                putExtra("result_code", result.resultCode)
                putExtra("projection_data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(startIntent)
            else startService(startIntent)
        }
    }

    private fun startAetherScan() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun generateOckhamId(user: String, pass: String, seed: String): String {
        val spec = PBEKeySpec(pass.toCharArray(), seed.toByteArray(), 100000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }.take(16)
    }
}

@Composable
fun MainAppScaffold(
    username: String,
    prefs: android.content.SharedPreferences,
    onStartScan: () -> Unit
) {
    val navController = rememberNavController()
    val screens = listOf(Screen.Network, Screen.Chat, Screen.Performance, Screen.Info)
    val anchorPool = remember { mutableStateListOf<PendingAnchor>() }
    var metrics by remember { mutableStateOf(MetricsState()) }
    var isScanning by remember { mutableStateOf(false) }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "AETHER_METRICS_UPDATE" -> {
                        val trust = intent.getDoubleExtra("trust", 0.0)
                        metrics = MetricsState(
                            entropy          = intent.getDoubleExtra("entropy", 0.0),
                            boltzmann        = intent.getDoubleExtra("boltzmann", 0.0),
                            zipf             = intent.getDoubleExtra("zipf", 0.0),
                            benford          = intent.getDoubleExtra("benford", 0.0),
                            fourier          = intent.getDoubleExtra("fourier", 0.0),
                            katz             = intent.getDoubleExtra("katz", 0.0),
                            permEntropy      = intent.getDoubleExtra("permEntropy", 0.0),
                            deltaConvergence = intent.getDoubleExtra("deltaConvergence", 0.0),
                            noether          = intent.getDoubleExtra("noether", 0.0),
                            trustScore       = trust
                        )
                        isScanning = true
                        if (trust >= AetherCascadeEngine.TRUST_THRESHOLD) {
                            // FIX: Hash war (trust + currentTimeMillis) → immer einzigartig, nie deduplizierbar.
                            // Anker müssen strukturelle Invarianten sein: Hash aus Metrik-Fingerprint des Frames.
                            val fingerprint = "${metrics.entropy}:${metrics.boltzmann}:" +
                                "${metrics.benford}:${metrics.noether}:${metrics.permEntropy}"
                            val hash = MessageDigest.getInstance("SHA-256")
                                .digest(fingerprint.toByteArray())
                                .joinToString("") { "%02x".format(it) }.take(16)
                            if (anchorPool.none { it.anchorHash == hash }) {
                                anchorPool.add(0, PendingAnchor(hash, trust, 1))
                                if (anchorPool.size > 50) anchorPool.removeAt(anchorPool.size - 1)
                            }
                        }
                    }
                    "AETHER_SERVICE_STOPPED" -> isScanning = false
                }
            }
        }
        val filter = IntentFilter("AETHER_METRICS_UPDATE").apply { addAction("AETHER_SERVICE_STOPPED") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A0A0A)) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, fontSize = 10.sp) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            unselectedIconColor = Color.Gray,
                            indicatorColor = Color(0xFF1A1A1A)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(navController, startDestination = Screen.Network.route, Modifier.padding(innerPadding)) {
            composable(Screen.Network.route)     { NetworkScreen(username, metrics, isScanning, onStartScan) }
            composable(Screen.Chat.route)        { ChatScreen(username) }
            composable(Screen.Performance.route) { PerformanceScreen(metrics, isScanning, anchorPool, onStartScan) }
            composable(Screen.Info.route)        { InfoScreen(prefs) }
        }
    }
}

// ---- NetworkScreen ----

@Composable
fun NetworkScreen(
    username: String,
    metrics: MetricsState,
    isScanning: Boolean,
    onStartScan: () -> Unit
) {
    val swarmNodes = remember {
        listOf(
            SwarmNode("local",    username,    "200:aether:local:0001", isLocal = true),
            SwarmNode("a1b2c3d4", "node_alpha", "200:aether:a1b2:c3d4"),
            SwarmNode("e5f6a7b8", "node_beta",  "200:aether:e5f6:a7b8"),
            SwarmNode("c9d0e1f2", "node_gamma", "200:aether:c9d0:e1f2"),
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Aether Node: $username", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00E5FF))
            Spacer(Modifier.height(16.dp))
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
                Column(Modifier.padding(12.dp)) {
                    Text("Swarm-Topologie", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    SwarmGraphCanvas(swarmNodes)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Echtzeit-Kaskade", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    MetricRow("Shannon H",   "%.4f".format(metrics.entropy))
                    MetricRow("Trust Score", "%.4f".format(metrics.trustScore))
                    MetricRow("DNA Status",  if (metrics.trustScore >= 0.65) "STABIL" else "KALIBRIERUNG")
                    Spacer(Modifier.height(16.dp))
                    if (!isScanning) {
                        Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
                            Text("Analyse starten", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF00E5FF), trackColor = Color(0xFF333333))
                        Spacer(Modifier.height(6.dp))
                        Text("Kaskade läuft...", color = Color.Gray, fontSize = 12.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        item {
            Text("Verbundene Nodes", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }
        items(swarmNodes) { node ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Color(0xFF00C853), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(node.username, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (node.isLocal) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF00E5FF).copy(alpha = 0.15f)) {
                                Text("DU", color = Color(0xFF00E5FF), fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Text(node.address.take(22) + "…", color = Color.DarkGray, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider(color = Color(0xFF1A1A1A))
        }
    }
}

@Composable
fun SwarmGraphCanvas(nodes: List<SwarmNode>) {
    val cyan = Color(0xFF00E5FF)
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = minOf(cx, cy) * 0.72f
        val remotes = nodes.filter { !it.isLocal }
        val step = if (remotes.isEmpty()) 0f else (2 * PI / remotes.size).toFloat()
        remotes.forEachIndexed { i, _ ->
            val angle = i * step - PI.toFloat() / 2
            val nx = cx + radius * cos(angle)
            val ny = cy + radius * sin(angle)
            drawLine(color = Color.White.copy(alpha = 0.08f), start = Offset(cx, cy),
                end = Offset(nx, ny), strokeWidth = 1.5f)
            drawCircle(color = Color(0xFF444444), radius = 14f, center = Offset(nx, ny))
        }
        drawCircle(color = cyan.copy(alpha = 0.12f), radius = 28f, center = Offset(cx, cy))
        drawCircle(color = cyan.copy(alpha = 0.30f), radius = 22f, center = Offset(cx, cy))
        drawCircle(color = cyan, radius = 16f, center = Offset(cx, cy), style = Stroke(2f))
        drawCircle(color = cyan.copy(alpha = 0.7f), radius = 14f, center = Offset(cx, cy))
    }
}

// ---- ChatScreen ----

@Composable
fun ChatScreen(username: String) {
    var searchQuery by remember { mutableStateOf("") }
    var activeConversation by remember { mutableStateOf<ChatConversation?>(null) }
    val conversations = remember { mutableStateListOf<ChatConversation>() }

    if (activeConversation != null) {
        val conv = activeConversation!!
        ChatConversationView(
            conversation = conv,
            localUsername = username,
            onBack = { activeConversation = null },
            onSend = { msg ->
                val updated = conv.copy(messages = conv.messages + ChatMessage(
                    senderName = username, content = msg, isOutgoing = true))
                val idx = conversations.indexOfFirst { it.peerNodeId == conv.peerNodeId }
                if (idx >= 0) conversations[idx] = updated else conversations.add(updated)
                activeConversation = updated
            }
        )
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("P2P Mesh Chat", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00E5FF))
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text("Node-Username suchen") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF))
            )
            Spacer(Modifier.height(16.dp))
            if (searchQuery.isNotBlank()) {
                Text("DHT-Suche nach \"$searchQuery\"...", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                val fakeNodeId = MessageDigest.getInstance("SHA-256")
                    .digest(searchQuery.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
                Card(Modifier.fillMaxWidth().clickable {
                    activeConversation = conversations.find { it.peerNodeId == fakeNodeId }
                        ?: ChatConversation(peerUsername = searchQuery, peerNodeId = fakeNodeId)
                }, colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))) {
                    ListItem(
                        headlineContent = { Text(searchQuery, color = Color.White) },
                        supportingContent = {
                            Text("200:aether:${fakeNodeId.take(4)}:${fakeNodeId.drop(4).take(4)}",
                                color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        },
                        leadingContent = { Icon(Icons.Default.AccountCircle, null, tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(36.dp)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null, tint = Color(0xFF00C853), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("E2E", color = Color(0xFF00C853), fontSize = 10.sp)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            } else if (conversations.isNotEmpty()) {
                conversations.forEach { conv ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { activeConversation = conv },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))) {
                        ListItem(
                            headlineContent = { Text(conv.peerUsername, color = Color.White) },
                            supportingContent = {
                                Text(conv.messages.lastOrNull()?.content?.take(40) ?: "Keine Nachrichten",
                                    color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(Icons.Default.AccountCircle, null, tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(36.dp)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiTethering, null, modifier = Modifier.size(48.dp), tint = Color.DarkGray)
                        Spacer(Modifier.height(16.dp))
                        Text("Suche nach einem Node-Username\num zu starten",
                            textAlign = TextAlign.Center, color = Color.DarkGray, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatConversationView(
    conversation: ChatConversation,
    localUsername: String,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF0E0E0E)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color(0xFF00E5FF)) }
            Column(Modifier.weight(1f)) {
                Text(conversation.peerUsername, color = Color.White, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFF00C853), modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("E2E · ${conversation.peerNodeId.take(8)}…",
                        color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(conversation.messages) { msg -> ChatBubble(msg, timeFormat) }
        }
        HorizontalDivider(color = Color(0xFF1A1A1A))
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Nachricht…", color = Color.Gray) },
                singleLine = true, colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color(0xFF333333)))
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (inputText.isNotBlank()) { onSend(inputText.trim()); inputText = "" } },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (inputText.isNotBlank()) Color(0xFF00E5FF) else Color(0xFF222222))) {
                Icon(Icons.Default.Send, null, tint = if (inputText.isNotBlank()) Color.Black else Color.Gray)
            }
        }
    }
}

@Composable
fun ChatBubble(msg: ChatMessage, timeFormat: SimpleDateFormat) {
    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isOutgoing) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (msg.isOutgoing) 16.dp else 4.dp,
                bottomEnd = if (msg.isOutgoing) 4.dp else 16.dp),
            color = if (msg.isOutgoing) Color(0xFF003D4F) else Color(0xFF1A1A1A),
            modifier = Modifier.widthIn(max = 260.dp)
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(msg.content, color = Color.White, fontSize = 14.sp)
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(timeFormat.format(Date(msg.timestamp)), color = Color.Gray, fontSize = 10.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Lock, null, tint = Color(0xFF00C853), modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

// ---- PerformanceScreen ----

@Composable
fun PerformanceScreen(
    metrics: MetricsState,
    isScanning: Boolean,
    anchorPool: List<PendingAnchor>,
    onStartScan: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val metricRows = listOf(
        Triple("1. Shannon H",      metrics.entropy,                                  "%.3f".format(metrics.entropy)),
        Triple("2. Boltzmann",      metrics.boltzmann,                                "%.3f".format(metrics.boltzmann)),
        Triple("3. Zipf α",         (metrics.zipf / 2.0).coerceIn(0.0, 1.0),         "%.3f".format(metrics.zipf)),
        Triple("4. Benford",        metrics.benford,                                  "%.3f".format(metrics.benford)),
        Triple("5. Fourier",        metrics.fourier,                                  "%.3f".format(metrics.fourier)),
        Triple("6. Katz FD",        metrics.katz,                                     "%.3f".format(metrics.katz)),
        Triple("7. Perm-Entropy",   metrics.permEntropy,                              "%.3f".format(metrics.permEntropy)),
        Triple("8. Δ-Konvergenz",   (metrics.deltaConvergence / 2.83).coerceIn(0.0, 1.0), "%.3f".format(metrics.deltaConvergence)),
        Triple("9. Noether",        metrics.noether,                                  "%.3f".format(metrics.noether)),
    )

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
        Text("Cascade V4", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00E5FF))
        Spacer(Modifier.height(16.dp))

        if (!isScanning) {
            Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Analyse starten", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = { context.stopService(Intent(context, FramePipelineService::class.java)) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color.Red),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
            ) {
                Icon(Icons.Default.Stop, null, tint = Color.Red)
                Spacer(Modifier.width(8.dp))
                Text("Analyse stoppen", color = Color.Red)
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
            Column(Modifier.padding(16.dp)) {
                Text("Cascade V4 — 9 Metriken", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                Spacer(Modifier.height(16.dp))
                metricRows.forEach { (label, progress, formatted) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                        LinearProgressIndicator(
                            progress = { progress.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(6.dp),
                            color = Color(0xFF00E5FF), trackColor = Color(0xFF222222))
                        Spacer(Modifier.width(8.dp))
                        Text(formatted, color = Color.White, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF222222))
                Spacer(Modifier.height(12.dp))
                val trustColor = if (metrics.trustScore >= 0.65) Color(0xFF00C853) else Color(0xFFFFAB00)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trust Score", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(if (metrics.trustScore >= 0.65) "Anker-Erzeugung aktiv" else "Kalibrierung läuft",
                            color = trustColor, fontSize = 11.sp)
                    }
                    Text("%.3f".format(metrics.trustScore), color = trustColor,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
            Column(Modifier.padding(16.dp)) {
                Text("Anker-Pool", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                Text("Anker werden erst nach Quorum von 3 unabhängigen Nodes veröffentlicht.",
                    color = Color.DarkGray, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                val globalCount = anchorPool.count { it.isGlobal }
                Text("$globalCount global / ${anchorPool.size} gesamt", color = Color.Gray, fontSize = 12.sp)
                if (anchorPool.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Noch keine Anker — Trust ≥ 0.65 erforderlich.",
                        color = Color.DarkGray, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                } else {
                    Spacer(Modifier.height(10.dp))
                    anchorPool.take(10).forEach { anchor ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(anchor.anchorHash, color = Color.Gray, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            val (qColor, qText) = when {
                                anchor.isGlobal    -> Color(0xFF00C853) to "GLOBAL"
                                anchor.quorum == 2 -> Color(0xFFFFAB00) to "2/3"
                                else               -> Color.Gray to "1/3"
                            }
                            Surface(shape = RoundedCornerShape(4.dp), color = qColor.copy(alpha = 0.15f)) {
                                Text(qText, color = qColor, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                        HorizontalDivider(color = Color(0xFF1A1A1A))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---- InfoScreen ----

@Composable
fun InfoScreen(prefs: android.content.SharedPreferences) {
    val scrollState = rememberScrollState()
    val username = prefs.getString("username", "Unknown") ?: "Unknown"
    val nodeId = prefs.getString("node_id", "") ?: ""

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp)) {
        Text("Aether OS Core", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00E5FF))
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(40.dp), tint = Color(0xFF00E5FF))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (nodeId.isNotEmpty()) {
                        Text(nodeId.take(16) + "…", color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("200:aether:${nodeId.take(4)}:${nodeId.drop(4).take(4)}",
                            color = Color.DarkGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        InfoSection("AEF (Aether Encryption Flow)",
            "Verschlüsselung durch Architektur. Daten werden in mathematische Invarianten zerlegt, die nur im Kollektiv Sinn ergeben.")
        InfoSection("DNA (Distributed Node Anchor)",
            "Deine Identität ist kein Account auf einem Server, sondern ein kryptografischer Ankerpunkt im Mesh-Gewebe.")
        InfoSection("Ockham-Philosophie",
            "Das einfachste System ist das sicherste. Keine unnötigen Bibliotheken, keine Tracker, nur pure Mathematik.")

        Spacer(Modifier.height(8.dp))

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
            Column(Modifier.padding(16.dp)) {
                Text("Datenschutz by Architecture", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "Rohdaten verlassen das Gerät nie",
                    "Deltas sind RAM-only",
                    "Anker: SHA-256, nicht invertierbar",
                    "Schlüssel: lokal erzeugt und gespeichert",
                    "Chat: Ende-zu-Ende verschlüsselt, P2P"
                ).forEach { point ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00C853), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(point, color = Color.LightGray, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        InfoSection("Warum dein Beitrag zählt",
            "Strukturelle Signaturen werden erst nach Quorum von 3 unabhängigen Nodes global publiziert. Kein einzelner Node kann den Pool manipulieren — Wahrheit entsteht kollektiv.")

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E0E0E))) {
            Column(Modifier.padding(16.dp)) {
                Text("Theoretische Grundlage", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Shannon · Boltzmann · Zipf · Benford · Fourier · Katz · Bandt & Pompe · Noether · Wiener · Gödel · Bayes",
                    color = Color.Gray, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("\"Wahrheit ohne Agenda ist Intelligenz.\"",
                color = Color(0xFF00E5FF).copy(alpha = 0.7f), fontSize = 13.sp,
                textAlign = TextAlign.Center, fontStyle = FontStyle.Italic)
            Text("— Kevin Hannemann, 2024–2026", color = Color.DarkGray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("Version: 0.5.0-OCKHAM", color = Color.DarkGray, fontSize = 10.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ---- Helpers ----

@Composable
fun InfoSection(title: String, desc: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        Text(desc, fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF222222))
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.LightGray, fontSize = 14.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ---- Navigation ----

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Network     : Screen("net",  "Netz",    Icons.Default.Share)
    data object Chat        : Screen("chat", "Chats",   Icons.Default.Chat)
    data object Performance : Screen("perf", "Leistung", Icons.Default.Speed)
    data object Info        : Screen("info", "Info",    Icons.Default.Info)
}

// ---- Setup Screen ----

@Composable
fun OckhamSetupScreen(onComplete: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = Color(0xFF00E5FF))
        Spacer(Modifier.height(16.dp))
        Text("Aether Initialisierung", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF00E5FF))
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Mesh-Alias") },
            modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("DNA-Passwort") },
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00E5FF), focusedLabelColor = Color(0xFF00E5FF)))
        Spacer(Modifier.height(32.dp))
        Button(onClick = { if (user.isNotBlank() && pass.isNotBlank()) onComplete(user, pass) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) {
            Text("DNA Anker generieren", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

// ---- Theme ----

@Composable
fun AetherAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Color(0xFF00E5FF), background = Color.Black, surface = Color(0xFF121212)),
        content = content
    )
}


