package io.github.nanomuse.apiproof

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** This text-only fixture may create upload directories, but may never create session payloads. */
internal fun removeOwnedSessionDirectories(filesRoot:File,id:String) {
    check(java.util.UUID.fromString(id).toString()==id)
    val root=File(filesRoot,"minis-sessions/$id")
    if(!root.exists() && !java.nio.file.Files.isSymbolicLink(root.toPath())) return
    check(!java.nio.file.Files.isSymbolicLink(filesRoot.toPath()) &&
        !java.nio.file.Files.isSymbolicLink(File(filesRoot,"minis-sessions").toPath())) { "Unknown owned session parent preserved" }
    val known=setOf("","attachments","attachments/uploads")
    val members=mutableListOf<File>()
    fun visit(file:File) {
        check(members.size<known.size && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory &&
            file.relativeTo(root).invariantSeparatorsPath in known &&
            file.canonicalPath.startsWith(filesRoot.canonicalPath+File.separator)) { "Unknown owned session file preserved" }
        members+=file; checkNotNull(file.listFiles()).forEach(::visit)
    }
    visit(root)
    members.sortedByDescending { it.path.length }.forEach { check(it.listFiles()?.isEmpty()==true && it.delete()) }
}

/** Delete only recognized empty fixture directories; unknown members are preserved. */
internal fun removeOwnedPublicDirectories(ownerRoot:File) {
    val known=setOf("files","cache","files/no-backup","cache/code","files/external","cache/external",
        "files/obb","files/databases","files/minis-sessions")
    val members=mutableListOf<File>()
    fun visit(file:File) {
        check(members.size<known.size && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory &&
            file.relativeTo(ownerRoot).invariantSeparatorsPath in known &&
            file.canonicalPath.startsWith(ownerRoot.canonicalPath+File.separator)) { "Unknown public context member preserved" }
        members+=file
        checkNotNull(file.listFiles()).forEach(::visit)
    }
    listOf(File(ownerRoot,"files"),File(ownerRoot,"cache"))
        .filter { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach(::visit)
    members.sortedByDescending { it.path.length }.forEach { check(it.listFiles()?.isEmpty()==true && it.delete()) }
}

/** VM prompt reads are confined to fresh public files and in-memory preferences. */
internal class ForegroundPublicContext(base: Context, val ownerRoot: File) : ContextWrapper(base) {
    private val preferences = mutableMapOf<String, ForegroundMemoryPreferences>()
    private val files = File(ownerRoot,"files")
    private val cache = File(ownerRoot,"cache")
    init {
        check(ownerRoot.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(ownerRoot.name.matches(Regex("foreground-api-proof-[0-9a-f-]{36}")))
        check(ownerRoot.isDirectory && !java.nio.file.Files.isSymbolicLink(ownerRoot.toPath()))
        files.mkdir(); cache.mkdir()
        check(listOf(files,cache).all { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
            it.canonicalFile.parentFile==ownerRoot.canonicalFile })
    }
    private fun leaf(root: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        return File(root,name).also { check(it.canonicalFile.parentFile == root.canonicalFile) }
    }
    override fun getApplicationContext(): Context = this
    override fun getFilesDir() = files
    override fun getCacheDir() = cache
    override fun getNoBackupFilesDir() = leaf(files,"no-backup").also { it.mkdir() }
    override fun getCodeCacheDir() = leaf(cache,"code").also { it.mkdir() }
    override fun getDataDir() = ownerRoot
    override fun getExternalFilesDir(type: String?) = leaf(files,"external").also { it.mkdir() }
    override fun getExternalFilesDirs(type: String?) = arrayOf(getExternalFilesDir(type))
    override fun getExternalCacheDir() = leaf(cache,"external").also { it.mkdir() }
    override fun getExternalCacheDirs() = arrayOf(getExternalCacheDir())
    override fun getObbDir() = leaf(files,"obb").also { it.mkdir() }
    override fun getObbDirs() = arrayOf(getObbDir())
    override fun getFileStreamPath(name: String) = leaf(files,name)
    override fun openFileInput(name: String) = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        require(mode == Context.MODE_PRIVATE || mode == Context.MODE_APPEND)
        return FileOutputStream(getFileStreamPath(name),mode == Context.MODE_APPEND)
    }
    override fun deleteFile(name: String) = getFileStreamPath(name).delete()
    override fun fileList() = files.list() ?: emptyArray()
    override fun getDir(name: String, mode: Int): File {
        require(mode == Context.MODE_PRIVATE)
        return leaf(files,"app_$name").also { it.mkdir() }
    }
    override fun getDatabasePath(name: String): File = leaf(files,"databases").also { it.mkdir() }.let { leaf(it,name) }
    override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?):android.database.sqlite.SQLiteDatabase =
        error("Optional database access is disabled for the public fixture")
    override fun openOrCreateDatabase(name:String,mode:Int,factory:android.database.sqlite.SQLiteDatabase.CursorFactory?,handler:android.database.DatabaseErrorHandler?):android.database.sqlite.SQLiteDatabase =
        error("Optional database access is disabled for the public fixture")
    override fun deleteDatabase(name:String):Boolean = error("Optional database access is disabled for the public fixture")
    override fun databaseList():Array<String> = emptyArray()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        require(mode == Context.MODE_PRIVATE)
        return synchronized(preferences) { preferences.getOrPut(name) { ForegroundMemoryPreferences() } }
    }
    override fun deleteSharedPreferences(name: String): Boolean = synchronized(preferences) { preferences.remove(name); true }
    override fun createDeviceProtectedStorageContext(): Context = this
    override fun isDeviceProtectedStorage() = false
    override fun moveDatabaseFrom(sourceContext: Context, name: String) = false
    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String) = false
}

internal class ForegroundMemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String,Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    @Synchronized override fun getAll(): MutableMap<String,*> = values.mapValues { (_,v) -> if(v is Set<*>) v.toSet() else v }.toMutableMap()
    @Synchronized override fun contains(key: String) = values.containsKey(key)
    @Synchronized override fun getString(key: String, default: String?) = values[key] as? String ?: default
    @Suppress("UNCHECKED_CAST") @Synchronized override fun getStringSet(key: String, default: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: default?.toMutableSet()
    @Synchronized override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    @Synchronized override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    @Synchronized override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
    @Synchronized override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    @Synchronized override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.add(listener) }
    @Synchronized override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener) }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending=mutableMapOf<String,Any?>(); private var clear=false
        override fun putString(k:String,v:String?) = apply { pending[k]=v }
        override fun putStringSet(k:String,v:MutableSet<String>?) = apply { pending[k]=v?.toSet() }
        override fun putInt(k:String,v:Int) = apply { pending[k]=v }
        override fun putLong(k:String,v:Long) = apply { pending[k]=v }
        override fun putFloat(k:String,v:Float) = apply { pending[k]=v }
        override fun putBoolean(k:String,v:Boolean) = apply { pending[k]=v }
        override fun remove(k:String) = apply { pending[k]=null }
        override fun clear() = apply { clear=true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            val changed: Set<String>; val observers: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(this@ForegroundMemoryPreferences) {
                changed = (if(clear) values.keys.toSet() else emptySet()) + pending.keys
                if(clear) values.clear()
                pending.forEach { (k,v) -> if(v==null) values.remove(k) else values[k]=v }
                observers=listeners.toList()
            }
            changed.forEach { k -> observers.forEach { it.onSharedPreferenceChanged(this@ForegroundMemoryPreferences,k) } }
            return true
        }
    }
}
