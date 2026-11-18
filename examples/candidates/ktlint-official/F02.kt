fun card() {
    renderCard(
        title = title,
        count = count,
        onClick = onClick,
    )
}

fun event() {
    Analytics.send(
        Event("click") {
            putString("target", target)
        },
    )
}
