package io.github.nanomuse.dot.selection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class RegionCropperNativeTest {
    @Test fun encodedCropContainsNoPixelsOutsideTheEvenOddRegion() {
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (y in 10 until 80) for (x in 10 until 80) if (x < 30 || y < 30) source.setPixel(x,y,Color.GREEN)
        val bounds=RectPx(100f,200f,200f,300f)
        val snapshot=ScreenSnapshot("public",4,42,SceneKey("fixture",7,0,9),bounds,90,
            RectPx(0f,0f,0f,0f),null,emptyList(),source,ScreenTransform(bounds,100,100))
        val shape=RegionSelection(listOf(PointPx(110f,210f),PointPx(180f,210f),PointPx(180f,230f),
            PointPx(130f,230f),PointPx(130f,280f),PointPx(110f,280f)),RectPx(110f,210f,180f,280f),2400f)
        snapshot.use {
            RegionCropper.crop(it,shape).use { crop ->
                assertEquals(RectPx(110f,210f,180f,280f),crop.bounds)
                assertEquals(snapshot.scene,crop.scene)
                val bytes=ByteArrayOutputStream().also { output -> assertTrue(crop.image!!.compress(Bitmap.CompressFormat.PNG,100,output)) }.toByteArray()
                val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                try {
                    assertEquals(70,decoded.width); assertEquals(70,decoded.height)
                    var green=0; var red=0
                    for(y in 0 until 70) for(x in 0 until 70) {
                        if(decoded.getPixel(x,y)==Color.RED) red++
                        if(decoded.getPixel(x,y)==Color.GREEN) green++
                    }
                    assertEquals(0,red); assertEquals(2400,green)
                } finally { decoded.recycle() }
            }
        }
    }
}
