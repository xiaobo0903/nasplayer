package com.example.nasplayer

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex

fun natural(a: String, b: String): Int {
    val re = Regex("\\d+|\\D+")
    val x = re.findAll(a.lowercase()).map { it.value }.toList()
    val y = re.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) (p.toBigInteger().compareTo(q.toBigInteger())).takeIf { it != 0 } ?: p.length.compareTo(q.length)
        else p.compareTo(q)
        if (c != 0) return c
    }
    return x.size.compareTo(y.size)
}

class BrowseVM(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    var cfg by mutableStateOf(store.cfg)
    var path by mutableStateOf("")
    var items by mutableStateOf<List<NasItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var sortKey by mutableIntStateOf(store.sortKey)
    var asc by mutableStateOf(store.asc)
    var tick by mutableIntStateOf(0)
    var wall by mutableStateOf(store.wall)
    val posters = PosterLoader(app) { cfg }
    private var session: SmbSession? = null
    private var raw = emptyList<NasItem>()

    init { cfg?.let { open(it.startPath.trim('/')) } }

    fun saveCfg(c: NasConfig) { store.cfg = c; cfg = c; closeSession(); open(c.startPath.trim('/')) }

    fun open(p: String) {
        val c = cfg ?: return
        viewModelScope.launch {
            loading = true; error = null
            try {
                raw = withContext(Dispatchers.IO) {
                    val s = session ?: SmbSession(c).also { session = it }
                    withPosters(s.list(p))
                }
                path = p; resort()
            } catch (e: Exception) {
                error = (e.cause ?: e).message ?: e.toString(); closeSession()
            }
            loading = false
        }
    }

    fun up(): Boolean { if (path.isEmpty()) return false; open(path.substringBeforeLast('/', "")); return true }

    fun sort(k: Int) {
        if (sortKey == k) asc = !asc else { sortKey = k; asc = (k == 0) }
        store.sortKey = sortKey; store.asc = asc; resort()
    }

    private fun resort() {
        val cmp: Comparator<NasItem> = if (sortKey == 0) Comparator { a, b -> natural(a.name, b.name) } else compareBy { it.time }
        items = raw.sortedWith(compareBy<NasItem> { !it.isDir }.then(if (asc) cmp else cmp.reversed()))
    }

    fun toggleWall() { wall = !wall; store.wall = wall }

    private fun closeSession() { val s = session; session = null; if (s != null) Thread { s.close() }.start() }
    override fun onCleared() { closeSession(); posters.close() }
}

class MainActivity : ComponentActivity() {
    private val vm: BrowseVM by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(vm) } } }
    }
    override fun onResume() { super.onResume(); vm.tick++ }
}

private val Blue = Color(0xFF3D5AFE)
private val Card = Color(0xFF1E1E2A)

@Composable fun App(vm: BrowseVM) {
    var editing by remember { mutableStateOf(vm.cfg == null) }
    if (editing) Settings(vm.cfg, onDone = { editing = false }, onSave = { vm.saveCfg(it); editing = false })
    else Browse(vm) { editing = true }
}

@Composable fun TvButton(text: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (f) Blue else Card)
        .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick).padding(20.dp, 10.dp)) {
        Text(text, fontSize = 18.sp)
    }
}

@Composable fun Settings(cur: NasConfig?, onDone: () -> Unit, onSave: (NasConfig) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(cur?.host ?: "") }
    var share by remember { mutableStateOf(cur?.share ?: "") }
    var user by remember { mutableStateOf(cur?.user ?: "") }
    var pass by remember { mutableStateOf(cur?.pass ?: "") }
    var start by remember { mutableStateOf(cur?.startPath ?: "") }
    val found = remember { mutableStateListOf<LanHost>() }
    val shares = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun scan() {
        if (scanning) return
        scanning = true; found.clear(); msg = ""
        scope.launch {
            try { scanLan(ctx) { h -> if (found.none { it.ip == h.ip }) found.add(h) } }
            catch (e: Exception) { msg = "扫描失败：${e.message}" }
            scanning = false
            if (found.isEmpty() && msg.isEmpty()) msg = "未发现 NAS：请确认电视与 NAS 在同一局域网，或手动输入地址"
        }
    }
    fun fetchShares() {
        if (host.isBlank()) { msg = "请先选择或填写 NAS 地址"; return }
        msg = "正在获取共享列表…"
        scope.launch {
            try {
                val l = withContext(Dispatchers.IO) { listShares(host.trim(), user.trim(), pass) }
                shares.clear(); shares.addAll(l)
                msg = if (l.isEmpty()) "没有可用的共享，请手动填写共享名" else ""
            } catch (e: Throwable) { msg = "获取共享列表失败，可手动填写共享名：${(e.cause ?: e).message}" }
        }
    }
    LaunchedEffect(Unit) { if (cur == null) scan() }      // 首次使用自动扫描
    if (cur != null) BackHandler { onDone() }

    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NAS 连接设置（SMB）", fontSize = 28.sp)
            OutlinedTextField(host, { host = it }, label = { Text("NAS 地址，如 192.168.1.10") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("用户名（留空为匿名访问）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(share, { share = it }, label = { Text("共享名，如 video") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(start, { start = it }, label = { Text("起始目录（可选，如 电影/2024）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("保存并连接") { if (host.isNotBlank() && share.isNotBlank()) onSave(NasConfig(host.trim(), share.trim(), user.trim(), pass, start.trim())) else msg = "地址和共享名不能为空" }
                if (cur != null) TvButton("取消", onDone)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("局域网中的 NAS", fontSize = 22.sp)
            TvButton(if (scanning) "扫描中…" else "重新扫描") { scan() }
            found.forEach { h -> TvButton("🖥  ${h.name}  (${h.ip})") { host = h.ip; shares.clear() } }
            Spacer(Modifier.height(12.dp))
            Text("共享文件夹（先填好地址、账号、密码）", fontSize = 18.sp)
            TvButton("获取共享列表") { fetchShares() }
            shares.forEach { n -> TvButton("📂  $n") { share = n } }
            if (msg.isNotEmpty()) Text(msg, fontSize = 16.sp, color = Color(0xFFFFCC80))
        }
    }
}

