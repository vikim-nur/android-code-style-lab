class SummaryViewModel
    @Inject
    constructor(
        private val repository: Repository,
    ) : BaseViewModel<SummaryState>(SummaryState()) {
    }

class RowAdapter(
    private val onClick: (Row) -> Unit,
) : ListAdapter<Row, RowAdapter.Holder>(
        RowDiff(),
    ) {
    inner class Holder(
        private val binding: RowBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
