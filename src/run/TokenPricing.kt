package run

/** User-supplied estimates; these are not provider invoices and do not model cache discounts. */
data class TokenPricing(val inputUsdPerMillion: Double, val outputUsdPerMillion: Double) {
    init {
        require(
            inputUsdPerMillion.isFinite() && inputUsdPerMillion >= 0 &&
                    outputUsdPerMillion.isFinite() && outputUsdPerMillion >= 0
        ) { "token prices must be finite and non-negative" }
    }
}
