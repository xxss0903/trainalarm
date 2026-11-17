package com.xxss0903.trainalaram.data

import android.content.Context
import com.xxss0903.trainalaram.timer.WorkoutSnapshot
import com.xxss0903.trainalaram.timer.snapshotFromStorage
import com.xxss0903.trainalaram.timer.toStorage

/**
 * 训练状态的实时持久化。使用 SharedPreferences(apply) 落盘，
 * 每次阶段切换与显示秒数变化都会写入，保证后台运行或进程被杀后仍可完整恢复。
 */
class WorkoutRepository(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSnapshot(): WorkoutSnapshot? = snapshotFromStorage(prefs.getString(KEY_SNAPSHOT, null))

    fun saveSnapshot(snapshot: WorkoutSnapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, snapshot.toStorage()).apply()
    }

    private companion object {
        const val PREFS_NAME = "workout_state"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
