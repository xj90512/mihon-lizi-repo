package eu.kanade.tachiyomi.extension.zh.lizi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.hasFactory
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.lang.reflect.Proxy

class LiziPreferencesTest {
    private val originalScope = Injekt

    @Before fun isolateHostRegistry() { Injekt = InjektScope(DefaultRegistrar()) }
    @After fun restoreHostRegistry() { Injekt = originalScope }

    @Test fun applicationOnlyHostReadsSavedAuthorizationFromSourceNamespace() {
        val application = PreferenceApplication()
        Injekt.addSingleton<Application>(application)
        assertFalse(Injekt.hasFactory<Context>())
        assertEquals("local-test-token", liziPreferences(6794114338793425275L).getString("authorization", ""))
        assertEquals("source_6794114338793425275", application.lastName)
        assertEquals(Context.MODE_PRIVATE, application.lastMode)
    }

    @Test fun contextOnlyHostReadsTheSameSourceNamespace() {
        val application = PreferenceApplication()
        Injekt.addSingleton<Context>(application)
        assertFalse(Injekt.hasFactory<Application>())
        assertEquals("local-test-token", liziPreferences(6794114338793425275L).getString("authorization", ""))
        assertEquals("source_6794114338793425275", application.lastName)
    }

    private class PreferenceApplication : Application() {
        var lastName: String? = null
        var lastMode: Int? = null

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            lastName = name
            lastMode = mode
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> if (args!![0] == "authorization") "local-test-token" else args[1]
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SharedPreferences
        }
    }
}