@Composable fun Browse(vm: BrowseVM, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    BackHandler(vm.path.isNotEmpty()) { vm.up() }
    LaunchedEffect(vm.items, vm.wall) { if (vm.items.isNotEmpty()) runCatching { fr.requestFocus() } }
    fun label(n: String, k: Int) = if (vm.sortKey == k) "$n ${if (vm.asc) "▲" else "▼"}" else n
    fun play(item: NasItem) {
        if (item.isDir) vm.open(item.path)
        else ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("path", item.path).putExtra("name", item.name))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("${vm.cfg?.host}/${vm.cfg?.share}/${vm.path}", fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(label("名称", 0)) { vm.sort(0) }
            TvButton(label("时间", 1)) { vm.sort(1) }
            TvButton(if (vm.wall) "切换为列表" else "切换为海报墙") { vm.toggleWall() }
            TvButton("刷新") { vm.open(vm.path) }
            TvButton("设置", onSettings)
        }
        Spacer(Modifier.height(12.dp))
        when {
            vm.loading -> Text("加载中…", fontSize = 20.sp)
            vm.error != null -> Text("连接失败：${vm.error}\n请检查地址、共享名、账号，或点「刷新」重试", fontSize = 20.sp, color = Color(0xFFFF8A80))
            vm.items.isEmpty() -> Text("此文件夹没有视频或子文件夹", fontSize = 20.sp)
            vm.wall -> LazyVerticalGrid(GridCells.Adaptive(168.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(vm.items, key = { _, it -> it.path }) { i, item ->
                    PosterCard(vm, item, if (i == 0) Modifier.focusRequester(fr) else Modifier) { play(item) }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(vm.items, key = { _, it -> it.path }) { i, item ->
                    ItemRow(vm, item, if (i == 0) Modifier.focusRequester(fr) else Modifier) { play(item) }
                }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun fmtSize(b: Long) = when { b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); else -> "${b / 1024} KB" }

@Composable fun ItemRow(vm: BrowseVM, item: NasItem, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val prog = remember(vm.tick, item.path) { if (item.isDir) null else vm.store.pos(item.path) }
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
        .background(if (f) Blue else Card).onFocusChanged { f = it.isFocused }
        .clickable(onClick = onClick).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (item.isDir) "📁" else "🎬", fontSize = 26.sp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (item.isDir) dateFmt.format(Date(item.time)) else "${fmtSize(item.size)}  ·  ${dateFmt.format(Date(item.time))}", fontSize = 14.sp, color = Color.LightGray)
        }
        if (prog != null && prog.first > 0) {
            val pct = if (prog.second > 0) " (${(prog.first * 100 / prog.second).coerceIn(0, 100)}%)" else ""
            Text("已看 ${fmtTime(prog.first)}$pct", fontSize = 15.sp, color = Color(0xFF80D8FF))
        }
    }
}

@Composable fun PosterCard(vm: BrowseVM, item: NasItem, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f) 1.08f else 1f, label = "focusScale")
    val bmp by produceState<Bitmap?>(null, item.path, item.time) { value = vm.posters.load(item) }
    val prog = remember(vm.tick, item.path) { if (item.isDir) null else vm.store.pos(item.path) }
    val pct = prog?.let { if (it.second > 0) (it.first * 100 / it.second).toInt().coerceIn(0, 100) else 0 } ?: 0
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.zIndex(if (f) 1f else 0f).padding(8.dp).scale(scale)
        .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(Card)
            .then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (item.isDir) "📁" else "🎬", fontSize = 44.sp, modifier = Modifier.align(Alignment.Center))
            if (item.isDir && b != null) Text("📁", fontSize = 20.sp, modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(4.dp))
            if (pct > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(pct / 100f).height(5.dp).background(Color(0xFF80D8FF)))
        }
        Spacer(Modifier.height(6.dp))
        Text(if (item.isDir) item.name else item.name.substringBeforeLast('.'), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
