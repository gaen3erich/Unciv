# Observation v1 — MVP Contract Plan

This plan locks in the **first formal version** of `LlmObservation` as a versioned contract validated by JSONL traces. v1 is intentionally about freezing what we already have, not about adding new fields. Each subsequent level (v2 economy, v3 map/cities, v4 diplomacy) should be additive where practical; if a later observation schema makes a breaking cleanup, the version bump is the explicit contract boundary.

The purpose of this stage is to be able to say "this trace conforms to `llm-obs/v1`" and have that mean something concrete that does not silently drift.

## 1. Контекст: что уже есть

`LlmObservation` сегодня уже содержит почти весь v1-набор:

- `turn` ✅
- `currentPlayerCivId` / `currentPlayerCivName` ✅
- `currentResearch` ✅ (nullable)
- `sciencePerTurn` — есть, но это уже шаг в сторону v2 economy
- `cities[]` с `name`, `population`, `currentConstruction`, `productionPerTurn`, `queue` ✅ (population + currentConstruction по плану v1; productionPerTurn — уже шаг в v2; queue — расширение поверх MVP-плана)
- `researchedTechs[]` — есть, удобно для будущих policies
- `legalActions[]` — полный список, не summary

То есть v1 — это **не "наполнение", а "фиксация контракта"**:

- определить, что входит в v1 формально (а не "то, что сейчас в коде");
- зафиксировать гарантии детерминизма и сортировки;
- решить судьбу нескольких неоднозначных мест (`cityId`, `null`-семантика, полный список vs summary);
- сделать JSONL header нести `"observationSchema": "llm-obs/v1"`;
- закрыть всё это тестами, которые валидируют JSON-форму, а не только in-memory объекты.

Поля, которые сейчас в `LlmObservation`, но логически принадлежат v2 (`sciencePerTurn`, `productionPerTurn`): см. секцию 6 "Что делать с ранними v2-полями".

## 2. Предлагаемые файлы/классы

Core:

```text
core/src/com/unciv/logic/llm/LlmObservationVersion.kt
```

Изменения в существующих файлах:

```text
core/src/com/unciv/logic/llm/LlmObservation.kt           (добавить поле schemaVersion)
core/src/com/unciv/logic/llm/LlmActionCollector.kt       (явный детерминизм + стабильный cityId)
core/src/com/unciv/logic/llm/io/LlmJsonCodec.kt          (учитывать schemaVersion)
core/src/com/unciv/logic/llm/io/LlmTraceWriter.kt        (header содержит observation version)
```

Tests:

```text
tests/src/com/unciv/logic/llm/observation/ObservationV1ContractTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV1DeterminismTests.kt
tests/src/com/unciv/logic/llm/observation/ObservationV1JsonShapeTests.kt
```

Class/file responsibilities:

- `LlmObservationVersion`
  - Просто `const val CURRENT = "llm-obs/v1"`. Отдельный файл намеренно — чтобы апдейт версии был визуально заметным коммитом.
  - Содержит документ-комментарий со списком полей v1 (см. секцию 4 этого плана) как "источник истины" в коде.

- `LlmObservation` (изменения)
  - Добавить поле `val schemaVersion: String = LlmObservationVersion.CURRENT` — default-значение, чтобы не ломать существующие конструкторы в тестах.
  - Не менять состав остальных полей в этом этапе.

- `LlmActionCollector` (изменения)
  - Заменить `cityId = city.name` на стабильный id:
    - основной вариант: `city.id`, так как Unciv уже поддерживает стабильный `City.id` и использует его в торговле/уведомлениях;
    - fallback, только если `city.id == City.NO_ID`: `"${civ.civName}/${city.location.x},${city.location.y}"`.
    - `city.location` — `HexCoord`, неизменна после основания города. Это решает проблему переименования и одноимённых городов в модах даже в fallback-сценарии.
    - `cityName` остаётся `city.name` — для людей.
  - Зафиксировать сортировку как часть контракта: cities — по `cityId` (а не по имени), techs — по имени. Сейчас сортировка по имени; перейти на cityId, чтобы при переименовании города diff трейсов не съезжал.
  - Не менять поведение `null` для `currentConstruction` — оставить `ifEmpty { null }`. Зафиксировать это в тесте.

