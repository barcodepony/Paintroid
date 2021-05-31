/*
 * Paintroid: An image manipulation application for Android.
 * Copyright (C) 2010-2021 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.catrobat.paintroid

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.catrobat.paintroid.common.Constants
import org.catrobat.paintroid.iotasks.BitmapReturnValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.*

import id.zelory.compressor.saveBitmap
import id.zelory.*
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.destination
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

class FileIO private constructor() {
    companion object {
        // TODO this is a mem-leak!
        private lateinit var context: Context

        @JvmStatic var filename = "image"
        @JvmStatic var ending = ".jpg"
        @JvmStatic var compressQuality = 100
        @JvmStatic var compressFormat = CompressFormat.JPEG
        @JvmStatic var catroidFlag = false
        @JvmStatic var isCatrobatImage = false
        @JvmStatic var wasImageLoaded = false
        @JvmStatic var currentFileNameJpg: String? = null
        @JvmStatic var currentFileNamePng: String? = null
        @JvmStatic var currentFileNameOra: String? = null
        @JvmStatic var uriFileJpg: Uri? = null
        @JvmStatic var uriFilePng: Uri? = null
        @JvmStatic var uriFileOra: Uri? = null

        @Throws(IOException::class)
        private fun saveBitmapToStream(outputStream: OutputStream?, bitmap: Bitmap) {
            var bitmap: Bitmap? = bitmap
            require(!(bitmap == null || bitmap.isRecycled)) { "Bitmap is invalid" }
            if (compressFormat == CompressFormat.JPEG) {
                val newBitmap = Bitmap.createBitmap(bitmap.width,
                        bitmap.height, bitmap.config)
                val canvas = Canvas(newBitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap = newBitmap
            }
            if(bitmap==null)throw IOException("Can't write empty Bitmap to stream")
            if (!bitmap.compress(compressFormat, compressQuality, outputStream)) {
                throw IOException("Can not write png to stream.")
            }
        }

        @Throws(IOException::class)
        fun saveBitmapToUri(uri: Uri, resolver: ContentResolver?, bitmap: Bitmap?): Uri {
            var u = URI(uri.toString())
            val f = File(u)

            if(bitmap==null) {
                throw IOException("Can not save bitmap.")
            }
            saveBitmap(bitmap, f, compressFormat, 100)
            val compressedImageFile = runBlocking {

                    val compressedImageFile = Compressor.compress(context, f, Dispatchers.Default) {
                        quality(100)
                        format(compressFormat)
//                        destination(f)
                    }
                    return@runBlocking compressedImageFile
            }

            return Uri.fromFile(compressedImageFile)
        }

        @Throws(IOException::class)
        fun saveBitmapToFile(fileName: String?, bitmap: Bitmap, resolver: ContentResolver): Uri? {
            val fos: OutputStream?
            val imageUri: Uri?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues()
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/*")
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (imageUri==null) throw IOException("Can't create imageUri.")
                fos = resolver.openOutputStream(imageUri)
                try {
                    saveBitmapToStream(fos, bitmap)
                    Objects.requireNonNull(fos, "Can't create fileoutputstream!")
                } finally {
                    fos!!.close()
                }
            } else {
                if (!(Constants.MEDIA_DIRECTORY.exists() || Constants.MEDIA_DIRECTORY.mkdirs())) {
                    throw IOException("Can not create media directory.")
                }
                val file = File(Constants.MEDIA_DIRECTORY, fileName)
                val outputStream: OutputStream = FileOutputStream(file)
                try {
                    saveBitmapToStream(outputStream, bitmap)
                } finally {
                    outputStream.close()
                }
                imageUri = Uri.fromFile(file)
            }
            return imageUri
        }

        fun saveBitmapToCache(bitmap: Bitmap, mainActivity: MainActivity): Uri? {
            var uri: Uri? = null
            try {
                val cachePath = File(mainActivity.cacheDir, "images")
                cachePath.mkdirs()
                val stream = FileOutputStream("$cachePath/image.png")
                saveBitmapToStream(stream, bitmap)
                stream.close()
                val imagePath = File(mainActivity.cacheDir, "images")
                val newFile = File(imagePath, "image.png")
                val fileProviderString = mainActivity.applicationContext.packageName + ".fileprovider"
                uri = FileProvider.getUriForFile(mainActivity.applicationContext, fileProviderString, newFile)
            } catch (e: IOException) {
                Log.e("Can not write", "Can not write png to stream.", e)
            }
            return uri
        }

        val defaultFileName: String
            get() = filename + ending

        @Throws(NullPointerException::class)
        fun createNewEmptyPictureFile(filename: String?, activity: Activity): File {
            var filename = filename
            if (filename == null) {
                filename = defaultFileName
            }
            if (!filename.toLowerCase(Locale.US).endsWith(ending.toLowerCase(Locale.US))) {
                filename += ending
            }
            if (!Objects.requireNonNull(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES))!!.exists()
                    && !Objects.requireNonNull(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES))!!.mkdirs()) {
                throw NullPointerException("Can not create media directory.")
            }
            return File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)
        }

        @Throws(IOException::class)
        private fun decodeBitmapFromUri(resolver: ContentResolver, uri: Uri, options: BitmapFactory.Options): Bitmap? {
            val inputStream = resolver.openInputStream(uri)
                    ?: throw IOException("Can't open input stream")
            return inputStream.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        }

        fun parseFileName(uri: Uri?, resolver: ContentResolver) {
            var fileName = "image"
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(uri!!, arrayOf(
                        MediaStore.Images.ImageColumns.DISPLAY_NAME
                ), null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME))
                }
            } finally {
                cursor?.close()
            }
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                ending = ".jpg"
                compressFormat = CompressFormat.JPEG
                filename = fileName.substring(0, fileName.length - ending.length)
            } else if (fileName.endsWith(".png")) {
                ending = ".png"
                compressFormat = CompressFormat.PNG
                filename = fileName.substring(0, fileName.length - ending.length)
            }
        }

        fun checkIfDifferentFile(filename: String): Int {
            if (currentFileNamePng == null && currentFileNameJpg == null && currentFileNameOra == null) {
                return Constants.IS_NO_FILE
            }
            if (currentFileNameJpg != null && currentFileNameJpg == filename) {
                return Constants.IS_JPG
            }
            if (currentFileNamePng != null && currentFileNamePng == filename) {
                return Constants.IS_PNG
            }
            return if (currentFileNameOra != null && currentFileNameOra == filename) {
                Constants.IS_ORA
            } else Constants.IS_NO_FILE
        }

        fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
            var width = width
            var height = height
            var sampleSize = 1
            while (width > maxWidth || height > maxHeight) {
                width /= 2
                height /= 2
                sampleSize *= 2
            }
            return sampleSize
        }

        @Throws(IOException::class)
        fun getBitmapFromUri(resolver: ContentResolver, bitmapUri: Uri): Bitmap? {
            val options = BitmapFactory.Options()
            options.inMutable = true
            return enableAlpha(decodeBitmapFromUri(resolver, bitmapUri, options))
        }

        @Throws(IOException::class)
        fun hasEnoughMemory(resolver: ContentResolver, bitmapUri: Uri, context: Context): Boolean {
            val requiredMemory: Long
            val availableMemory: Long
            var scaling = false
            val memoryinfo = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memoryinfo)
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            decodeBitmapFromUri(resolver, bitmapUri, options)
            if (options.outHeight < 0 || options.outWidth < 0) {
                throw IOException("Can't load bitmap from uri")
            }
            availableMemory = if ((memoryinfo.availMem - memoryinfo.threshold) * 0.9 > 5000 * 5000 * 4) {
                5000.toLong() * 5000 * 4
            } else {
                ((memoryinfo.availMem - memoryinfo.threshold) * 0.9).toLong()
            }
            requiredMemory = (options.outWidth * options.outHeight * 4).toLong()
            if (requiredMemory > availableMemory) {
                scaling = true
            }
            return scaling
        }

        @Throws(IOException::class)
        fun getScaleFactor(resolver: ContentResolver, bitmapUri: Uri, context: Context): Int {
            val heightToWidthFactor: Float
            val availablePixels: Float
            val availableHeight: Float
            val availableWidth: Float
            val availableMemory: Float
            val memoryinfo = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memoryinfo)
            val options = BitmapFactory.Options()
            decodeBitmapFromUri(resolver, bitmapUri, options)
            if (options.outHeight <= 0 || options.outWidth <= 0) {
                throw IOException("Can't load bitmap from uri")
            }
            val info = Runtime.getRuntime()
            availableMemory = ((info.maxMemory() - info.totalMemory() + info.freeMemory()) * 0.9).toFloat()
            heightToWidthFactor = (options.outWidth / (options.outHeight * 1.0)).toFloat()
            availablePixels = (availableMemory * 0.9 / 4.0).toFloat() //4 byte per pixel, 10% safety buffer on memory
            availableHeight = Math.sqrt((availablePixels / heightToWidthFactor).toDouble()).toFloat()
            availableWidth = availablePixels / availableHeight
            return calculateSampleSize(options.outWidth, options.outHeight,
                    availableWidth.toInt(), availableHeight.toInt())
        }

        @Throws(IOException::class)
        fun getBitmapFromUri(resolver: ContentResolver, bitmapUri: Uri, context: Context): BitmapReturnValue {
            val options = BitmapFactory.Options()
            options.inMutable = true
            options.inJustDecodeBounds = false
            val scaling = hasEnoughMemory(resolver, bitmapUri, context)
            return BitmapReturnValue(null, enableAlpha(decodeBitmapFromUri(resolver, bitmapUri, options)), scaling)
        }

        @Throws(IOException::class)
        fun getScaledBitmapFromUri(resolver: ContentResolver, bitmapUri: Uri, context: Context): BitmapReturnValue {
            val options = BitmapFactory.Options()
            options.inMutable = true
            options.inJustDecodeBounds = false
            options.inSampleSize = getScaleFactor(resolver, bitmapUri, context)
            return BitmapReturnValue(null, enableAlpha(decodeBitmapFromUri(resolver, bitmapUri, options)), false)
        }

        fun getBitmapFromFile(bitmapFile: File): Bitmap? {
            val options = BitmapFactory.Options()
            options.inMutable = true
            return enableAlpha(BitmapFactory.decodeFile(bitmapFile.absolutePath, options))
        }

        fun enableAlpha(bitmap: Bitmap?): Bitmap? {
            bitmap?.setHasAlpha(true)
            return bitmap
        }

        fun setContext(con: Context) {
            context=con
        }
    }

    init{
        throw AssertionError()
    }
}