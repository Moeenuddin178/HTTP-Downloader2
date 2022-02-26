package resonance.http.httpdownloader.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.room.Room
import kotlinx.android.synthetic.main.activity_history_bookmark.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import resonance.http.httpdownloader.R
import resonance.http.httpdownloader.adapters.BookmarkAdapter
import resonance.http.httpdownloader.core.log
import resonance.http.httpdownloader.core.now
import resonance.http.httpdownloader.helpers.*
import resonance.http.httpdownloader.helpers.db.BookmarkDB
import resonance.http.httpdownloader.helpers.db.BookmarkDbConn
import resonance.http.httpdownloader.helpers.db.BookmarkItem

class BookmarkActivity : ParentActivity() {
    companion object {
        const val COUNT_PER_PAGE = 500
    }

    var isLongClicked = false
    private val adapter: BookmarkAdapter by lazy { BookmarkAdapter(this) }
    private var from = 0
    val ioScope = CoroutineScope(Dispatchers.IO)
    val db: BookmarkDbConn by lazy {
        Room.databaseBuilder(
            applicationContext,
            BookmarkDB::class.java, "bookmarks"
        ).build().conn()
    }


    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_bookmark)

        log("BookmarkActivity", "oCr:")

        title_bar.text = "Bookmarks"
        emptyIndicator.text = "No bookmarks found"

        search_text.addTextChangedListener(object : TextWatcher {
            var lastSearch: Editable? = null
            @Volatile
            var isSearching = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            @Synchronized
            override fun afterTextChanged(s: Editable?) {
                if (!isSearching) {
                    isSearching = true
                    ioScope.launch {
                        setAdapterValues(db.search("%$s%"))
                        isSearching = false
                        if (lastSearch != null) {
                            val last = lastSearch
                            lastSearch = null
                            afterTextChanged(last)
                        }
                    }
                } else lastSearch = s
            }
        })

        historyList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        ioScope.launch {
            setAdapterValues(db.getAll(from, COUNT_PER_PAGE))
        }
    }

    private fun setAdapterValues(items: List<BookmarkItem>) {
        handler.post {
            adapter.clear()
            val now = now()
            var currentPast = Past.PAST_12_HOURS
            adapter.add(BookmarkItem("", "", currentPast.time)) // initial: less than 12 hours ago

            items.forEach {
                val diff = now - it.time
                if (diff >= currentPast.time && currentPast.time != Past.OLDER.time) {
                    val lastItem = adapter.getItem(adapter.count - 1)
                    if (lastItem?.time == currentPast.time) adapter.remove(lastItem) // only time indicator exists, remove it

                    currentPast = currentPast.next()
                    adapter.add(BookmarkItem("", "", currentPast.time))
                }
                adapter.add(it)
            }
            val lastItem = adapter.getItem(adapter.count - 1)
            if (lastItem?.time == currentPast.time) adapter.remove(lastItem) // only time indicator exists, remove it

            if (adapter.isEmpty) emptyIndicator.unHide()
            else emptyIndicator.setGone()
        }
    }

    fun searchToggle(view: View) {
        if (search_bar.visibility == View.INVISIBLE) {
            search_bar.unHide()
            title_bar.hide()
            search_text.startAnimation(widthChangeAnim(true) {
                search_text.requestFocus()
                showKeyboard()
            })
        } else searchCollapse()
    }

    private fun searchCollapse() {
        search_text.startAnimation(widthChangeAnim(false) {
            search_text.setText("")
            search_bar.hide()
            title_bar.unHide()
            hideKeyboard()
        })
    }

    override fun onBackPressed() {
        when {
            isLongClicked -> adapter.deactivateLongClickMode()
            search_bar.visibility != View.INVISIBLE -> searchCollapse()
            else -> {
                Browser.start(this, null)
                super.onBackPressed()
            }
        }
    }

    private fun widthChangeAnim(expand: Boolean = true, onEnd: (() -> Unit)? = null): Animation {
        val animRes = if (expand) R.anim.width_increase else R.anim.width_decrease
        return AnimationUtils.loadAnimation(this, animRes).apply {
            duration = 70 /*ms*/
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    onEnd?.invoke()
                }
            })
        }
    }

    enum class Past(val time: Long) {
        PAST_12_HOURS(12 * 3600 * 1000L),
        PAST_24_HOURS(24 * 3600 * 1000L),
        PAST_2_DAYS(2 * 24 * 3600 * 1000L),
        PAST_7_DAYS(7 * 24 * 3600 * 1000L),
        PAST_30_DAYS(30 * 24 * 3600 * 1000L),
        OLDER(-1L);

        fun next(): Past {
            return when (this) {
                PAST_12_HOURS -> PAST_24_HOURS
                PAST_24_HOURS -> PAST_2_DAYS
                PAST_2_DAYS -> PAST_7_DAYS
                PAST_7_DAYS -> PAST_30_DAYS
                PAST_30_DAYS -> OLDER
                else -> throw RuntimeException("Can't get next() for OLDER")
            }
        }

        operator fun contains(obj: BookmarkItem): Boolean {
            return obj.time in obj.createdTime - time..obj.createdTime
        }

        val text: String
            get() {
                return when (this) {
                    PAST_12_HOURS -> "Less than 12 hours ago"
                    PAST_24_HOURS -> "Less than 24 hours ago"
                    PAST_2_DAYS -> "Less than 2 days ago"
                    PAST_7_DAYS -> "Less than a week ago"
                    PAST_30_DAYS -> "Less than a month ago"
                    OLDER -> "Old"
                }
            }
    }
}
