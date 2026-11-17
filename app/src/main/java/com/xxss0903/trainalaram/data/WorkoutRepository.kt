package com.xxss0903.trainalaram.data

import android.content.Context
import com.xxss0903.trainalaram.timer.SessionSnapshot
import com.xxss0903.trainalaram.timer.sessionSnapshotFromStorage
import com.xxss0903.trainalaram.timer.toStorage

/**
 * 训练会话（训练计划 + 每组执行数据 + 流程状态）的实时持久化。
 *
 * 采用 SharedPreferences(apply) 落盘：每次阶段切换、组次变化与显示秒数变化都会写入，
 * 保证应用进入后台或进程被杀后仍能完整恢复，不会丢失已记录的组次数据。
 */
class WorkoutRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSession(): SessionSnapshot? = sessionSnapshotFromStorage(prefs.getString(KEY_SESSION, null))

    fun saveSession(snapshot: SessionSnapshot) {
        prefs.edit().putString(KEY_SESSION, snapshot.toStorage()).apply()
    }

    private companion object {
        const val PREFS_NAME = "workout_state"
        const val KEY_SESSION = "session"
    }
}
