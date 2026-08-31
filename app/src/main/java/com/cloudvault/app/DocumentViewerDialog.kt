package com.cloudvault.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

object DocumentViewerDialog {

    private val CODE_EXTENSIONS = setOf(
        "txt", "log", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
        "kt", "kts", "java", "py", "c", "cpp", "h", "hpp", "cs", "php", "rb", "go",
        "rs", "swift", "dart", "sh", "bash", "zsh", "md", "markdown", "csv", "tsv",
        "sql", "conf", "config", "yaml", "yml", "ini", "env", "gradle", "properties",
        "svg", "proto", "graphql", "bat", "ps1"
    )

    fun isViewableDocument(fileName: String, mimeType: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "pdf" || ext == "epub" || CODE_EXTENSIONS.contains(ext) ||
                mimeType.startsWith("text/") || mimeType.contains("json") ||
                mimeType.contains("xml") || mimeType.contains("javascript") ||
                mimeType.contains("pdf")
    }

    fun show(activity: Activity, item: VaultMediaItem) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_document_viewer, null)
        val tvDocIcon: TextView = view.findViewById(R.id.tvDocIcon)
        val tvDocTitle: TextView = view.findViewById(R.id.tvDocTitle)
        val tvDocSubtitle: TextView = view.findViewById(R.id.tvDocSubtitle)
        val btnDocOrientation: TextView = view.findViewById(R.id.btnDocOrientation)
        val btnDocSearch: TextView = view.findViewById(R.id.btnDocSearch)
        val btnDocWrap: TextView = view.findViewById(R.id.btnDocWrap)
        val btnDocCopy: TextView = view.findViewById(R.id.btnDocCopy)
        val btnDocDownload: TextView = view.findViewById(R.id.btnDocDownload)
        val btnDocClose: TextView = view.findViewById(R.id.btnDocClose)

        val layoutDocSearchBar: LinearLayout = view.findViewById(R.id.layoutDocSearchBar)
        val etDocSearchInput: EditText = view.findViewById(R.id.etDocSearchInput)
        val tvDocSearchCount: TextView = view.findViewById(R.id.tvDocSearchCount)
        val btnDocSearchPrev: TextView = view.findViewById(R.id.btnDocSearchPrev)
        val btnDocSearchNext: TextView = view.findViewById(R.id.btnDocSearchNext)
        val btnDocSearchClose: TextView = view.findViewById(R.id.btnDocSearchClose)

        val layoutPdfContainer: FrameLayout = view.findViewById(R.id.layoutPdfContainer)
        val vpPdfPages: ViewPager2 = view.findViewById(R.id.vpPdfPages)
        val btnPdfPrevPage: TextView = view.findViewById(R.id.btnPdfPrevPage)
        val tvPdfPageIndicator: TextView = view.findViewById(R.id.tvPdfPageIndicator)
        val btnPdfNextPage: TextView = view.findViewById(R.id.btnPdfNextPage)
        val btnPdfZoomOut: TextView = view.findViewById(R.id.btnPdfZoomOut)
        val btnPdfZoomReset: TextView = view.findViewById(R.id.btnPdfZoomReset)
        val btnPdfZoomIn: TextView = view.findViewById(R.id.btnPdfZoomIn)

        val layoutCodeContainer: ScrollView = view.findViewById(R.id.layoutCodeContainer)
        val hsvCode: HorizontalScrollView = view.findViewById(R.id.hsvCode)
        val tvLineNumbers: TextView = view.findViewById(R.id.tvLineNumbers)
        val tvCodeContent: TextView = view.findViewById(R.id.tvCodeContent)

        val wvDocWeb: WebView = view.findViewById(R.id.wvDocWeb)
        val layoutDocLoading: LinearLayout = view.findViewById(R.id.layoutDocLoading)
        val tvDocLoadingText: TextView = view.findViewById(R.id.tvDocLoadingText)

        val ext = item.title.substringAfterLast('.', "").lowercase()
        tvDocTitle.text = item.title
        tvDocSubtitle.text = "${ext.uppercase()} • ${item.formattedSize}"

        when (ext) {
            "pdf" -> tvDocIcon.text = "📕"
            "epub" -> tvDocIcon.text = "📚"
            "md", "markdown" -> tvDocIcon.text = "📝"
            "json", "xml", "html", "csv" -> tvDocIcon.text = "📊"
            in CODE_EXTENSIONS -> tvDocIcon.text = "💻"
            else -> tvDocIcon.text = "📄"
        }

        val dialog = Dialog(activity, R.style.Theme_CloudVault_Dialog_Fullscreen)
        dialog.setContentView(view)

        btnDocClose.setOnClickListener { dialog.dismiss() }
        btnDocDownload.setOnClickListener {
            DownloadManager.startDownload(activity, item)
            Toast.makeText(activity, "Downloading ${item.title}...", Toast.LENGTH_SHORT).show()
        }

        var fullLoadedText = ""
        var isWrapped = false
        var currentPdfRenderer: PdfRenderer? = null
        var currentPdfPfd: ParcelFileDescriptor? = null
        var pdfAdapter: PdfPagerAdapter? = null
        var totalPdfPages = 0
        var isHorizontalOrientation = true

        val lifecycleOwner = activity as? LifecycleOwner
        val coroutineScope = lifecycleOwner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main)

        // Helper to get currently visible ZoomableImageView
        fun getActiveZoomableView(): ZoomableImageView? {
            val currentPos = vpPdfPages.currentItem
            val holder = (vpPdfPages.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(currentPos)
            return (holder as? PdfPagerAdapter.PageViewHolder)?.ivZoomablePdfPage
        }

        // PDF Page navigation
        btnPdfPrevPage.setOnClickListener {
            if (vpPdfPages.currentItem > 0) {
                vpPdfPages.setCurrentItem(vpPdfPages.currentItem - 1, true)
            }
        }

        btnPdfNextPage.setOnClickListener {
            if (vpPdfPages.currentItem < totalPdfPages - 1) {
                vpPdfPages.setCurrentItem(vpPdfPages.currentItem + 1, true)
            }
        }

        // Zoom in, Zoom out, Zoom reset
        btnPdfZoomIn.setOnClickListener {
            getActiveZoomableView()?.zoomIn()
            val scale = getActiveZoomableView()?.getScale() ?: 1f
            btnPdfZoomReset.text = "${(scale * 100).toInt()}%"
        }

        btnPdfZoomOut.setOnClickListener {
            getActiveZoomableView()?.zoomOut()
            val scale = getActiveZoomableView()?.getScale() ?: 1f
            btnPdfZoomReset.text = "${(scale * 100).toInt()}%"
        }

        btnPdfZoomReset.setOnClickListener {
            getActiveZoomableView()?.resetZoom()
            btnPdfZoomReset.text = "100%"
        }

        // Toggle Horizontal vs Vertical Page Scrolling
        btnDocOrientation.setOnClickListener {
            isHorizontalOrientation = !isHorizontalOrientation
            vpPdfPages.orientation = if (isHorizontalOrientation) {
                ViewPager2.ORIENTATION_HORIZONTAL
            } else {
                ViewPager2.ORIENTATION_VERTICAL
            }
            btnDocOrientation.text = if (isHorizontalOrientation) "↔" else "↕"
            Toast.makeText(
                activity,
                if (isHorizontalOrientation) "Horizontal Swipe Mode" else "Vertical Scroll Mode",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Click on page indicator to Jump to Page
        tvPdfPageIndicator.setOnClickListener {
            if (totalPdfPages <= 1) return@setOnClickListener
            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "1 - $totalPdfPages"
                setText("${vpPdfPages.currentItem + 1}")
                setSelection(text.length)
            }
            AlertDialog.Builder(activity)
                .setTitle("Go to Page (1 - $totalPdfPages)")
                .setView(input)
                .setPositiveButton("Jump") { _, _ ->
                    val pageNum = input.text.toString().toIntOrNull()
                    if (pageNum != null && pageNum in 1..totalPdfPages) {
                        vpPdfPages.setCurrentItem(pageNum - 1, true)
                    } else {
                        Toast.makeText(activity, "Invalid page number", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        vpPdfPages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tvPdfPageIndicator.text = "Page ${position + 1} / $totalPdfPages"
                btnPdfPrevPage.isEnabled = position > 0
                btnPdfNextPage.isEnabled = position < totalPdfPages - 1
                btnPdfPrevPage.alpha = if (position > 0) 1.0f else 0.35f
                btnPdfNextPage.alpha = if (position < totalPdfPages - 1) 1.0f else 0.35f
                btnPdfZoomReset.text = "100%"
            }
        })

        btnDocWrap.setOnClickListener {
            isWrapped = !isWrapped
            btnDocWrap.setTextColor(if (isWrapped) Color.parseColor("#38BDF8") else Color.WHITE)
            if (isWrapped) {
                hsvCode.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                tvCodeContent.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            } else {
                hsvCode.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                tvCodeContent.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }

        btnDocCopy.setOnClickListener {
            if (fullLoadedText.isNotBlank()) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(item.title, fullLoadedText))
                Toast.makeText(activity, "Copied content to clipboard! 📋", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "No text content to copy", Toast.LENGTH_SHORT).show()
            }
        }

        // Search logic
        var searchMatchIndices = mutableListOf<Int>()
        var currentMatchPointer = -1

        fun performSearch(query: String) {
            searchMatchIndices.clear()
            currentMatchPointer = -1

            if (query.isBlank() || fullLoadedText.isBlank()) {
                tvDocSearchCount.text = "0/0"
                tvCodeContent.text = fullLoadedText
                return
            }

            val textLower = fullLoadedText.lowercase()
            val qLower = query.lowercase()
            var index = 0
            while (index != -1) {
                index = textLower.indexOf(qLower, index)
                if (index != -1) {
                    searchMatchIndices.add(index)
                    index += qLower.length
                }
            }

            val spannable = SpannableString(fullLoadedText)
            for (matchStart in searchMatchIndices) {
                val matchEnd = matchStart + query.length
                spannable.setSpan(
                    BackgroundColorSpan(Color.parseColor("#EAB308")),
                    matchStart,
                    matchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(Color.BLACK),
                    matchStart,
                    matchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            tvCodeContent.text = spannable

            if (searchMatchIndices.isNotEmpty()) {
                currentMatchPointer = 0
                tvDocSearchCount.text = "1/${searchMatchIndices.size}"
            } else {
                tvDocSearchCount.text = "0/0"
            }
        }

        btnDocSearch.setOnClickListener {
            layoutDocSearchBar.visibility = if (layoutDocSearchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (layoutDocSearchBar.visibility == View.VISIBLE) {
                etDocSearchInput.requestFocus()
            }
        }

        btnDocSearchClose.setOnClickListener {
            layoutDocSearchBar.visibility = View.GONE
            etDocSearchInput.setText("")
            performSearch("")
        }

        etDocSearchInput.setOnEditorActionListener { _, _, _ ->
            performSearch(etDocSearchInput.text.toString())
            true
        }

        btnDocSearchNext.setOnClickListener {
            if (searchMatchIndices.isNotEmpty()) {
                currentMatchPointer = (currentMatchPointer + 1) % searchMatchIndices.size
                tvDocSearchCount.text = "${currentMatchPointer + 1}/${searchMatchIndices.size}"
            }
        }

        btnDocSearchPrev.setOnClickListener {
            if (searchMatchIndices.isNotEmpty()) {
                currentMatchPointer = if (currentMatchPointer <= 0) searchMatchIndices.size - 1 else currentMatchPointer - 1
                tvDocSearchCount.text = "${currentMatchPointer + 1}/${searchMatchIndices.size}"
            }
        }

        dialog.setOnDismissListener {
            runCatching {
                currentPdfRenderer?.close()
                currentPdfPfd?.close()
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        // Load document content in background
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val tdFile = TelegramClient.downloadFileAndWait(item.fileId, priority = 32, timeoutMs = 60000L)
                val localPath = tdFile?.local?.path.orEmpty()
                val localFile = if (localPath.isNotBlank()) File(localPath) else null

                if (localFile != null && localFile.exists() && localFile.length() > 0) {
                    when (ext) {
                        "pdf" -> {
                            val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = PdfRenderer(pfd)
                            currentPdfPfd = pfd
                            currentPdfRenderer = renderer
                            totalPdfPages = renderer.pageCount

                            val displayMetrics = activity.resources.displayMetrics
                            val density = displayMetrics.density
                            val screenWidth = displayMetrics.widthPixels
                            val screenHeight = displayMetrics.heightPixels

                            val adapter = PdfPagerAdapter(
                                renderer = renderer,
                                scope = coroutineScope,
                                density = density,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )
                            pdfAdapter = adapter

                            withContext(Dispatchers.Main) {
                                layoutDocLoading.visibility = View.GONE
                                layoutPdfContainer.visibility = View.VISIBLE
                                btnDocOrientation.visibility = View.VISIBLE
                                vpPdfPages.adapter = adapter
                                vpPdfPages.orientation = ViewPager2.ORIENTATION_HORIZONTAL
                                vpPdfPages.offscreenPageLimit = 1
                                tvPdfPageIndicator.text = "Page 1 / $totalPdfPages"
                            }
                        }
                        "epub" -> {
                            val extractedHtml = extractEpubText(localFile)
                            withContext(Dispatchers.Main) {
                                layoutDocLoading.visibility = View.GONE
                                wvDocWeb.visibility = View.VISIBLE
                                val bgColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.bg_dark))
                                val textColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.text_primary))
                                val accentColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.accent_cyan))
                                wvDocWeb.setBackgroundColor(activity.getColor(R.color.bg_dark))
                                val styledHtml = """
                                    <html>
                                    <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1">
                                    <style>
                                        body { background-color: $bgColor; color: $textColor; font-family: sans-serif; padding: 16px; line-height: 1.6; }
                                        h1, h2, h3 { color: $accentColor; }
                                        p { margin-bottom: 12px; }
                                    </style>
                                    </head>
                                    <body>$extractedHtml</body>
                                    </html>
                                """.trimIndent()
                                wvDocWeb.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                            }
                        }
                        "md", "markdown" -> {
                            val text = localFile.readText(Charsets.UTF_8)
                            fullLoadedText = text
                            withContext(Dispatchers.Main) {
                                layoutDocLoading.visibility = View.GONE
                                wvDocWeb.visibility = View.VISIBLE
                                val bgColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.bg_dark))
                                val surfaceElevated = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.bg_surface_elevated))
                                val cardBg = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.card_bg))
                                val textColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.text_primary))
                                val accentColor = String.format("#%06X", 0xFFFFFF and activity.getColor(R.color.accent_cyan))
                                wvDocWeb.setBackgroundColor(activity.getColor(R.color.bg_dark))
                                val styledHtml = """
                                    <html>
                                    <head>
                                    <meta name="viewport" content="width=device-width, initial-scale=1">
                                    <style>
                                        body { background-color: $bgColor; color: $textColor; font-family: sans-serif; padding: 16px; line-height: 1.6; }
                                        h1, h2, h3 { color: $accentColor; }
                                        code { background-color: $surfaceElevated; padding: 2px 6px; border-radius: 4px; font-family: monospace; }
                                        pre { background-color: $cardBg; padding: 12px; border-radius: 8px; overflow-x: auto; }
                                    </style>
                                    </head>
                                    <body><pre>${text.replace("<", "&lt;").replace(">", "&gt;")}</pre></body>
                                    </html>
                                """.trimIndent()
                                wvDocWeb.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                            }
                        }
                        else -> {
                            // Code & text files
                            val text = localFile.readText(Charsets.UTF_8)
                            fullLoadedText = text
                            val lines = text.lines()
                            val lineCount = lines.size
                            val lineNumbersText = (1..lineCount).joinToString("\n")

                            withContext(Dispatchers.Main) {
                                layoutDocLoading.visibility = View.GONE
                                layoutCodeContainer.visibility = View.VISIBLE
                                tvLineNumbers.text = lineNumbersText
                                tvCodeContent.text = text
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        layoutDocLoading.visibility = View.GONE
                        Toast.makeText(activity, "Could not load file contents from Telegram Cloud", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    layoutDocLoading.visibility = View.GONE
                    Toast.makeText(activity, "Failed to preview document: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private class PdfPagerAdapter(
        private val renderer: PdfRenderer,
        private val scope: CoroutineScope,
        private val density: Float,
        private val screenWidth: Int,
        private val screenHeight: Int
    ) : RecyclerView.Adapter<PdfPagerAdapter.PageViewHolder>() {

        private val pageCache = LruCache<Int, Bitmap>(16)

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivZoomablePdfPage: ZoomableImageView = itemView.findViewById(R.id.ivZoomablePdfPage)
            val pbPageLoading: ProgressBar = itemView.findViewById(R.id.pbPageLoading)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.ivZoomablePdfPage.resetZoom()
            val cached = pageCache.get(position)
            if (cached != null) {
                holder.ivZoomablePdfPage.setImageBitmap(cached)
                holder.pbPageLoading.visibility = View.GONE
            } else {
                holder.pbPageLoading.visibility = View.VISIBLE
                scope.launch(Dispatchers.IO) {
                    val bitmap = renderPageBitmap(position)
                    withContext(Dispatchers.Main) {
                        if (holder.bindingAdapterPosition == position) {
                            holder.pbPageLoading.visibility = View.GONE
                            if (bitmap != null) {
                                pageCache.put(position, bitmap)
                                holder.ivZoomablePdfPage.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }

        private fun renderPageBitmap(pageIndex: Int): Bitmap? {
            synchronized(renderer) {
                return try {
                    val page = renderer.openPage(pageIndex)
                    val scale = (density * 1.5f).coerceIn(1.5f, 3.0f)
                    val width = (page.width * scale).toInt().coerceAtLeast(screenWidth)
                    val height = (page.height * scale).toInt().coerceAtLeast(screenHeight)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmap
                } catch (_: Throwable) {
                    null
                }
            }
        }

        override fun getItemCount(): Int = renderer.pageCount
    }

    private fun extractEpubText(file: File): String {
        val sb = StringBuilder()
        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".html", ignoreCase = true) ||
                        entry.name.endsWith(".xhtml", ignoreCase = true) ||
                        entry.name.endsWith(".htm", ignoreCase = true)
                    ) {
                        val content = zis.bufferedReader(Charsets.UTF_8).readText()
                        sb.append(content).append("<hr/>")
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}
        return if (sb.isNotEmpty()) sb.toString() else "<p>No text found in EPUB.</p>"
    }
}
