package com.fr.colorostools.utils

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.ArrayMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fr.colorostools.BuildConfig
import com.fr.colorostools.data.MemcConfigActivity
import com.fr.colorostools.data.MemcConfigPackage
import com.topjohnwu.superuser.ShellUtils
import org.lsposed.lsparanoid.Obfuscate
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

@Suppress("MemberVisibilityCanBePrivate")
@Obfuscate
object FileUtils {

    /**
     * 返回cache目录child
     * @receiver Context
     * @param child String
     * @return File
     */
    fun Context.cacheChild(child: String): File {
        return File(cacheDir.path, child)
    }

    /**
     * 获取文件路径
     * @param uri Uri 文件URI
     * @return String 文件Path
     */
    fun getDocumentPath(context: Context, uri: Uri): String? {
        if (ContentResolver.SCHEME_CONTENT != uri.scheme) return null
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val authority = when (uri.authority) {
            "com.android.externalstorage.documents" -> "ExternalStorageDocument"
            "com.android.providers.downloads.documents" -> "DownloadsDocument"
            "com.android.providers.media.documents" -> "MediaDocument"
            else -> null
        }
        when (authority) {
            "ExternalStorageDocument" -> {
                // ExternalStorageProvider
                val docId = DocumentsContract.getDocumentId(uri)
                val docArray = docId.split(":")
                val type = docArray[0]
                val dir = docArray[1]
                if ("primary" != type) return "null"
                return Environment.getExternalStorageDirectory().path + "/" + dir
            }

            "DownloadsDocument" -> {
                // DownloadsProvider
                val docId = DocumentsContract.getDocumentId(uri)
                if (TextUtils.isEmpty(docId)) return "null"
                return if (docId.startsWith("raw:")) {
                    docId.replaceFirst("raw:", "")
                } else if (docId.contains("msf:")) {
                    getMSMCacheFile(context, uri)?.path ?: "null"
                } else {
                    val contentUri = ContentUris.withAppendedId(
                        "content://downloads/public_downloads".toUri(), ContentUris.parseId(uri)
                    )
                    getDataColumn(context, contentUri, null, null)
                }
            }

            "MediaDocument" -> {
                // MediaProvider
                val docId = DocumentsContract.getDocumentId(uri)
                val docArray = docId.split(":")
                val contentUri: Uri? = when (docArray[0]) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> null
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(docArray[1])
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        }
        return null
    }

    /**
     * 获取文件数据列
     * @param context Context
     * @param uri Uri
     * @param selection String?
     * @param selectionArgs Array<String>?
     * @return String?
     */
    fun getDataColumn(
        context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?
    ): String? {
        if (uri == null) return null

        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex: Int = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    /**
     * 处理MSF类型
     * @param context Context
     * @param uri Uri
     * @return String?
     */
    fun getMSMCacheFile(context: Context, uri: Uri): File? {
        val toolDir = checkDownloadDir(context, "ColorOSTools")
        val dir = File(toolDir, "cache").apply {
            if (isFile) delete()
            if (!exists()) mkdirs()
        }
        File(dir, ".nomedia").apply {
            if (!exists()) createNewFile()
        }
//        LogUtils.d("getMSMCacheFile", "uri", uri.toString(), true)
        val fileType = context.contentResolver.getType(uri)?.split("/")?.get(1) ?: "png"
//        LogUtils.d("getMSMCacheFile", "fileType", fileType, true)
        val fileName = SystemClock.uptimeMillis().toString() + "." + fileType
        val file = File(dir, fileName)
//        LogUtils.d("getMSMCacheFile", "file", file.path, true)
        return copyUriToFile(context, uri, file)
    }

    /**
     * 创建指定后缀的缓存文件
     * @param context Context
     * @param suffix String
     * @return File
     */
    fun createCacheFile(context: Context, suffix: String): File {
        val toolDir = checkDownloadDir(context, "ColorOSTools")
        val dir = File(toolDir, "cache").apply {
            if (isFile) delete()
            if (!exists()) mkdirs()
        }
        File(dir, ".nomedia").apply {
            if (!exists()) createNewFile()
        }
        val fileName = SystemClock.uptimeMillis().toString() + "." + suffix
        return File(dir, fileName)
    }

    /**
     * 从Uri赋值文件
     * @param context Context
     * @param uri Uri
     * @param outputFile File
     * @return String
     */
    fun copyUriToFile(context: Context, uri: Uri, outputFile: File): File? {
        try {
            getUriPath(context, uri)?.let {
//                LogUtils.d("getMSMCacheFile", "getUriPath", it, true)
                val target = File(it).copyTo(outputFile, true)
                if (target.exists()) return target
            }
        } catch (e: Exception) {
//            LogUtils.d("getMSMCacheFile", "uri to file copyTo", e.toString(), true)
        }
        try {
            context.contentResolver.openInputStream(uri)?.let {
                return copyStreamToFile(it, outputFile)
            }
        } catch (e: FileNotFoundException) {
//            LogUtils.d("getMSMCacheFile", "copyUriToFile", e.toString(), true)
        }
        return null
    }

    /**
     * 从InputStream复制文件
     * @param inputStream InputStream
     * @param outputFile File
     * @return String
     */
    fun copyStreamToFile(inputStream: InputStream, outputFile: File): File {
        inputStream.use { input ->
            val outputStream = FileOutputStream(outputFile)
            outputStream.use { output ->
                val buffer = ByteArray(4 * 1024) // buffer size
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    output.write(buffer, 0, byteCount)
                }
                output.flush()
            }
        }
        return outputFile
    }

    /**
     * 从InputStream复制Stream
     * @param inputStream InputStream
     * @param outputStream OutputStream
     */
    fun copyStream(inputStream: InputStream, outputStream: OutputStream) {
        inputStream.use { input ->
            outputStream.use { output ->
                val buffer = ByteArray(4 * 1024) // buffer size
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    output.write(buffer, 0, byteCount)
                }
                output.flush()
            }
        }
    }

