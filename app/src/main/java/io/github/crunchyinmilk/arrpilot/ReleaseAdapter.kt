package io.github.crunchyinmilk.arrpilot

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReleaseAdapter(private val context: Context) : BaseAdapter() {
    private val releases = mutableListOf<ReleaseOption>()

    fun replace(items: List<ReleaseOption>) {
        releases.clear()
        releases.addAll(items)
        notifyDataSetChanged()
    }

    override fun getCount() = releases.size
    override fun getItem(position: Int) = releases[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, old: View?, parent: ViewGroup): View {
        val holder: Holder
        val row: LinearLayout
        if (old == null) {
            row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
                background = context.getDrawable(R.drawable.focusable_panel)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(122))
            }
            val title = TextView(context).apply {
                textSize = 16f
                maxLines = 1
                setTextColor(Dracula.Foreground)
            }
            val facts = TextView(context).apply {
                textSize = 13f
                maxLines = 1
                setTextColor(Dracula.Comment)
                setPadding(0, dp(5), 0, 0)
            }
            val uploaded = TextView(context).apply {
                textSize = 12f
                maxLines = 1
                setTextColor(Dracula.Cyan)
                setPadding(0, dp(3), 0, 0)
            }
            val warning = TextView(context).apply {
                textSize = 12f
                maxLines = 1
                setTextColor(Dracula.Red)
                setPadding(0, dp(3), 0, 0)
            }
            row.addView(title)
            row.addView(facts)
            row.addView(uploaded)
            row.addView(warning)
            holder = Holder(title, facts, uploaded, warning)
            row.tag = holder
        } else {
            row = old as LinearLayout
            holder = row.tag as Holder
        }

        val release = releases[position]
        holder.title.text = release.title
        holder.facts.text = listOfNotNull(
            release.quality,
            formatBytes(release.sizeBytes),
            release.seeders?.let { "$it seeders" },
            release.leechers?.let { "$it leechers" },
            "score ${release.customFormatScore}",
            release.indexer
        ).joinToString("  •  ")
        holder.uploaded.text = formatPublished(release)
        holder.warning.text = when {
            release.rejections.isNotEmpty() -> "Not eligible: ${release.rejections.joinToString("; ")}"
            !release.downloadAllowed -> "Radarr has disabled downloading this release"
            else -> "Eligible — select to review"
        }
        holder.warning.setTextColor(
            if (release.rejected || !release.downloadAllowed) Dracula.Red else Dracula.Green
        )
        return row
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown size"
        val gib = bytes / 1_073_741_824.0
        return if (gib >= 1.0) String.format(Locale.US, "%.2f GiB", gib)
        else String.format(Locale.US, "%.0f MiB", bytes / 1_048_576.0)
    }

    private fun formatPublished(release: ReleaseOption): String {
        val date = runCatching { Instant.parse(release.publishedAt) }.getOrNull()
        val absolute = date?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
            ?: "Unknown date"
        val relative = when {
            release.ageHours < 1.0 -> "less than an hour ago"
            release.ageHours < 24.0 -> "${release.ageHours.toInt()} hours ago"
            else -> "${(release.ageHours / 24.0).toInt()} days ago"
        }
        return "Uploaded $absolute  •  $relative"
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private data class Holder(val title: TextView, val facts: TextView, val uploaded: TextView, val warning: TextView)
}
