fun message() {
    val displayMessage =
        cachedMessage ?: repository.loadMessageForAccount(currentAccountIdentifier, selectedLanguageCode)
    val selectedItem =
        repository
            .loadAvailableItemsForCurrentSession(
                currentAccountIdentifier,
                selectedCategoryIdentifier,
                includeArchivedItems,
            ).firstOrNull()
}
