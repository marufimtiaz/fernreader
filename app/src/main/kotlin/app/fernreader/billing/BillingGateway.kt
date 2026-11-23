package app.fernreader.billing

/** Seam for store billing (F-112). The MVP has no billing in any flavor. */
interface BillingGateway {
    val available: Boolean
}
