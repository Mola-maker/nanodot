package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.RectPx
import org.junit.Assert.*
import org.junit.Test

class CareInstructionPlacementTest {
    private val host = RectPx(0f,0f,1080f,2400f)
    private val card = RectPx(68f,1900f,1013f,2380f)
    @Test fun cueAvoidsWholeCardAndTargetAndRespectsImeClippedHost() {
        val target = RectPx(100f,1300f,900f,1500f)
        val imeHost = host.copy(bottom=1700f)
        val movedCard = card.copy(top=50f,bottom=450f)
        val cue = requireNotNull(careInstructionBounds(target,movedCard,imeHost,700f,200f,21f,false))
        assertTrue(imeHost.cueContains(cue)); assertFalse(cue.cueOverlaps(target)); assertFalse(cue.cueOverlaps(movedCard))
    }
    @Test fun noSafeSpaceSuppressesCueInsteadOfCoveringContent() {
        assertNull(careInstructionBounds(RectPx(0f,0f,1080f,1800f),card,host,700f,250f,21f,false))
        assertNull(careInstructionBounds(RectPx(200f,800f,800f,1000f),card,host,700f,2600f,21f,false))
        assertNull(careInstructionBounds(RectPx(Float.NaN,0f,100f,100f),card,host,200f,80f,21f,false))
    }
    @Test fun equalSideChoicesFollowLayoutDirectionWithoutCrossingTarget() {
        val wide = RectPx(0f,0f,1200f,500f)
        val middle = RectPx(500f,0f,700f,500f)
        val smallCard = RectPx(500f,400f,700f,500f)
        val ltr = requireNotNull(careInstructionBounds(middle,smallCard,wide,200f,100f,10f,false))
        val rtl = requireNotNull(careInstructionBounds(middle,smallCard,wide,200f,100f,10f,true))
        assertTrue(ltr.left >= middle.right); assertTrue(rtl.right <= middle.left)
    }
    @Test fun targetOutsideCurrentHostIsNotReusedAfterAnImeOrSceneChange() {
        assertNull(careInstructionBounds(RectPx(50f,1600f,300f,1700f),card,host.copy(bottom=1500f),250f,100f,20f,false))
    }
}
