package com.example.callnotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

data class Contact(val name: String, val number: String, val designation: String = "")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

// शेवटचा कॉल उचलला गेला का ते कॉल लॉगवरून तपासतो
fun checkLastCall(ctx: Context, number: String): String {
    val last10 = number.filter { it.isDigit() }.takeLast(10)
    ctx.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.DURATION),
        "${CallLog.Calls.NUMBER} LIKE ? AND ${CallLog.Calls.TYPE} = ?",
        arrayOf("%$last10", CallLog.Calls.OUTGOING_TYPE.toString()),
        "${CallLog.Calls.DATE} DESC"
    )?.use { c ->
        if (c.moveToFirst()) {
            return if (c.getInt(0) > 0) "✅ कॉल उचलला" else "❌ कॉल उचलला नाही"
        }
    }
    return ""
}

// ---------- CSV helpers ----------
fun csvEscape(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

fun col(r: List<String>, i: Int): String = if (i < 0) "" else r.getOrElse(i) { "" }.trim()

fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQuotes) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ }
                else inQuotes = false
            } else sb.append(ch)
        } else when (ch) {
            '"' -> inQuotes = true
            ',' -> { row.add(sb.toString()); sb.clear() }
            '\r' -> {}
            '\n' -> { row.add(sb.toString()); sb.clear(); rows.add(row); row = mutableListOf() }
            else -> sb.append(ch)
        }
        i++
    }
    if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); rows.add(row) }
    return rows
}

// ---------- SMS helper ----------
@Suppress("DEPRECATION")
fun smsManager(ctx: Context): SmsManager =
    if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
    else SmsManager.getDefault()

// ---------- WhatsApp helpers ----------
fun waNumber(raw: String, cc: String): String {
    val d = raw.filter { it.isDigit() }
    return when {
        raw.trim().startsWith("+") -> d
        d.length == 10 -> cc + d
        d.length == 11 && d.startsWith("0") -> cc + d.drop(1)
        else -> d
    }
}

// चॅट थेट उघडतो; फोटो असल्यास फोटोसह. WhatsApp / WhatsApp Business दोन्ही चालतात.
fun openWhatsApp(ctx: Context, number: String, msg: String, image: Uri?): Boolean {
    for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
        try {
            val i = if (image != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, image)
                    putExtra(Intent.EXTRA_TEXT, msg)
                    putExtra("jid", "$number@s.whatsapp.net")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=${Uri.encode(msg)}"))
            }
            i.setPackage(pkg)
            ctx.startActivity(i)
            return true
        } catch (e: Exception) {
        }
    }
    return false
}

