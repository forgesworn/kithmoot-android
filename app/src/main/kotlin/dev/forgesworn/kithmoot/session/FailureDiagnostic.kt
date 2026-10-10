package dev.forgesworn.kithmoot.session

/** Internal code locations only. Never retain an exception or its messages:
 * they can contain bearer, signer, storage or transport data. */
internal fun codeLocationDiagnostic(error: Throwable): String =
    generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(" <- ") { cause ->
        cause.javaClass.name + cause.stackTrace.take(4).joinToString(prefix = " [", postfix = "]") {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        }
    }
