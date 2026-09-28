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

class CastAdapter(
    private val context: Context,
    private val client: RadarrClient,
    private val executor: ExecutorService
) : BaseAdapter() {
    private val members = mutableListOf<CastMember>()
    private val cache = object : LruCache<String, android.graphics.Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var cardWidth = dp(180)
    private var cardHeight = dp(300)

    fun replace(items: List<CastMember>) {
        members.clear()
        members.addAll(items)
        notifyDataSetChanged()
    }

    fun setCardSize(width: Int, height: Int) {
        if (width == cardWidth && height == cardHeight) return
        cardWidth = width
        cardHeight = height
        notifyDataSetChanged()
    }

    override fun getCount() = members.size
    override fun getItem(position: Int) = members[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val holder: Holder
        val cell: FrameLayout
        if (convertView == null) {
            cell = FrameLayout(context).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isDuplicateParentStateEnabled = true
                background = context.getDrawable(R.drawable.focusable_panel)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val photo = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Dracula.BackgroundDarker)
            }
            val placeholder = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 28f
                setTextColor(Dracula.Comment)
            }
            val photoFrame = FrameLayout(context).apply {
                background = context.getDrawable(R.drawable.poster_panel)
                isDuplicateParentStateEnabled = true
                clipToOutline = true
                addView(photo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(placeholder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            card.addView(photoFrame)
            val name = TextView(context).apply {
                textSize = 16f
                maxLines = 2
                setTextColor(Dracula.Foreground)
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(name)
            val character = TextView(context).apply {
                textSize = 13f
                maxLines = 2
                setTextColor(Dracula.Comment)
                setPadding(0, dp(3), 0, 0)
            }
            card.addView(character)
            cell.addView(card)
            holder = Holder(card, photoFrame, photo, placeholder, name, character)
            cell.tag = holder
        } else {
            cell = convertView as FrameLayout
            holder = cell.tag as Holder
        }

        val member = members[position]
        // Reserve enough room for a two-line performer name and a two-line
        // character name on the compact five-column layout.
        val photoHeight = (cardHeight - dp(112)).coerceAtLeast(dp(150))
        cell.layoutParams = android.widget.AbsListView.LayoutParams(cardWidth, cardHeight)
        holder.card.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        holder.photoFrame.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoHeight)
        holder.name.text = member.name
        holder.character.text = member.character
        holder.photo.setImageDrawable(ColorDrawable(Dracula.BackgroundDarker))
        val url = member.profileUrl
        if (url == null) {
            holder.placeholder.text = initials(member.name)
            holder.placeholder.visibility = View.VISIBLE
        } else {
            val bitmap = cache.get(url)
            if (bitmap != null) {
                holder.photo.setImageBitmap(bitmap)
                holder.placeholder.visibility = View.GONE
            } else {
                holder.placeholder.text = ""
                holder.placeholder.visibility = View.GONE
                if (inFlight.add(url)) executor.execute {
                    val loaded = runCatching { client.loadBitmap(url, cardWidth, photoHeight) }.getOrNull()
                    if (loaded != null) cache.put(url, loaded)
                    inFlight.remove(url)
                    handler.post { notifyDataSetChanged() }
                }
            }
        }
        return cell
    }

    private fun initials(name: String) = name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private data class Holder(
        val card: LinearLayout,
        val photoFrame: FrameLayout,
        val photo: ImageView,
        val placeholder: TextView,
        val name: TextView,
        val character: TextView
    )
}
