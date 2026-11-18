fun track() {
    Analytics.send(
        Event("click") {
            putString("target", target)
        },
    )
}
