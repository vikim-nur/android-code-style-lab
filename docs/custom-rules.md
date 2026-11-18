# Пять дополнений к ktlint

Это авторские экспериментальные правила оформления, не полный набор ktlint и не готовые правила бизнес-логики.

| Правило | В предлагаемом профиле | Что делает и где границы |
|---|---|---|
| nur:modifier-chain | Включено | Переносит известные синтаксические Modifier-цепочки с двух вызовов. Есть защита от части затенений; полного анализа типов нет. |
| nur:type-colon-spacing | Выключено | Сохраняет оба вида наследования, исправляя пробелы у типов. Сейчас используется штатный colon-spacing для согласованности со Studio. |
| nur:nested-lambda-wrapping | Включено | Сохраняет компактный вложенный вызов с trailing lambda; делегирует остальное штатному wrapping. Не удовлетворяет зависимости strict comma rules от standard:wrapping. |
| nur:call-arguments | Выключено | При включении переносит вызовы по порогу числа аргументов (по умолчанию 4). |
| nur:compact-intent | Включено | Схлопывает короткий intent с одним выражением без комментариев. Проверяет имя, не доказывает тип Orbit. Порог 120 ограничен также общей длиной строки. |

Числа 2/4/120 меняются через .editorconfig без пересборки JAR. Изменение алгоритма требует новой версии JAR. В транспортном прототипе compact-intent пока выключен: таблица относится именно к предлагаемому профилю.

## Перенос Modifier

`nur:modifier-chain`. Учебный проверенный пример.

Ввод:

```kotlin
import androidx.compose.ui.Modifier

fun card(modifier: Modifier) {
    val objectChain = Modifier.fillMaxSize().padding(8)
    val parameterChain = modifier.fillMaxSize().padding(8)
    val normalizedText = text.trim().lowercase()
}
```

Результат соответствующего варианта:

```kotlin
import androidx.compose.ui.Modifier

fun card(modifier: Modifier) {
    val objectChain = Modifier
        .fillMaxSize()
        .padding(8)
    val parameterChain = modifier
        .fillMaxSize()
        .padding(8)
    val normalizedText = text.trim().lowercase()
}
```

## Сохранение вложенной лямбды

`nur:nested-lambda-wrapping`. Учебный проверенный пример.

Ввод:

```kotlin
fun card() {
    renderCard(
        title = title,
        count = count,
        onClick = onClick
    )
}

fun event() {
    Analytics.send(Event("click") {
        putString("target", target)
    })
}
```

Результат соответствующего варианта:

```kotlin
fun card() {
    renderCard(
        title = title,
        count = count,
        onClick = onClick
    )
}

fun event() {
    Analytics.send(Event("click") {
        putString("target", target)
    })
}
```

## Вызов с четырьмя аргументами при включении

`nur:call-arguments`. Учебный проверенный пример.

Ввод:

```kotlin
fun card() {
    renderCard(title, count, enabled, onClick)
}
```

Результат соответствующего варианта:

```kotlin
fun card() {
    renderCard(
        title,
        count,
        enabled,
        onClick
    )
}
```

## Короткий intent

`nur:compact-intent`. Учебный проверенный пример.

Ввод:

```kotlin
private fun back() = intent {
    postSideEffect(Effect.Back)
}
```

Результат соответствующего варианта:

```kotlin
private fun back() = intent { postSideEffect(Effect.Back) }
```

## Почему замена colon-spacing сейчас выключена

Она сохраняет оба допустимых вида наследования и нормализует пробелы у параметров/возвращаемых типов. В текущем предложении выбран штатный colon-spacing, чтобы использовать уже проверенное направление совпадения с IDE. Это технический выбор текущего кандидата, не правило о корректности Kotlin-кода.
