package com.tv.player.ui

import android.app.Activity
import android.app.Dialog
import android.media.AudioManager
import android.view.KeyEvent
import java.lang.ref.WeakReference

/**
 * The phone as a remote control. The buttons on the phone's page are replayed on the TV as the same key presses the
 * real remote sends, so every screen reacts exactly as it does to the remote (focus, OK, back, the player's keys).
 */
object RemoteControl {
    @Volatile private var screen = WeakReference<Activity>(null)

    // Dialogs open over the activity: the phone's keys would reach the activity underneath, not the dialog, so they wait.
    @Volatile private var covers = 0

    /** Call for a dialog shown over the screen; the phone's keys are refused until it is dismissed. */
    fun cover(dialog: Dialog) {
        covers++
        dialog.setOnDismissListener { covers-- }
    }

    private val keys = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER,
        "back" to KeyEvent.KEYCODE_BACK,
        "menu" to KeyEvent.KEYCODE_MENU,
        "play" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "rew" to KeyEvent.KEYCODE_MEDIA_REWIND,
        "ff" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        "ch_up" to KeyEvent.KEYCODE_CHANNEL_UP,
        "ch_down" to KeyEvent.KEYCODE_CHANNEL_DOWN,
    )

    /** Called from the activity's onResume: it is the one that receives the phone's presses from now on. */
    fun attach(activity: Activity) {
        screen = WeakReference(activity)
    }

    /** Called from the activity's onPause; a screen that is covered or gone must not get the presses. */
    fun detach(activity: Activity) {
        if (screen.get() === activity) screen = WeakReference(null)
    }

    /** Returns the message shown on the phone. Safe to call from any thread. */
    fun press(name: String): String {
        if (covers > 0) return "电视上有窗口没关，先在电视上关掉它"
        val activity = screen.get() ?: return "电视上现在没有可以遥控的页面，请先在电视上打开 App"
        if (name == "vol_up" || name == "vol_down") {
            val direction = if (name == "vol_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            activity.runOnUiThread {
                activity.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
            return "已发送"
        }
        val code = keys[name] ?: return "未知按键：$name"
        activity.runOnUiThread {
            // A press is a down and an up, as the real remote sends it: clicks happen on the up part.
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        return "已发送"
    }
}
