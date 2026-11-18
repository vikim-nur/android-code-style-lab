fun icon(success: Boolean) {
    val icon =
        if (success) {
            Icons.Success
        } else {
            Icons.Warning
        }
    display(icon)
}
