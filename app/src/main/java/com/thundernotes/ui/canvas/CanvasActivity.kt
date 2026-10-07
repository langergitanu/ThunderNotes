package com.thundernotes.ui.canvas

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.thundernotes.canvas.inject.InkInjector
import com.thundernotes.canvas.inject.ThunderClipboard
import com.thundernotes.databinding.ActivityCanvasBinding
import com.thundernotes.databinding.ItemCanvasPageBinding
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
class CanvasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCanvasBinding
    private val viewModel: NoteEditorViewModel by viewModels()

    // Map tool → the ImageButton id so render() can highlight the active one.
    private val toolButtonIds: List<Pair<EditorTool, Int>> = listOf(
        EditorTool.PEN to R.id.toolPen,
        EditorTool.HIGHLIGHTER to R.id.toolHighlighter,
        EditorTool.ERASER to R.id.toolEraser,
        EditorTool.LASSO to R.id.toolLasso,
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
    private val injector = InkInjector(document)
    private var canvasLight = true   // spec §6.10 Row 1 right theme toggle state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCanvasBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.btnAiSnip.setOnClickListener {
            // SnipEngine fallback chain is Phase 9.
            Toast.makeText(this, R.string.canvas_ai_snip_pending, Toast.LENGTH_SHORT).show()
        }
    }

    private fun wireToolTray() {
        toolButtonIds.forEach { (tool, id) ->
            findViewById<ImageButton>(id).setOnClickListener { viewModel.selectTool(tool) }
        }
    }

    // ─── Phase 8: page-host lifecycle + document bridge ─────────────────────

    /** Inflate a page item, host a [CanvasInkHost] in it, wire its finished-stroke
     *  callback to the document, and append it (with a dotted separator before it,
     *  except for the first page). */
    private fun addPageItem(pageNumber: Int) {
        if (pageNumber > 1) {
            // Dotted separator between pages (spec §6.10: no gap, just a dotted line).
            val sep = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 4
                ).apply { topMargin = dp(8); bottomMargin = dp(8) }
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
            // Phase 8b: TEXT + LASSO tool routing (per-page, with the page index
            // captured so the activity targets the right page's overlays/layers).
            onTextTap = { x, y -> handleTextTap(x, y) }
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
        val records = injector.inject(item, dropX = 0f, dropY = 0f)
        // Render each injected stroke on the current page's completed-strokes layer.
        val idx = viewModel.uiState.value.currentPageIndex
        pageCompletedViews.getOrNull(idx)?.let { cv ->
            records.forEach { cv.addFromRecord(it) }
        }
        syncUndoRedoFlags()
        Toast.makeText(this, R.string.canvas_paste_done, Toast.LENGTH_SHORT).show()
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

    /** Render a [TextBoxRecord] as a positioned TextView on the page's textbox layer. */
    private fun renderTextbox(pageIndex: Int, tb: TextBoxRecord) {
        val layer = pageTextboxLayers.getOrNull(pageIndex) ?: return
        val tv = TextView(this).apply {
            text = tb.text
            setTextColor(tb.colorArgb)
            textSize = tb.fontSizeSp
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                when {
                    tb.bold && tb.italic -> android.graphics.Typeface.BOLD_ITALIC
                    tb.bold -> android.graphics.Typeface.BOLD
                    tb.italic -> android.graphics.Typeface.ITALIC
                    else -> android.graphics.Typeface.NORMAL
                },
            )
            // Thin underline (spec §7.1 lists 4 underline styles; thin is the baseline).
            if (tb.underline != 0) {
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = tb.x.toInt(); topMargin = tb.y.toInt()
        }
        layer.addView(tv, lp)
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
        val strokes = com.thundernotes.canvas.lasso.ShapeGeometry.buildShape(
            state.shapeType, l, t, r, b, color, width,
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
        )
        items.forEachIndexed { idx, label -> menu.menu.add(0, idx, idx, label) }
        menu.setOnMenuItemClickListener { item ->
            val sel = selected  // capture
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
