data class CardOptions(val title: String, val count: Int, val enabled: Boolean)

fun event() {
    Analytics.send(Event("click") {
        putString("target", target)
    })
}
