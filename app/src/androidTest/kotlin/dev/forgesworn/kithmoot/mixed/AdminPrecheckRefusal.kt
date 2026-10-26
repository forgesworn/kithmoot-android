package dev.forgesworn.kithmoot.mixed

/** Test-only static evidence. Never exports messages, causes, filenames or arbitrary stack frames. */
internal data class AdminPrecheckRefusal(val code: String, val origin: String)

internal fun adminPrecheckRefusal(step: String, failure: Throwable): AdminPrecheckRefusal? {
    if (failure.javaClass != IllegalStateException::class.java) return null
    val runtime = "dev.forgesworn.kithmoot.mls.VmlsRuntime"
    val roleIntent = step == "admin-role-change" && failure.stackTrace.any {
        it.className == "dev.forgesworn.kithmoot.mls.AdminRoleIntent" && it.methodName == "targetLeafIds"
    }
    val method = when (step) {
        "admin-issue" -> "issuePrivateAdminInvitation"
        "admin-receipt-add" -> "addPrivateAdminInvitationMember"
        "admin-remove" -> "removeAdminInvitationMember"
        else -> null
    }
    val indy = method?.let { allowed -> failure.stackTrace.firstOrNull {
        it.className == runtime && it.methodName.startsWith(allowed + "\$lambda\$") &&
            it.methodName.removePrefix(allowed).matches(Regex("(?:\\\$lambda\\\$[0-9]{1,5}){1,3}"))
    } }
    val anonymous = method != null && failure.stackTrace.any { it.className.startsWith(runtime + "\$" + method + "\$") }
    val origin = when {
        roleIntent -> "AdminRoleIntent.targetLeafIds"
        indy != null -> "VmlsRuntime." + indy.methodName // Only the exact allowlisted class/method and bounded numeric lambda suffix.
        anonymous -> "VmlsRuntime." + method + ".anonymous"
        else -> return null
    }
    val reason = when (failure.message) {
        "This device is not a current group admin." -> "AdminRequired"
        "Admin roles changed; review the current roles again." -> if (roleIntent) "StaleAdminRoleReview" else null
        "Select an exact current device leaf." -> if (roleIntent) "UnknownAdminLeaf" else null
        "This device already has that role." -> if (roleIntent) "AdminRoleUnchanged" else null
        "The last group admin cannot be demoted." -> if (roleIntent) "LastAdminDemotion" else null
        "The group already has the maximum number of admins." -> if (roleIntent) "AdminSetFull" else null
        else -> null
    }
    return reason?.let { AdminPrecheckRefusal(it, origin) }
}

/** Runs the exact shipped test-helper logic on a host JVM as well as instrumentation, without app or device state. */
internal object AdminPrecheckRefusalRegression {
    @JvmStatic fun main(args: Array<String>) { verify(); println("AdminPrecheckRefusalRegression: passed") }
    fun verify() {
        val runtime = "dev.forgesworn.kithmoot.mls.VmlsRuntime"
        val sensitive = "private-capability-must-never-be-exported"
        fun failure(owner: String, method: String, message: String = "This device is not a current group admin."): IllegalStateException =
            IllegalStateException(message, RuntimeException(sensitive)).also { it.stackTrace = arrayOf(StackTraceElement(owner, method, sensitive, 17)) }
        val verified = listOf(
            Triple("admin-issue", "issuePrivateAdminInvitation\$lambda\$115\$lambda\$111", "issuePrivateAdminInvitation"),
            Triple("admin-remove", "removeAdminInvitationMember\$lambda\$134\$lambda\$132", "removeAdminInvitationMember"),
            Triple("admin-receipt-add", "addPrivateAdminInvitationMember\$lambda\$129\$lambda\$127", "addPrivateAdminInvitationMember"),
        )
        for ((step, frame, method) in verified) {
            val actual = failure(runtime, frame)
            check(adminPrecheckRefusal(step, actual) == AdminPrecheckRefusal("AdminRequired", "VmlsRuntime.$frame"))
            check(adminPrecheckRefusal(step, failure(runtime + "\$" + method + "\$1\$1", "invoke")) == AdminPrecheckRefusal("AdminRequired", "VmlsRuntime.$method.anonymous"))
            for (bad in listOf(
                failure(runtime + "Extra", frame), failure("Transport", frame), failure(runtime, frame + sensitive),
                failure(runtime, method), failure(runtime, method + "\$lambda\$" + sensitive),
                failure(runtime, frame, "Transport"), failure(runtime, frame, sensitive),
            )) check(adminPrecheckRefusal(step, bad) == null)
            check(adminPrecheckRefusal("send", actual) == null)
            check(adminPrecheckRefusal("admin-role-change", actual) == null)
            check(adminPrecheckRefusal(if (step == "admin-issue") "admin-remove" else "admin-issue", actual) == null)
            check(adminPrecheckRefusal(step, RuntimeException(actual.message).also { it.stackTrace = actual.stackTrace }) == null)
            check(!adminPrecheckRefusal(step, actual).toString().contains(sensitive))
        }
        check(adminPrecheckRefusal("admin-remove", IllegalStateException("This device is not a current group admin.")) == null)
        val role = failure("dev.forgesworn.kithmoot.mls.AdminRoleIntent", "targetLeafIds", "The last group admin cannot be demoted.")
        check(adminPrecheckRefusal("admin-role-change", role) == AdminPrecheckRefusal("LastAdminDemotion", "AdminRoleIntent.targetLeafIds"))
        check(adminPrecheckRefusal("admin-remove", role) == null)
    }
}
