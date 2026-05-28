package com.personal.vaultdrive.ui.browser

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.personal.vaultdrive.R
import com.personal.vaultdrive.core.security.Prefs
import com.personal.vaultdrive.core.session.TokenManager
import com.personal.vaultdrive.data.model.FileItem
import com.personal.vaultdrive.data.model.Result
import com.personal.vaultdrive.data.repository.Repository
import com.personal.vaultdrive.databinding.ActivityBrowserBinding
import com.personal.vaultdrive.ui.auth.AuthActivity
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var repo: Repository
    private lateinit var adapter: FileAdapter
    private val breadcrumb = ArrayDeque<FileItem>()
    private var currentFolder: FileItem? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    // ── Launchers ─────────────────────────────────────────────────────

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
            if (uris.size == 1) {
                askRenameAndUpload(uris[0])
            } else {
                uris.forEach { uploadFile(it, null) }
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                val defaultName = "Foto_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
                askRenameBeforeUpload(uri, defaultName)
            }
        }
        pendingCameraFile?.delete()
        pendingCameraFile = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) openPicker()
        else snack("Se necesitan permisos para acceder a la galería")
    }

    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else snack("Se necesita permiso de cámara")
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = Repository(this)

        setupRecycler()
        setupButtons()
        updatePinnedCard()
        loadFolder("root")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (breadcrumb.isNotEmpty()) navigateUp() else super.onBackPressed()
    }

    // ── Setup ──────────────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = FileAdapter(
            onClick = { item -> if (item.isFolder) openFolder(item) },
            onLongClick = { item -> showItemMenu(item) }
        )
        b.rvFiles.layoutManager = LinearLayoutManager(this)
        b.rvFiles.adapter = adapter
        b.swipeRefresh.setOnRefreshListener { refresh() }
    }

    private fun setupButtons() {
        b.btnBack.setOnClickListener { navigateUp() }
        b.fabCamera.setOnClickListener { checkCameraAndLaunch() }
        b.fabUpload.setOnClickListener { checkPermAndPick() }
        b.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
        b.tvUserName.text = Prefs.userName.substringBefore("@")
        b.btnSignOut.setOnClickListener { confirmSignOut() }

        // Card carpeta fijada — toque abre la carpeta
        b.cardPinned.setOnClickListener {
            val id = Prefs.pinnedFolderId
            val name = Prefs.pinnedFolderName
            if (id.isNotBlank()) openFolder(FileItem(id, name, true))
        }
    }

    // ── Navegación ────────────────────────────────────────────────────

    private fun openFolder(folder: FileItem) {
        currentFolder?.let { breadcrumb.addLast(it) }
            ?: breadcrumb.addLast(FileItem("root", "Inicio", true))
        currentFolder = folder
        b.tvCurrentFolder.text = folder.name
        b.btnBack.visibility = View.VISIBLE
        b.cardPinned.visibility = View.GONE   // ocultar card cuando se entra a una carpeta
        loadFolder(folder.id)
    }

    private fun navigateUp() {
        if (breadcrumb.isEmpty()) return
        val parent = breadcrumb.removeLast()
        currentFolder = if (parent.id == "root") null else parent
        b.tvCurrentFolder.text = currentFolder?.name ?: "Mi OneDrive"
        b.btnBack.visibility = if (breadcrumb.isEmpty() && currentFolder == null) View.GONE else View.VISIBLE
        if (breadcrumb.isEmpty() && currentFolder == null) updatePinnedCard()
        loadFolder(currentFolder?.id ?: "root")
    }

    private fun refresh() = loadFolder(currentFolder?.id ?: "root")

    private fun loadFolder(folderId: String) {
        lifecycleScope.launch {
            b.swipeRefresh.isRefreshing = true
            val token = getToken() ?: return@launch
            when (val r = repo.getChildren(token, folderId)) {
                is Result.Success -> {
                    adapter.submitList(r.data)
                    b.tvEmpty.visibility = if (r.data.isEmpty()) View.VISIBLE else View.GONE
                }
                is Result.Error -> snack(r.message)
            }
            b.swipeRefresh.isRefreshing = false
        }
    }

    // ── Card carpeta fijada ───────────────────────────────────────────

    private fun updatePinnedCard() {
        val id = Prefs.pinnedFolderId
        val name = Prefs.pinnedFolderName
        if (id.isNotBlank() && currentFolder == null) {
            b.cardPinned.visibility = View.VISIBLE
            b.tvPinnedName.text = name
            b.tvPinnedSub.text = "Toca para abrir tu carpeta"
        } else {
            b.cardPinned.visibility = View.GONE
        }
    }

    // ── Menú item ─────────────────────────────────────────────────────

    private fun showItemMenu(item: FileItem) {
        val options = mutableListOf<String>()
        if (item.isFolder) options.add("📌 Fijar como carpeta principal")
        options.add("✏️ Renombrar")
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle(item.name)
            .setItems(options.toTypedArray()) { _, idx ->
                when (options[idx]) {
                    "📌 Fijar como carpeta principal" -> {
                        Prefs.pinnedFolderId = item.id
                        Prefs.pinnedFolderName = item.name
                        updatePinnedCard()
                        snack("\"${item.name}\" fijada como carpeta principal ✓")
                    }
                    "✏️ Renombrar" -> showRenameDialog(item)
                }
            }.show()
    }

    private fun showRenameDialog(item: FileItem) {
        val ext = if (!item.isFolder) ".${item.name.substringAfterLast('.', "")}" else ""
        val baseName = if (!item.isFolder) item.name.substringBeforeLast('.') else item.name
        val et = EditText(this).apply {
            setText(baseName)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = null
            selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle("Renombrar")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Guardar") { _, _ ->
                val newName = et.text.toString().trim() + ext
                if (newName.isNotBlank() && newName != item.name) {
                    snack("Renombrado a \"$newName\" (próximamente en OneDrive)")
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ── Crear carpeta ─────────────────────────────────────────────────

    private fun showCreateFolderDialog() {
        val et = EditText(this).apply {
            hint = "Nombre de la carpeta"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = null
        }
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle("Nueva carpeta")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Crear") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) createFolder(name) else snack("El nombre no puede estar vacío")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun createFolder(name: String) {
        lifecycleScope.launch {
            val token = getToken() ?: return@launch
            when (val r = repo.createFolder(token, currentFolder?.id ?: "root", name)) {
                is Result.Success -> { snack("Carpeta \"$name\" creada ✓"); refresh() }
                is Result.Error -> snack(r.message)
            }
        }
    }

    // ── Cámara ────────────────────────────────────────────────────────

    private fun checkCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val tmpFile = File(cacheDir, "camera_$timeStamp.jpg")
        pendingCameraFile = tmpFile
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    // Pide nombre antes de subir foto de cámara
    private fun askRenameBeforeUpload(uri: Uri, defaultName: String) {
        val baseName = defaultName.substringBeforeLast('.')
        val et = EditText(this).apply {
            setText(baseName)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = null
            selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle("Nombre de la foto")
            .setMessage("Puedes cambiar el nombre antes de subir:")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Subir") { _, _ ->
                val name = (et.text.toString().trim().ifBlank { baseName }) + ".jpg"
                uploadFile(uri, name)
            }
            .setNegativeButton("Cancelar", null)
            .setCancelable(false)
            .show()
    }

    // Pide nombre antes de subir archivo de galería (solo si es 1 archivo)
    private fun askRenameAndUpload(uri: Uri) {
        val originalName = getFileName(uri)
        val ext = if (originalName.contains('.')) ".${originalName.substringAfterLast('.')}" else ""
        val baseName = originalName.substringBeforeLast('.')
        val et = EditText(this).apply {
            setText(baseName)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = null
            selectAll()
        }
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle("Nombre del archivo")
            .setView(LinearLayout(this).apply { setPadding(64, 24, 64, 0); addView(et) })
            .setPositiveButton("Subir") { _, _ ->
                val name = (et.text.toString().trim().ifBlank { baseName }) + ext
                uploadFile(uri, name)
            }
            .setNegativeButton("Subir sin renombrar") { _, _ -> uploadFile(uri, null) }
            .show()
    }

    // ── Upload ────────────────────────────────────────────────────────

    private fun uploadFile(uri: Uri, customName: String?) {
        lifecycleScope.launch {
            val token = getToken() ?: return@launch
            val parentId = currentFolder?.id ?: Prefs.pinnedFolderId.ifBlank { "root" }
            b.layoutUploadProgress.visibility = View.VISIBLE
            b.uploadBar.visibility = View.VISIBLE
            b.tvUploadPct.visibility = View.VISIBLE
            when (val r = repo.uploadFile(token, parentId, uri, customName) { pct ->
                runOnUiThread { b.uploadBar.progress = pct; b.tvUploadPct.text = "$pct%" }
            }) {
                is Result.Success -> { snack("Subido ✓"); refresh() }
                is Result.Error -> snack(r.message)
            }
            b.layoutUploadProgress.visibility = View.GONE
            b.uploadBar.visibility = View.GONE
            b.tvUploadPct.visibility = View.GONE
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx) ?: name
        }
        return name
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this, R.style.VaultDialog)
            .setTitle("Cerrar sesión").setMessage("¿Seguro que quieres cerrar sesión?")
            .setPositiveButton("Sí") { _, _ -> TokenManager.signOut { startActivity(Intent(this, AuthActivity::class.java)); finish() } }
            .setNegativeButton("Cancelar", null).show()
    }

    private suspend fun getToken(): String? {
        if (Prefs.isTokenValid()) return Prefs.token
        val r = TokenManager.refreshSilently()
        if (r == null) { startActivity(Intent(this, AuthActivity::class.java)); finish() }
        return r
    }

    private fun checkPermAndPick() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            openPicker() else permLauncher.launch(perms)
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        pickLauncher.launch(Intent.createChooser(intent, "Seleccionar archivos"))
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
}
