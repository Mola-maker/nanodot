package io.github.nanomuse.dot.selection

import android.graphics.Bitmap
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import kotlin.math.ceil
import kotlin.math.floor

/** Derives owned evidence from one admitted snapshot. The caller still closes the source. */
object RegionCropper {
    fun crop(snapshot: ScreenSnapshot, selection: RegionSelection): ScreenSnapshot {
        val source=snapshot.image
        val imageA=snapshot.transform.screenToImage(PointPx(selection.bounds.left,selection.bounds.top))
        val imageB=snapshot.transform.screenToImage(PointPx(selection.bounds.right,selection.bounds.bottom))
        val left=floor(imageA.x).toInt().coerceIn(0,snapshot.transform.imageWidth)
        val top=floor(imageA.y).toInt().coerceIn(0,snapshot.transform.imageHeight)
        val right=ceil(imageB.x).toInt().coerceIn(0,snapshot.transform.imageWidth)
        val bottom=ceil(imageB.y).toInt().coerceIn(0,snapshot.transform.imageHeight)
        require(right > left && bottom > top)
        val a=snapshot.transform.imageToScreen(PointPx(left.toFloat(),top.toFloat()))
        val b=snapshot.transform.imageToScreen(PointPx(right.toFloat(),bottom.toFloat()))
        val bounds=RectPx(a.x,a.y,b.x,b.y)
        val transform=ScreenTransform(bounds,right-left,bottom-top)
        var image: Bitmap?=null
        try {
            if (source != null) {
                val pixels=IntArray((right-left)*(bottom-top))
                source.getPixels(pixels,0,right-left,left,top,right-left,bottom-top)
                maskPixels(pixels,right-left,bottom-top,transform,selection,
                    snapshot.nodes.filter { it.password }.map { it.bounds })
                image=Bitmap.createBitmap(pixels,right-left,bottom-top,Bitmap.Config.ARGB_8888)
            }
            return ScreenSnapshot(snapshot.id,snapshot.generation,snapshot.capturedAtMs,snapshot.scene,bounds,
                snapshot.rotation,snapshot.insets,snapshot.imeBounds,selectedNodes(snapshot.nodes,selection),image,transform)
        } catch (failure: Throwable) { image?.recycle(); throw failure }
    }

    fun selectedNodes(nodes: List<NodeDto>, selection: RegionSelection): List<NodeDto> {
        val admitted = nodes.filter { !it.password && SelectionGeometry.containsRect(selection,it.bounds) }
        val excluded = nodes.filterNot { it in admitted }
        // NodeReader's snapshot:path references retain ancestry even when a parent reports a
        // smaller rectangle than its children. Never leak outside/password text via that parent.
        return admitted.filter { parent -> excluded.none { it.ref.startsWith("${parent.ref}.") } }
    }

    /** Masks outside pixels and password rectangles in the already cropped buffer. */
    fun maskPixels(pixels: IntArray, width: Int, height: Int, transform: ScreenTransform,
        selection: RegionSelection, passwords: List<RectPx>) {
        require(width > 0 && height > 0 && width.toLong()*height == pixels.size.toLong())
        require(transform.imageWidth == width && transform.imageHeight == height)
        val polygon=selection.polygon
        val black=0xff000000.toInt()
        // Half-open scanline intersections implement even-odd fill, including self intersections.
        // Copy only admitted spans: no antialias fringe or hidden RGB survives outside the polygon.
        val row=IntArray(width)
        for (y in 0 until height) {
            pixels.copyInto(row,0,y*width,(y+1)*width)
            pixels.fill(black,y*width,(y+1)*width)
            val screenY=transform.imageToScreen(PointPx(0f,y+.5f)).y
            val crossings=ArrayList<Float>(polygon.size)
            for (i in polygon.indices) {
                val a=polygon[i]; val b=polygon[(i+1)%polygon.size]
                if ((a.y > screenY) != (b.y > screenY))
                    crossings.add(a.x+(screenY-a.y)*(b.x-a.x)/(b.y-a.y))
            }
            crossings.sort()
            for (i in 0 until crossings.size-1 step 2) {
                val left=ceil(transform.screenToImage(PointPx(crossings[i],screenY)).x-.5f).toInt().coerceIn(0,width)
                val right=ceil(transform.screenToImage(PointPx(crossings[i+1],screenY)).x-.5f).toInt().coerceIn(0,width)
                if (right > left) row.copyInto(pixels,y*width+left,left,right)
            }
        }
        for (rect in passwords) {
            val a=transform.screenToImage(PointPx(rect.left,rect.top))
            val b=transform.screenToImage(PointPx(rect.right,rect.bottom))
            val left=floor(a.x).toInt().coerceIn(0,width); val right=ceil(b.x).toInt().coerceIn(0,width)
            val top=floor(a.y).toInt().coerceIn(0,height); val bottom=ceil(b.y).toInt().coerceIn(0,height)
            if (right > left) for (y in top until bottom) pixels.fill(black,y*width+left,y*width+right)
        }
    }
}
