package example

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

data class CardOptions(
    val title: String,
    val count: Int,
    val enabled: Boolean,
    val subtitle: String
)

@Composable
fun SummaryCard(
    title: String,
    count: Int,
    enabled: Boolean,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16)
    ) {
        Text(text = title)
        Counter(
            count = count,
            enabled = enabled
        )
    }
}

private fun back() = intent { postSideEffect(Effect.Back) }

fun normalize() {
    val normalizedText = text.trim().lowercase()
    display(normalizedText)
}