- `LlmJsonCodec`
  - Перенести `"schema": "llm-trace/v1"` (контейнер) и `"observation": "llm-obs/v1"` (содержимое) — это **две независимые версии**.
    - Trace schema = формат файла (header + step lines).
    - Observation schema = форма самого observation.
    - Это разделение важно: v2 economy не меняет формат файла, только содержимое observation. Не хочется бампать обе версии за компанию.

- `LlmTraceWriter`
  - Header строка теперь содержит и trace schema, и `observationSchema: "llm-obs/v1"`. Это упрощает чтение трейса извне: одна строка, две версии.

## 3. Версионирование: trace schema vs observation schema

Два независимых "ключа версии" в JSONL:

- `schema` (trace container) — `"llm-trace/v1"`. Описывает структуру файла: что есть header, что есть step, какие поля у step. Меняется, если меняется сам формат строк.
- `observationSchema` (содержимое observation) — `"llm-obs/v1"`. Описывает форму поля `observation` в каждой step-строке. Меняется при v1 → v2 → v3 → v4.

Это позволяет:

- v2 economy = `observationSchema: "llm-obs/v2"`, `schema: "llm-trace/v1"`. Файл читается старым ридером — он просто увидит больше полей внутри observation.
- Будущий decode/replay в `llm-trace/v2` (если когда-нибудь появится) не блокирует апдейты observation.

Header пример после v1:

```json
{
  "schema": "llm-trace/v1",
  "observationSchema": "llm-obs/v1",
  "type": "header",
  "createdAt": "2026-05-22T11:42:00Z",
  ...
}
```

Step запись остаётся прежней по структуре; меняется только содержимое `observation`.

## 4. Состав v1 (источник истины)

Это нормативный список полей `LlmObservation` для уровня v1. Любое поле вне списка — это v2+ и должно ждать своего этапа (см. секцию 6).

Top-level:

- `schemaVersion: String` — всегда `"llm-obs/v1"`.
- `gameId: String`
- `turn: Int`
- `currentPlayerCivId: String`
- `currentPlayerCivName: String`
- `isOnlineMultiplayer: Boolean`
- `currentResearch: String?` — `null` означает "ничего не исследуется на данный момент" (между завершением одного и выбором следующего). Не означает "нет науки в принципе".
- `cities: List<LlmCityObservation>` — отсортирован по `cityId`, восходяще. Пусто, если у civ нет городов.
- `researchedTechs: List<String>` — отсортирован по имени, восходяще. Содержит имена технологий, для которых `civ.tech.isResearched(...) == true`.
- `legalActions: List<LlmLegalAction>` — полный список, не summary. См. секцию 5.

`LlmCityObservation` для v1:

- `cityId: String` — стабильный. Обычно `City.id`; fallback-формат `"${civName}/${location.x},${location.y}"`, если `City.id` недоступен.
- `name: String` — отображаемое имя города.
- `population: Int`
- `currentConstruction: String?` — `null`, если очередь пуста.
- `queue: List<String>` — последовательность имён конструкций в очереди города. Может быть пустым.

Поля, которых в v1 **НЕТ** (резерв для v2+):

- gold, gold per turn, happiness, culture, faith, science per turn, strategic resources, stockpiles — v2.
- city location в виде координат, defenders, nearby enemies, workable tiles, expansion pressure — v3.
- known civs, relationships, wars, trades, threats, relative military/economy — v4.

## 5. Решение: полный `legalActions` vs summary

В исходном описании v1 шло "available actions summary". В текущей реализации — полный список. Это разница в десятки КБ на observation при средней игре.

Решение для v1: **в JSONL пишем полный список** (без него replay/анализ невозможны), но в `LlmObservation` добавляем вычисляемый summary-блок, который дублирует структуру компактно.

Добавить в `LlmObservation`:

```kotlin
val legalActionsSummary: LlmLegalActionsSummary
```

```kotlin
data class LlmLegalActionsSummary(
    val technologies: Int,
    val constructionsByCity: Map<String, Int>, // key = cityId
    val endTurn: Boolean
)
```

Полезность:

- Будущие prompt-builder-ы для LLM смогут включать только summary, оставив полный список как fallback для tool-use.
- Удобно для дашбордов / быстрого глаза в трейсе ("o, на ходу 87 техов вдруг 0 — что случилось").
- Не дублирует данные на диск критически: summary это ~50 байт, полный список это килобайты — оба полезны для разных целей.

