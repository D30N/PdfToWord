package com.deon.pdftoword

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_PDF = 1001
        private const val REQ_STORAGE = 1002
        private const val RED = "#B3261E"
        private const val GREEN = "#0A7D2C"
        private const val INK = "#1A1C1E"
        private const val GREY = "#8A8A8A"
    }

    private var pdfUri: Uri? = null
    private var pdfName: String = ""
    private var keepOriginal = true
    private var pendingDownload: File? = null
    private var convertMode = ConversionPipeline.Mode.AUTO

    private lateinit var selectedCard: LinearLayout
    private lateinit var selectedName: TextView
    private lateinit var selectedMeta: TextView
    private lateinit var keepSubtitle: TextView
    private lateinit var keepSwitch: Switch
    private lateinit var convertBtn: Button
    private lateinit var recentListHome: LinearLayout
    private lateinit var recentListFiles: LinearLayout
    private lateinit var homeScroll: ScrollView
    private lateinit var filesView: ScrollView
    private lateinit var settingsView: ScrollView
    private lateinit var navItems: List<LinearLayout>
    private lateinit var navIcons: List<ImageView>
    private lateinit var navLabels: List<TextView>

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Required by PdfBox-Android: lets it load its bundled resources
        // (glyphlist.txt etc.) from the APK's assets.
        PDFBoxResourceLoader.init(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FDF8F2"))
        }
        root.addView(buildAppBar())

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        homeScroll = ScrollView(this).apply { addView(buildHomeContent()) }
        filesView = ScrollView(this).apply {
            addView(buildFilesContent())
            visibility = View.GONE
        }
        settingsView = ScrollView(this).apply {
            addView(buildSettingsContent())
            visibility = View.GONE
        }
        content.addView(homeScroll)
        content.addView(filesView)
        content.addView(settingsView)
        root.addView(content)
        root.addView(buildBottomNav())
        setContentView(root)
        refreshRecents()
        // Handle PDF shared from another app (share menu).
        handleSharedPdf(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedPdf(intent)
    }

    /** If launched via share menu with a PDF, start conversion directly. */
    private fun handleSharedPdf(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) return
        // Take persistable read permission so conversion can open it.
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) { /* not all shares grant persistable */ }
        pdfUri = uri
        pdfName = queryDisplayName(uri) ?: "document.pdf"
        showSelected()
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    // ---------- UI builders ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildAppBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(RED))
            setPadding(dp(18), dp(16), dp(18), dp(20))
            val iconBox = FrameLayout(this@MainActivity).apply {
                background = getDrawable(R.drawable.bg_icon_box)
                val iv = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_doc)
                    setColorFilter(Color.WHITE)
                }
                addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            addView(iconBox, LinearLayout.LayoutParams(dp(44), dp(44)))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "Pdf to Word by Deon"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Malayalam PDF → Word converter"
                    setTextColor(Color.WHITE)
                    alpha = 0.9f
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            addView(texts)
        }
    }

    private fun buildHomeContent(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Select PDF button
        val selectBtn = Button(this).apply {
            text = "  Select PDF"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.bg_btn_red)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isAllCaps = false
            setOnClickListener { pickPdf() }
        }
        col.addView(selectBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Selected file card (hidden until a PDF is chosen)
        selectedCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_selected_dashed)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            val docBox = FrameLayout(this@MainActivity).apply {
                background = getDrawable(R.drawable.bg_doc_icon)
                val iv = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_doc)
                    setColorFilter(Color.parseColor(RED))
                }
                addView(iv, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }
            addView(docBox, LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                selectedName = TextView(this@MainActivity).apply {
                    setTextColor(Color.parseColor(INK))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                }
                selectedMeta = TextView(this@MainActivity).apply {
                    setTextColor(Color.parseColor(GREY))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                addView(selectedName)
                addView(selectedMeta)
            }
            addView(texts)
        }
        col.addView(selectedCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Keep original file name toggle
        val keepCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Keep original file name"
                    setTextColor(Color.parseColor(INK))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                })
                keepSubtitle = TextView(this@MainActivity).apply {
                    text = "Output: <original name>.docx"
                    setTextColor(Color.parseColor(GREY))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                addView(keepSubtitle)
            }
            addView(texts)
            keepSwitch = Switch(this@MainActivity).apply {
                isChecked = true
                setOnCheckedChangeListener { _, checked ->
                    keepOriginal = checked
                    updateKeepSubtitle()
                }
            }
            addView(keepSwitch)
        }
        col.addView(keepCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Conversion mode selector: Auto / Text PDF / Scanned PDF (OCR)
        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        modeCard.addView(TextView(this).apply {
            text = "Conversion mode"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val modeButtons = mutableListOf<Button>()
        val modeDefs = listOf(
            ConversionPipeline.Mode.AUTO to "Auto",
            ConversionPipeline.Mode.TEXT to "Text PDF",
            ConversionPipeline.Mode.SCANNED to "Scanned / OCR"
        )
        for ((m, label) in modeDefs) {
            val b = Button(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isAllCaps = false
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    convertMode = m
                    for ((i, btn) in modeButtons.withIndex()) {
                        val selected = modeDefs[i].first == convertMode
                        btn.background = getDrawable(
                            if (selected) R.drawable.bg_btn_green
                            else R.drawable.bg_btn_green_disabled
                        )
                        btn.setTextColor(
                            if (selected) Color.WHITE
                            else Color.parseColor(GREY)
                        )
                    }
                }
            }
            modeButtons.add(b)
            modeRow.addView(b, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (modeButtons.size > 1) leftMargin = dp(8)
            })
        }
        // Default: Auto selected
        modeButtons[0].background = getDrawable(R.drawable.bg_btn_green)
        modeButtons[0].setTextColor(Color.WHITE)
        for (i in 1 until modeButtons.size) {
            modeButtons[i].background = getDrawable(R.drawable.bg_btn_green_disabled)
            modeButtons[i].setTextColor(Color.parseColor(GREY))
        }
        modeCard.addView(modeRow)
        modeCard.addView(TextView(this).apply {
            text = "Auto detects text vs scanned pages. Scanned uses Malayalam OCR."
            setTextColor(Color.parseColor(GREY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, 0)
        })
        col.addView(modeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Convert button
        convertBtn = Button(this).apply {
            text = "  Convert to Word file"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            background = getDrawable(R.drawable.bg_btn_green_disabled)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_download, 0, 0, 0)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isAllCaps = false
            isEnabled = false
            setOnClickListener { convert() }
        }
        col.addView(convertBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        // Recent conversions
        col.addView(TextView(this).apply {
            text = "Recent conversions"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        recentListHome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentListHome, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // Note
        col.addView(TextView(this).apply {
            text = "Text PDFs are extracted directly. Scanned photo PDFs use Malayalam OCR. " +
                "Line spacing and the Malayalam font are always applied automatically."
            setTextColor(Color.parseColor("#7A5C00"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = getDrawable(R.drawable.bg_note)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        // Footer: made-by line with clickable Instagram handle
        col.addView(TextView(this).apply {
            val handle = "@deepak.deon"
            val full = "This app was made by $handle"
            val span = SpannableString(full)
            val start = full.indexOf(handle)
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://instagram.com/deepak.deon")
                            )
                        )
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Color.parseColor("#0097A7")
                    ds.isUnderlineText = false
                }
            }, start, start + handle.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = span
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(GREY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(18), 0, dp(10))
        })

        return col
    }

    private fun buildFilesContent(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Files"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        recentListFiles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recentListFiles)
        return col
    }

    private fun buildSettingsContent(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(TextView(this).apply {
            text = "Pdf to Word  1.0"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(this).apply {
            text = "Converts Malayalam PDFs to Word (.docx).\n\n" +
                "• Auto / Text PDF / Scanned (OCR) modes\n" +
                "• Malayalam OCR with image preprocessing\n" +
                "• Line spacing is preserved from the PDF\n" +
                "• Malayalam font is always applied\n" +
                "• Files are saved to your Downloads folder"
            setTextColor(Color.parseColor(GREY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        col.addView(card)

        // App usage & copyright notice card
        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        notice.addView(TextView(this).apply {
            text = "APP USAGE & COPYRIGHT NOTICE"
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })
        val sb = SpannableStringBuilder()
        fun para(text: String, boldRanges: List<Pair<Int, Int>>) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            val start = sb.length
            sb.append(text)
            for ((s, e) in boldRanges) {
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start + s, start + e,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        val p2 = "This application is free to use and share."
        para(p2, emptyList())
        para("All rights reserved.", listOf(0 to "All rights reserved.".length))
        notice.addView(TextView(this).apply {
            text = sb
            setTextColor(Color.parseColor(INK))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        // © Deepak Deon -> blue hyperlink to Instagram
        notice.addView(TextView(this).apply {
            val label = "© Deepak Deon"
            val span = SpannableString(label)
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://instagram.com/deepak.deon")
                            )
                        )
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = Color.parseColor("#1A73E8")
                    ds.isUnderlineText = true
                }
            }, 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = span
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, 0)
        })
        val noticeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        col.addView(notice, noticeParams)
        return col
    }

    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(10))
        }
        val items = listOf(
            Triple("Home", R.drawable.ic_home, 0),
            Triple("Files", R.drawable.ic_folder, 1),
            Triple("Settings", R.drawable.ic_settings, 2)
        )
        navItems = items.map { (label, icon, idx) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                val iv = ImageView(this@MainActivity).apply { setImageResource(icon) }
                val tv = TextView(this@MainActivity).apply {
                    text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                }
                addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL })
                setOnClickListener { selectTab(idx) }
                tag = Pair(iv, tv)
            }.also { nav.addView(it) }
        }
        navIcons = navItems.map { (it.tag as Pair<ImageView, TextView>).first }
        navLabels = navItems.map { (it.tag as Pair<ImageView, TextView>).second }
        selectTab(0)
        // top divider
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(nav)
        }
        return wrap
    }

    private fun selectTab(idx: Int) {
        homeScroll.visibility = if (idx == 0) View.VISIBLE else View.GONE
        filesView.visibility = if (idx == 1) View.VISIBLE else View.GONE
        settingsView.visibility = if (idx == 2) View.VISIBLE else View.GONE
        navIcons.forEachIndexed { i, iv ->
            val active = i == idx
            iv.setColorFilter(Color.parseColor(if (active) RED else GREY))
            navLabels[i].setTextColor(Color.parseColor(if (active) RED else GREY))
            navLabels[i].typeface =
                if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------- recents ----------

    private fun refreshRecents() {
        val items = RecentStore.list(this)
        recentListHome.removeAllViews()
        recentListFiles.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No conversions yet"
                setTextColor(Color.parseColor(GREY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, dp(4))
            }
            recentListHome.addView(empty)
            recentListFiles.addView(TextView(this).apply {
                text = "No conversions yet"
                setTextColor(Color.parseColor(GREY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            return
        }
        for (item in items) {
            recentListHome.addView(buildRecentRow(item))
            recentListFiles.addView(buildRecentRow(item))
        }
    }

    private fun buildRecentRow(item: RecentItem): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_recent_row)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val docBox = FrameLayout(this@MainActivity).apply {
                background = getDrawable(R.drawable.bg_doc_icon)
                val iv = ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_doc)
                    setColorFilter(Color.parseColor("#2B6CB0"))
                }
                addView(iv, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }
            addView(docBox, LinearLayout.LayoutParams(dp(40), dp(40)))
            val texts = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = item.name
                    setTextColor(Color.parseColor(INK))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Converted • ${formatTime(item.timeMs)}"
                    setTextColor(Color.parseColor(GREEN))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
            }
            addView(texts)
            val shareBtn = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_share)
                setColorFilter(Color.parseColor(GREY))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { shareRecent(item) }
            }
            addView(shareBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            val deleteBtn = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_delete)
                setColorFilter(Color.parseColor(RED))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { confirmDelete(item) }
            }
            addView(deleteBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
            setOnClickListener { openRecent(item) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ms
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hr ago"
            else -> SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    // ---------- PDF picking ----------

    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(Intent.createChooser(intent, "Select PDF"), REQ_PICK_PDF)
    }

    @Deprecated("classic picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PDF && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            pdfUri = uri
            pdfName = queryDisplayName(uri) ?: "document.pdf"
            showSelected()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        }.getOrNull()
    }

    private fun showSelected() {
        selectedCard.visibility = View.VISIBLE
        selectedName.text = pdfName
        selectedMeta.text = "PDF • Selected"
        convertBtn.isEnabled = true
        convertBtn.background = getDrawable(R.drawable.bg_btn_green)
        updateKeepSubtitle()
    }

    private fun updateKeepSubtitle() {
        val base = pdfName.substringBeforeLast('.', pdfName).ifBlank { "document" }
        keepSubtitle.text = if (keepOriginal) "Output: $base.docx"
        else "Output: converted-<time>.docx"
    }

    // ---------- conversion ----------

    private fun convert() {
        val uri = pdfUri ?: return
        convertBtn.isEnabled = false

        // Progress dialog: stage name + big % + horizontal bar + detail line.
        lateinit var pctText: TextView
        lateinit var bar: ProgressBar
        lateinit var stageText: TextView
        lateinit var statusText: TextView
        val dlgView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "Converting…"
                setTextColor(Color.parseColor(INK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(2))
            })
            addView(TextView(this@MainActivity).apply {
                text = "$pdfName • ${modeLabel(convertMode)}"
                setTextColor(Color.parseColor(GREY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(10))
            })
            val pctRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pctText = TextView(this@MainActivity).apply {
                text = "0%"
                setTextColor(Color.parseColor(INK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                typeface = Typeface.DEFAULT_BOLD
            }
            pctRow.addView(pctText)
            pctRow.addView(TextView(this@MainActivity).apply {
                text = " completed"
                setTextColor(Color.parseColor(GREY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(6), dp(12), 0, 0)
            })
            addView(pctRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            bar = ProgressBar(
                this@MainActivity, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(GREEN)
                )
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#E4E4E4")
                )
            }
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)).apply {
                bottomMargin = dp(10)
            })
            stageText = TextView(this@MainActivity).apply {
                text = "Starting…"
                setTextColor(Color.parseColor(INK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(2))
            }
            addView(stageText)
            statusText = TextView(this@MainActivity).apply {
                text = ""
                setTextColor(Color.parseColor(GREY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            addView(statusText)
        }
        val dlg = AlertDialog.Builder(this)
            .setView(dlgView)
            .setCancelable(false)
            .create()
        dlg.show()

        thread {
            var stageName = "reading"
            try {
                // Copy PDF to a temp file (pipeline needs a File)
                val tmpPdf = File(cacheDir, "convert-input.pdf")
                contentResolver.openInputStream(uri)?.use { ins ->
                    FileOutputStream(tmpPdf).use { out -> ins.copyTo(out) }
                } ?: throw Exception("Could not open PDF")

                val pipeline = ConversionPipeline(this)
                val result = pipeline.convert(tmpPdf, convertMode) { p ->
                    runOnUiThread {
                        pctText.text = "${p.percent}%"
                        bar.progress = p.percent
                        stageText.text = "${p.stage}. ${p.stageName}"
                        statusText.text = p.detail
                    }
                }
                stageName = "saving"

                val blocks = result.blocks
                val hasText = blocks.any {
                    it is DocBlock.Para && it.runs.any { r -> r.text.isNotBlank() }
                }
                if (!hasText) {
                    val msg = if (result.detectedType == PdfTypeDetector.PdfType.SCANNED) {
                        "OCR found no text in this scanned PDF. " +
                            "Try a higher-quality scan."
                    } else {
                        "No text found in this PDF."
                    }
                    failOnUi(dlg, msg)
                    return@thread
                }

                runOnUiThread {
                    pctText.text = "100%"
                    bar.progress = 100
                    stageText.text = "Writing Word file…"
                    statusText.text = ""
                }
                val base = pdfName.substringBeforeLast('.', pdfName)
                    .ifBlank { "document" }
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val wanted = if (keepOriginal) "$base.docx"
                else "converted-${System.currentTimeMillis()}.docx"
                val dir = File(filesDir, "converted").apply { mkdirs() }
                var outFile = File(dir, wanted)
                var n = 1
                while (outFile.exists()) {
                    outFile = File(dir, "${wanted.substringBeforeLast('.')}-$n.docx")
                    n++
                }
                FileOutputStream(outFile).use { DocxWriter.write(blocks, it) }
                saveToDownloads(outFile)
                RecentStore.add(this, outFile.name, outFile.name)
                try { tmpPdf.delete() } catch (_: Exception) {}
                runOnUiThread {
                    dlg.dismiss()
                    convertBtn.isEnabled = true
                    refreshRecents()
                    showDone(outFile, result)
                }
            } catch (e: SecurityException) {
                failOnUi(dlg, "This PDF is password protected.")
            } catch (e: Throwable) {
                failOnUi(dlg, "Failed while $stageName: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    private fun modeLabel(mode: ConversionPipeline.Mode): String = when (mode) {
        ConversionPipeline.Mode.AUTO -> "Auto"
        ConversionPipeline.Mode.TEXT -> "Text PDF"
        ConversionPipeline.Mode.SCANNED -> "Scanned / OCR"
    }

    private fun failOnUi(dlg: AlertDialog, msg: String, err: Throwable? = null) {
        runOnUiThread {
            dlg.dismiss()
            convertBtn.isEnabled = true
            if (err != null) {
                val sw = StringWriter()
                err.printStackTrace(PrintWriter(sw))
                val log = "PdfToWord convert error\n$msg\n\n${sw}"
                AlertDialog.Builder(this)
                    .setTitle("Conversion failed")
                    .setMessage("$msg\n\n'Copy log' amarthi log ayachal mathi.")
                    .setPositiveButton("Copy log") { _, _ ->
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("pdftoword-log", log))
                        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("OK", null)
                    .show()
            } else {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDone(file: File, result: ConversionPipeline.Result? = null) {
        val stats = if (result != null) {
            val typeStr = if (result.detectedType == PdfTypeDetector.PdfType.TEXT) {
                "Text PDF (direct extraction)"
            } else {
                "Scanned PDF (Malayalam OCR)"
            }
            val langStr = when (result.detectedLanguage) {
                "ml" -> "Malayalam"
                "en" -> "English"
                "ml+en" -> "Malayalam + English"
                else -> "Unknown"
            }
            val confStr = if (result.ocrConfidence != null) {
                "\nOCR confidence: ${result.ocrConfidence}%"
            } else ""
            "\n\n$typeStr\nPages: ${result.pageCount}\n" +
                "Language: $langStr$confStr\n" +
                "Words: ${result.wordCount}\n" +
                "Malayalam: ${result.malayalamPercent.toInt()}%"
        } else ""
        AlertDialog.Builder(this)
            .setTitle("Converted")
            .setMessage("${file.name} saved to Downloads.$stats")
            .setPositiveButton("Open") { _, _ -> openFile(file) }
            .setNeutralButton("Share") { _, _ -> shareFile(file) }
            .setNegativeButton("OK", null)
            .show()
    }

    // ---------- Downloads (no permission needed on API 29+) ----------

    private fun saveToDownloads(src: File) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(src).use { it.copyTo(out) }
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = src
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE
                )
                return
            }
            copyToLegacyDownloads(src)
        }
    }

    private fun copyToLegacyDownloads(src: File) {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dl.mkdirs()
        FileInputStream(src).use { ins ->
            FileOutputStream(File(dl, src.name)).use { ins.copyTo(it) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val f = pendingDownload
            pendingDownload = null
            if (f != null && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                runCatching { copyToLegacyDownloads(f) }
            } else {
                Toast.makeText(
                    this, "Storage permission needed to save to Downloads", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------- open / share recents ----------

    private fun recentFile(item: RecentItem): File =
        File(File(filesDir, "converted"), item.fileName)

    private fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(this, "com.deon.pdftoword.fileprovider", file)

    private fun openRecent(item: RecentItem) {
        val f = recentFile(item)
        if (!f.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        openFile(f)
    }

    private fun openFile(file: File) {
        val uri = contentUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            Toast.makeText(this, "No app found to open Word files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecent(item: RecentItem) {
        val f = recentFile(item)
        if (!f.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        shareFile(f)
    }

    private fun confirmDelete(item: RecentItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete file?")
            .setMessage("${item.name} will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ -> deleteRecent(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRecent(item: RecentItem) {
        runCatching { recentFile(item).delete() }
        RecentStore.remove(this, item.fileName)
        refreshRecents()
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
    }

    private fun shareFile(file: File) {
        val uri = contentUri(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }
}
