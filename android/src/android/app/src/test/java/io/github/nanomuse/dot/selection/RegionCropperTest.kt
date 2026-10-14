package io.github.nanomuse.dot.selection

import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import org.junit.Assert.*
import org.junit.Test

class RegionCropperTest {
    private fun polygon(vararg points: Pair<Float,Float>) = RegionSelection(points.map { PointPx(it.first,it.second) },
        RectPx(points.minOf { it.first },points.minOf { it.second },points.maxOf { it.first },points.maxOf { it.second }),0f)

    @Test fun concaveSelectionActuallyErasesOutsidePixelsWithinItsBoundingBox() {
        // An L: selected top row and left column. The lower-right red marker is outside.
        val shape=polygon(0f to 0f,3f to 0f,3f to 1f,1f to 1f,1f to 3f,0f to 3f)
        val pixels=intArrayOf(1,2,3,4,0xffff0000.toInt(),0xffff0000.toInt(),5,0xffff0000.toInt(),0xffff0000.toInt())
        RegionCropper.maskPixels(pixels,3,3,ScreenTransform(RectPx(0f,0f,3f,3f),3,3),shape,emptyList())
        assertArrayEquals(intArrayOf(1,2,3,4,0xff000000.toInt(),0xff000000.toInt(),5,0xff000000.toInt(),0xff000000.toInt()),pixels)
        assertEquals(0,pixels.count { it==0xffff0000.toInt() })
    }

    @Test fun offsetWindowAndScaleUseImagePixelCentersWithoutRotatingAgain() {
        val shape=polygon(110f to 220f,130f to 220f,130f to 240f,110f to 240f)
        val pixels=IntArray(16) { 0xff00ff00.toInt() }
        RegionCropper.maskPixels(pixels,4,4,ScreenTransform(RectPx(100f,200f,140f,240f),4,4),shape,emptyList())
        val green=0xff00ff00.toInt(); val black=0xff000000.toInt()
        assertArrayEquals(intArrayOf(black,black,black,black,black,black,black,black,
            black,green,green,black,black,green,green,black),pixels)
    }

    @Test fun passwordPixelsAreRemovedEvenWhenTheirNodeCrossesTheSelection() {
        val shape=polygon(0f to 0f,3f to 0f,3f to 2f,0f to 2f)
        val pixels=IntArray(6) { 123 }
        RegionCropper.maskPixels(pixels,3,2,ScreenTransform(RectPx(0f,0f,3f,2f),3,2),shape,
            listOf(RectPx(-1f,0f,1.2f,1f)))
        assertArrayEquals(intArrayOf(0xff000000.toInt(),0xff000000.toInt(),123,123,123,123),pixels)
    }

    @Test fun textEvidenceOmitsPasswordOutsideAndAggregatingAncestorNodes() {
        val shape=polygon(10f to 10f,90f to 10f,90f to 90f,10f to 90f)
        fun node(id:String,rect:RectPx,password:Boolean=false)=NodeDto(id,rect,id,null,null,"Text",false,false,false,true,password)
        val nodes=listOf(node("inside",RectPx(20f,20f,40f,40f)),node("outside",RectPx(100f,100f,120f,120f)),
            node("outside words in ancestor",RectPx(0f,0f,200f,200f)),node("password",RectPx(50f,50f,60f,60f),true))
        assertEquals(listOf("inside"),RegionCropper.selectedNodes(nodes,shape).map { it.text })
    }
}
