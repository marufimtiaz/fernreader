package app.fernreader.billing

/** F-Droid flavor: never contains proprietary billing libraries. */
object BillingModule {
    val gateway: BillingGateway = object : BillingGateway { override val available = false }
}
