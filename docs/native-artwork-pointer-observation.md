# Observe the artwork opener before selecting a correction

The ordinary offline-media journey has failed its original ten-second wait
for Stickers. Its failure screenshot shows the draft focused, the IME visible
and no artwork tray. That does not establish whether the Emoji tap reached
its button. The tray already clears focus and requests keyboard hiding.

An observer runs only in that synthetic instrumentation fixture. It watches
the original pointer down and up without consuming either event, and samples
the point, live layout bounds and expanded touch bounds on the same UI thread.
It retains at most two numeric rows and the picker state at preparation.
Detached or unavailable nodes remain inconclusive. No drafts, arbitrary
semantics, identities or room data enter the observation.

Failure metadata is captured before teardown without replacing the original
exception. A success control is retained only after the original Stickers
wait succeeds. A passing instrumented case cannot attribute the old failure:
the observer can itself affect scheduling. Points outside the live expanded
touch target establish a missed pointer at that measured boundary; points
inside it require further handler/focus investigation.

The original pointer click, all five artwork journeys, ten-second wait,
offline/no-upload/no-send assertions and production UI remain unchanged.
Do not substitute a semantics action, force-hide the keyboard, add a blind
sleep or widen a deadline. Require complete ordinary and final-head CI before
merge; emulator evidence does not qualify physical phone or BLE acceptance.
