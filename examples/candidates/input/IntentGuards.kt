private fun simple() = intent {
    postSideEffect(Effect.Back)
}

private fun explained() = intent {
    // Keep the explanation.
    postSideEffect(Effect.Back)
}

private fun multiple() = intent {
    first()
    second()
}
