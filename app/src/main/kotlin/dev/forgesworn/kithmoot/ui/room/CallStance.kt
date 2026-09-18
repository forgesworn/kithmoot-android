package dev.forgesworn.kithmoot.ui.room

/**
 * One answer to "what is this button for", for every control that offers a
 * call. A port of the web client's `app/src/call-stance.ts`, and it must stay
 * one: the two clients sit in the same rooms and a person moving between them
 * should not have to learn two vocabularies.
 *
 * The stance is computed from what other DEVICES say, never from the roster's
 * count of calls, and a leave in flight is stated rather than inferred.
 * Leaving is two steps - this device stops saying it is on the call, and then
 * the roster settles - and between them the roster still carries this
 * device's own entry. Read naively that is "a call is on in this room and you
 * are not on it", which is exactly the join door. It was never somebody
 * else's call; it was our own shadow.
 */
enum class CallStance { START, JOIN, LEAVE }

/**
 * @param mineOn this device says it is on a call - the membership, not "has a
 *   track".
 * @param otherDevicesOn devices on the room's current call that are not this
 *   one. Own other devices count: a call taken on the laptop is one this
 *   phone may join.
 * @param leaving Leave was pressed and has not settled, so nothing paints a
 *   state that is already on its way out.
 */
fun callStance(mineOn: Boolean, otherDevicesOn: Int, leaving: Boolean): CallStance = when {
    mineOn && !leaving -> CallStance.LEAVE
    otherDevicesOn > 0 -> CallStance.JOIN
    else -> CallStance.START
}

/** The words on the control. The full sentence, always: "Call" told a person
 *  nothing about what pressing it would do. */
fun callStanceLabel(stance: CallStance): String = when (stance) {
    CallStance.START -> "Start call"
    CallStance.JOIN -> "Join call"
    CallStance.LEAVE -> "Leave call"
}

/** The line beside it. */
fun callStanceTitle(stance: CallStance): String = when (stance) {
    CallStance.START -> "Start a call in this room"
    CallStance.JOIN -> "A call is on in this room"
    CallStance.LEAVE -> "You are on the call"
}
