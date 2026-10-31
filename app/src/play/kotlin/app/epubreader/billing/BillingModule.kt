package app.epubreader.billing

/** Play flavor. Real store billing will be implemented here (and only here) after the MVP. */
object BillingModule {
    val gateway: BillingGateway = object : BillingGateway { override val available = false }
}
