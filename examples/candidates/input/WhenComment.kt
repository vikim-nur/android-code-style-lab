fun handle(event: Event) {
    when (event) {
        Event.Open -> {
            prepare()
            open()
        }
        // Keep this explanation attached to Close.
        Event.Close -> close()

        Event.Back -> back()
    }
}
