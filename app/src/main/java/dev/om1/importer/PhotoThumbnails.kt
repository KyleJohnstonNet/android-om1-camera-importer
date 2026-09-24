package dev.om1.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

object PhotoThumbnails {
    private fun retained(context:Context)=dev.om1.importer.core.RecentPhotos.retainedHashes(
        QueueStore.get(context).recentHashes())
    @Synchronized fun prune(context:Context) {
        val keep=retained(context)
        File(context.filesDir,"thumbnails").listFiles()?.filter {
            (it.name.matches(Regex("[0-9a-f]{64}\\.jpg")) && it.nameWithoutExtension !in keep) ||
                (it.name.startsWith("thumb-") && it.name.endsWith(".part"))
        }?.forEach { it.delete() }
    }
    @Synchronized fun file(context:Context,sha:String?,local:String?):File? {
        if(sha?.matches(Regex("[0-9a-f]{64}"))!=true) return null
        if(sha !in retained(context)) return null
        val directory=File(context.filesDir,"thumbnails").apply { mkdirs() }
        val target=File(directory,"$sha.jpg")
        if(target.exists()) return target
        if(local!="$sha.jpg") return null
        val original=File(context.filesDir,"originals/$local")
        if(!original.exists()) return null
        val oriented=decode(original,1280) ?: return null
        val staging=File.createTempFile("thumb-",".part",directory)
        try {
            staging.outputStream().use { check(oriented.compress(Bitmap.CompressFormat.JPEG,82,it)) }
            check(staging.renameTo(target))
        } finally {
            staging.delete();oriented.recycle()
        }
        return target
    }
    /** Read/decode under the same lock as pruning; an open popup then owns its bitmap. */
    @Synchronized fun load(context:Context,sha:String?,local:String?,full:Boolean=false):Bitmap? {
        if(sha?.matches(Regex("[0-9a-f]{64}"))!=true) return null
        val original=if(local=="$sha.jpg") File(context.filesDir,"originals/$local") else null
        if(full && original?.exists()==true) runCatching { decode(original,2048) }.getOrNull()?.let { return it }
        val preview=file(context,sha,local) ?: return null
        return decode(preview,if(full) 1280 else 128)
    }
    private fun decode(original:File,maxEdge:Int):Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(original.path,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        val options=BitmapFactory.Options().apply {
            inSampleSize=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>maxEdge) inSampleSize*=2
        }
        val bitmap=BitmapFactory.decodeFile(original.path,options) ?: return null
        val matrix=Matrix()
        when(runCatching { ExifInterface(original.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)) {
            2 -> matrix.setScale(-1f,1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f,-1f)
            5 -> { matrix.setRotate(90f);matrix.postScale(-1f,1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f);matrix.postScale(-1f,1f) }
            8 -> matrix.setRotate(270f)
        }
        val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        if(oriented!==bitmap) bitmap.recycle()
        return oriented
    }
}
