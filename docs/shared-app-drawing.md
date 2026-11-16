# Drawing directly over a shared app

Android 0.6.159 adds a transparent marks canvas and a small Screen / App / Draw /
Clear / Stop toolbar while sharing. Incoming drawings appear over the attached
window; Draw temporarily captures local touches and sends normalised points
through the existing screen-annotation protocol. Use app restores ordinary
input. Clear and Stop use the current share's existing controls.

Before the normal Android projection chooser, KithMoot offers the optional
**Display over other apps** permission. You can choose **Share without drawings**.
For a whole-screen capture, tap Screen on the toolbar. For Android's one-app
capture, open the same app you selected in the system chooser and tap App.
This explicit attachment is needed because Android does not disclose the
selected app's window ID to the projection client.

App attachment needs the separately user-enabled **KithMoot shared-app drawing**
accessibility service. Android describes this as a broad accessibility
permission. KithMoot uses only application window IDs, active state and physical
bounds, only while an app presentation is attached. It never reads accessibility
roots, nodes, UI text, titles, packages or screenshots, performs accessibility
actions, injects input, or enables accessibility settings itself. No window
information is transmitted. The existing MediaProjection permission remains
the only source of shared video. Screen attachment does not query the service.
Disable this service in Android Accessibility settings to revoke app attachment;
revoke Display over other apps to remove the overlay. Neither requires Google
Play or a remote service.

Marks hide when the attached app is no longer active, the capture becomes
hidden, or projection and window aspect ratios disagree during rotation/resize.
The target never silently follows another foreground app. Drawing mode resets
when its bounds change. Closing the share/call, ending MediaProjection or
stopping its foreground service removes the overlay and discards unfinished
input. Remote marks cannot enable touch capture. Canvas opacity stays below
Android's obscuring limit so ordinary touches reach the underlying app.

Projection resize callbacks preserve the captured app's aspect ratio in the
WebRTC texture and existing virtual display. Late callbacks are bound to the
capture generation so they cannot resize or stop a replacement share.

`DrawingTargetTest` covers geometry, missing/hidden targets, rotation callback
ordering, whole-screen attachment and capture sizing. `ShareDrawingOverlayTest`
uses API 35 MediaProjection consent in both modes and a separate-UID native
target. It verifies incoming pixels, a real button receiving pass-through
touches, normalised pointer strokes and input release on close. The fixture
grants only its own disposable emulator's permissions. Physical GrapheneOS,
permission-consent UX and live mixed-client call acceptance remain pending.
