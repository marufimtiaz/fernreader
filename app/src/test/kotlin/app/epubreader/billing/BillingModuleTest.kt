package app.epubreader.billing

import org.junit.Assert.assertFalse
import org.junit.Test

class BillingModuleTest {
    @Test fun mvp_has_no_billing_in_any_flavor() = assertFalse(BillingModule.gateway.available)
}
