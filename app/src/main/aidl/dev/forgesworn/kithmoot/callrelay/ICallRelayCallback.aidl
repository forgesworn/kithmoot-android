package dev.forgesworn.kithmoot.callrelay;

oneway interface ICallRelayCallback {
    /** The state as JSON (RelayState), with no secret in it. */
    void onState(String state);
}
