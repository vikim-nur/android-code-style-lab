data class Options(
    val title: String,
    val enabled: Boolean,
)

fun render() {
    display(
        title,
        enabled,
    )
}
