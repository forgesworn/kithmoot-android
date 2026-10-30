package dev.forgesworn.spry

/**
 * The JNI surface of spry-turn's `libspry_relay.so` (spry-turn
 * docs/embedding.md, "The JNI shim"). The library binds
 * `Java_dev_forgesworn_spry_SpryRelayNative_*` by this exact class and method
 * names, so neither may change; `proguard-rules.pro` keeps both. Loading the
 * library happens on first use, never at app start. Call it only through
 * [dev.forgesworn.kithmoot.media.SpryRelayLibrary], which checks the ABI.
 */
object SpryRelayNative {
    init { System.loadLibrary("spry_relay") }

    /** `1` for this library; checked before anything else. */
    @JvmStatic external fun abiVersion(): Int

    /** A handle, or 0 if an argument was malformed or the node thread could not start. */
    @JvmStatic external fun start(args: Array<String>, environment: Array<String>): Long

    /** The next line as UTF-8, `null` if none arrived in time, empty once `stopped` was read. */
    @JvmStatic external fun nextLine(handle: Long, timeoutMillis: Int): ByteArray?

    /** Asks for an orderly stop and returns at once. */
    @JvmStatic external fun stop(handle: Long): Int

    /** `-1` while running, else 0, 1 or 3. */
    @JvmStatic external fun exitCode(handle: Long): Int

    /** Blocks up to seven seconds; never on the main thread. Never use the handle after. */
    @JvmStatic external fun free(handle: Long): Int
}
