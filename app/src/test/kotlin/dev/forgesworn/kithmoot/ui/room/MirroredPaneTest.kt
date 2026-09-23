package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.session.Roles
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mirrored self-view is this device's preview only, and can be turned off. */
class MirroredPaneTest {

    @Test fun ownCamerasAreMirroredByDefault() {
        assertTrue(mirroredPane(isSelf = true, role = Roles.CAMERA, mirrorSelf = true))
    }

    @Test fun turningItOffShowsOwnCamerasTheRightWayRound() {
        assertFalse(mirroredPane(isSelf = true, role = Roles.CAMERA, mirrorSelf = false))
    }

    @Test fun screensAndOtherPeopleAreNeverMirrored() {
        assertFalse(mirroredPane(isSelf = true, role = Roles.SCREEN, mirrorSelf = true))
        assertFalse(mirroredPane(isSelf = false, role = Roles.CAMERA, mirrorSelf = true))
    }
}
