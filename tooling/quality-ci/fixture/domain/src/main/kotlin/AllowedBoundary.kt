package fixture.domain.allowed

data class Value(val id: String)
fun value(): Value = Value("synthetic")