@Composable
fun SmallCheck(checked: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .border(1.5.dp, LocalContentColor.current.copy(alpha = 0.6f), shape)
            .background(if (checked) Color(0xFF2E7D32) else Color.Transparent)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- लहान बटन ----------
@Composable
fun SmallBtn(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = Color.Transparent,
    fg: Color = LocalContentColor.current,
    fontSize: Int = 12,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("callnotes", Context.MODE_PRIVATE) }
    val contacts = remember { mutableStateListOf<Contact>() }
    val status = remember { mutableStateMapOf<String, String>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    var pending by remember { mutableStateOf<String?>(null) }

    var showDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }

    // WhatsApp
    val selected = remember { mutableStateListOf<String>() }
    var showWa by remember { mutableStateOf(false) }
    var waMsg by remember { mutableStateOf(prefs.getString("wa_msg", "") ?: "") }
    var cc by remember { mutableStateOf(prefs.getString("cc", "91") ?: "91") }
    var waImageUri by remember { mutableStateOf<Uri?>(null) }
    var queue by remember { mutableStateOf(listOf<Contact>()) }
    var qIndex by remember { mutableStateOf(0) }

    // SMS
    var showSms by remember { mutableStateOf(false) }
    var smsMsg by remember { mutableStateOf(prefs.getString("sms_msg", "") ?: "") }
    var smsToAll by remember { mutableStateOf(false) }
    var smsSending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var nameInput by remember { mutableStateOf("") }
    var numberInput by remember { mutableStateOf("") }
    var desInput by remember { mutableStateOf("") }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun clean(s: String) = s.replace("\t", " ").replace("\n", " ")

    fun saveList() {
        prefs.edit().putString(
            "list",
            contacts.joinToString("\n") { "${clean(it.name)}\t${it.number}\t${clean(it.designation)}" }
        ).apply()
    }

    // सर्व काही (यादी, स्टेटस, नोंदी) सेव्ह करा
    fun saveAll() {
        saveList()
        val e = prefs.edit()
        contacts.forEach {
            e.putString("s_${it.number}", status[it.number] ?: "")
            e.putString("n_${it.number}", notes[it.number] ?: "")
        }
        e.apply()
        toast("सेव्ह झाले")
    }

    // अँप उघडल्यावर सेव्ह केलेली यादी लोड करा
    LaunchedEffect(Unit) {
        (prefs.getString("list", "") ?: "").lines()
            .filter { it.contains("\t") }
            .forEach {
                val p = it.split("\t")
                contacts.add(Contact(p[0], p[1], p.getOrElse(2) { "" }))
                status[p[1]] = prefs.getString("s_${p[1]}", "") ?: ""
                notes[p[1]] = prefs.getString("n_${p[1]}", "") ?: ""
            }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
        )
    }

    // ---------- SMS थेट पाठवणे ----------
    fun startSms() {
        val targets = if (smsToAll) contacts.toList() else contacts.filter { it.number in selected }
        if (targets.isEmpty()) { toast("आधी नंबर निवडा"); return }
        if (smsSending) return
        prefs.edit().putString("sms_msg", smsMsg).apply()
        showSms = false
        smsSending = true
        toast("SMS पाठवत आहे… कृपया थांबा")
        scope.launch {
            var ok = 0
            var fail = 0
            val sm = smsManager(ctx)
            for (c in targets) {
                try {
                    val text = smsMsg.replace("{name}", c.name)
                    val parts = sm.divideMessage(text)
                    sm.sendMultipartTextMessage(c.number.replace(" ", ""), null, parts, null, null)
                    ok++
                } catch (e: Exception) {
                    fail++
                }
                delay(400)
            }
            smsSending = false
            toast("SMS: $ok पाठवले" + if (fail > 0) ", $fail अयशस्वी" else "")
        }
    }

    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSms() else toast("SMS परवानगी नाकारली")
    }

    // WhatsApp साठी फोटो निवडणे (कॅशमध्ये कॉपी करून FileProvider ने शेअर)
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val ext = if (ctx.contentResolver.getType(uri) == "image/png") "png" else "jpg"
                val f = File(ctx.cacheDir, "wa_image.$ext")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
                waImageUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            } catch (e: Exception) {
                toast("फोटो जोडता आला नाही")
            }
        }
    }

    // कॉन्टॅक्ट लिस्टमधून नंबर निवडण्यासाठी
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        r.data?.data?.let { uri ->
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    nameInput = c.getString(0) ?: ""
                    numberInput = (c.getString(1) ?: "").replace(" ", "")
                }
            }
        }
    }

    // ---------- Export (CSV) ----------
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                val sb = StringBuilder("\uFEFF")
                sb.append("Name,Number,Designation,Status,Note\n")
                contacts.forEach {
                    sb.append(csvEscape(it.name)).append(',')
                        .append(csvEscape(it.number)).append(',')
                        .append(csvEscape(it.designation)).append(',')
                        .append(csvEscape(status[it.number] ?: "")).append(',')
                        .append(csvEscape(notes[it.number] ?: "")).append('\n')
                }
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                toast("${contacts.size} नंबर एक्सपोर्ट झाले")
            } catch (e: Exception) {
                toast("एक्सपोर्ट अयशस्वी")
            }
        }
    }

    // ---------- Import (CSV) ----------
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?.removePrefix("\uFEFF") ?: ""
                val rows = parseCsv(text).filter { r -> r.any { it.isNotBlank() } }

                // जुन्या फाईलसाठी: Name, Number, Status, Note
                var iName = 0; var iNum = 1; var iDes = -1; var iSt = 2; var iNote = 3
                var data = rows
                if (rows.isNotEmpty()) {
                    val first = rows[0]
                    val hasHeader = first.none { cell -> cell.count { ch -> ch.isDigit() } >= 5 }
                    if (hasHeader) {
                        val h = first.map { it.trim().lowercase() }
                        iName = h.indexOf("name")
                        iNum = h.indexOf("number")
                        iDes = h.indexOf("designation")
                        iSt = h.indexOf("status")
                        iNote = h.indexOf("note")
                        if (iName < 0) iName = 0
                        if (iNum < 0) iNum = 1
                        data = rows.drop(1)
                    }
                }

                var added = 0
                data.forEach { r ->
                    var name = col(r, iName)
                    var number = col(r, iNum).replace(" ", "")
                    if (r.size == 1) { number = name.replace(" ", ""); name = "" }
                    if (number.none { it.isDigit() }) return@forEach
                    if (contacts.none { it.number == number }) {
                        contacts.add(Contact(name.ifBlank { number }, number, col(r, iDes)))
                        status[number] = col(r, iSt)
                        notes[number] = col(r, iNote)
                        added++
                    }
                }
                saveAll()
                toast("$added नंबर इम्पोर्ट झाले")
            } catch (e: Exception) {
                toast("इम्पोर्ट अयशस्वी")
            }
        }
    }

    // कॉलनंतर अँपमध्ये परत आल्यावर स्टेटस अपडेट
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                pending?.let { num ->
                    val s = checkLastCall(ctx, num)
                    status[num] = s
                    prefs.edit().putString("s_$num", s).apply()
                    pending = null
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("कॉल नोट्स", fontSize = 18.sp) },
                actions = {
                    SmallBtn(
                        "सेव्ह",
                        bg = MaterialTheme.colorScheme.primary,
                        fg = MaterialTheme.colorScheme.onPrimary
                    ) { saveAll() }
                    Spacer(Modifier.width(6.dp))
                    Box {
                        SmallBtn("⋮", fontSize = 16) { menuOpen = true }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("इम्पोर्ट (CSV)") },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(
                                        arrayOf("text/*", "application/csv", "application/vnd.ms-excel")
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("एक्सपोर्ट (CSV)") },
                                onClick = {
                                    menuOpen = false
                                    exportLauncher.launch("call_notes.csv")
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (contacts.isNotEmpty() && selected.size == contacts.size)
                                            "निवड काढा" else "सर्व निवडा (WhatsApp)"
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    if (selected.size == contacts.size) selected.clear()
                                    else { selected.clear(); selected.addAll(contacts.map { it.number }) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("सर्व नंबर हटवा (क्लीन अँप)", color = Color(0xFFC62828)) },
                                onClick = {
                                    menuOpen = false
                                    showClearAll = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("सर्व डिटेल क्लिअर") },
                                onClick = {
                                    menuOpen = false
                                    val e = prefs.edit()
                                    contacts.forEach {
                                        status[it.number] = ""; notes[it.number] = ""
                                        e.remove("s_${it.number}").remove("n_${it.number}")
                                    }
                                    e.apply()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    SmallBtn("बाहेर", bg = Color(0xFFC62828), fg = Color.White) {
                        saveAll()
                        (ctx as? Activity)?.finishAffinity()
                    }
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            if (queue.isNotEmpty()) {
                Surface(tonalElevation = 4.dp, color = Color(0xFFE8F5E9)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "WhatsApp: $qIndex/${queue.size}",
                            fontSize = 13.sp,
                            color = Color(0xFF1B1B1B),
                            modifier = Modifier.weight(1f)
                        )
                        if (qIndex < queue.size) {
                            val nx = queue[qIndex]
                            SmallBtn("पुढचा: ${nx.name}", bg = Color(0xFF25D366), fg = Color.White) {
                                val ok = openWhatsApp(
                                    ctx, waNumber(nx.number, cc),
                                    waMsg.replace("{name}", nx.name), waImageUri
                                )
                                if (ok) qIndex++ else toast("WhatsApp सापडले नाही")
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        SmallBtn(
                            if (qIndex < queue.size) "थांबवा" else "पूर्ण ✓ बंद",
                            fg = Color(0xFFC62828)
                        ) { queue = emptyList(); qIndex = 0 }
                    }
                }
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallBtn(
                    "💬 WhatsApp (${selected.size})",
                    modifier = Modifier.shadow(6.dp, RoundedCornerShape(20.dp)),
                    bg = Color(0xFF25D366),
                    fg = Color.White,
                    fontSize = 13,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    if (selected.isEmpty()) toast("आधी कार्डवरील चौकटीतून नंबर निवडा")
                    else showWa = true
                }
                SmallBtn(
                    "✉ SMS",
                    modifier = Modifier.shadow(6.dp, RoundedCornerShape(20.dp)),
                    bg = Color(0xFFEF6C00),
                    fg = Color.White,
                    fontSize = 13,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    if (contacts.isEmpty()) toast("यादी रिकामी आहे")
                    else { smsToAll = selected.isEmpty(); showSms = true }
                }
                SmallBtn(
                    "+ नंबर",
                    modifier = Modifier.shadow(6.dp, RoundedCornerShape(20.dp)),
                    bg = MaterialTheme.colorScheme.primary,
                    fg = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    nameInput = ""; numberInput = ""; desInput = ""; showDialog = true
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 10.dp)) {
            items(contacts, key = { it.number }) { c ->
                val st = status[c.number] ?: ""
                val received = st.startsWith("✅")
                val missed = st.startsWith("❌")
                val tint = when {
                    received -> Color(0xFFDFF5E1)   // हिरवा: उचलला
                    missed -> Color(0xFFFCE0E0)     // लाल: उचलला नाही
                    else -> null
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = if (tint == null) CardDefaults.cardColors()
                    else CardDefaults.cardColors(containerColor = tint, contentColor = Color(0xFF1B1B1B))
                ) {
                    val fg = LocalContentColor.current
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallCheck(c.number in selected) {
                                if (c.number in selected) selected.remove(c.number)
                                else selected.add(c.number)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        c.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (received) Text("  ✅", fontSize = 14.sp)
                                    if (missed) Text("  ❌", fontSize = 14.sp)
                                }
                                Text(
                                    listOf(c.designation, c.number)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • "),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            SmallBtn("कॉल", bg = Color(0xFF3F51B5), fg = Color.White) {
                                pending = c.number
                                ctx.startActivity(
                                    Intent(Intent.ACTION_CALL, Uri.parse("tel:${c.number}"))
                                )
                            }
                        }

                        val noteVal = notes[c.number] ?: ""
                        BasicTextField(
                            value = noteVal,
                            onValueChange = {
                                notes[c.number] = it
                                prefs.edit().putString("n_${c.number}", it).apply()
                            },
                            textStyle = TextStyle(fontSize = 13.sp, color = fg),
                            cursorBrush = SolidColor(fg),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .border(1.dp, fg.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (noteVal.isEmpty()) {
                                        Text(
                                            "उत्तर / नोंद",
                                            fontSize = 13.sp,
                                            color = fg.copy(alpha = 0.5f)
                                        )
                                    }
                                    inner()
                                }
                            }
                        )

                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallBtn("क्लिअर", fontSize = 11) {
                                status[c.number] = ""; notes[c.number] = ""
                                prefs.edit().remove("s_${c.number}").remove("n_${c.number}").apply()
                            }
                            SmallBtn("काढा", fontSize = 11) {
                                contacts.remove(c)
                                selected.remove(c.number)
                                prefs.edit().remove("s_${c.number}").remove("n_${c.number}").apply()
                                saveList()
                            }
                            Spacer(Modifier.weight(1f))
                            if (st.isNotEmpty()) {
                                Text(
                                    st.removePrefix("✅").removePrefix("❌").trim(),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(70.dp)) }
        }
    }

    if (showSms) {
        val count = if (smsToAll) contacts.size else selected.size
        AlertDialog(
            onDismissRequest = { showSms = false },
            title = { Text("SMS थेट पाठवा") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallBtn(
                            "निवडलेल्यांना (${selected.size})",
                            bg = if (!smsToAll) Color(0xFF2E7D32) else Color.Transparent,
                            fg = if (!smsToAll) Color.White else LocalContentColor.current
                        ) { smsToAll = false }
                        Spacer(Modifier.width(6.dp))
                        SmallBtn(
                            "सर्वांना (${contacts.size})",
                            bg = if (smsToAll) Color(0xFF2E7D32) else Color.Transparent,
                            fg = if (smsToAll) Color.White else LocalContentColor.current
                        ) { smsToAll = true }
                    }
                    OutlinedTextField(
                        value = smsMsg,
                        onValueChange = { smsMsg = it },
                        label = { Text("संदेश ({name} = नाव)") },
                        supportingText = {
                            val info = if (smsMsg.isEmpty()) "अक्षरे: 0"
                            else "अक्षरे: ${smsMsg.length} • SMS: ${SmsMessage.calculateLength(smsMsg, false)[0]}"
                            Text(info, fontSize = 12.sp)
                        },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(
                        "सर्वांना एकच संदेश थेट SMS ने जाईल. मराठी संदेशात एका SMS मध्ये सुमारे 70 अक्षरे बसतात; " +
                            "जास्त असल्यास एका व्यक्तीला अनेक SMS जातात आणि तुमच्या SIM चे शुल्क लागते.",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = smsMsg.isNotBlank() && count > 0 && !smsSending,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
                            == PackageManager.PERMISSION_GRANTED
                        ) startSms()
                        else smsPermLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                ) { Text("पाठवा ($count)") }
            },
            dismissButton = { TextButton(onClick = { showSms = false }) { Text("रद्द") } }
        )
    }

    if (showWa) {
        AlertDialog(
            onDismissRequest = { showWa = false },
            title = { Text("WhatsApp संदेश") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("निवडलेले नंबर: ${selected.size}", fontSize = 13.sp)
                    OutlinedTextField(
                        value = waMsg,
                        onValueChange = { waMsg = it },
                        label = { Text("संदेश ({name} = नाव)") },
                        supportingText = { Text("अक्षरे: ${waMsg.length}", fontSize = 12.sp) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallBtn(
                            if (waImageUri == null) "📷 फोटो जोडा" else "📷 फोटो बदला",
                            bg = Color(0xFF3F51B5), fg = Color.White
                        ) { imagePicker.launch("image/*") }
                        if (waImageUri != null) {
                            Spacer(Modifier.width(8.dp))
                            Text("जोडला ✓", fontSize = 12.sp)
                            SmallBtn("काढा", fontSize = 11) { waImageUri = null }
                        }
                    }
                    OutlinedTextField(
                        value = cc,
                        onValueChange = { cc = it.filter { ch -> ch.isDigit() } },
                        label = { Text("देश कोड (भारत = 91)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(
                        "प्रत्येक चॅटमध्ये तुम्हाला स्वतः Send दाबावे लागेल.",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = waMsg.isNotBlank() || waImageUri != null,
                    onClick = {
                        prefs.edit().putString("wa_msg", waMsg).putString("cc", cc).apply()
                        val list = contacts.filter { it.number in selected }
                        showWa = false
                        if (list.isNotEmpty()) {
                            val c0 = list[0]
                            val ok = openWhatsApp(
                                ctx, waNumber(c0.number, cc),
                                waMsg.replace("{name}", c0.name), waImageUri
                            )
                            if (ok) { queue = list; qIndex = 1 }
                            else toast("WhatsApp सापडले नाही")
                        }
                    }
                ) { Text("सुरू करा") }
            },
            dismissButton = { TextButton(onClick = { showWa = false }) { Text("रद्द") } }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("सर्व नंबर हटवायचे?") },
            text = {
                Text(
                    "यादीतील सर्व नंबर, कॉल स्टेटस आणि नोंदी कायमच्या हटवल्या जातील " +
                        "(${contacts.size} नंबर). हवे असल्यास आधी एक्सपोर्ट करून ठेवा."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    contacts.clear()
                    selected.clear()
                    status.clear()
                    notes.clear()
                    pending = null
                    prefs.edit().clear().apply()
                    showClearAll = false
                    toast("अँप क्लीन झाले")
                }) { Text("हो, हटवा", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("रद्द") } }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("नंबर जोडा") },
            text = {
                Column {
                    OutlinedButton(
                        onClick = {
                            pickLauncher.launch(
                                Intent(
                                    Intent.ACTION_PICK,
                                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("कॉन्टॅक्ट लिस्टमधून निवडा", fontSize = 13.sp) }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("नाव") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = desInput,
                        onValueChange = { desInput = it },
                        label = { Text("पदनाम (Designation)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it },
                        label = { Text("मोबाईल नंबर") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = numberInput.isNotBlank(),
                    onClick = {
                        val num = numberInput.replace(" ", "")
                        if (contacts.none { it.number == num }) {
                            contacts.add(Contact(nameInput.ifBlank { num }, num, desInput.trim()))
                            status[num] = ""; notes[num] = ""
                            saveList()
                        }
                        showDialog = false
                    }
                ) { Text("जोडा") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("रद्द") } }
        )
    }
}
