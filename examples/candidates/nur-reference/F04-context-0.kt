fun installListener() {
    view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            selectItem(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