Validation:

- `legalActionsSummary.technologies == legalActions.count { it is ChooseTechnology }`
- Сумма по `constructionsByCity` == `legalActions.count { it is ChooseCityConstruction }`
- `legalActionsSummary.endTurn == legalActions.any { it is EndTurn }`

Эти инварианты — отдельный тест, см. секцию 8.

## 6. Что делать с ранними v2-полями (`sciencePerTurn`, `productionPerTurn`)

В коде уже есть `sciencePerTurn` и `productionPerTurn`. Они логически принадлежат v2 economy, но физически в `LlmObservation` уже сидят. Варианты:

1. **Удалить сейчас**, переоткрыть в v2. Минус: ломает существующие тесты, выглядит как регрессия.
2. **Оставить в v1, документировать как "early economy preview"**. Минус: размывает границу v1/v2.
3. **Оставить в v1, при v2 расширить блок до полного economy и временно продублировать значения**. Это компромисс — самый прагматичный.

Решение: **вариант 3**. Зафиксировать в v1 ровно эти два числа, явно отметить в коде комментарием `// promoted to economy block in v2`, и не добавлять в v1 больше ни одного economy-поля. Когда придёт v2, эти два значения станут частью большего блока `economy: { gold, goldPerTurn, sciencePerTurn, productionPerTurn, ... }`. На первом v2-шаге top-level поля лучше оставить как deprecated-дубликаты, чтобы v2 был additive; удалить их можно только отдельным breaking bump.

Альтернативно — если хочется чище — можно сразу в v1 завести подобъект:

```kotlin
data class LlmEarlyStats(
    val sciencePerTurn: Int,
    val productionByCity: Map<String, Int> // cityId -> production
)
```

…но это уже маленький рефакторинг, и он по-хорошему относится к v2. Для v1 оставляем плоские поля как есть.

## 7. Методы Unciv, на которые опираемся

Существующие и стабильные (используются уже сейчас):

- `gameInfo.turns`, `gameInfo.gameId`, `gameInfo.gameParameters.isOnlineMultiplayer`
- `civ.civID`, `civ.civName`
- `civ.tech.currentTechnologyName()`
- `civ.tech.isResearched(techName)`
- `gameInfo.ruleset.technologies.keys`
- `civ.cities` (List<City>)
- `city.name`, `city.population.population`
- `city.cityConstructions.currentConstructionName()`
- `city.cityConstructions.constructionQueue`
- `civ.stats.statsForNextTurn.science` (промежуточное v2-поле, см. секцию 6)
- `city.cityStats.currentCityStats.production` (промежуточное v2-поле, см. секцию 6)

Новое для v1 (стабильный city id):

- `city.id` — стабильный идентификатор города Unciv. Используется как основной `cityId`.
- `city.location` — `HexCoord`, координата на карте. Неизменна после основания. Используется как fallback-часть `cityId`, если `city.id` недоступен.

Чего **НЕ касаемся** в v1:

- `civ.gold`, `civ.civConstructions`, `civ.happiness`, `civ.cultureManager`, `civ.religionManager` — это v2.
- `civ.diplomacy`, `civ.getKnownCivs()`, war/peace состояние — это v4.
- `civ.viewableTiles`, `civ.units`, threat detection — это v3/v4.
- `civ.victoryManager` — отдельный вопрос, не покрытый текущей нарезкой.

## 8. Тесты для валидации v1

`ObservationV1ContractTests`:

- На фикстурном `GameInfo` собрать observation, утвердить:
  - `schemaVersion == "llm-obs/v1"`;
  - все top-level поля v1 присутствуют, типы корректны;
  - `currentConstruction == null` для города с пустой очередью;
  - `currentResearch == null` для civ без активного исследования (тест-фикстура с этим состоянием).
- Утвердить отсутствие неожиданных top-level полей — рефлексия по data class properties, сравнение с whitelist v1.

`ObservationV1DeterminismTests`:

