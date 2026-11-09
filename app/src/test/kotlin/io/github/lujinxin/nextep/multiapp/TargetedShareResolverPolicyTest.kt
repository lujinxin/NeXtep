package io.github.lujinxin.nextep.multiapp

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetedShareResolverPolicyTest {
    @Test fun resolverKeepsTheCloneUserInsteadOfItsPersonalTab() {
        assertEquals(999, route(target = 999, process = 999))
    }

    @Test fun cloneIdentifiersAreNotHardcoded() {
        assertEquals(137, route(target = 137, process = 137))
    }

    @Test fun primaryUserAndMultipleImageSharesRemainTargeted() {
        assertEquals(0, route(target = 0, process = 0, action = Intent.ACTION_SEND_MULTIPLE))
    }

    @Test fun ordinarySharesKeepTheSystemRouting() {
        assertNull(route(marked = false))
        assertNull(route(action = Intent.ACTION_VIEW))
    }

    @Test fun missingOrForeignUserCannotRedirectTheResolver() {
        assertNull(route(target = -1))
        assertNull(route(target = 0, process = 999))
    }

    @Test fun anotherPackageCannotReuseTheTargetingMarker() {
        assertNull(route(resolved = "another.package"))
        assertNull(route(requested = null))
        assertNull(route(resolved = null))
    }

    @Test fun systemHandoffUsesThePromotedTaskIdentity() {
        assertEquals(999, handoff())
        assertNull(handoff(origin = origin().copy(taskId = 18)))
        assertNull(handoff(origin = origin().copy(userId = 0)))
    }

    @Test fun untrustedCallerOrSourceCannotRetargetAnActivity() {
        assertNull(handoff(origin = origin().copy(launchedFromSystemUi = false)))
        assertNull(handoff(origin = origin().copy(callerMatchesResolver = false)))
        assertNull(handoff(origin = origin().copy(packageName = "another.package")))
    }

    private fun origin() = TargetedShareResolverPolicy.Origin(17, 999, "android", true, true)

    private fun handoff(origin: TargetedShareResolverPolicy.Origin = origin()) =
        TargetedShareResolverPolicy.authorizeHandoff(true, Intent.ACTION_SEND, 17, 999,
            "com.tencent.mobileqq", "com.tencent.mobileqq", origin)

    private fun route(marked: Boolean = true, action: String? = Intent.ACTION_SEND,
        target: Int = 999, process: Int = 999, requested: String? = "com.tencent.mobileqq",
        resolved: String? = requested) = TargetedShareResolverPolicy.targetUserId(
        marked, action, target, process, requested, resolved,
    )
}