    /**
     * 读取文件输出字符串
     * @param file File
     * @return String?
     */
    fun readFromFile(file: File): String? {
        var fis: FileInputStream? = null
        var output: String? = null
        try {
            fis = FileInputStream(file)
            val buffer = ByteArray(4096)
            var len: Int
            val sb = StringBuilder()
            while (fis.read(buffer).also { len = it } != -1) {
                sb.append(String(buffer, 0, len))
            }
            output = sb.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            fis?.close()
        }
        return output
    }

    /**
     * 从URI文件读取字符串
     * @param context Context
     * @param uri Uri
     * @return String
     */
    fun readFromUri(context: Context, uri: Uri): String {
        val stringBuilder = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    stringBuilder.append(line)
                    line = reader.readLine()
                }
            }
        }
        return stringBuilder.toString()
    }

    /**
     * 分享文件
     * @param context Context
     * @param title String
     * @param file File
     */
    fun shareFile(context: Context, title: CharSequence, file: File) {
        if (file.exists()) {
            val share = Intent(Intent.ACTION_SEND)
            val contentUri = FileProvider.getUriForFile(
                context, BuildConfig.APPLICATION_ID + ".FileProvider", file
            )
            share.putExtra(Intent.EXTRA_STREAM, contentUri)
            share.type = "application/vnd.android.package-archive"
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(share, title))
        }
    }

    /**
     * 分享文本内容
     * @param context Context
     * @param content CharSequence?
     */
    fun shareString(context: Context, title: CharSequence, content: CharSequence?) {
        val sendIntent = Intent()
        sendIntent.action = Intent.ACTION_SEND
        sendIntent.putExtra(Intent.EXTRA_TEXT, content)
        sendIntent.type = "text/plain"
        context.startActivity(Intent.createChooser(sendIntent, title))
    }

    /**
     * 检查文件读写权限
     * @param context Context
     */
    fun checkRWPermission(context: Context) {
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent.setData("package:${context.packageName}".toUri()))
        }
    }

    /**
     * 检查获取LogCat目录
     * @receiver Context
     */
    fun checkLogCatDir(context: Context, fileName: String): File {
        val dir = File(context.cacheDir.path, "logcat").apply {
            if (isFile) delete()
            if (!exists()) mkdirs()
        }
        return File(dir.path, fileName).apply {
            if (isDirectory) delete()
            if (!exists()) createNewFile()
        }
    }

    /**
     * 检查获取Download子目录
     * @receiver Context
     */
    fun checkDownloadDir(context: Context, fileName: String): File {
        checkRWPermission(context)
        val file =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply {
                if (isFile) delete()
                if (!exists()) mkdirs()
            }
        return File(file, fileName).apply {
            if (isFile) delete()
            if (!exists()) mkdirs()
        }
    }

    /**
     * 使用SU强制删除文件
     * @param path String
     */
    fun forceDeleteFile(path: String) {
        ShellUtils.fastCmd(
            "chattr -R -i -a $path >/dev/null 2>&1", "chmod -R 644 $path", "rm -rf $path"
        )
    }

    /**
     * 解析Xml文件到Map
     * @param file File
     * @return Map<String, String>
     */
    fun parseXmlToMap(file: File): Map<String, String> {
        val mapData = ArrayMap<String, String>()
        try {
            val fileInputStream = FileInputStream(file)
            val xmlParser = android.util.Xml.newPullParser()
            xmlParser.setInput(fileInputStream, null)

            var eventType = xmlParser.eventType
            var key: String? = null
            var value: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (xmlParser.name == "string") {
                            key = xmlParser.getAttributeValue(null, "name")
                        }
                        if (xmlParser.name == "int" || xmlParser.name == "long" || xmlParser.name == "boolean") {
                            key = xmlParser.getAttributeValue(null, "name")
                        }
                        xmlParser.next()
                        if (xmlParser.name == "string") {
                            value = xmlParser.text
                        }
                        if (xmlParser.name == "int" || xmlParser.name == "long" || xmlParser.name == "boolean") {
                            value = xmlParser.getAttributeValue(null, "value")
                        }
                        if (key != null && value != null) {
                            mapData[key] = value
                            key = null
                            value = null
                        }
                    }

                    XmlPullParser.TEXT -> {}
                    XmlPullParser.END_TAG -> {}
                }
                eventType = xmlParser.next()
            }
            fileInputStream.close()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return mapData
    }

    /**
     * 解析Memc Xml配置文件
     * @param inputStream InputStream
     * @param packages ArrayList<MemcConfigPackage>
     * @param activitys ArrayList<MemcConfigActivity>
     */
    fun parseMemcXml(
        inputStream: InputStream,
        packages: ArrayList<MemcConfigPackage>,
        activitys: ArrayList<MemcConfigActivity>
    ) {
        try {
            val xmlParser = android.util.Xml.newPullParser()
            xmlParser.setInput(inputStream, null)

            var eventType = xmlParser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (xmlParser.name) {
                            "mConfigPackage" -> {
                                val screenFrameRate: String =
                                    xmlParser.getAttributeValue(null, "rate")
                                val type: String = xmlParser.getAttributeValue(null, "type")
                                xmlParser.next()
                                val text: String = xmlParser.text
                                if (text.isNotBlank()) {
                                    packages.add(MemcConfigPackage(text, screenFrameRate, type))
                                }
                            }

                            "mConfigActivity" -> {
                                val type: String = xmlParser.getAttributeValue(null, "type")
                                xmlParser.next()
                                val text: String = xmlParser.text
                                if (text.isNotBlank() && text.contains("/")) {
                                    val packName = text.substringBefore("/")
                                    val activity = text.substringAfter("/")
                                    activitys.add(MemcConfigActivity(packName, activity, type))
                                }
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {}
                    XmlPullParser.END_TAG -> {}
                }
                eventType = xmlParser.next()
            }
            inputStream.close()
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    /**
     * 获取文件大小
     * @param file File
     * @return Long
     */
    fun getFileSize(file: File): Long {
        return try {
            if (file.exists() && file.isFile) {
                FileInputStream(file).channel.size()
            } else 0L
        } catch (e: Exception) {
            LogUtils.e("getFileSize", file.path, "$e")
            0L
        }
    }

    /**
     * Uri转BitMap
     * @param context Context
     * @param uri Uri
     * @return Bitmap?
     */
    fun uriToBitmap(context: Context, uri: Uri) = safeOfNull {
        val contentResolver: ContentResolver = context.contentResolver
        val source = ImageDecoder.createSource(contentResolver, uri)
        ImageDecoder.decodeBitmap(source)
    }

    /**
     * 获取持久性URI权限
     * @param appInfoContext Context
     * @param uri Uri
     */
    fun takeUriPermission(appInfoContext: Context, uri: Uri) {
//        val packName = appInfoContext.packageName
//        appInfoContext.grantUriPermission(packName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
//
        val flag = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        appInfoContext.contentResolver.takePersistableUriPermission(uri, flag)
    }

    /**
     * 获取Uri文件路径
     * @param context Context
     * @param uri Uri
     */
    fun getUriPath(context: Context, uri: Uri): String? {
        var path: String? = null
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    path = it.getString(columnIndex)
                }
            }
        } else if (ContentResolver.SCHEME_FILE == uri.scheme) {
            path = uri.path
        }
        return path
    }

    /**
     * 循环遍历删除文件
     */
    fun deleteFile(file: File) {
        if (file.exists().not()) return
        if (file.isDirectory) {
            file.listFiles()?.onEach(::deleteFile)
            file.delete()
        } else {
            file.delete()
        }
    }
}