package eu.kanade.tachiyomi.extension.zh.lizi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.getOrNull

internal fun liziPreferences(sourceId: Long): SharedPreferences {
    // CopyManga's host contract uses Application. Newer Android hosts may bind only Context.
    val context = Injekt.getOrNull<Application>() ?: Injekt.get<Context>()
    return context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
}
