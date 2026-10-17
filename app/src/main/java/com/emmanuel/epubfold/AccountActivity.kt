package com.emmanuel.epubfold

import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cuenta en la nube y copias de seguridad. */
class AccountActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout
    private lateinit var prefs: ReaderPrefs
    private var busy = false

    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { runIo("Copia guardada") { Backup.export(this, it, includeBooks = true) } }
    }
    private val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { runIo("Copia guardada") { Backup.export(this, it, includeBooks = false) } }
    }
    private val restore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Backup.restore(this@AccountActivity, uri) } }
            r.onSuccess {
                toast("Restaurado: ${it.updated} libros actualizados, ${it.added} añadidos")
                build()
            }.onFailure { toast("No se pudo restaurar: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = ReaderPrefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.color.bg_grouped)) }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), dpi(32))
        }
        scroll.addView(col)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            col.updatePadding(top = b.top + dpi(8), bottom = b.bottom + dpi(32), left = b.left + dpi(20), right = b.right + dpi(20))
            insets
        }
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !night
        build()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun runIo(ok: String, block: () -> Unit) {
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            toast(if (r.isSuccess) ok else "Error: ${r.exceptionOrNull()?.message}")
        }
    }

    private fun build() {
        col.removeAllViews()
        col.addView(TextView(this).apply {
            text = "‹ Biblioteca"
            setTextColor(color(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, dpi(10), 0, dpi(10))
            setOnClickListener { finish() }
        })
        col.addView(TextView(this).apply {
            text = "Cuenta"
            setTextAppearance(R.style.Text_LargeTitle)
            setPadding(0, dpi(4), 0, dpi(16))
        })

        caption("Sincronización en la nube")
        val cloud = group()
        if (!CloudSync.available(this)) {
            cloud.addView(paragraph(
                "La sincronización con cuenta necesita un proyecto de Firebase. " +
                    "Cuando se compile la app con el archivo google-services.json, aquí podrás iniciar sesión " +
                    "con tu email y seguir leyendo en otro dispositivo (progreso, marcadores, notas y estadísticas).\n\n" +
                    "Mientras tanto puedes usar la copia de seguridad de abajo."
            ))
        } else {
            val u = CloudSync.user(this)
            if (u == null) buildLogin(cloud) else buildSignedIn(cloud, u.email ?: "")
        }

        spacer(24)
        caption("Copia de seguridad")
        val backup = group()
        backup.addView(row("Exportar copia completa", "ZIP con libros, progreso, notas y estadísticas") {
            exportZip.launch("EpubFold-${stamp()}.zip")
        })
        backup.addView(hairline(this, dpi(16)))
        backup.addView(row("Exportar sólo progreso", "Archivo pequeño (.json) sin los libros") {
            exportJson.launch("EpubFold-progreso-${stamp()}.json")
        })
        backup.addView(hairline(this, dpi(16)))
        backup.addView(row("Restaurar copia…", "Fusiona con tu biblioteca: no borra nada") {
            restore.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
        })
        col.addView(TextView(this).apply {
            text = "Guarda la copia en Google Drive o en tu PC para pasar todo a otro teléfono."
            setTextAppearance(R.style.Text_Footnote)
            setPadding(dpi(16), dpi(8), dpi(16), 0)
        })

        spacer(24)
        caption("Aplicación")
        val appGroup = group()
        appGroup.addView(row("Buscar actualizaciones", "Versión instalada: ${BuildConfig.VERSION_NAME}") {
            Updater.check(this, manual = true)
        })
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun buildLogin(cloud: LinearLayout) {
        cloud.addView(paragraph("Inicia sesión para guardar tu progreso, marcadores, notas y estadísticas en la nube."))
        val email = field("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pass = field("Contraseña (mín. 6 caracteres)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        cloud.addView(email)
        cloud.addView(pass)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpi(16), dpi(12), dpi(16), dpi(6))
        }
        buttons.addView(pill("Iniciar sesión", filled = true) {
            auth { CloudSync.signIn(email.text.toString(), pass.text.toString()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(6) })
        buttons.addView(pill("Crear cuenta", filled = false) {
            auth { CloudSync.signUp(email.text.toString(), pass.text.toString()) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(6) })
        cloud.addView(buttons)
        if (CloudSync.webClientId(this) != null) {
            cloud.addView(pill("G   Continuar con Google", filled = false) {
                auth { CloudSync.signInWithGoogle(this) }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpi(16), dpi(6), dpi(16), 0)
                }
            })
        }
        cloud.addView(TextView(this).apply {
            text = "¿Olvidaste tu contraseña?"
            setTextColor(color(R.color.accent))
            gravity = Gravity.CENTER
            setPadding(0, dpi(8), 0, dpi(16))
            setOnClickListener {
                val e = email.text.toString()
                if (e.isBlank()) { toast("Escribe tu email primero"); return@setOnClickListener }
                lifecycleScope.launch {
                    val r = runCatching { CloudSync.resetPassword(e) }
                    toast(if (r.isSuccess) "Te enviamos un email para cambiar la contraseña" else "Error: ${r.exceptionOrNull()?.message}")
                }
            }
        })
    }

    private fun auth(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val r = runCatching { block() }
            busy = false
            if (r.isSuccess) {
                toast("Sesión iniciada")
                build()
                sync()
            } else {
                toast(friendly(r.exceptionOrNull()))
            }
        }
    }

    private fun friendly(e: Throwable?): String {
        val m = e?.message ?: return "Error desconocido"
        if (e is androidx.credentials.exceptions.GetCredentialCancellationException) return "Inicio de sesión cancelado"
        if (e is androidx.credentials.exceptions.NoCredentialException) return "No hay cuentas de Google en el teléfono"
        return when {
            m.contains("password", true) && m.contains("invalid", true) -> "Email o contraseña incorrectos"
            m.contains("credential", true) -> "Email o contraseña incorrectos"
            m.contains("already in use", true) -> "Ya existe una cuenta con ese email"
            m.contains("badly formatted", true) -> "El email no es válido"
            m.contains("network", true) -> "Sin conexión"
            m.contains("6 characters", true) || m.contains("WEAK_PASSWORD", true) -> "La contraseña debe tener al menos 6 caracteres"
            else -> m
        }
    }

    private fun buildSignedIn(cloud: LinearLayout, email: String) {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(14), dpi(16), dpi(12))
        }
        head.addView(TextView(this).apply { text = email; setTextAppearance(R.style.Text_Headline) })
        val last = prefs.lastSync
        head.addView(TextView(this).apply {
            text = if (last > 0) "Última sincronización: " + DateUtils.getRelativeTimeSpanString(last) else "Aún sin sincronizar"
            setTextAppearance(R.style.Text_Footnote)
        })
        cloud.addView(head)
        cloud.addView(hairline(this, dpi(16)))
        cloud.addView(row("Sincronizar ahora", "Sube y descarga progreso, notas y estadísticas") { sync() })
        cloud.addView(hairline(this, dpi(16)))
        cloud.addView(row("Cerrar sesión", null, destructive = true) {
            MaterialAlertDialogBuilder(this)
                .setTitle("¿Cerrar sesión?")
                .setMessage("Tus libros y notas se quedan en este dispositivo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar sesión") { _, _ ->
                    lifecycleScope.launch { CloudSync.signOut(this@AccountActivity); build() }
                }
                .show()
        })
    }

    private fun sync() {
        if (busy) return
        busy = true
        toast("Sincronizando…")
        lifecycleScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { CloudSync.syncAll(this@AccountActivity) } }
            busy = false
            r.onSuccess { res ->
                val extra = if (res.cloudOnly.isNotEmpty())
                    "\n\nEn tu cuenta pero no en este dispositivo (añade el EPUB para recuperar su progreso):\n• " +
                        res.cloudOnly.joinToString("\n• ") else ""
                MaterialAlertDialogBuilder(this@AccountActivity)
                    .setTitle("Sincronizado")
                    .setMessage("${res.pulled} libros actualizados desde la nube, ${res.pushed} subidos.$extra")
                    .setPositiveButton("OK", null)
                    .show()
                build()
            }.onFailure { toast("No se pudo sincronizar: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- piezas

    private fun caption(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextAppearance(R.style.Text_Caption)
        setPadding(dpi(16), 0, 0, dpi(8))
    })

    private fun spacer(h: Int) = col.addView(View(this), LinearLayout.LayoutParams(1, dpi(h)))

    private fun group(): LinearLayout {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(color(R.color.card), dp(12f))
        }
        col.addView(g, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return g
    }

    private fun paragraph(t: String) = TextView(this).apply {
        text = t
        setTextAppearance(R.style.Text_Subhead)
        setPadding(dpi(16), dpi(14), dpi(16), dpi(10))
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
        background = roundRect(color(R.color.fill), dp(10f))
        setPadding(dpi(12), dpi(11), dpi(12), dpi(11))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dpi(16), dpi(6), dpi(16), dpi(6))
        }
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else color(R.color.accent))
        background = roundRect(if (filled) color(R.color.accent) else color(R.color.fill), dp(12f))
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        setOnClickListener { onClick() }
    }

    private fun row(title: String, sub: String?, destructive: Boolean = false, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dpi(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16), dpi(10), dpi(16), dpi(10))
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            foreground = getDrawable(tv.resourceId)
            setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply {
            text = title
            setTextAppearance(R.style.Text_Body)
            setTextColor(if (destructive) 0xFFFF3B30.toInt() else color(R.color.accent))
        })
        if (sub != null) r.addView(TextView(this).apply { text = sub; setTextAppearance(R.style.Text_Footnote) })
        return r
    }
}
