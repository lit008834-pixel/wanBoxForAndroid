// @author 雾晚
package io.nekohasekai.sagernet.utils

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build

object BoundedImageDecoder {
    fun decode(resolver: ContentResolver, uri: Uri, legacy: Boolean = Build.VERSION.SDK_INT < 28): Bitmap {
        if (!legacy && Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val size = ImageBudget.target(info.size.width, info.size.height)
                decoder.setTargetSize(size.first, size.second)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageBudget.sample(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("无法读取二维码图片")
    }
}