- Собрать observation дважды для одного и того же `GameInfo`. Результаты равны как объекты И как строки JSON (после кодирования). Это ловит случайные порядки `Set`/`HashMap`, которые могли просочиться.
- Cities в observation отсортированы по `cityId` восходяще.
- `researchedTechs` отсортирован по имени восходяще.
- `legalActions` имеют тот же порядок, что выдаёт `LlmActionCollector.collectLegalActions(...)` — отдельный явный assert, чтобы случайный `toSet()` где-то в середине поломал тест, а не молча испортил трейсы.

`ObservationV1JsonShapeTests`:

- Закодировать observation в JSON, распарсить обратно общим парсером, утвердить:
  - корневой объект имеет поле `schemaVersion` со значением `"llm-obs/v1"`;
  - `cities` — JSON array, каждый элемент имеет ровно поля `{cityId, name, population, currentConstruction, productionPerTurn, queue}`;
  - `legalActions` — массив, каждый элемент имеет `kind` из закрытого множества `{ChooseTechnology, ChooseCityConstruction, EndTurn}`.
- Утвердить инварианты `legalActionsSummary`:
  - `summary.technologies == legalActions.count { kind == "ChooseTechnology" }`;
  - `sum(summary.constructionsByCity.values) == legalActions.count { kind == "ChooseCityConstruction" }`;
  - `summary.endTurn == legalActions.any { kind == "EndTurn" }`.

`ObservationV1CityIdStabilityTests`:

- Создать civ с городом, переименовать город (через существующий API, без модификации save-формата), собрать observation до и после.
  - `cityId` не меняется.
  - `name` меняется.
- Создать civ с двумя городами с одинаковым именем (если ruleset позволяет — иначе мокнуть через test fixture):
  - `cityId` разные.

Test command:

```bash
./gradlew tests:test
```

## 9. Что НЕ трогать в v1

- Save format. Никаких новых полей в `GameInfo`, `City`, `Civilization`. `cityId` вычисляется в collector-е, не сериализуется в save.
- UI. Никаких новых экранов / тултипов / окошек.
- v2 поля. Соблазн добавить `gold` "пока тут рядом" — игнорировать. Это удваивает scope этапа и размывает контракт.
- Trace file format. Формат строк (header + step) не меняется. Меняется только содержимое поля `observation` (косметически — добавляется `schemaVersion`) и шапка (добавляется `observationSchema`).
- `LlmGateway`, `LlmActionApplier`, `LlmPolicy` — без изменений. v1 это про **наблюдение**, не про применение действий.
- Decoder в `LlmJsonCodec`. Всё ещё encode-only, как в CLI plan.

## 10. Definition of done

- `./gradlew tests:test` зелёный.
- JSONL header, написанный `LlmTraceWriter`, содержит поле `observationSchema: "llm-obs/v1"`.
- Каждое observation в JSONL-трейсе содержит поле `schemaVersion: "llm-obs/v1"`.
- `cityId` в трейсе стабилен между прогонами на одном и том же save (тест прогоняет CLI дважды и diff-ит cities-блок).
- Состав top-level полей `LlmObservation` совпадает с разделом 4 этого документа — закреплено `ObservationV1ContractTests`.
- `legalActionsSummary` присутствует и удовлетворяет трём инвариантам.

## 11. Implementation order

1. `LlmObservationVersion.kt` + поле `schemaVersion` в `LlmObservation` (с default-значением). Это самое маленькое и безопасное — компилируется и не ломает существующие тесты.
2. Стабильный `cityId` в `LlmActionCollector`. Обновить существующие тесты, которые сравнивают `cityId` с именем города — их немного, но точно есть в `LlmGatewayConstructionTests`. **Перед изменением** прогнать существующий тестовый набор, чтобы знать список упадений.
   - Одновременно обновить `LlmActionApplier`, чтобы он искал город по тому же `cityId`, а не полагался на `cityName` fallback.
3. `LlmLegalActionsSummary` + вычисление в collector + инвариантные тесты.
4. Передать `observationSchema` в JSONL header через `LlmTraceWriter` и поправить `LlmJsonCodec`, чтобы он включал `schemaVersion` при сериализации observation.
5. `ObservationV1ContractTests`, `ObservationV1DeterminismTests`, `ObservationV1JsonShapeTests`, `ObservationV1CityIdStabilityTests`.
6. Ручной smoke: прогнать CLI на тестовом save, открыть JSONL, глазами проверить header и одну step-строку.
