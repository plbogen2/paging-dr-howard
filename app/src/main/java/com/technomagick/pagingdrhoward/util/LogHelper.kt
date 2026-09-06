package com.technomagick.pagingdrhoward.util

import android.util.Log

object LogHelper {
    var enabled: Boolean = false

    fun v(tag: String, msg: String) { if (enabled) Log.v(tag, msg) }
    fun d(tag: String, msg: String) { if (enabled) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (enabled) Log.i(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable? = null) { if (enabled) Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String, tr: Throwable? = null) { if (enabled) Log.e(tag, msg, tr) }
}
