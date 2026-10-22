package com.fr.colorostools.utils

import android.content.SharedPreferences
import androidx.collection.arraySetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.HashSet

/**
 * remote prefs 跨进程写入的值类约束。
 *
 * remote prefs 把值打包进 Bundle 经 Binder 交给框架进程，接收侧按 Serializable 反序列化且默认用
 * bootstrap 类加载器解析类：Kotlin 单例集合（`emptySet()` 得到的 `kotlin.collections.EmptySet`）与
 * androidx 集合都解析不到，会抛 `BadParcelableException: ... ClassNotFoundException`（name = kotlin.collections.EmptySet），
 * 并且整次 prefs 更新都会失败。所以写入前必须复制成 java.util 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSafeSetTest {

    @Test
    fun `empty kotlin set is copied into a plain java HashSet`() {
        val safe = emptySet<String>().toPrefsSafeSet()

        assertEquals(HashSet::class.java, safe.javaClass)
        assertTrue(safe.isEmpty())
    }

    @Test
    fun `non empty and androidx sets are copied into a plain java HashSet`() {
        val kotlinSet = setOf("com.a", "com.b").toPrefsSafeSet()
        assertEquals(HashSet::class.java, kotlinSet.javaClass)
        assertEquals(setOf("com.a", "com.b"), kotlinSet)

        val androidxSet = arraySetOf("com.a").toPrefsSafeSet()
        assertEquals(HashSet::class.java, androidxSet.javaClass)
        assertEquals(setOf("com.a"), androidxSet)
    }

    @Test
    fun `PrefState hands a plain java HashSet to the editor`() {
        val recording = RecordingPrefs()
        val state = PrefState.ofProvider { recording }

        assertTrue(state.set("set", emptySet<String>()))

        assertEquals("set", recording.lastSetKey)
        val written = recording.lastSetValue
        assertEquals(HashSet::class.java, written?.javaClass)
        assertTrue(written!!.isEmpty())
    }

    @Test
    fun `PrefState copies non empty sets as well`() {
        val recording = RecordingPrefs()
        val state = PrefState.ofProvider { recording }

        assertTrue(state.set("set", arraySetOf("com.a", "com.b")))

        val written = recording.lastSetValue
        assertEquals(HashSet::class.java, written?.javaClass)
        assertEquals(setOf("com.a", "com.b"), written)
    }

    @Test
    fun `raw kotlin singleton set is not loadable by the bootstrap class loader`() {
        // 崩溃根因的直述：Parcel$2.resolveClass 用 Class.forName(name, false, <boot loader>) 解析 Serializable，
        // emptySet() 得到的是 kotlin.collections.EmptySet，不在 boot 路径上
        val raw = emptySet<String>()

        assertEquals("kotlin.collections.EmptySet", raw.javaClass.name)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName(raw.javaClass.name, false, null)
        }
    }

    @Test
    fun `copied set class is loadable by the bootstrap class loader`() {
        val safe = emptySet<String>().toPrefsSafeSet()

        Class.forName(safe.javaClass.name, false, null)
        assertEquals("java.util.HashSet", safe.javaClass.name)
    }

    /** 只记录 putStringSet 收到的值；落盘后的集合类会被框架实现自己复制成 HashSet，断言不到真正交给 editor 的值 */
    private class RecordingPrefs : SharedPreferences {

        var lastSetKey: String? = null
        var lastSetValue: Set<String>? = null

        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>()

        override fun getString(key: String?, defValue: String?): String? = defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = false

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                lastSetKey = key
                lastSetValue = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

            override fun remove(key: String?): SharedPreferences.Editor = this

            override fun clear(): SharedPreferences.Editor = this

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
