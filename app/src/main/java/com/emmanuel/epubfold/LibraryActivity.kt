package com.emmanuel.epubfold

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class LibraryActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var prefs: ReaderPrefs
    private lateinit var adapter: LibraryAdapter
    private lateinit var list: RecyclerView
    private lateinit var progress: CircularProgressIndicator

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        when (uris.size) {
            0 -> {}
            1 -> importBook(uris[0], open = true)
            else -> importMany(uris.map { u -> { library.import(u) } })
        }
    }

    private val inboxPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Inbox.setFolder(this, tree)
        Toast.makeText(this, "Carpeta de entrada lista: se revisará al abrir la app y cada 6 horas", Toast.LENGTH_LONG).show()
        scanInbox()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@registerForActivityResult
        pickFound("Libros en la carpeta") { cancelled -> StorageScan.scanTree(this, tree, cancelled) }
    }

    /** Vuelve de Ajustes («Acceso a todos los archivos», Android 11+). */
    private val allFilesAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (StorageScan.hasAccess(this)) scanStorage()
    }

    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanStorage()
        else Toast.makeText(this, "Sin permiso no se puede buscar en el teléfono. Puedes elegir una carpeta.", Toast.LENGTH_LONG).show()
    }

    private var dailySkip = 0
    /** Subrayados de todos los libros (se cargan en segundo plano al volver a la biblioteca). */
    private var memories: List<Memory> = emptyList()

    companion object {
        val BOOK_EXT = listOf("epub", "pdf", "cbz", "cbr")
        val BOOK_MIME = arrayOf(
            "application/epub+zip", "application/pdf", "application/vnd.comicbook+zip",
            "application/vnd.comicbook-rar", "application/x-cbz", "application/x-cbr",
            "application/zip", "application/x-rar-compressed", "application/octet-stream", "*/*",
        )

        fun readerIntent(context: android.content.Context, entry: BookEntry): Intent =
            Intent(context, if (entry.format.isImage) ImageReaderActivity::class.java else ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_ID, entry.id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = ReaderPrefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.appNightMode())
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_library)
        library = Library(this)

        val root = findViewById<View>(R.id.root)
        val topBar = findViewById<View>(R.id.topBar)
        val smallTitle = findViewById<View>(R.id.smallTitle)
        val topDivider = findViewById<View>(R.id.topDivider)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)

        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            list.updatePadding(
                top = bars.top + dpi(52),
                left = bars.left + dpi(14), right = bars.right + dpi(14),
                bottom = bars.bottom + dpi(24)
            )
            insets
        }

        // Columnas según el ancho: 2 en ventanas estrechas, 3 en la pantalla exterior, más con el Fold abierto
        val usable = widthDp() - 28
        val columns = (usable / 122).coerceIn(2, 8)
        val glm = GridLayoutManager(this, columns)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) columns else 1
        }
        list.layoutManager = glm
        adapter = LibraryAdapter()
        list.adapter = adapter

        // Título grande que se convierte en título compacto al desplazar (iOS)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val off = rv.computeVerticalScrollOffset().toFloat()
                val f = min(1f, max(0f, (off - dp(20f)) / dp(24f)))
                smallTitle.alpha = f
                topDivider.alpha = f
                topBar.setBackgroundColor(if (f > 0f) withAlpha(color(R.color.bg_grouped), (0xF0 * f).toInt()) else 0)
            }
        })

        findViewById<View>(R.id.add).setOnClickListener { showAddMenu(it) }
        findViewById<View>(R.id.stats).setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        findViewById<View>(R.id.account).setOnClickListener { startActivity(Intent(this, AccountActivity::class.java)) }
        findViewById<View>(R.id.more).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.appearance).setOnClickListener { showAppearance(animate = true) }
        handleIntent(intent)
        if (savedInstanceState?.getBoolean("appearance") == true) showAppearance(animate = false)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { library.purgeTrash() } }
    }

    /** Tras un cambio de apariencia la pantalla se recrea; la hoja vuelve a abrirse. */
    private var reopenAppearance = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("appearance", reopenAppearance)
    }

    private fun showAppearance(animate: Boolean) {
        lateinit var sheet: AppearanceSheet
        sheet = AppearanceSheet(this, prefs) { recreateNow ->
            reopenAppearance = true
            sheet.dialog.window?.setWindowAnimations(0)
            sheet.dialog.dismiss()
            Widgets.updateAll(this)
            if (recreateNow) recreate()
        }
        sheet.show(animate)
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun pickBook() {
        picker.launch(BOOK_MIME)
    }

    private fun showAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Archivos (EPUB, PDF, CBZ, CBR)…")
            menu.add(0, 4, 1, "Buscar en el teléfono…")
            menu.add(0, 2, 2, "Carpeta completa…")
            menu.add(0, 3, 3, "Catálogos en línea (OPDS)…")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> pickBook()
                    4 -> scanStorage()
                    2 -> folderPicker.launch(null)
                    3 -> startActivity(Intent(this@LibraryActivity, OpdsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    /** Busca libros en todo el almacenamiento, pidiendo antes el permiso si hace falta. */
    private fun scanStorage() {
        if (StorageScan.hasAccess(this)) {
            pickFound("Libros en tu teléfono") { cancelled ->
                StorageScan.scan(this, cancelled) { dirs, found -> runOnUiThread { scanSheet?.setProgress(dirs, found) } }
            }
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Buscar libros en el teléfono")
            .setMessage(
                "Para revisar todas las carpetas, Android pide activar «Acceso a todos los archivos» para EpubFold. " +
                    "Solo se leen EPUB, PDF y cómics, nada sale del teléfono y tú eliges cuáles añadir.\n\n" +
                    "Si prefieres no darlo, elige una carpeta concreta."
            )
            .setPositiveButton("Abrir Ajustes") { _, _ ->
                runCatching { allFilesAccess.launch(StorageScan.accessSettingsIntent(this)) }
            }
            .setNegativeButton("Elegir carpeta") { _, _ -> folderPicker.launch(null) }
            .show()
    }

    private var scanSheet: ScanPickerSheet? = null

    /** Abre la hoja de selección mientras [find] busca; solo se importa lo que se marque. */
    private fun pickFound(title: String, find: (cancelled: () -> Boolean) -> List<FoundBook>) {
        val sheet = ScanPickerSheet(this, title,
            onImport = { chosen ->
                importMany(chosen.map { b -> { library.import(b.uri).also { StorageScan.markImported(this, b) } } })
            },
            onPickFolder = { folderPicker.launch(null) },
        )
        scanSheet = sheet
        val job = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { find { !isActive }.also { StorageScan.markKnown(this@LibraryActivity, it) } }
            }
            found.onSuccess { sheet.setResults(it) }
                .onFailure { sheet.setError("No se pudo revisar el almacenamiento: ${it.message ?: "error"}") }
        }
        sheet.dialog.setOnDismissListener {
            if (scanSheet === sheet) scanSheet = null
            job.cancel()
        }
        sheet.show()
    }

    /** Importa varios libros seguidos mostrando el avance. */
    private fun importMany(jobs: List<() -> BookEntry>) {
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Importando libros")
            .setMessage("0 de ${jobs.size}")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            var ok = 0
            var dup = 0
            val failed = ArrayList<String>()
            jobs.forEachIndexed { i, job ->
                val r = withContext(Dispatchers.IO) { runCatching { job() } }
                r.onSuccess { if (library.lastImportDuplicate) dup++ else ok++ }.onFailure { failed += (it.message ?: "error") }
                dialog.setMessage("${i + 1} de ${jobs.size}")
                if (i % 5 == 4) refresh()
            }
            dialog.dismiss()
            refresh()
            autoCovers()
            val msg = (if (ok == 1) "1 libro añadido" else "$ok libros añadidos") +
                (if (dup > 0) " · $dup ya estaban en tu biblioteca" else "") +
                if (failed.isNotEmpty()) " · ${failed.size} no se pudieron abrir" else ""
            Toast.makeText(this@LibraryActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Busca en Open Library portadas para los libros que no tienen. */
    private fun autoCovers() {
        if (library.everything().none { it.coverPath == null && !it.coverLookedUp }) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Metadata.fillMissingCovers(this@LibraryActivity) }.getOrDefault(0) }
            if (n > 0) {
                coverCache.evictAll()
                refresh()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Claro u oscuro según «Apariencia» (por defecto, el del tema de lectura)
        AppCompatDelegate.setDefaultNightMode(prefs.appNightMode())
        refresh()
        autoSync()
        autoCovers()
        scanInbox()
        Updater.check(this, manual = false)
        Widgets.updateAll(this)
        lifecycleScope.launch {
            memories = withContext(Dispatchers.IO) { runCatching { Review.all(this@LibraryActivity) }.getOrDefault(emptyList()) }
            adapter.notifyItemChanged(0, "header")
        }
    }

    /** Sincroniza en segundo plano como mucho cada 10 minutos si hay sesión. */
    private fun autoSync() {
        if (CloudSync.user(this) == null) return
        if (System.currentTimeMillis() - prefs.lastSync < 10 * 60_000L) return
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { CloudSync.syncAll(this@LibraryActivity) } }
            if (r.getOrNull()?.pulled ?: 0 > 0) refresh()
        }
    }

    private var query = ""

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            intent.action = null
            importBook(uri, open = true)
        }
    }

    private val filters = listOf("Todos", "Leyendo", "Quiero leer", "Terminados")

    /** Libros privados desbloqueados (se vuelven a bloquear al salir de la app). */
    private var privateMode = false

    private fun refresh() {
        val all = if (privateMode) library.privateBooks() else library.all()
        val sorted = when (prefs.librarySort) {
            LibrarySort.RECENT -> all
            LibrarySort.TITLE -> all.sortedBy { it.title.lowercase() }
            LibrarySort.AUTHOR -> all.sortedBy { it.author.lowercase() }
            // Las sagas juntas y en orden; después los libros sueltos por título
            LibrarySort.SERIES -> all.sortedWith(
                compareBy<BookEntry>({ it.series.isEmpty() }, { it.series.lowercase() }, { it.seriesIndex }, { it.title.lowercase() })
            )
        }
        val q = query.trim().lowercase()
        val shelf = if (privateMode) "all" else prefs.libraryShelf
        val filtered = sorted.filter { b ->
            val shelfOk = when {
                shelf == "reading" -> b.collection == Shelf.READING
                shelf == "want" -> b.collection == Shelf.WANT
                shelf == "finished" -> b.collection == Shelf.FINISHED
                shelf.startsWith("tag:") -> shelf.removePrefix("tag:") in b.tags
                else -> true
            }
            shelfOk && (q.isEmpty() || b.title.lowercase().contains(q) || b.author.lowercase().contains(q) ||
                b.series.lowercase().contains(q))
        }
        val hero = if (q.isEmpty() && shelf == "all" && !privateMode)
            all.firstOrNull { it.collection == Shelf.READING } else null
        adapter.submit(filtered, hero, all.size)
    }

    private fun importBook(uri: Uri, open: Boolean) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { library.import(uri) } }
            progress.visibility = View.GONE
            result.onSuccess {
                if (library.lastImportDuplicate) {
                    Toast.makeText(this@LibraryActivity, "«${it.title}» ya estaba en tu biblioteca", Toast.LENGTH_SHORT).show()
                }
                refresh()
                CloudSync.pullOne(this@LibraryActivity, it.id) { refresh() }
                autoCovers()
                if (open) openBook(it)
            }.onFailure {
                Toast.makeText(this@LibraryActivity, "No se pudo abrir el libro: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shelfLabel(k: String) = when {
        k == "all" -> "Todos"
        k == "reading" -> "Leyendo"
        k == "want" -> "Quiero leer"
        k == "finished" -> "Terminados"
        else -> k.removePrefix("tag:")
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.create(if (on) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        setTextColor(if (on) 0xFFFFFFFF.toInt() else color(R.color.label))
        background = roundRect(if (on) color(R.color.accent) else color(R.color.seg_bg), dp(16f))
        setPadding(dpi(14), dpi(7), dpi(14), dpi(7))
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dpi(8) }
        setOnClickListener { onClick() }
    }

    /** Crear una estantería; si se indica un libro, se le añade. */
    private fun newShelf(entry: BookEntry?) {
        val f = android.widget.EditText(this).apply { hint = "Nombre (p. ej. Clásicos, Para el viaje…)"; setSingleLine() }
        val box = android.widget.FrameLayout(this).apply { setPadding(dpi(20), dpi(8), dpi(20), 0); addView(f) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Nueva estantería")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear") { _, _ ->
                val name = f.text.toString().trim().replace("|", " ")
                if (name.isEmpty()) return@setPositiveButton
                if (entry != null) {
                    library.setTags(entry.id, entry.tags + name)
                    CloudSync.schedulePush(this, entry.id)
                    refresh()
                } else {
                    Toast.makeText(this, "Mantén pulsado un libro › Estanterías… para añadirlo a «$name»", Toast.LENGTH_LONG).show()
                    pendingShelf = name
                }
            }
            .show()
    }

    /** Estantería recién creada que aún no tiene libros (se muestra al elegir libros). */
    private var pendingShelf: String? = null

    private fun chooseShelves(entry: BookEntry) {
        val all = (library.allTags() + listOfNotNull(pendingShelf)).distinct()
        if (all.isEmpty()) { newShelf(entry); return }
        val checked = all.map { it in entry.tags }.toBooleanArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Estanterías de «${entry.title.take(40)}»")
            .setMultiChoiceItems(all.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setNeutralButton("Nueva…") { _, _ -> newShelf(entry) }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                library.setTags(entry.id, all.filterIndexed { i, _ -> checked[i] })
                CloudSync.schedulePush(this, entry.id)
                refresh()
            }
            .show()
    }

    private fun manageShelf(name: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Estantería «$name»")
            .setItems(arrayOf("Renombrar…", "Quitar estantería (los libros no se borran)")) { _, which ->
                if (which == 0) {
                    val f = android.widget.EditText(this).apply { setText(name); setSingleLine() }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Renombrar")
                        .setView(android.widget.FrameLayout(this).apply { setPadding(dpi(20), dpi(8), dpi(20), 0); addView(f) })
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Guardar") { _, _ ->
                            val nn = f.text.toString().trim().replace("|", " ")
                            if (nn.isEmpty()) return@setPositiveButton
                            library.all().filter { name in it.tags }.forEach { b ->
                                library.setTags(b.id, b.tags.map { t -> if (t == name) nn else t })
                                CloudSync.schedulePush(this, b.id)
                            }
                            prefs.libraryShelf = "tag:$nn"
                            refresh()
                        }.show()
                } else {
                    library.all().filter { name in it.tags }.forEach { b ->
                        library.setTags(b.id, b.tags - name)
                        CloudSync.schedulePush(this, b.id)
                    }
                    prefs.libraryShelf = "all"
                    refresh()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- menú «Más»

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Buscar en mis notas…")
            menu.add(0, 2, 1, "Vocabulario")
            menu.add(0, 3, 2, "Qué leer después")
            menu.add(0, 4, 3, "Clubes de lectura")
            menu.add(0, 5, 4, "Tu año en lectura")
            menu.add(0, 6, 5, "Exportar notas (Obsidian, Readwise, Notion)")
            menu.add(0, 7, 6, "Carpeta de entrada…")
            menu.add(0, 8, 7, "Recordatorio diario…")
            menu.add(0, 9, 8, if (privateMode) "Bloquear libros privados" else "Libros privados…")
            val inTrash = library.trash().size
            menu.add(0, 10, 9, if (inTrash > 0) "Papelera ($inTrash)" else "Papelera")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> searchEverything()
                    2 -> startActivity(Intent(this@LibraryActivity, VocabActivity::class.java))
                    3 -> startActivity(Intent(this@LibraryActivity, DiscoverActivity::class.java))
                    4 -> startActivity(Intent(this@LibraryActivity, ClubsActivity::class.java))
                    5 -> startActivity(Intent(this@LibraryActivity, WrappedActivity::class.java))
                    6 -> startActivity(Intent(this@LibraryActivity, IntegrationsActivity::class.java))
                    7 -> inboxSettings()
                    8 -> reminderSettings()
                    9 -> if (privateMode) lockPrivate() else unlockPrivate()
                    10 -> showTrash()
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- privados y papelera

    private fun unlockPrivate() {
        if (library.privateBooks().isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Libros privados")
                .setMessage("Mantén pulsado un libro y elige «Mover a privados». Desaparecerá de la biblioteca, " +
                    "de los widgets y de los recuerdos, y solo se verá aquí tras desbloquear con tu huella o PIN.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Configura un bloqueo de pantalla (huella o PIN) para proteger tus libros privados", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    privateMode = true
                    refresh()
                    list.scrollToPosition(0)
                }
            })
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Libros privados")
                .setSubtitle("Desbloquea para verlos")
                .setAllowedAuthenticators(auth)
                .build()
        )
    }

    private fun lockPrivate() {
        if (!privateMode) return
        privateMode = false
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // Al salir de la app (o abrir un libro) los privados se vuelven a bloquear
        if (privateMode && !isChangingConfigurations) {
            privateMode = false
            refresh()
        }
    }

    private fun showTrash() {
        val sheet = ListSheet(this, "Papelera")
        val empty = TextView(this).apply {
            text = "Vaciar papelera"
            setTextColor(0xFFFF3B30.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        }
        sheet.headerBox.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.END))
        fun load() {
            val items = library.trash()
            empty.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            sheet.setStatus(if (items.isEmpty()) "La papelera está vacía."
            else "Se borran para siempre a los ${Library.TRASH_DAYS} días. Toca uno para recuperarlo.")
            val now = System.currentTimeMillis()
            sheet.adapter.rows = items.map { b ->
                val left = Library.TRASH_DAYS - ((now - b.deletedAt) / 86_400_000L).toInt()
                SheetRow(b.title, caption = b.author.ifEmpty { null },
                    trailing = if (left <= 1) "Último día" else "$left días") {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(b.title)
                        .setPositiveButton("Recuperar") { _, _ ->
                            library.restore(b.id)
                            load()
                            refresh()
                            Widgets.updateAll(this)
                        }
                        .setNeutralButton("Borrar para siempre") { _, _ ->
                            library.delete(b.id)
                            load()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }
        empty.setOnClickListener {
            val n = library.trash().size
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("¿Vaciar la papelera?")
                .setMessage(if (n == 1) "Se borrará 1 libro con sus notas. No se puede deshacer."
                else "Se borrarán $n libros con sus notas. No se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Vaciar") { _, _ ->
                    library.trash().forEach { library.delete(it.id) }
                    load()
                }
                .show()
        }
        load()
        sheet.show()
    }

    /** Busca en subrayados, notas y cuadernos de todos los libros. */
    private fun searchEverything() {
        val sheet = ListSheet(this, "Buscar en mis notas")
        val field = android.widget.EditText(this).apply {
            hint = "Palabra o frase"
            setSingleLine()
            background = getDrawable(R.drawable.bg_search_field)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dpi(8)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color(R.color.label_secondary))
            setTextColor(color(R.color.label))
            setHintTextColor(color(R.color.label_secondary))
            setPadding(dpi(10), dpi(9), dpi(10), dpi(9))
        }
        sheet.headerBox.addView(field)
        // Se carga una vez: subrayados + cuadernos
        val books = library.all()
        val data = books.map { it to Annotations(this, it.id) }
        fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        fun run(q: String) {
            val n = fold(q.trim())
            if (n.length < 2) {
                sheet.adapter.rows = emptyList()
                sheet.setStatus("${data.sumOf { it.second.highlights.size }} subrayados y ${data.count { it.second.notebook.isNotBlank() }} cuadernos")
                return
            }
            val rows = ArrayList<SheetRow>()
            data.forEach { (b, a) ->
                a.highlights.forEach { h ->
                    if (fold(h.text).contains(n) || fold(h.note).contains(n)) {
                        rows += SheetRow("“${h.text.trim().take(220)}”",
                            caption = b.title + if (h.note.isNotBlank()) " · Nota: ${h.note.trim().take(100)}" else "") {
                            sheet.dismiss()
                            openMemory(Memory(b, h))
                        }
                    }
                }
                if (fold(a.notebook).contains(n)) {
                    val i = fold(a.notebook).indexOf(n)
                    val s = (i - 60).coerceAtLeast(0)
                    rows += SheetRow("…" + a.notebook.substring(s, (i + 120).coerceAtMost(a.notebook.length)).replace('\n', ' ') + "…",
                        caption = "Cuaderno · ${b.title}") {
                        sheet.dismiss()
                        openBook(b)
                    }
                }
            }
            sheet.setStatus(if (rows.isEmpty()) "Sin resultados" else "${rows.size} resultados")
            sheet.adapter.rows = rows
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = run(s?.toString() ?: "")
        })
        sheet.dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        run("")
        sheet.show()
        field.requestFocus()
    }

    private fun scanInbox() {
        if (Inbox.folder(this) == null) return
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { runCatching { Inbox.scan(this@LibraryActivity) }.getOrDefault(emptyList()) }
            if (added.isNotEmpty()) {
                refresh()
                autoCovers()
                Toast.makeText(this@LibraryActivity,
                    if (added.size == 1) "Nuevo: «${added[0]}»" else "${added.size} libros nuevos desde tu carpeta de entrada",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun inboxSettings() {
        val current = Inbox.folder(this)
        val name = current?.let { androidx.documentfile.provider.DocumentFile.fromTreeUri(this, it)?.name }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Carpeta de entrada")
            .setMessage(
                "Elige una carpeta y EpubFold importará solo los EPUB, PDF y cómics que aparezcan en ella " +
                    "(al abrir la app y cada 6 horas). Por ejemplo, Descargas, una carpeta que llenas desde el PC " +
                    "o con Quick Share, o una de Drive sincronizada con una app de sincronización.\n\n" +
                    (if (name != null) "Ahora: $name" else "Ahora: ninguna")
            )
            .setPositiveButton(if (current == null) "Elegir carpeta" else "Cambiar") { _, _ -> inboxPicker.launch(null) }
            .apply {
                if (current != null) {
                    setNeutralButton("Desactivar") { _, _ -> Inbox.setFolder(this@LibraryActivity, null) }
                    setNegativeButton("Revisar ahora") { _, _ -> scanInbox() }
                } else setNegativeButton("Cancelar", null)
            }
            .show()
    }

    private fun reminderSettings() {
        val on = prefs.reminderOn
        val time = "%02d:%02d".format(prefs.reminderMin / 60, prefs.reminderMin % 60)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Recordatorio diario")
            .setMessage(
                "Un aviso a tu hora preferida con tu racha y lo que te falta para la meta de ${prefs.dailyGoalMin} min. " +
                    "Si ya la cumpliste ese día, no aparece.\n\n" + if (on) "Activado a las $time" else "Desactivado"
            )
            .setPositiveButton(if (on) "Cambiar hora" else "Activar") { _, _ ->
                android.app.TimePickerDialog(this, { _, h, m ->
                    prefs.reminderMin = h * 60 + m
                    prefs.reminderOn = true
                    Reminder.schedule(this)
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    Toast.makeText(this, "Te avisaré cada día a las %02d:%02d".format(h, m), Toast.LENGTH_LONG).show()
                }, prefs.reminderMin / 60, prefs.reminderMin % 60, true).show()
            }
            .apply {
                if (on) setNeutralButton("Desactivar") { _, _ ->
                    prefs.reminderOn = false
                    Reminder.schedule(this@LibraryActivity)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openBook(entry: BookEntry) {
        startActivity(readerIntent(this, entry))
    }

    private fun openMemory(m: Memory) {
        startActivity(
            readerIntent(this, m.book)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, m.highlight.chapter)
                .putExtra(ReaderActivity.EXTRA_START, m.highlight.start)
                .putExtra(ReaderActivity.EXTRA_END, m.highlight.end)
        )
    }

    /** Todos los subrayados de todos los libros, del más reciente al más antiguo. */
    private fun showAllMemories() {
        val all = memories.sortedByDescending { it.highlight.created }
        val sheet = ListSheet(this, "Tus subrayados")
        sheet.setStatus(if (all.isEmpty()) "Aún no has subrayado nada." else "${all.size} subrayados en ${all.map { it.book.id }.distinct().size} libros")
        sheet.adapter.rows = all.map { m ->
            val note = if (m.highlight.note.isNotBlank()) " · Nota: ${m.highlight.note.trim()}" else ""
            SheetRow("“${m.highlight.text.trim().take(280)}”", caption = m.book.title + note) {
                sheet.dismiss()
                openMemory(m)
            }
        }
        sheet.show()
    }

    private fun editSeries(entry: BookEntry) {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        val name = android.widget.EditText(this).apply { hint = "Nombre de la serie"; setText(entry.series); setSingleLine() }
        val number = android.widget.EditText(this).apply {
            hint = "Número (p. ej. 2)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (entry.seriesIndex > 0f) setText(Formats.fmtIndex(entry.seriesIndex))
            setSingleLine()
        }
        box.addView(name)
        box.addView(number)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Serie")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                library.setSeries(entry.id, name.text.toString(), number.text.toString().replace(',', '.').toFloatOrNull() ?: 0f)
                refresh()
            }
            .show()
    }

    private fun findCover(entry: BookEntry) {
        Toast.makeText(this, "Buscando portada…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Metadata.findCover(entry.title, entry.author)?.let { library.setCover(entry.id, it) } ?: false }
                    .getOrDefault(false)
            }
            if (ok) {
                coverCache.evictAll()
                refresh()
            } else {
                Toast.makeText(this@LibraryActivity, "No se encontró portada en Open Library", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showBookMenu(anchor: View, entry: BookEntry) {
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Leer")
            if (entry.shelf == "want") menu.add(0, 4, 1, "Quitar de «Quiero leer»")
            else if (entry.collection != Shelf.FINISHED) menu.add(0, 5, 1, "Añadir a «Quiero leer»")
            if (entry.collection != Shelf.FINISHED) menu.add(0, 6, 2, "Marcar como terminado")
            menu.add(0, 2, 3, "Marcar como no leído")
            menu.add(0, 7, 4, if (entry.series.isEmpty()) "Añadir a una serie…" else "Serie: ${entry.series}…")
            menu.add(0, 9, 4, if (entry.tags.isEmpty()) "Estanterías…" else "Estanterías: ${entry.tags.joinToString(", ")}…")
            menu.add(0, 8, 5, "Buscar portada en internet")
            menu.add(0, 10, 6, if (entry.hidden) "Quitar de privados" else "Mover a privados")
            menu.add(0, 3, 7, "Mover a la papelera")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openBook(entry)
                    2 -> { library.resetProgress(entry.id); refresh() }
                    3 -> {
                        library.moveToTrash(entry.id)
                        refresh()
                        Widgets.updateAll(this@LibraryActivity)
                        com.google.android.material.snackbar.Snackbar
                            .make(findViewById(R.id.root), "«${entry.title}» está en la papelera", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAction("Deshacer") {
                                library.restore(entry.id)
                                refresh()
                                Widgets.updateAll(this@LibraryActivity)
                            }
                            .setActionTextColor(color(R.color.accent))
                            .show()
                    }
                    10 -> {
                        library.setHidden(entry.id, !entry.hidden)
                        refresh()
                        Widgets.updateAll(this@LibraryActivity)
                        if (!entry.hidden) Toast.makeText(this@LibraryActivity,
                            "«${entry.title}» está en Libros privados (menú ⋯)", Toast.LENGTH_LONG).show()
                    }
                    4 -> { library.setShelf(entry.id, ""); refresh() }
                    5 -> { library.setShelf(entry.id, "want"); refresh() }
                    6 -> { library.markFinished(entry.id); refresh() }
                    7 -> editSeries(entry)
                    9 -> chooseShelves(entry)
                    8 -> findCover(entry)
                }
                CloudSync.schedulePush(this@LibraryActivity, entry.id)
                true
            }
            show()
        }
    }

    // ------------------------------------------------------------------ portadas

    private val coverCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun bindCover(path: String?, title: String, image: ImageView, fallback: TextView) {
        val bmp = path?.let { p ->
            coverCache.get(p) ?: (if (File(p).exists()) BitmapFactory.decodeFile(p) else null)?.also { coverCache.put(p, it) }
        }
        if (bmp != null) {
            image.setImageBitmap(bmp)
            fallback.visibility = View.GONE
        } else {
            image.setImageDrawable(null)
            fallback.text = title
            fallback.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ adaptador

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<BookEntry> = emptyList()
        var hero: BookEntry? = null
        var total = 0
        private var first = true

        fun submit(list: List<BookEntry>, heroBook: BookEntry?, totalBooks: Int) {
            val old = items.size
            items = list
            hero = heroBook
            total = totalBooks
            if (first) {
                first = false
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
                return
            }
            // La cabecera se actualiza con payload para no perder el foco del buscador
            notifyItemChanged(0, "header")
            val n = list.size
            if (n > old) {
                notifyItemRangeChanged(1, old)
                notifyItemRangeInserted(1 + old, n - old)
            } else {
                notifyItemRangeChanged(1, n)
                if (old > n) notifyItemRangeRemoved(1 + n, old - n)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder is HeaderHolder) holder.bind(items, hero) else super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount() = items.size + 1
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(inf.inflate(R.layout.item_library_header, parent, false))
            else BookHolder(inf.inflate(R.layout.item_book, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(items, hero)
                is BookHolder -> holder.bind(items[position - 1])
            }
        }
    }

    private inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        val count: TextView = v.findViewById(R.id.count)
        val heroBox: View = v.findViewById(R.id.hero)
        val heroCover: ImageView = v.findViewById(R.id.heroCover)
        val heroCoverTitle: TextView = v.findViewById(R.id.heroCoverTitle)
        val heroTitle: TextView = v.findViewById(R.id.heroTitle)
        val heroAuthor: TextView = v.findViewById(R.id.heroAuthor)
        val heroProgress: LinearProgressIndicator = v.findViewById(R.id.heroProgress)
        val heroPercent: TextView = v.findViewById(R.id.heroPercent)
        val heroContinue: View = v.findViewById(R.id.heroContinue)
        val empty: View = v.findViewById(R.id.empty)
        val section: View = v.findViewById(R.id.section)
        val sortButton: TextView = v.findViewById(R.id.sortButton)
        val search: android.widget.EditText = v.findViewById(R.id.search)
        val filterBox: FrameLayout = v.findViewById(R.id.filterBox)
        val chips = android.widget.LinearLayout(v.context).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val sectionTitle: TextView = v.findViewById(R.id.sectionTitle)
        val daily: View = v.findViewById(R.id.daily)
        val dailyQuote: TextView = v.findViewById(R.id.dailyQuote)
        val dailyNote: TextView = v.findViewById(R.id.dailyNote)
        val dailySource: TextView = v.findViewById(R.id.dailySource)

        init {
            filterBox.addView(android.widget.HorizontalScrollView(v.context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            search.setText(query)
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString() ?: ""
                    if (q != query) {
                        query = q
                        refresh()
                    }
                }
            })
            sortButton.text = "${prefs.librarySort.label} ▾"
            sortButton.setOnClickListener { anchor ->
                PopupMenu(this@LibraryActivity, anchor).apply {
                    LibrarySort.entries.forEachIndexed { i, s ->
                        menu.add(0, i, i, s.label).isCheckable = true
                        menu.getItem(i).isChecked = s == prefs.librarySort
                    }
                    menu.setGroupCheckable(0, true, true)
                    setOnMenuItemClickListener {
                        prefs.librarySort = LibrarySort.entries[it.itemId]
                        sortButton.text = "${prefs.librarySort.label} ▾"
                        refresh()
                        true
                    }
                    show()
                }
            }
            v.findViewById<View>(R.id.emptyAdd).setOnClickListener { pickBook() }
            v.findViewById<View>(R.id.dailyNext).setOnClickListener {
                dailySkip++
                bindDaily()
            }
            v.findViewById<View>(R.id.dailyAll).setOnClickListener { showAllMemories() }
        }

        /** Estanterías como chips: las de siempre, las tuyas y «+». */
        fun bindChips() {
            chips.removeAllViews()
            if (privateMode) {
                chips.addView(chip("Privados", true) {})
                chips.addView(chip("Bloquear", false) { lockPrivate() })
                return
            }
            val keys = listOf("all", "reading", "want", "finished") + library.allTags().map { "tag:$it" }
            if (prefs.libraryShelf !in keys) prefs.libraryShelf = "all"
            keys.forEach { k ->
                val on = k == prefs.libraryShelf
                chips.addView(chip(shelfLabel(k), on) {
                    prefs.libraryShelf = k
                    refresh()
                }.apply {
                    if (k.startsWith("tag:")) setOnLongClickListener { manageShelf(k.removePrefix("tag:")); true }
                })
            }
            chips.addView(chip("＋ Estantería", false) { newShelf(null) })
        }

        fun bindDaily() {
            val m = if (query.isEmpty() && prefs.libraryShelf == "all" && !privateMode) Review.pick(memories, dailySkip) else null
            if (m == null) {
                daily.visibility = View.GONE
                return
            }
            daily.visibility = View.VISIBLE
            dailyQuote.text = "“${m.highlight.text.trim()}”"
            dailyNote.visibility = if (m.highlight.note.isBlank()) View.GONE else View.VISIBLE
            dailyNote.text = "Tu nota: ${m.highlight.note.trim()}"
            dailySource.text = m.book.title + if (m.book.author.isNotEmpty()) " · ${m.book.author}" else ""
            daily.setOnClickListener { openMemory(m) }
        }

        fun bind(books: List<BookEntry>, hero: BookEntry?) {
            val total = adapter.total
            count.text = when (total) {
                0 -> ""
                1 -> "1 libro"
                else -> "$total libros"
            }
            bindChips()
            val libEmpty = total == 0
            count.visibility = if (libEmpty) View.GONE else View.VISIBLE
            empty.visibility = if (libEmpty) View.VISIBLE else View.GONE
            search.visibility = if (libEmpty) View.GONE else View.VISIBLE
            filterBox.visibility = if (libEmpty) View.GONE else View.VISIBLE
            section.visibility = if (libEmpty) View.GONE else View.VISIBLE
            val shelfName = shelfLabel(prefs.libraryShelf)
            sectionTitle.text = when {
                query.isNotBlank() -> if (books.isEmpty()) "Sin resultados" else "Resultados (${books.size})"
                prefs.libraryShelf == "all" -> "Todos los libros"
                books.isEmpty() -> "$shelfName: ninguno"
                else -> "$shelfName (${books.size})"
            }
            if (hero == null) {
                heroBox.visibility = View.GONE
            } else {
                heroBox.visibility = View.VISIBLE
                bindCover(hero.coverPath, hero.title, heroCover, heroCoverTitle)
                heroTitle.text = hero.title
                heroAuthor.text = hero.author
                heroProgress.progress = (hero.progress * 1000).toInt()
                heroPercent.text = "${(hero.progress * 100).toInt()} % leído"
                heroBox.setOnClickListener { openBook(hero) }
                heroContinue.setOnClickListener { openBook(hero) }
            }
            bindDaily()
        }
    }

    private inner class BookHolder(v: View) : RecyclerView.ViewHolder(v) {
        val coverCard: View = v.findViewById(R.id.coverCard)
        val cover: ImageView = v.findViewById(R.id.cover)
        val coverTitle: TextView = v.findViewById(R.id.coverTitle)
        val title: TextView = v.findViewById(R.id.title)
        val status: TextView = v.findViewById(R.id.status)

        fun bind(e: BookEntry) {
            title.text = e.title
            val pct = (e.progress * 100).toInt()
            val seriesTag = if (e.series.isNotEmpty())
                (if (e.seriesIndex > 0f) "#${Formats.fmtIndex(e.seriesIndex)} " else "") + e.series + " · " else ""
            when {
                e.progress <= 0f -> {
                    status.text = seriesTag + "NUEVO"
                    status.setTextColor(color(R.color.accent))
                }
                e.progress >= 0.999f -> {
                    status.text = seriesTag + "Terminado"
                    status.setTextColor(color(R.color.label_secondary))
                }
                else -> {
                    status.text = "$seriesTag$pct %"
                    status.setTextColor(color(R.color.label_secondary))
                }
            }
            // Portada con proporción 2:3
            itemView.post {
                val w = coverCard.width
                if (w > 0 && coverCard.layoutParams.height != w * 3 / 2) {
                    coverCard.layoutParams.height = w * 3 / 2
                    coverCard.requestLayout()
                }
            }
            bindCover(e.coverPath, e.title, cover, coverTitle)
            coverCard.setOnClickListener { openBook(e) }
            coverCard.setOnLongClickListener { showBookMenu(it, e); true }
        }
    }
}
