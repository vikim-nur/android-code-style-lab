fun handle(event: Event) {
    when (event) {
        Event.Open -> {
            closeKeyboard()
            openDetails()
        }

        Event.Back -> goBack()
        Event.Refresh -> refresh()
        Event.Close -> close()
    }
}
