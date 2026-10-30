package dev.forgesworn.kithmoot.callrelay;

import dev.forgesworn.kithmoot.callrelay.ICallRelayCallback;

/**
 * The built-in call relay's service in its own process. Only this app's own
 * main process binds to it (the service is not exported, and checks the
 * caller's uid). The TURN secret crosses here once per start and is never
 * written to disk by the relay process; no other secret ever crosses.
 */
interface ICallRelay {
    const int OK = 0;
    const int ERROR_INVALID = 1;

    /** The person's Turn on. Returns OK, or ERROR_INVALID for a malformed request. */
    int start(in byte[] turnSecret, String pairingPublicKey, in String[] controlRelays, int listenPort);

    /** The person's own Stop. */
    void stop();

    /** The current state as JSON (RelayState), with no secret in it. */
    String state();

    void watch(ICallRelayCallback callback);
    void unwatch(ICallRelayCallback callback);
}
