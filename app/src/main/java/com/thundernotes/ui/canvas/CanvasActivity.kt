package com.thundernotes.ui.canvas

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.thundernotes.R
import com.thundernotes.canvas.BrushRegistry
import com.thundernotes.canvas.CanvasDocument
import com.thundernotes.canvas.StrokeRecord
import com.thundernotes.canvas.TextBoxRecord
import com.thundernotes.canvas.lasso.LassoOps
import com.thundernotes.canvas.lasso.LassoSelector
import com.thundernotes.canvas.lasso.StrokeTransforms
import com.thundernotes.canvas.inject.ClipboardItem
import com.thundernotes.canvas.inject.InkInjector
import com.thundernotes.canvas.inject.ThunderClipboard
import com.thundernotes.ui.canvas.tabs.CanvasTabs
import com.thundernotes.databinding.ActivityCanvasBinding
import com.thundernotes.databinding.ItemCanvasPageBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CanvasActivity — the full-screen note editor (Phase 6).
 *
 * Per the architecture plan + MainActivity's documented intent, the canvas
 * lives in its OWN Activity (not the NavHost) because it needs the whole
 * screen for the pen tray + page surface + AI-snip overlay, with the sidebar
 * hidden.
 *
 * **This phase (Phase 6) ships the editor shell:**
 *   - Top bar with editable note title + page indicator
 *   - Workflow row (undo / redo / zoom − % + / add page / AI-snip)
 *   - Pen tray (6 tools) + secondary row (color swatches + stroke widths)
 *     whose visibility follows the active tool
 *   - Scrollable page surface with a lined-paper placeholder card
 *
 * **Deferred to Phase 7:** AndroidX Ink [InProgressStrokesView] hosting real
 * strokes. Today the page card shows a placeholder label.
 *
 * Opened with an optional `EXTRA_NOTE_ID`; empty/absent → create a fresh note.
 */
