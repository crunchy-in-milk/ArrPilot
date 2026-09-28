package io.github.crunchyinmilk.arrpilot

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.LruCache
import java.util.concurrent.ExecutorService

class MovieAdapter(
    private val context: Context,
    private val client: RadarrClient,
    private val executor: ExecutorService
) : BaseAdapter() {
    private val movies = mutableListOf<Movie>()
    private val cache = object : LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlightUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var cardWidth = dp(196)
    private var maxCardHeight = dp(260)

    fun replace(items: List<Movie>) {
        movies.clear()
        movies.addAll(items)
        notifyDataSetChanged()
    }

    fun append(items: List<Movie>) {
        val knownIds = movies.mapTo(mutableSetOf()) { it.raw.optInt("tmdbId", 0) }
        movies.addAll(items.filter { knownIds.add(it.raw.optInt("tmdbId", 0)) })
        notifyDataSetChanged()
    }

    fun setCardSize(width: Int, maxHeight: Int) {
        if (width <= 0 || maxHeight <= 0 || (width == cardWidth && maxHeight == maxCardHeight)) return
        cardWidth = width
        maxCardHeight = maxHeight
        notifyDataSetChanged()
    }

    override fun getCount() = movies.size
    override fun getItem(position: Int) = movies[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, old: View?, parent: ViewGroup): View {
        val holder: Holder
        val cell: FrameLayout
        if (old == null) {
            cell = FrameLayout(context).apply {
                isFocusable = false
                isFocusableInTouchMode = false
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = false
                isFocusableInTouchMode = false
                isDuplicateParentStateEnabled = true
                background = context.getDrawable(R.drawable.movie_panel)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val posterFrame = FrameLayout(context).apply {
                isDuplicateParentStateEnabled = true
                background = context.getDrawable(R.drawable.poster_panel)
                // The rounded frame supplies the outline; clip the bitmap to it
                // instead of nesting a square image inside extra padding.
                clipToOutline = true
            }
            val poster = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(ColorDrawable(Dracula.BackgroundDarker))
                setBackgroundColor(Dracula.BackgroundDarker)
            }
            posterFrame.addView(poster, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            val coverStatus = TextView(context).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Dracula.Comment)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            posterFrame.addView(coverStatus, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            card.addView(posterFrame)
            val title = TextView(context).apply {
                setTextColor(Dracula.Foreground)
                textSize = 16f
                maxLines = 2
                setPadding(2, dp(7), 2, 0)
            }
            card.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val subtitle = TextView(context).apply {
                setTextColor(Dracula.Comment)
                textSize = 12f
                setPadding(2, dp(3), 2, 0)
            }
            card.addView(subtitle)
            cell.addView(card)
            holder = Holder(card, posterFrame, poster, coverStatus, title, subtitle)
            cell.tag = holder
        } else {
            cell = old as FrameLayout
            holder = cell.tag as Holder
        }

        val edgeGutter = dp(4)
        val innerCardHeight = (maxCardHeight - edgeGutter * 2).coerceAtLeast(dp(240))
        val innerWidth = (cardWidth - dp(16)).coerceAtLeast(dp(120))
        // A focused card can have a two-line title. Reserve enough room for
        // both title lines and the year/status line so the latter is never
        // clipped at the bottom of the card.
        val textAreaHeight = dp(80)
        val verticalPadding = dp(8)
        val availablePosterHeight = (innerCardHeight - textAreaHeight - verticalPadding).coerceAtLeast(dp(180))
        val posterHeight = minOf(innerWidth * 3 / 2, availablePosterHeight)
        cell.layoutParams = android.widget.AbsListView.LayoutParams(cardWidth, maxCardHeight)
        holder.card.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            innerCardHeight
        ).apply { topMargin = edgeGutter }
        holder.posterFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, posterHeight)

        val movie = movies[position]
        holder.title.text = movie.title
        val availability = when {
            movie.downloaded -> "Downloaded"
            movie.inLibrary -> "In Radarr"
            else -> movie.status.replaceFirstChar { it.uppercase() }
        }
        holder.subtitle.text = listOfNotNull(movie.year.takeIf { it > 0 }?.toString(), availability).joinToString(" • ")
        holder.poster.tag = movie.posterUrl
        holder.poster.setImageDrawable(ColorDrawable(Dracula.BackgroundDarker))
        val url = movie.posterUrl
        when {
            url == null -> {
                holder.coverStatus.text = "No cover"
                holder.coverStatus.visibility = View.VISIBLE
            }
            failedUrls.contains(url) -> {
                holder.coverStatus.text = "Cover unavailable"
                holder.coverStatus.visibility = View.VISIBLE
            }
            cache.get(url) != null -> {
                holder.poster.setImageBitmap(cache.get(url))
                holder.coverStatus.visibility = View.GONE
            }
            else -> {
                holder.coverStatus.text = "Loading cover…"
                holder.coverStatus.visibility = View.VISIBLE
                if (inFlightUrls.add(url)) {
                    executor.execute {
                        val bitmap = runCatching { client.loadBitmap(url, innerWidth, posterHeight) }.getOrNull()
                        if (bitmap != null) {
                            cache.put(url, bitmap)
                        } else {
                            failedUrls.add(url)
                        }
                        inFlightUrls.remove(url)
                        mainHandler.post {
                            notifyDataSetChanged()
                        }
                    }
                }
            }
        }
        return cell
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private data class Holder(
        val card: LinearLayout,
        val posterFrame: FrameLayout,
        val poster: ImageView,
        val coverStatus: TextView,
        val title: TextView,
        val subtitle: TextView
    )
}
