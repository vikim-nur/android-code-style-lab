fun handle(event: Event) {
    when (event) {
        Event.Open -> {
            when (state) {
                State.Ready -> display()
                State.Empty -> hide()
            }
        }

        Event.Close -> close()
        Event.Back -> back()
    }
}
