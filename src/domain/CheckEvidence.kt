package domain

data class CheckEvidence(val name: String, val certificate: String, val refutation: String = "not_configured")
