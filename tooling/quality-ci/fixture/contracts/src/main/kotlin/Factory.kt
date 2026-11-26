package fixture.contracts

typealias Controller = androidx.navigation.NavController
typealias ApiCard = fixture.network_api.model.Card

fun loadCard(): ApiCard = ApiCard("synthetic")
