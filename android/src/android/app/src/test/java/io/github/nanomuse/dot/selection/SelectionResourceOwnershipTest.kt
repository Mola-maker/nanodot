package io.github.nanomuse.dot.selection
import io.github.nanomuse.dot.gesture.PointPx
import io.github.nanomuse.dot.screen.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class SelectionResourceOwnershipTest {
 private val key=SceneKey("fixture.host",7,0,1)
 private val host=RectPx(0f,0f,200f,200f)
 private fun shot()=ScreenSnapshot("fixture",9,42,key,host,0,RectPx(0f,0f,0f,0f),null,emptyList(),null,ScreenTransform(host,200,200))
 private fun closed(s:ScreenSnapshot):Boolean = (ScreenSnapshot::class.java.getDeclaredField("closed").apply { isAccessible=true }.get(s) as AtomicBoolean).get()
 @Test fun stopDuringDispatcherHandoffMustCloseDerivedSnapshot()= runBlocking {
  val source=shot();val derived=shot();val started=CompletableDeferred<Unit>();val release=CountDownLatch(1)
  val shape=RegionSelection(listOf(PointPx(10f,10f),PointPx(100f,10f),PointPx(100f,100f),PointPx(10f,100f)),RectPx(10f,10f,100f,100f),8100f)
  val coordinator=SelectionCoordinator({key},{CaptureOutcome.Success(source)},{_,_->withContext(Dispatchers.Default) {
   started.complete(Unit);release.await();derived
  }})
  val work=launch { coordinator.capture(shape,SelectionOrigin(key,host),9) { true } }
  started.await();work.cancel();release.countDown();work.join()
  println("sourceClosed=${closed(source)} derivedClosed=${closed(derived)} cancelled=${work.isCancelled}")
  assertTrue("Source ownership released",closed(source))
  assertTrue("Derived ownership must survive the cancelled cross-dispatcher return",closed(derived))
 }
 @Test fun ancestorLabelMustNotRetainKnownOutsideDescendantText() {
  val region=RegionSelection(listOf(PointPx(10f,10f),PointPx(90f,10f),PointPx(90f,90f),PointPx(10f,90f)),RectPx(10f,10f,90f,90f),6400f)
  fun node(ref:String,r:RectPx,text:String)=NodeDto(ref,r,text,null,null,"View",false,false,false,true,false)
  val nodes=listOf(node("snapshot:0",RectPx(20f,20f,80f,80f),"Inside. OUTSIDE_MARKER."),
   node("snapshot:0.0",RectPx(30f,30f,50f,50f),"Inside."),
   node("snapshot:0.1",RectPx(100f,100f,150f,150f),"OUTSIDE_MARKER."))
  val retained=RegionCropper.selectedNodes(nodes,region)
  println("retained="+retained.map { it.ref+":"+it.text })
  assertFalse("Known outside descendant text must not escape via retained ancestor label", retained.any { it.text?.contains("OUTSIDE_MARKER") == true })
 }
}