class CanvasActivity : AppCompatActivity(),
    CanvasSettingsBottomSheet.OnExportPdfListener {

    private lateinit var binding: ActivityCanvasBinding
    private val viewModel: NoteEditorViewModel by viewModels()

    // Map tool → the ImageButton id so render() can highlight the active one.
    private val toolButtonIds: List<Pair<EditorTool, Int>> = listOf(
        EditorTool.FOUNTAIN_PEN to R.id.toolFountainPen,
        EditorTool.PEN to R.id.toolPen,
        EditorTool.HIGHLIGHTER to R.id.toolHighlighter,
        EditorTool.ERASER to R.id.toolEraser,
        EditorTool.LASSO to R.id.toolLasso,
        EditorTool.FILLER to R.id.toolFiller,
        EditorTool.SHAPE to R.id.toolShape,
        EditorTool.TEXT to R.id.toolText,
    )

    // Cached swatch views (index → view) so we can toggle selection rings.
    private val swatchViews: MutableList<View> = mutableListOf()
    private val strokeWidthViews: MutableList<View> = mutableListOf()

    private var titleEditing = false  // guard so observer doesn't echo edits back

    // ─── Phase 8: real Ink canvas ────────────────────────────────────────────
    // The document is the stroke/undo/redo source of truth (pure + tested).
    // pageHosts mirrors the document's pages for rendering + touch routing.
    private val document = CanvasDocument()
    private val pageHosts = mutableListOf<CanvasInkHost>()
    private val pageRoots = mutableListOf<View>()  // page-item roots (for theme toggle)
    private val pageCompletedViews = mutableListOf<CompletedStrokesView>()  // finished-stroke layer per page
    private val pageTextboxLayers = mutableListOf<ViewGroup>()  // typed-content overlay per page
    private val pageLassoOverlays = mutableListOf<LassoOverlayView>()  // lasso drag overlay per page
    private val pageGridlineOverlays = mutableListOf<GridlineOverlayView>()  // grid overlay per page
    private val pageRulerOverlays = mutableListOf<RulerOverlayView>()  // ruler overlay per page
    private val injector = InkInjector(document)
    private val spacerManager = com.thundernotes.canvas.CanvasSpacerManager()  // §7.4 Add Writing Space
    private var canvasLight = true   // spec §6.10 Row 1 right theme toggle state
    private var rulerVisible = false  // spec §6.10 Row 2c Scale ruler toggle state
    private var canvasLocked = false   // spec §6.10 Row 2 right Lock Canvas
    private var readMode = false       // spec §6.10 Row 2 right Read Mode
    private var fullscreenMode = false // spec §6.10 Row 2 right Fullscreen (hides tab row)
    private var minimapVisible = true  // spec §6.10 Row 2 right Page Minimap toggle

    /** System image picker (spec §6.10 Row 2b Image insert) — returns the picked
     *  image Uri; the callback loads it + drops an ImageView on the current page. */
    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) handleImagePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCanvasBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init the palette-name store (spec §6.10.7 — user-renamable palettes).
        PaletteNameStore.init(this)
        // Init the font cache (Phase 9e — 10 spec fonts + JetBrains Mono).
        com.thundernotes.ui.common.FontCache.init(this)
        // Init the snip-account + toggle stores (§7.3.6 — keys persist to
        // SharedPreferences; hydrates SnipAccounts on startup so the snip
        // chain + translator plugin have the user's keys after restart).
        com.thundernotes.snip.SnipAccountsStore.init(this)
        com.thundernotes.snip.SnipToggleStore.init(this)
        // Restore persisted engine-disable toggles into the shared SnipSettings.
        com.thundernotes.snip.SnipSettings.shared.let { s ->
            for (name in listOf("Gemini 3 Flash", "GLM-4.6V-Flash", "PaddleOCR-VL-1.6 (offline)")) {
                if (com.thundernotes.snip.SnipToggleStore.isDisabled(name)) s.disableEngine(name)
            }
        }

        wireToolbar()
        wireWorkflowRow()
        wireToolTray()
        buildColorSwatches()
        buildStrokeWidthDots()
        observeState()
        observeClipboard()  // Phase 8b: paste-button glow

        // Build the first page's Ink host (the document starts with 1 page).
        addPageItem(pageNumber = 1)

        // Load (or create) the note. Empty id → new untitled note.
        viewModel.load(intent.getStringExtra(EXTRA_NOTE_ID))

        // System back = activity back (consistent with the ← button).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    // ─── wiring ─────────────────────────────────────────────────────────────

    private fun wireToolbar() {
        binding.btnBack.setOnClickListener { finish() }
        // Phase 8b: the overflow button opens the canvas Settings popup
        // (canvasSettingsPage — constant-scaling toggle §7.5 + palm rejection §6.1.9).
        binding.btnOverflow.setOnClickListener {
            CanvasSettingsBottomSheet().show(supportFragmentManager, "canvas_settings")
        }
        // Phase 8b: canvas-area theme toggle (spec §6.10 Row 1 right).
        binding.btnThemeToggle.setOnClickListener {
            canvasLight = !canvasLight
            applyTheme()
        }
        // Palette switcher (spec §6.10 Row 3c) — PopupMenu of the 3 palettes.
        // Long-press → Rename popup (spec §6.10.7: Sunflower can be renamed).
        binding.paletteSwitcher.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            EditorPalette.PALETTE_NAMES.forEachIndexed { idx, name ->
                menu.menu.add(0, idx, idx, name)
            }
            menu.setOnMenuItemClickListener { item ->
                viewModel.selectPalette(item.itemId)
                binding.paletteSwitcher.text = EditorPalette.PALETTE_NAMES[item.itemId]
                buildColorSwatches()
                true
            }
            menu.show()
        }
        binding.paletteSwitcher.setOnLongClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            EditorPalette.PALETTE_NAMES.forEachIndexed { idx, name ->
                menu.menu.add(0, idx, idx, "$name — ${getString(R.string.palette_rename)}")
            }
            menu.setOnMenuItemClickListener { item ->
                showPaletteRenameDialog(item.itemId)
                true
            }
            menu.show()
            true
        }
        // Line-type switcher (spec §6.10 Row 3a: straight / dotted / dashed).
        binding.lineTypeSwitcher.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            LineType.entries.forEachIndexed { idx, lt ->
                menu.menu.add(0, idx, idx, lt.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            menu.setOnMenuItemClickListener { item ->
                val lt = LineType.entries[item.itemId]
                viewModel.selectLineType(lt)
                binding.lineTypeSwitcher.text = lt.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
                true
            }
            menu.show()
        }
        // Shape-type switcher (§6.10 Row 3g): rect / circle / line.
        binding.shapeTypeSwitcher.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            ShapeType.entries.forEachIndexed { idx, st ->
                menu.menu.add(0, idx, idx, st.name.lowercase().replaceFirstChar { it.uppercase() })
            }
            menu.setOnMenuItemClickListener { item ->
                val st = ShapeType.entries[item.itemId]
                viewModel.setShapeType(st)
                binding.shapeTypeSwitcher.text = st.name.lowercase()
                    .replaceFirstChar { it.uppercase() }
                true
            }
            menu.show()
        }
        // Rename on IME action "Done" or when focus leaves the title field.
        binding.noteTitle.setOnEditorActionListener { v, _, _ ->
            viewModel.renameNote(v.text.toString())
            false
        }
        binding.noteTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.renameNote(binding.noteTitle.text.toString())
        }
        binding.noteTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                titleEditing = true
            }
            override fun afterTextChanged(s: Editable?) { titleEditing = false }
        })
    }

    /** Palette-rename dialog (spec §6.10.7 — Sunflower can be renamed; here
     *  any of the 3 palettes can be renamed, since they're all user-visible). */
    private fun showPaletteRenameDialog(paletteIndex: Int) {
        val current = EditorPalette.PALETTE_NAMES.getOrElse(paletteIndex) { "Palette" }
        val input = EditText(this).apply {
            hint = getString(R.string.palette_rename_hint)
            setText(current)
            setSelection(current.length)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.palette_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text?.toString().orEmpty().trim()
                if (newName.isNotEmpty() && newName != current) {
                    PaletteNameStore.rename(paletteIndex, newName)
                    binding.paletteSwitcher.text = newName
                    Toast.makeText(this, R.string.palette_renamed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun wireWorkflowRow() {
        binding.btnUndo.setOnClickListener { handleUndo() }
        binding.btnRedo.setOnClickListener { handleRedo() }
        // Phase 8b: paste button — drops the clipboard item onto the canvas
        // (the live-injection paste path). Glows while ThunderClipboard has 1 item.
        binding.btnPaste.setOnClickListener { handlePaste() }
        binding.btnZoomIn.setOnClickListener {
            viewModel.zoomIn()
            applyZoom()
        }
        binding.btnZoomOut.setOnClickListener {
            viewModel.zoomOut()
            applyZoom()
        }
        binding.btnAddPage.setOnClickListener {
            viewModel.addPage()
            document.addPage()
            addPageItem(pageNumber = document.totalPages)
            syncUndoRedoFlags()
        }
        // Phase 8b: Gridline toggle (§6.10 Row 2c). Long-press → m×n presets.
        binding.btnGridline.setOnClickListener { viewModel.toggleGrid() }
        binding.btnGridline.setOnLongClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            val presets = listOf("4×2" to (4 to 2), "8×4" to (8 to 4), "12×6" to (12 to 6), "16×8" to (16 to 8))
            presets.forEachIndexed { idx, (label, _) -> menu.menu.add(0, idx, idx, label) }
            menu.setOnMenuItemClickListener { item ->
                val (rows, cols) = presets[item.itemId].second
                viewModel.setGridSize(rows, cols)
                viewModel.setGridVisible(true)
                true
            }
            menu.show()
            true
        }
        // Scale ruler (§6.10 Row 2c): toggle visibility. Long-press → rotation-angle presets.
        binding.btnRuler.setOnClickListener {
            rulerVisible = !rulerVisible
            applyRuler()
        }
        binding.btnRuler.setOnLongClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            val angles = listOf(0, 15, 30, 45, 60, 75, 90, -15, -30, -45, -60, -75, -90)
            angles.forEachIndexed { idx, deg -> menu.menu.add(0, idx, idx, "${deg}°") }
            menu.setOnMenuItemClickListener { item ->
                val deg = angles[item.itemId].toFloat()
                pageRulerOverlays.forEach { it.setAngle(deg) }
                true
            }
            menu.show()
            true
        }
        // Image insert (§6.10 Row 2b): launch the system image picker.
        binding.btnImage.setOnClickListener {
            imagePicker.launch(arrayOf("image/*"))
        }
        // Add Writing Space (§6.10 Row 3c + §7.4): insert a spacer gap on the
        // current page. The spacer is recorded in CanvasSpacerManager (the tested
        // O(log N) page-local Fenwick algorithm); the visual page-card growth +
        // stroke reflow (content below the spacer shifts by the cumulative offset)
        // is the remaining rendering piece.
        binding.btnAddSpace.setOnClickListener {
            val state = viewModel.uiState.value
            val pageId = document.currentPage?.id.orEmpty()
            val gapPx = dp(200)
            spacerManager.insertSpacer(pageId, offsetInPage = 500f, height = gapPx.toFloat())
            Toast.makeText(this,
                getString(R.string.canvas_space_added, gapPx),
                Toast.LENGTH_SHORT).show()
        }
        // Table Maker (§6.10 Row 3g): dialog for rows/cols → build table grid lines.
        binding.btnTable.setOnClickListener {
            val rowsInput = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.canvas_table_rows)
                setText("3")
            }
            val colsInput = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.canvas_table_cols)
                setText("4")
            }
            // §6.10.9b options: Border visibility, header color, alt-row color.
            val borderCb = CheckBox(this).apply {
                text = "Border visible"; isChecked = true
            }
            val headerCb = CheckBox(this).apply {
                text = "Header row (bold top + bottom)"
            }
            val altRowCb = CheckBox(this).apply {
                text = "Alt-row stripe (zebra)"
            }
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(rowsInput); addView(colsInput)
                addView(borderCb); addView(headerCb); addView(altRowCb)
                setPadding(48, 24, 48, 24)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.canvas_table_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val rows = rowsInput.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 3
                    val cols = colsInput.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 4
                    handleTableCreated(rows, cols,
                        borderVisible = borderCb.isChecked,
                        headerColor = if (headerCb.isChecked)
                            0xFF1A1A1A.toInt() else null,
                        altRowColor = if (altRowCb.isChecked)
                            0xFFE0E0E0.toInt() else null,
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        // Finger/Stylus Mode (§6.10 Row 3g): toggle palm rejection.
        binding.btnFingerStylus.setOnClickListener {
            val newState = !viewModel.uiState.value.palmRejection
            viewModel.setPalmRejection(newState)
            Toast.makeText(this,
                if (newState) R.string.canvas_stylus_only else R.string.canvas_finger_allowed,
                Toast.LENGTH_SHORT).show()
        }
        // Split view (§6.10 Row 1: two files side-by-side).
        binding.btnSplit.setOnClickListener { handleSplitToggle() }
        binding.btnAiSnip.setOnClickListener {
            // Phase 9b: capture the canvas surface → SnipBottomSheet → pipeline.
            val bmp = captureCanvas()
            if (bmp == null) {
                Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
            } else {
                com.thundernotes.snip.SnipBottomSheet.bitmap = bmp
                com.thundernotes.snip.SnipBottomSheet().show(supportFragmentManager, "snip")
            }
        }
        // ─── Phase 9g: Row 2 top-right cluster (spec §6.10.5) ───────────
        // Bookmark (toggle the current note's bookmark flag).
        binding.btnBookmark.setOnClickListener {
            val noteId = viewModel.uiState.value.noteId
            if (noteId.isBlank()) {
                Toast.makeText(this, "No note open", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val note = com.thundernotes.data.repository.RepositoryModule.notes
                    .getNote(noteId)
                if (note == null) return@launch
                com.thundernotes.data.repository.RepositoryModule.notes
                    .setNoteBookmarked(noteId, !note.isBookmarked)
                Toast.makeText(this@CanvasActivity,
                    if (!note.isBookmarked) R.string.canvas_bookmarked
                    else R.string.canvas_unbookmarked, Toast.LENGTH_SHORT).show()
            }
        }
        // Read Mode — hide editing chrome (pen tray + workflow row's edit buttons)
        // + make the canvas non-editable while on. Tap again to resume editing.
        binding.btnReadMode.setOnClickListener {
            readMode = !readMode
            binding.toolTray.visibility = if (readMode) View.GONE else View.VISIBLE
            binding.optionsRow.visibility = if (readMode) View.GONE else View.VISIBLE
            Toast.makeText(this,
                if (readMode) R.string.canvas_read_mode_on else R.string.canvas_read_mode_off,
                Toast.LENGTH_SHORT).show()
        }
        // Fullscreen Mode (spec: "hides the topmost file explorer row").
        binding.btnFullscreen.setOnClickListener {
            fullscreenMode = !fullscreenMode
            binding.tabsRow.visibility = if (fullscreenMode) View.GONE else View.VISIBLE
            Toast.makeText(this,
                if (fullscreenMode) R.string.canvas_fullscreen_on else R.string.canvas_fullscreen_off,
                Toast.LENGTH_SHORT).show()
        }
        // Lock Canvas (canvas cannot be moved/scrolled by fingers — the ink
        // host still accepts stylus strokes for writing).
        binding.btnLockCanvas.setOnClickListener {
            canvasLocked = !canvasLocked
            binding.pageScroll.isEnabled = !canvasLocked
            binding.pageScroll.requestDisallowInterceptTouchEvent(canvasLocked)
            Toast.makeText(this,
                if (canvasLocked) R.string.canvas_locked else R.string.canvas_unlocked,
                Toast.LENGTH_SHORT).show()
        }
        // Change Page Margin — popup with 3 presets (Narrow/Normal/Wide).
        binding.btnChangeMargin.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            listOf(
                R.string.canvas_margin_narrow to 16,
                R.string.canvas_margin_normal to 32,
                R.string.canvas_margin_wide to 64,
            ).forEachIndexed { idx, (label, _) ->
                menu.menu.add(0, idx, idx, getString(label))
            }
            menu.setOnMenuItemClickListener { item ->
                val pairs = listOf(16 to 16, 32 to 32, 64 to 64)
                val (h, v) = pairs[item.itemId]
                binding.pageScroll.setPadding(dp(h), dp(v), dp(h), dp(v))
                Toast.makeText(this, R.string.canvas_margin_changed, Toast.LENGTH_SHORT).show()
                true
            }
            menu.show()
        }
        // Change Cover Page — the cover picker is launched from the note's
        // 3-dot menu (Change Cover). From the canvas, redirect the user there.
        binding.btnChangeCover.setOnClickListener {
            Toast.makeText(this,
                "Use the note's 3-dot menu → Change Cover to pick a cover",
                Toast.LENGTH_LONG).show()
        }
        // LaTeX Direct-Input shortcut (§7.7 — typed LaTeX → strokes → clipboard).
        binding.btnLatexDirect.setOnClickListener {
            com.thundernotes.snip.LatexInputBottomSheet()
                .show(supportFragmentManager, "latex_direct")
        }
        // Page Minimap — toggle the pages-sidebar visibility.
        binding.btnMinimap.setOnClickListener {
            minimapVisible = !minimapVisible
            binding.pagesSidebar.visibility =
                if (minimapVisible) View.VISIBLE else View.GONE
            Toast.makeText(this,
                if (minimapVisible) R.string.canvas_minimap_shown
                else R.string.canvas_minimap_hidden, Toast.LENGTH_SHORT).show()
        }
        // 100% Fit — tap the zoom indicator to reset zoom to 100%.
        binding.zoomIndicator.setOnClickListener {
            viewModel.resetZoom()
            applyZoom()
            Toast.makeText(this, R.string.canvas_fit_done, Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Multi-file tab system (§6.10 Row 1: max 10 files) ────────────────

    /** Render the tab strip from [CanvasTabs]. Each tab = a chip (title + close);
     *  active tab highlighted. A '+' button at the end opens the note picker. */
    private fun buildTabsRow() {
        val row = binding.tabsRow
        row.removeAllViews()
        val tabs = CanvasTabs.tabs.value
        for (tab in tabs) {
            val isActive = tab.noteId == CanvasTabs.activeNoteId
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(
                    this@CanvasActivity,
                    if (isActive) R.drawable.bg_canvas_tool_active else R.drawable.bg_canvas_tool,
                )
                val pad = dp(8)
                setPadding(pad, 4, pad, 4)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) }
                layoutParams = lp
                setOnClickListener { handleTabSwitch(tab.noteId) }
            }
            val title = TextView(this).apply {
                text = tab.title.take(16)
                setTextColor(ContextCompat.getColor(this@CanvasActivity,
                    if (isActive) R.color.canvas_tool_active_tint else R.color.canvas_tool_inactive_tint))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val close = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                background = null
                setOnClickListener { handleTabClose(tab.noteId) }
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@CanvasActivity, R.color.canvas_tool_inactive_tint))
                val sz = dp(20)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = dp(4) }
            }
            chip.addView(title); chip.addView(close)
            row.addView(chip)
        }
        // '+' button to open a new note in a tab.
        val addTab = TextView(this).apply {
            text = "+"
            setTextColor(ContextCompat.getColor(this@CanvasActivity, R.color.canvas_tool_inactive_tint))
            textSize = 14f
            background = ContextCompat.getDrawable(this@CanvasActivity, R.drawable.bg_canvas_tool)
            val pad = dp(8)
            setPadding(pad, 4, pad, 4)
            setOnClickListener { handleOpenNotePicker() }
        }
        row.addView(addTab)
    }

    /** Switch to a tab — re-launch CanvasActivity with the tapped noteId. */
    private fun handleTabSwitch(noteId: String) {
        CanvasTabs.switch(noteId)
        finish()
        CanvasActivity.launch(this, noteId)
    }

    /** Close a tab — remove from CanvasTabs + switch to the next (or finish). */
    private fun handleTabClose(noteId: String) {
        CanvasTabs.close(noteId)
        val next = CanvasTabs.activeNoteId
        if (next != null) {
            CanvasActivity.launch(this, next)
        }
        finish()
    }

    /** '+' button — open a note picker (recent notes from the DB). */
    private fun handleOpenNotePicker() {
        lifecycleScope.launch {
            val notes = runCatching {
                com.thundernotes.data.repository.RepositoryModule.notes.observeAllNotes().first()
            }.getOrDefault(emptyList())
            if (notes.isEmpty()) {
                Toast.makeText(this@CanvasActivity, "No notes to open", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = notes.map { it.displayName }.toTypedArray()
            AlertDialog.Builder(this@CanvasActivity)
                .setTitle(R.string.canvas_open_note)
                .setItems(items) { _, which ->
                    val n = notes[which]
                    CanvasTabs.open(n.noteId, n.displayName)
                    finish()
                    CanvasActivity.launch(this@CanvasActivity, n.noteId)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Split view (§6.10 Row 1): toggle the second pane showing the second tab's
     *  note. The right pane is a view-only surface (the full second-note Ink
     *  host is a refinement — the tab system + split pane are the foundation). */
    private fun handleSplitToggle() {
        if (binding.splitPane.visibility == View.VISIBLE) {
            binding.splitPane.visibility = View.GONE
            binding.btnSplit.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.canvas_tool_inactive_tint))
            return
        }
        val second = CanvasTabs.secondTab()
        if (second == null) {
            Toast.makeText(this, R.string.canvas_split_no_second, Toast.LENGTH_SHORT).show()
            return
        }
        binding.splitPagesContainer.removeAllViews()
        val label = TextView(this).apply {
            text = "Viewing: ${second.title}"
            setTextColor(ContextCompat.getColor(this@CanvasActivity, R.color.canvas_on_surface))
            textSize = 14f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        binding.splitPagesContainer.addView(label)
        binding.splitPane.visibility = View.VISIBLE
        binding.btnSplit.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.canvas_secondary))
    }

    private fun wireToolTray() {
        toolButtonIds.forEach { (tool, id) ->
            findViewById<ImageButton>(id).setOnClickListener { viewModel.selectTool(tool) }
        }
        // Eraser long-press → type popup (Area/Shape). Spec §6.10.6d.
        findViewById<ImageButton>(R.id.toolEraser).setOnLongClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            EraserType.entries.forEachIndexed { idx, t ->
                val label = if (t == EraserType.AREA) R.string.eraser_type_area
                    else R.string.eraser_type_shape
                menu.menu.add(0, idx, idx, getString(label))
            }
            menu.setOnMenuItemClickListener { item ->
                viewModel.setEraserType(EraserType.entries[item.itemId])
                true
            }
            menu.show()
            true
        }
        // Lasso long-press → mode popup (Rect/Freeform). Spec §6.10.6e.
        findViewById<ImageButton>(R.id.toolLasso).setOnLongClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            LassoMode.entries.forEachIndexed { idx, m ->
                val label = if (m == LassoMode.RECT) R.string.lasso_mode_rect
                    else R.string.lasso_mode_freeform
                menu.menu.add(0, idx, idx, getString(label))
            }
            menu.setOnMenuItemClickListener { item ->
                viewModel.setLassoMode(LassoMode.entries[item.itemId])
                true
            }
            menu.show()
            true
        }
    }

    // ─── Phase 8: page-host lifecycle + document bridge ─────────────────────

    /** Inflate a page item, host a [CanvasInkHost] in it, wire its finished-stroke
     *  callback to the document, and append it (with a dotted separator before it,
     *  except for the first page). */
    private fun addPageItem(pageNumber: Int) {
        if (pageNumber > 1) {
            // Dotted separator between pages (spec §6.10: "no gap between two
            // pages; only a dotted line separator"). Zero margins — the
            // previous page's bottom + this page's top touch directly.
            val sep = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 4
                ).apply { topMargin = 0; bottomMargin = 0 }
                background = ContextCompat.getDrawable(this@CanvasActivity, R.drawable.bg_dotted_separator)
            }
            binding.pagesContainer.addView(sep)
        }
        val pageBinding = ItemCanvasPageBinding.inflate(layoutInflater, binding.pagesContainer, false)
        pageBinding.pageNumberText.text = pageNumber.toString()
        val pageIndex = pageHosts.size  // index this page will have after add
        val host = CanvasInkHost(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            onStrokeFinished = { record -> handleStrokeFinished(record) }
            onStrokeRemoved = { id ->
                document.removeStroke(id)
                syncUndoRedoFlags()
            }
            // Phase 9h: eraser hit-test callbacks (spec §6.10.6d). The host
            // calls these with page-local coords; we look up the stroke(s) from
            // the document's current page + return the ids to remove.
            onErasePoint = { x, y ->
                val strokes = document.currentStrokes
                val r = viewModel.uiState.value.eraserSizeDp *
                    (resources.displayMetrics.density)
                // Topmost stroke (most-recently-drawn wins) whose bbox contains
                // (x, y) within the eraser radius.
                var hit: String? = null
                for (s in strokes.asReversed()) {
                    val bb = com.thundernotes.canvas.lasso.LassoSelector.strokeBounds(s)
                    if (x + r >= bb[0] && x - r <= bb[2] &&
                        y + r >= bb[1] && y - r <= bb[3]) {
                        hit = s.id; break
                    }
                }
                hit
            }
            onEraseRect = { l, t, r, b ->
                // Area eraser: every stroke whose bbox intersects the drag rect.
                val sel = com.thundernotes.canvas.lasso.LassoSelector.selectByRect(
                    document.currentStrokes, l, t, r, b
                )
                sel.strokeIds
            }
            // Phase 8b: TEXT + LASSO tool routing (per-page, with the page index
            // captured so the activity targets the right page's overlays/layers).
            onTextTap = { x, y -> handleTextTap(x, y) }
            // Phase 9j: FILLER tool (§6.10.6f) — tap → drop a translucent
            // filled rect (a fill stamp) at the tap point.
            onFillTap = { x, y -> handleFillTap(x, y) }
            onLassoDrag = { l, t, r, b -> handleLassoDrag(pageIndex, l, t, r, b) }
            onLassoEnd = { l, t, r, b -> handleLassoEnd(pageIndex, l, t, r, b) }
            onShapeDrag = { l, t, r, b -> handleLassoDrag(pageIndex, l, t, r, b) }  // reuse lasso overlay for the shape preview
            onShapeEnd = { l, t, r, b -> handleShapeEnd(pageIndex, l, t, r, b) }
        }
        pageBinding.inkHostContainer.addView(host)
        pageHosts.add(host)
        pageRoots.add(pageBinding.root)
        pageCompletedViews.add(pageBinding.completedStrokesView)
        pageTextboxLayers.add(pageBinding.textboxLayer)
        pageLassoOverlays.add(pageBinding.lassoOverlay)
        pageGridlineOverlays.add(pageBinding.gridlineOverlay)
        pageRulerOverlays.add(pageBinding.rulerOverlay)
        binding.pagesContainer.addView(pageBinding.root)

        // Phase 8b: pages sidebar / minimap chip (canvasUtilityPage) — a small
        // black strip with the page number; tap to jump (scroll) to that page.
        val chip = TextView(this).apply {
            text = pageNumber.toString()
            setTextColor(ContextCompat.getColor(this@CanvasActivity, R.color.canvas_page_number_strip_fg))
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                bottomMargin = dp(4)
            }
            background = ContextCompat.getDrawable(this@CanvasActivity, R.drawable.bg_page_number_strip)
            setOnClickListener { jumpToPage(pageIndex) }
        }
        binding.pagesSidebar.addView(chip)

        // Apply the current brush config + theme + stroke-inversion to the new page.
        applyBrushToHosts()
        applyTheme()
        pageBinding.completedStrokesView.setColorInverted(!canvasLight)
    }

    /** Jump-to-page (pages sidebar tap, canvasUtilityPage minimap): scroll the
     *  page surface to the Nth page item + set it as the document's current page. */
    private fun jumpToPage(pageIndex: Int) {
        document.goToPage(pageIndex)
        viewModel.goToPage(pageIndex)  // sync the VM's currentPageIndex for the indicator
        // The Nth page item is at child index pageIndex*2 in pagesContainer
        // (a dotted separator precedes each page except the first).
        val target = binding.pagesContainer.getChildAt(pageIndex * 2) ?: return
        binding.pageScroll.post {
            binding.pageScroll.smoothScrollTo(0, target.top)
        }
    }

    // ─── Phase 8b: live injection + theme toggle ──────────────────────────

    /** Paste-button action: take the clipboard item (clears the glow) + drop it
     *  onto the current page via [InkInjector]. The translated records are then
     *  rendered on-screen by the current page's [CompletedStrokesView] (Phase 8b:
     *  this makes injected/pasted strokes appear live — the live-injection UX). */
    private fun handlePaste() {
        val item = ThunderClipboard.take()
        if (item == null) {
            Toast.makeText(this, R.string.canvas_paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val idx = viewModel.uiState.value.currentPageIndex
        when (item) {
            is ClipboardItem.StrokeGroup -> {
                val records = injector.inject(item, dropX = 0f, dropY = 0f)
                // Render each injected stroke on the current page's completed-strokes layer.
                pageCompletedViews.getOrNull(idx)?.let { cv ->
                    records.forEach { cv.addFromRecord(it) }
                }
            }
            is ClipboardItem.TextBox -> {
                // Phase 9g: the textbox injection path is now wired — pasted
                // TEXT/CODE snip outputs land on the canvas. The injector adds
                // the TextBoxRecord to the document + returns it for rendering.
                val tb = injector.injectTextbox(item, dropX = 0f, dropY = 0f)
                if (tb != null) renderTextbox(idx, tb)
            }
        }
        syncUndoRedoFlags()
        Toast.makeText(this, R.string.canvas_paste_done, Toast.LENGTH_SHORT).show()
    }

    /** §6.2.5 Export PDF — rasterize the live canvas pages to a PdfDocument +
     *  share via FileProvider. Called from the Canvas Settings sheet's Export-PDF row. */
    override fun onExportPdf() {
        val name = viewModel.uiState.value.noteTitle.ifBlank { "note" }
        val file = com.thundernotes.export.PdfExporter.exportAndShare(
            this, name, pageRoots,
        )
        Toast.makeText(this,
            if (file != null) R.string.settings_export_pdf_done
            else R.string.settings_export_pdf_failed,
            Toast.LENGTH_SHORT).show()
    }

    /** TEXT-tool tap (spec §7.1 Textbox): open a text-input dialog + drop a
     *  [TextBoxRecord] at the tap coords on the current page's textbox layer. */
    private fun handleTextTap(x: Float, y: Float) {
        val state = viewModel.uiState.value
        val input = EditText(this).apply {
            hint = getString(R.string.canvas_textbox_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setLines(3)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.canvas_textbox_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isEmpty()) return@setPositiveButton
                val tb = TextBoxRecord(
                    pageId = document.currentPage?.id.orEmpty(),
                    text = text,
                    x = x, y = y,
                    colorArgb = (state.selectedColorArgb
                        ?: EditorPalette.COLORS.getOrNull(state.selectedColorIndex)
                        ?: 0xFF1A1A1A.toInt()),
                )
                document.addTextbox(tb)
                renderTextbox(state.currentPageIndex, tb)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Render a [TextBoxRecord] as a positioned TextView on the page's textbox layer.
     *
     * **Phase 9g:** when [TextBoxRecord.codeLanguage] is non-null (CODE snip
     * output), apply [com.thundernotes.snip.CodeFormatter.toSpannable] syntax
     * colours on top of the base Typeface (Patrick Hand by default). The
     * colours are font-agnostic spans, so a later font change to JetBrains
     * Mono (monospace) survives. The theme defaults to
     * [com.thundernotes.snip.CodeTheme.forLanguage].
     *
     * **Phase 9h:** applies [TextBoxRecord.fillColor] (spec §7.1 Fill Color)
     * + wires a long-press on the rendered textbox → [TextboxEditorBottomSheet]
     * (the full §7.1 formatting popup). On Apply, the old TextView is removed
     * + the updated record is re-rendered (same id → the document swap is
     * undoable).
     */
    private fun renderTextbox(pageIndex: Int, tb: TextBoxRecord) {
        val layer = pageTextboxLayers.getOrNull(pageIndex) ?: return
        val tv = TextView(this).apply {
            // Fill color (spec §7.1 Fill Color — background of the textbox).
            if (tb.fillColor != null) {
                setBackgroundColor(tb.fillColor)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            // Code snip → syntax-coloured Spannable; otherwise plain text.
            val lang = tb.codeLanguage
            if (lang != null) {
                val codeLang = com.thundernotes.snip.CodeLanguage.entries.firstOrNull {
                    it.displayName.equals(lang, ignoreCase = true) ||
                    it.name.equals(lang, ignoreCase = true)
                }
                if (codeLang != null) {
                    val theme = com.thundernotes.snip.CodeTheme.forLanguage(codeLang)
                    text = com.thundernotes.snip.CodeFormatter.toSpannable(tb.text, codeLang, theme)
                    // Code textbox: dark background so the theme colours read right
                    // (One Dark / Monokai / GitHub Dark all target dark bg).
                    // This overrides the user fill color for code (the syntax theme
                    // is paired with its bg); a future editor can make this optional.
                    setBackgroundColor(theme.background)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setTextColor(theme.plain)
                } else {
                    text = tb.text  // unknown language → plain
                    setTextColor(tb.colorArgb)
                }
            } else {
                text = tb.text
                setTextColor(tb.colorArgb)
            }
            textSize = tb.fontSizeSp
            typeface = com.thundernotes.ui.common.FontCache.get(tb.fontFamily)
            // Bold / italic via paint flags (the textbox editor can toggle these).
            if (tb.bold) paintFlags = paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
            if (tb.italic) typeface = android.graphics.Typeface.create(
                com.thundernotes.ui.common.FontCache.get(tb.fontFamily),
                android.graphics.Typeface.ITALIC,
            )
            // Underline (spec §7.1 lists 4 styles; thin is the baseline).
            if (tb.underline != 0) {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            }
            // Long-press → open the §7.1 editor popup (edit text + formatting).
            setOnLongClickListener {
                TextboxEditorBottomSheet.newInstance(tb) { updated ->
                    applyTextboxEdit(pageIndex, tb.id, updated)
                }.show(supportFragmentManager, "textbox_editor")
                true
            }
            // Tag the view with the textbox id so applyTextboxEdit can find it.
            tag = tb.id
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = tb.x.toInt(); topMargin = tb.y.toInt()
            // FILLER stamp (§6.10.6f) has a fixed size → use it; otherwise
            // WRAP_CONTENT so the textbox grows with its text.
            tb.widthPx?.let { width = it.toInt() }
            tb.heightPx?.let { height = it.toInt() }
        }
        layer.addView(tv, lp)
    }

    /**
     * Apply a textbox edit (§7.1 editor Apply): remove the old TextView from
     * the page's textbox layer + re-render with the updated record. The
     * [CanvasDocument] swap is done via [CanvasDocument.updateTextbox] (so
     * the change is undoable). Phase 9h.
     */
    private fun applyTextboxEdit(pageIndex: Int, textboxId: String, updated: TextBoxRecord) {
        val layer = pageTextboxLayers.getOrNull(pageIndex) ?: return
        // Find + remove the old TextView whose tag matches the textbox id.
        for (i in 0 until layer.childCount) {
            val child = layer.getChildAt(i)
            if (child.tag == textboxId) { layer.removeViewAt(i); break }
        }
        // Swap the record in the document (undoable) + re-render (tagged).
        document.updateTextbox(updated)
        renderTextbox(pageIndex, updated)
    }

    // ─── Phase 8b: Lasso (spec §7.2 — select + 9 functions) ────────────────

    /** Live lasso-drag update: draw the rect on the page's overlay. */
    private fun handleLassoDrag(pageIndex: Int, l: Float, t: Float, r: Float, b: Float) {
        pageLassoOverlays.getOrNull(pageIndex)?.setRect(l, t, r, b)
    }

    /** Lasso drag finalized: select strokes by rect-intersect + show the
     *  9-function context menu (Cut/Copy/Rotate/Enlarge/Reduce/Change Color/
     *  Change Stroke Thickness/Flip H/Flip V/Delete). */
    private fun handleLassoEnd(pageIndex: Int, l: Float, t: Float, r: Float, b: Float) {
        val overlay = pageLassoOverlays.getOrNull(pageIndex)
        overlay?.clear()
        // Operate on the pageIndex the user drew on.
        document.goToPage(pageIndex)
        val sel = LassoSelector.selectByRect(document.currentStrokes, l, t, r, b)
        if (sel.isEmpty) {
            Toast.makeText(this, R.string.canvas_lasso_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val selected = document.currentStrokes.filter { it.id in sel.strokeIds }
        val host = pageHosts.getOrNull(pageIndex) ?: return
        val cv = pageCompletedViews.getOrNull(pageIndex)
        val anchor: View = host
        showLassoMenu(anchor, pageIndex, selected, cv, host)
    }

    /** SHAPE-tool drag finalized (spec §6.10 Row 3g Shape Picker): clear the
     *  preview overlay + build the shape (rect/circle/line) via [ShapeGeometry]
     *  inscribed in the drag box + add its strokes to the document + render on
     *  the page's CompletedStrokesView. Uses the active pen color + stroke width. */
    private fun handleShapeEnd(pageIndex: Int, l: Float, t: Float, r: Float, b: Float) {
        pageLassoOverlays.getOrNull(pageIndex)?.clear()
        document.goToPage(pageIndex)
        val state = viewModel.uiState.value
        val color = state.selectedColorArgb ?: EditorPalette.COLORS.first()
        val width = state.selectedStrokeWidthDp ?: EditorStrokeWidths.WIDTHS_DP[EditorStrokeWidths.DEFAULT_WIDTH_INDEX]
        // §6.10.4d magnetic snapping: if the grid is visible, snap both drag-
        // box corners to the nearest grid intersection before building the shape.
        var sl = l; var st = t; var sr = r; var sb = b
        if (state.gridVisible) {
            val grid = pageGridlineOverlays.getOrNull(pageIndex)
            if (grid != null && grid.visibility == View.VISIBLE) {
                val (sl2, st2) = grid.snapToGrid(l, t)
                val (sr2, sb2) = grid.snapToGrid(r, b)
                sl = sl2; st = st2; sr = sr2; sb = sb2
            }
        }
        val strokes = com.thundernotes.canvas.lasso.ShapeGeometry.buildShape(
            state.shapeType, sl, st, sr, sb, color, width,
        )
        val cv = pageCompletedViews.getOrNull(pageIndex)
        for (s in strokes) {
            // Re-stamp the page id (ShapeGeometry uses a "shape" placeholder).
            val record = s.copy(pageId = document.currentPage?.id.orEmpty())
            document.addStroke(record)
            cv?.addFromRecord(record)
        }
        syncUndoRedoFlags()
    }

    /**
     * FILLER-tool tap (spec §6.10.6f: "Fills enclosed areas; 2 options — Fill
     * Color, Fill Opacity"). On a vector canvas, true flood-fill is complex
     * (rasterize → fill → re-vectorize); this implementation drops a **filled
     * rect stamp** at the tap point — a [TextBoxRecord] with empty text + a
     * [TextBoxRecord.fillColor] (the selected color at ~50% alpha). The stroke-
     * width slider doubles as the stamp size. Functional + visible + erasable
     * (it's an ordinary textbox → the eraser + lasso work on it). A future
     * editor can expose the opacity slider + true flood-fill.
     */
    private fun handleFillTap(x: Float, y: Float) {
        val state = viewModel.uiState.value
        val color = state.selectedColorArgb ?: EditorPalette.COLORS.first()
        // Bake 50% alpha into the fill color (spec §6.10.6f Fill Opacity).
        val alpha = (0x80 shl 24) or (color and 0x00FFFFFF)
        val sizeDp = state.selectedStrokeWidthDp ?: 3.0f
        val sizePx = sizeDp * 20f * resources.displayMetrics.density  // stamp ≈ 20× the stroke width
        val pageId = document.currentPage?.id.orEmpty()
        val tb = com.thundernotes.canvas.TextBoxRecord(
            pageId = pageId,
            text = "",
            x = x - sizePx / 2f,
            y = y - sizePx / 2f,
            fillColor = alpha,
            widthPx = sizePx,
            heightPx = sizePx,
        )
        document.addTextbox(tb)
        renderTextbox(state.currentPageIndex, tb)
        Toast.makeText(this, R.string.canvas_fill_done, Toast.LENGTH_SHORT).show()
    }

    /** Table Maker (§6.10 Row 3g): build a [rows]×[cols] table grid at the page
     *  centre + add each grid-line stroke to the document + CompletedStrokesView. */
    private fun handleTableCreated(
        rows: Int, cols: Int,
        borderVisible: Boolean = true,
        headerColor: Int? = null,
        altRowColor: Int? = null,
    ) {
        val idx = viewModel.uiState.value.currentPageIndex
        document.goToPage(idx)
        val cv = pageCompletedViews.getOrNull(idx)
        val state = viewModel.uiState.value
        val color = state.selectedColorArgb ?: EditorPalette.COLORS.first()
        val width = state.selectedStrokeWidthDp ?: EditorStrokeWidths.WIDTHS_DP[EditorStrokeWidths.DEFAULT_WIDTH_INDEX]
        val strokes = com.thundernotes.canvas.lasso.TableGeometry.buildTable(
            x = 50f, y = 50f, w = 600f, h = 400f, rows = rows, cols = cols,
            colorArgb = color, brushSize = width,
            borderVisible = borderVisible,
            borderThickness = width * 1.3f,
            headerColor = headerColor,
            altRowColor = altRowColor,
        )
        for (s in strokes) {
            val record = s.copy(pageId = document.currentPage?.id.orEmpty())
            document.addStroke(record)
            cv?.addFromRecord(record)
        }
        syncUndoRedoFlags()
    }

    private fun showLassoMenu(
        anchor: View,
        pageIndex: Int,
        selected: List<StrokeRecord>,
        cv: CompletedStrokesView?,
        host: CanvasInkHost?,
    ) {
        // Spec §7.5: constant-scaling ON → Enlarge/Reduce keeps stroke thickness;
        // OFF → thickness scales with the resize.
        val scaleBrush = !viewModel.uiState.value.constantScaling
        val menu = PopupMenu(this, anchor)
        val items = listOf(
            "Cut", "Copy", "Rotate 90°", "Enlarge 1.5×", "Reduce 0.66×",
            "Change Color", "Thicker 1.5×", "Thinner 0.66×",
            "Flip Horizontal", "Flip Vertical", "Delete",
            "Shift Up 50dp", "Shift Down 50dp", "Shift Left 50dp", "Shift Right 50dp",
        )
        items.forEachIndexed { idx, label -> menu.menu.add(0, idx, idx, label) }
        menu.setOnMenuItemClickListener { item ->
            val sel = selected  // capture
            val shift = dp(50).toFloat()
            when (item.itemId) {
                0 -> { // Cut — clipboard + remove (drawn: host; injected: cv)
                    LassoOps.cut(document, sel)
                    sel.forEach { host?.removeStrokeFromView(it.id); cv?.remove(it.id) }
                    syncUndoRedoFlags()
                }
                1 -> { // Copy — clipboard only
                    LassoOps.copy(sel)
                }
                2 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.rotate(it, 90f) }
                3 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.scale(it, 1.5f, scaleBrush) }
                4 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.scale(it, 0.66f, scaleBrush) }
                5 -> { // Change Color — the currently-selected pen color
                    val color = viewModel.uiState.value.selectedColorArgb
                        ?: EditorPalette.COLORS.getOrNull(viewModel.uiState.value.selectedColorIndex)
                        ?: 0xFF1A1A1A.toInt()
                    applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.changeColor(it, color) }
                }
                6 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.changeStrokeThickness(it, 1.5f) }
                7 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.changeStrokeThickness(it, 0.66f) }
                8 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.flipHorizontal(it) }
                9 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.flipVertical(it) }
                10 -> { // Delete — remove (no clipboard)
                    LassoOps.delete(document, sel)
                    sel.forEach { host?.removeStrokeFromView(it.id); cv?.remove(it.id) }
                    syncUndoRedoFlags()
                }
                // §6.10 Row 3d/e/f — Vertical/Horizontal Shifters (lasso-selected content repositions).
                11 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.translate(it, 0f, -shift) }
                12 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.translate(it, 0f, shift) }
                13 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.translate(it, -shift, 0f) }
                14 -> applyTransform(pageIndex, sel, cv, host) { StrokeTransforms.translate(it, shift, 0f) }
            }
            true
        }
        menu.show()
    }

    /** Apply a [StrokeTransforms] op to the selected strokes: replace each in the
     *  document + re-render on the CompletedStrokesView (remove old + addFromRecord
     *  the new). Drawn strokes (on the InProgressStrokesView) are removed from
     *  the host + re-rendered on the CompletedStrokesView post-transform. */
    private fun applyTransform(
        pageIndex: Int,
        selected: List<StrokeRecord>,
        cv: CompletedStrokesView?,
        host: CanvasInkHost?,
        op: (List<StrokeRecord>) -> List<StrokeRecord>,
    ) {
        val transformed = op(selected)
        for (i in selected.indices) {
            val old = selected[i]
            val new = transformed[i]
            document.replaceStroke(new)              // model: swap by id
            host?.removeStrokeFromView(old.id)       // drawn-stroke: off the in-progress view
            cv?.remove(old.id)                        // injected-stroke: off the completed view (no redo buffer)
            cv?.addFromRecord(new)                    // re-render the transformed stroke on the completed view
        }
        syncUndoRedoFlags()
    }

    /** Observe the clipboard → toggle the paste button's glow (emerald ring
     *  while ThunderClipboard holds 1 item, spec §6.10 Row 2). */
    private fun observeClipboard() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ThunderClipboard.item.collect { item -> applyPasteGlow(item != null) }
            }
        }
    }

    private fun applyPasteGlow(hasItem: Boolean) {
        binding.btnPaste.background = ContextCompat.getDrawable(
            this,
            if (hasItem) R.drawable.bg_canvas_paste_glow else R.drawable.bg_canvas_tool,
        )
        binding.btnPaste.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (hasItem) R.color.canvas_secondary else R.color.canvas_tool_inactive_tint,
            )
        )
    }

    /** Apply the canvas-area theme (light ↔ dark page cards, spec §6.10 Row 1 right).
     *  Also pushes smart color inversion (preserve hue, invert lightness) into every
     *  page's [CompletedStrokesView] so injected/pasted strokes invert with the theme. */
    private fun applyTheme() {
        val bg = if (canvasLight) R.drawable.bg_canvas_page_lined else R.drawable.bg_canvas_page_dark
        ContextCompat.getDrawable(this, bg)?.let { d ->
            pageRoots.forEach { it.background = d } }
        pageCompletedViews.forEach { it.setColorInverted(!canvasLight) }
    }

    /** Show/hide the Scale ruler on every page (spec §6.10 Row 2c). The ruler
     *  is draggable + rotatable (long-press the button → angle presets). */
    private fun applyRuler() {
        pageRulerOverlays.forEach { ov ->
            ov.visibility = if (rulerVisible) View.VISIBLE else View.GONE
            if (rulerVisible) ov.initAtCentre()
        }
        binding.btnRuler.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this,
                if (rulerVisible) R.color.canvas_secondary else R.color.canvas_tool_inactive_tint)
        )
    }

    /** Image insert callback (§6.10 Row 2b): load the picked image + drop an
     *  ImageView on the current page's textbox layer. Downsamples to ≤1024px on
     *  the long edge to avoid OOM. (Persisting the ImageEntity to the .thunder DB
     *  is a later step; the in-memory + on-canvas display is now.) */
    private fun handleImagePicked(uri: android.net.Uri) {
        val idx = viewModel.uiState.value.currentPageIndex
        val layer = pageTextboxLayers.getOrNull(idx)
        if (layer == null) {
            Toast.makeText(this, R.string.canvas_image_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Take the persistable read permission so the Uri survives (best-effort).
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // Decode on a background coroutine (Dispatchers.IO), then add the ImageView
        // on the main thread.
        lifecycleScope.launch {
            val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    val longEdge = maxOf(opts.outWidth, opts.outHeight)
                    var sample = 1
                    while (longEdge / sample > 1024) sample *= 2
                    val dec = BitmapFactory.Options().apply { inSampleSize = sample }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, dec) }
                }.getOrNull()
            }
            if (bmp == null) {
                Toast.makeText(this@CanvasActivity, R.string.canvas_image_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val iv = android.widget.ImageView(this@CanvasActivity).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxWidth = dp(320); maxHeight = dp(320)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(16); topMargin = dp(16) }
            }
            layer.addView(iv)
        }
    }

    /** A finished stroke arrived from a host → record it in the document. */
    private fun handleStrokeFinished(record: StrokeRecord) {
        document.addStroke(record)
        syncUndoRedoFlags()
    }

    private fun handleUndo() {
        val undone = document.undo()
        if (undone) {
            // Phase 8b: remove the undone stroke from whichever layer renders it.
            // Drawn strokes live on the InProgressStrokesView (host); injected/pasted
            // strokes live on the CompletedStrokesView. Try both — only the one that
            // has the stroke succeeds. (RemoveStroke/AddPage undos are model-only.)
            val action = document.lastUndoneAction
            if (action is com.thundernotes.canvas.DocAction.AddStroke) {
                val idx = viewModel.uiState.value.currentPageIndex
                pageHosts.getOrNull(idx)?.removeStrokeFromView(action.stroke.id)
                pageCompletedViews.getOrNull(idx)?.remove(action.stroke.id)
            }
        }
        syncUndoRedoFlags()
    }

    private fun handleRedo() {
        val redone = document.redo()
        val idx = viewModel.uiState.value.currentPageIndex
        val cv = pageCompletedViews.getOrNull(idx)
        if (redone && cv != null) {
            // Phase 8b: live redo for BOTH injected + drawn strokes.
            // - Injected/pasted strokes: the CompletedStrokesView keeps a redo
            //   buffer (its .remove buffered them) → .redo() re-adds the live Stroke.
            // - Drawn strokes: undo removed them via CompletedStrokesView.remove
            //   (which also buffered them) → .redo() re-adds. As a fallback, if the
            //   CompletedStrokesView's redo buffer is empty (e.g. the stroke was
            //   undone via the InProgressStrokesView path), re-render from the
            //   document's redone record via addFromRecord.
            val fromBuffer = cv.redo()
            if (!fromBuffer) {
                val action = document.lastRedoneAction
                if (action is com.thundernotes.canvas.DocAction.AddStroke) {
                    cv.addFromRecord(action.stroke)
                }
            }
        }
        syncUndoRedoFlags()
    }

    /** Push the document's undo/redo availability into the VM so the buttons
     *  enable/disable + the render() flow re-renders them. */
    private fun syncUndoRedoFlags() {
        viewModel.markUndoAvailable(document.canUndo)
        viewModel.markRedoAvailable(document.canRedo)
    }

    /** Apply the VM's selected tool/color/width to every page host via the registry. */
    private fun applyBrushToHosts() {
        val state = viewModel.uiState.value
        val color = state.selectedColorArgb
            ?: EditorPalette.COLORS.getOrNull(state.selectedColorIndex)
            ?: EditorPalette.COLORS.first()
        val width = state.selectedStrokeWidthDp
            ?: EditorStrokeWidths.WIDTHS_DP.getOrNull(state.strokeWidthIndex)
            ?: EditorStrokeWidths.WIDTHS_DP[EditorStrokeWidths.DEFAULT_WIDTH_INDEX]
        val config = BrushRegistry.configFor(state.selectedTool, color, width)
        pageHosts.forEach {
            it.setBrush(config)
            it.currentTool = state.selectedTool
            it.currentLineType = state.selectedLineType
            it.palmRejection = state.palmRejection
            // onTextTap / onLassoDrag / onLassoEnd are set per-host in addPageItem
            // (with the page index captured) — don't clobber them here.
        }
    }

    /** Scale the pages container by the VM's zoom percentage. */
    private fun applyZoom() {
        val state = viewModel.uiState.value
        val scale = state.zoomPercent / 100f
        binding.pagesContainer.scaleX = scale
        binding.pagesContainer.scaleY = scale
    }

    /** Capture the canvas page surface as a Bitmap (for the AI-snip pipeline).
     *  Captures the View hierarchy (CompletedStrokesView + textboxLayer + etc.);
     *  the GL InProgressStrokesView's live pen trail isn't captured (only finished
     *  strokes — which is what the snip should recognize). */
    private fun captureCanvas(): Bitmap? {
        return try {
            val view = binding.pageScroll
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            view.draw(canvas)
            bmp
        } catch (e: Exception) { null }
    }

    private var lastBuiltPaletteIndex: Int = -1

    private fun buildColorSwatches() {
        val paletteIndex = viewModel.uiState.value.selectedPaletteIndex
        if (paletteIndex == lastBuiltPaletteIndex && swatchViews.isNotEmpty()) return
        lastBuiltPaletteIndex = paletteIndex
        binding.colorSwatchesContainer.removeAllViews()
        swatchViews.clear()
        val palette = EditorPalette.PALETTES.getOrNull(paletteIndex) ?: EditorPalette.PALETTES.first()
        palette.forEachIndexed { index, argb ->
            val swatch = makeSwatchView(index, argb)
            binding.colorSwatchesContainer.addView(swatch)
            swatchViews.add(swatch)
        }
    }

    private fun buildStrokeWidthDots() {
        binding.strokeWidthContainer.removeAllViews()
        strokeWidthViews.clear()
        EditorStrokeWidths.WIDTHS_DP.forEachIndexed { index, widthDp ->
            val dot = makeStrokeWidthDot(index, widthDp)
            binding.strokeWidthContainer.addView(dot)
            strokeWidthViews.add(dot)
        }
    }

    private fun makeSwatchView(index: Int, argb: Int): View {
        val sizePx = dp(28)
        val gap = dp(8)
        val outer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = gap
            }
        }
        // Colored circle
        val circle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = android.view.Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(argb)
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.canvas_color_swatch, index)
            setOnClickListener { viewModel.selectColor(index) }
        }
        outer.addView(circle)
        outer.contentDescription = circle.contentDescription
        return outer
    }

    private fun makeStrokeWidthDot(index: Int, widthDp: Float): View {
        val dotSizeDp = (widthDp + 6).toInt().coerceAtLeast(14)
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(dotSizeDp + 8), dp(28)).apply {
                marginEnd = dp(6)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { viewModel.setStrokeWidth(index) }
        }
        val dot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(dotSizeDp), dp(dotSizeDp)).apply {
                gravity = android.view.Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@CanvasActivity, R.color.canvas_on_surface))
            }
        }
        container.addView(dot)
        container.contentDescription = getString(R.string.canvas_stroke_width, index + 1)
        return container
    }

    // ─── state observation ───────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: EditorUiState) {
        // Title (don't clobber the EditText while the user is typing).
        if (!titleEditing && binding.noteTitle.text?.toString() != state.noteTitle) {
            binding.noteTitle.setText(state.noteTitle)
        }

        // Page indicator + zoom
        binding.pageIndicator.text = getString(
            R.string.canvas_page_indicator,
            state.currentPageIndex + 1,
            state.totalPages.coerceAtLeast(1),
        )
        binding.zoomIndicator.text = getString(R.string.canvas_zoom_pct, state.zoomPercent)

        // Tool selection highlight
        toolButtonIds.forEach { (tool, id) ->
            val btn = findViewById<ImageButton>(id)
            val active = state.selectedTool == tool
            btn.background = ContextCompat.getDrawable(
                this,
                if (active) R.drawable.bg_canvas_tool_active else R.drawable.bg_canvas_tool,
            )
            btn.imageTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.canvas_tool_active_tint
                    else R.color.canvas_tool_inactive_tint,
                )
            )
        }

        // Secondary options row visibility
        binding.optionsRow.visibility =
            if (state.showsColorPicker || state.showsStrokeWidth) View.VISIBLE else View.GONE
        binding.paletteSwitcher.visibility =
            if (state.showsColorPicker) View.VISIBLE else View.GONE
        binding.paletteSwitcher.text =
            EditorPalette.PALETTE_NAMES.getOrNull(state.selectedPaletteIndex)
                ?: EditorPalette.PALETTE_NAMES.first()
        binding.lineTypeSwitcher.visibility =
            if (state.showsLineType) View.VISIBLE else View.GONE
        binding.lineTypeSwitcher.text =
            state.selectedLineType.name.lowercase().replaceFirstChar { it.uppercase() }
        // Shape-type switcher — visible only for the SHAPE tool.
        binding.shapeTypeSwitcher.visibility =
            if (state.selectedTool == EditorTool.SHAPE) View.VISIBLE else View.GONE
        binding.shapeTypeSwitcher.text =
            state.shapeType.name.lowercase().replaceFirstChar { it.uppercase() }
        // Phase 9h: propagate the eraser type + size + lasso mode to every
        // page's ink host (spec §6.10.6d + §6.10.6e).
        pageHosts.forEach { host ->
            host.eraserType = state.eraserType
            host.eraserRadiusPx = state.eraserSizeDp * resources.displayMetrics.density
        }
        // Gridline overlay per page (§6.10 Row 2c) — toggle + size.
        pageGridlineOverlays.forEach { ov ->
            if (state.gridVisible) { ov.show(state.gridRows, state.gridCols); ov.visibility = View.VISIBLE }
            else ov.visibility = View.GONE }
        // Gridline button glows when the grid is on.
        binding.btnGridline.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this,
                if (state.gridVisible) R.color.canvas_secondary else R.color.canvas_tool_inactive_tint)
        )
        binding.colorSwatchesContainer.visibility =
            if (state.showsColorPicker) View.VISIBLE else View.GONE
        binding.strokeWidthContainer.visibility =
            if (state.showsStrokeWidth) View.VISIBLE else View.GONE
        // Rebuild the swatches if the active palette changed (palette switcher).
        buildColorSwatches()

        // Swatch selection ring (re-apply selected drawable to the active swatch's FrameLayout)
        swatchViews.forEachIndexed { i, v ->
            v.background = if (i == state.selectedColorIndex && state.showsColorPicker)
                ContextCompat.getDrawable(this, R.drawable.bg_canvas_swatch_selected)
            else null
        }

        // Stroke-width selection: scale up the selected dot a bit
        strokeWidthViews.forEachIndexed { i, v ->
            v.alpha = if (i == state.strokeWidthIndex && state.showsStrokeWidth) 1f else 0.45f
        }

        // Undo/redo enabled state
        binding.btnUndo.isEnabled = state.canUndo
        binding.btnUndo.alpha = if (state.canUndo) 1f else 0.4f
        binding.btnRedo.isEnabled = state.canRedo
        binding.btnRedo.alpha = if (state.canRedo) 1f else 0.4f

        // Phase 8: push the tool/color/width + zoom into the real Ink hosts.
        applyBrushToHosts()
        applyZoom()

        // Multi-file tab system (§6.10 Row 1): register the active note in
        // CanvasTabs + keep the tab strip in sync with the real title.
        if (state.noteId.isNotBlank() && state.noteTitle.isNotBlank()) {
            val activeTab = CanvasTabs.tabs.value.firstOrNull { it.noteId == state.noteId }
            if (activeTab == null || activeTab.title != state.noteTitle) {
                CanvasTabs.open(state.noteId, state.noteTitle)
                buildTabsRow()
            }
        }

        // Error surfacing
        state.errorMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NOTE_ID = "com.thundernotes.extra.NOTE_ID"

        /** Launch the editor for an existing note (noteId may be null for a new one). */
        fun launch(context: Context, noteId: String? = null) {
            val intent = Intent(context, CanvasActivity::class.java)
            noteId?.let { intent.putExtra(EXTRA_NOTE_ID, it) }
            context.startActivity(intent)
        }
    }
}
