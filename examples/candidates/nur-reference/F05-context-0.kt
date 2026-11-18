class ContentViewModel @Inject constructor(
    repository: Repository,
    stateStore: StateStore,
    savedState: SavedStateHandle,
    logger: Logger
) : BaseViewModel<ContentState>(ContentState()) {
}

class RowAdapter(
    private val imageLoader: ImageLoader,
    private val formatter: Formatter,
    private val onClick: (Row) -> Unit,
    private val onLongClick: (Row) -> Unit
) : ListAdapter<Row, RowAdapter.Holder>(RowDiff()) {
}

data class RowModel(
    val id: Long,
    val title: String,
    val subtitle: String,
    val enabled: Boolean
) : BaseModel()
