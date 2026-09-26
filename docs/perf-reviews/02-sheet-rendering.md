# Performance / Resource-Efficiency Review — Sheet Rendering Path

Read-only review. No files modified. Performance only, no security findings.

Scope:

- `util/sheet/SheetDb.kt` (629 lines) — schema and queries
- `util/sheet/SheetStore.kt` (1380 lines) — state holder
- `util/sheet/SheetModels.kt` (222 lines) — models, `MAX_GRID_ROWS`
- `SheetMenuOverlay.kt` (723 lines) — the bubble's sheet submenu
- `ui/screens/sheet/SheetGrid.kt`, `SheetFilesTab.kt`, `SheetFileCard.kt` — Compose UI
- `res/layout/bubble_sheet_menu.xml`

This is the second of three bubble passes that were originally requested. The circle menu and
the service hot paths are covered separately; the popup overlays are `popup_rendering.md`.

---

## Summary

| # | Finding | File:line | Impact | Effort |
|---|---|---|---|---|
| 1 | `listFiles` runs 5 extra queries per file inside the cursor loop (1 + 5N), one a correlated `EXISTS` subquery | `SheetDb.kt:106-125`, `:591-627` | **High** | M |
| 2 | No index supports `WHERE archived=? ORDER BY updatedAt DESC` | `SheetDb.kt:21`, `:110` | **High** | S |
| 3 | Every `openRows` emission rebuilds a 500-element list from scratch | `SheetStore.kt:452-458`, `:515`, `:563` | **High** | M |
| 4 | `normalizedRows` reallocates every row even when `rowIdx` already matches | `SheetStore.kt:460-461` | Med | S |
| 5 | `staleChecks` builds two full `Map`s per call | `SheetStore.kt:463-466` | Med | S |
| 6 | 13 independent `MutableStateFlow`s; related pairs assigned back to back | `SheetStore.kt:71-90`, `:116-117` | Med | M |
| 7 | `openChecks` rebuilt wholesale to drop a few keys | `SheetStore.kt:576` | Med | S |
| 8 | Sheet submenu rebuilt by `addView` per open, rows constructed in code | `SheetMenuOverlay.kt:481-526` | Low | M |

---

## 1. N+1 in `listFiles` — **High / M**

`SheetDb.kt:106-125`

The files list is fetched in one query, then **five more queries run per file, inside the
cursor loop**:

```kotlin
db.rawQuery(
    "SELECT id,name,preset,password,archived,deletedAt,createdAt,updatedAt,seq " +
    "FROM files WHERE archived=? ORDER BY updatedAt DESC",
    arrayOf(if (archived) "1" else "0")
).use { c ->
    while (c.moveToNext()) {
        val id = c.getString(0)
        out.add(SheetFile(
            ...
            rowCount = countDataRows(id),      // :591  COUNT(*) ... WHERE fileId=?
            liveCount = countRows(id, liveOnly = true),   // :598
            deadCount = countRows(id, deadOnly = true),   // :598
            dupCount  = countDups(id),         // :613  correlated EXISTS
            pageCount = countPage(id)          // :624
        ))
```

For N files that is **1 + 5N queries**, all inside a single `use { }` block, so the
`files` cursor is held open for the duration.

`countDups` is the worst of the five — it is not a simple count:

```kotlin
"SELECT COUNT(*) FROM rows r WHERE fileId=? AND (" +
    "(uid<>'' AND EXISTS (SELECT 1 FROM rows o WHERE o.fileId != r.fileId AND o.uid<>'' AND o.uid = r.uid)) OR " +
    ...
```

A correlated `EXISTS` subquery over `rows` per candidate row, per file. It is also the one
that cannot be trivially rewritten as a join without changing semantics, so it deserves its
own plan.

**Cost.** The files tab is the app's entry point for the whole sheet feature, and this runs on
every refresh. With 20 files that is 101 queries, one of which is a nested scan. It is the
single largest cost in the sheet path.

**Recommended fix.** Two stages, in order of payoff:

1. **Aggregate in SQL.** Replace the four `COUNT(*)` calls with a single grouped query joined
   onto the file list, e.g. `SELECT fileId, COUNT(*) … FROM rows WHERE fileId IN (…) GROUP BY
   fileId` with conditional sums for the live/dead/eligible splits. That turns 5N into 1
   extra query regardless of N. `countDups` should be computed once per refresh on its own,
   or maintained incrementally, rather than per file per refresh.
2. **Hoist the file-id set** out of the loop and use a single `IN (…)` bind, so the
   aggregation is one pass rather than N.

## 2. No index for the files list query — **High / S**

`SheetDb.kt:21`, `:110`

```sql
CREATE TABLE files(id TEXT PRIMARY KEY, name TEXT NOT NULL, …, archived INTEGER NOT NULL DEFAULT 0, deletedAt INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0)
```

`id TEXT PRIMARY KEY` is the only index. The list query filters on `archived` and orders by
`updatedAt`, and neither is indexed — nor is the pair `(archived, updatedAt)` that the query
actually wants. Every files-list refresh is therefore a full scan of `files` plus a sort.

Note this table is already indexed-aware elsewhere: `journal`, `check_reqs`, `undo_hist` and
`redo_hist` all get dedicated `CREATE INDEX` statements at `:26`, `:33`, `:35` and `:36`. The
`files` table is the outlier, which suggests the omission is an oversight rather than a
deliberate trade-off.

**Recommended fix.** `CREATE INDEX idx_files_archived_updated ON files(archived, updatedAt)`.
A covering index is possible but the row is wide (`name`, `preset`, `password`), so start with
the two-column index. This needs a migration — see the existing migration block at `:40-50`
for the established pattern.

## 3. Every `openRows` emission rebuilds 500 rows — **High / M**

`SheetStore.kt:452-458`

```kotlin
private fun topUp(rows: List<SheetRow>): List<SheetRow> {
    val normalized = meaningfulSheetRows(rows).mapIndexed { index, row ->
        if (row.rowIdx == index) row else row.copy(rowIdx = index)
    }
    if (normalized.size >= MAX_GRID_ROWS) return normalized.take(MAX_GRID_ROWS)
    return normalized + (normalized.size until MAX_GRID_ROWS).map { SheetRow(rowIdx = it) }
}
```

`MAX_GRID_ROWS` is **500** (`SheetModels.kt:5`). Every assignment to `openRows` runs through
this, so each emission allocates a list of 500 `SheetRow` objects — real rows plus a padded
tail of empty ones — even when a single cell changed:

```kotlin
openRows.value = topUp(rows)     // :515, :563
```

During a bulk check run this is the dominant cost: each completed check calls
`openRows.value = topUp(rows)`, so a run touching N rows rebuilds the full 500-element list
N times.

**Recommended fix.** Keep the padded list stable and emit only what changed. Concretely:
hold the padded `List<SheetRow>` in a private field, and for a single-row update replace the
one element rather than reallocating the list. If the `StateFlow` must still receive a new
list instance for equality to work, build it with a preallocated array and `copyOf`, or move
the padding to the UI layer (the `LazyColumn` can render placeholder rows for indices past the
end) so the state flow only ever carries meaningful rows.

## 4. `normalizedRows` reallocates every row — **Med / S**

`SheetStore.kt:460-461`

```kotlin
private fun normalizedRows(rows: List<SheetRow>): List<SheetRow> =
    rows.take(MAX_GRID_ROWS).mapIndexed { index, row -> row.copy(rowIdx = index) }
```

Unlike `topUp`, this has no `if (row.rowIdx == index) row` guard, so it allocates a fresh
`SheetRow` for **every** row even when the index already matches — a pure waste of up to 500
allocations per call.

**Recommended fix.** Reuse the `topUp` guard:

```kotlin
rows.take(MAX_GRID_ROWS).mapIndexed { index, row -> if (row.rowIdx == index) row else row.copy(rowIdx = index) }
```

## 5. `staleChecks` builds two full maps per call — **Med / S**

`SheetStore.kt:463-466`

```kotlin
private fun staleChecks(previous: List<SheetRow>, rows: List<SheetRow>): Set<Int> {
    val before = previous.associateBy { it.rowIdx }
    val after = rows.associateBy { it.rowIdx }
    return (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
```

Two `HashMap`s of up to 500 entries, a key-set union, a filter and a `Set` — all per call, on
lists that are almost always identical apart from a handful of rows.

**Recommended fix.** This only needs the *differing* indices, so a single indexed comparison
is enough:

```kotlin
val size = maxOf(previous.size, rows.size)
return (0 until size).filter { previous.getOrNull(it) != rows.getOrNull(it) }.toSet()
```

That is one pass and no intermediate maps. `SheetRow` is a data class, so `!=` already uses
structural equality.

## 6. Thirteen independent state flows, pairs assigned back to back — **Med / M**

`SheetStore.kt:71-90`

The store exposes 13 `MutableStateFlow`s — `files`, `archive`, `balance`, `txs`, `openFile`,
`openRows`, `openStyles`, `openHidden`, `openCrossDups`, `openChecks`, `openCheckReqs`,
`checking`, `bubbleFileId` — and related ones are assigned adjacently:

```kotlin
files.value = next.files          // :116
archive.value = next.archive      // :117
```

```kotlin
openRows.value = snapshot.rows        // :431
openStyles.value = snapshot.styles    // :432
openChecks.value = snapshot.checks    // :434
```

Each assignment on a `MutableStateFlow` is a distinct emission. `MutableStateFlow` does
dedupe *identical* consecutive values, but these are different flows, so a single refresh
notifies every collector, and each collector that reads several of them recomposes once per
emission rather than once per refresh.

The store does the right thing on the producer side — `scope` is
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` at `:63`, so the queries are off the main
thread.

**Recommended fix.** Group state that always changes together into a single data class behind
one flow, so a refresh is one emission:

```kotlin
data class OpenFileState(
    val file: SheetFile?, val rows: List<SheetRow>, val styles: Map<String, CellStyle>,
    val hidden: Set<String>, val checks: Map<Int, RowCheck>, …
)
val openFileState = MutableStateFlow(OpenFileState.EMPTY)
```

This also gives Compose a single stable input to compare, which is what makes
`derivedStateOf` and `remember` effective downstream. `files`/`archive` are the same shape of
problem and can be one `FilesState(files, archived)`.

## 7. `openChecks` rebuilt wholesale to drop keys — **Med / S**

`SheetStore.kt:576`

```kotlin
openChecks.value = openChecks.value.filterKeys { it !in stale }
```

A full `Map` copy to remove a few entries, and it re-reads `openChecks.value` twice. Fine at
small sizes, wasteful as the check map grows.

**Recommended fix.** `openChecks.value = openChecks.value - stale` — `minus` on a `Map`
removes the keys directly without the `filterKeys` intermediate. Hoist the current value to a
local so it is read once.

## 8. Sheet submenu built by `addView` per open — **Low / M**

`SheetMenuOverlay.kt:481-526`

The submenu assembles its rows imperatively, creating `TextView`s and `View`s in code and
`addView`-ing them:

```kotlin
menu.addView(menuSwitchRow("UID check", uid) { toggleUid() })      // :481
menu.addView(menuSwitchRow("Simple check", simple) { togglePage(simple = true) })  // :482
menu.addView(menuSwitchRow("Advanced check", adv) { togglePage(simple = false) })  // :483
…
row.addView(TextView(context).apply { … })                          // :498
track.addView(View(context).apply { … })                            // :519
row.addView(track)                                                 // :526
```

The row count is small and fixed (three switches plus a couple of views), so this is far
mildlier than the 243-row problem in the country popup — it is listed for completeness, not
because it is a real cost. Constructing views in code rather than inflating
`bubble_sheet_menu.xml` also means the layout is not editable in the layout editor and is not
covered by the `-night` resource variants the rest of the bubble uses.

**Recommended fix.** Move the row construction into `bubble_sheet_menu.xml` and inflate, so
the structure is reviewable and themeable like the other overlays. Worth doing for
consistency; not a performance fix.

---

## Already correct — keep

This is the best-structured part of the codebase for performance, and it is worth being
explicit about it:

- **The Compose lists use proper stable keys.** `SheetFilesTab.kt:283` and `:324` both use
  `items(files, key = { it.id })`; `SheetGrid.kt:172` uses `items(rows, key = { it.rowIdx })`.
  Missing keys are the single most common cause of unnecessary item recreation and lost
  scroll/state in a lazy list, and these are all correct.
- **`LazyVerticalGrid` for the file grid** (`SheetFilesTab.kt:314`) rather than a manual
  `GridLayout` of inflated children.
- **Producer work is on `Dispatchers.IO`** — `SheetStore.scope` at `:63` is
  `SupervisorJob() + Dispatchers.IO`, and `SheetMenuOverlay` uses its own
  `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` (`:193`) for UI-side work
  with a `Handler(Looper.getMainLooper())` (`:87`). Dispatchers are chosen deliberately and
  correctly.
- **No `runBlocking` anywhere in the codebase** — verified across all Kotlin sources. Nothing
  bridges a coroutine back onto the main thread.
- **DB work is transactional where it writes** — `SheetDb.kt:72-75` wraps
  `beginTransaction()` / `setTransactionSuccessful()`.
- **The heavy child tables are well indexed for their access pattern.** `rows`,
  `styles`, `hidden_cols` and `row_checks` all have a `PRIMARY KEY` whose leading column is
  `fileId`, so per-file lookups use the PK index. `check_reqs`, `undo_hist`, `redo_hist` and
  `journal` each have a dedicated index matching their query shape. The `rows` access pattern
  is genuinely well designed.
- **Paging limits exist** — 14 `LIMIT` clauses across 19 `rawQuery` sites in `SheetDb.kt`.
- **The migration block is idempotent** (`CREATE TABLE IF NOT EXISTS` /
  `CREATE INDEX IF NOT EXISTS` at `:42-50`), which is the pattern to follow for the index in
  #2.
- **No `Color.parseColor` and no `BitmapFactory.decodeResource` anywhere in the sheet path** —
  the draw-path problems found in the circle menu are absent here.

## Suggested order

1. **#2** — one `CREATE INDEX`. Smallest change in this document, and it makes the most
   frequent query in the feature stop full-scanning.
2. **#1** — collapse the 5N per-file queries into one aggregate. Largest single win.
3. **#3 + #4** — stop rebuilding 500 rows per emission; reuse the `topUp` guard in
   `normalizedRows` while in there.
4. **#6** — group the state flows so a refresh is one emission instead of several.
5. **#5, #7** — small local cleanups.
6. **#8** — layout extraction, for consistency rather than speed.

## How to verify

- Android Studio Database Inspector: run `EXPLAIN QUERY PLAN` on the `listFiles` statement
  and confirm it moves from `SCAN files` + `USE TEMP B-TREE FOR ORDER BY` to a search on
  `idx_files_archived_updated` after #2.
- StrictMode `detectDiskReads` around the files-tab refresh, before and after #1.
- Memory Profiler allocation recording on a bulk check run: the `SheetRow` allocation rate
  should fall by roughly the number of rows checked once #3 lands.
- Recomposition counts in the Compose layout inspector while a check run progresses — should
  drop noticeably after #6.

## Carried over from the original review, still open

`SheetChecker.kt` (335 lines), `SheetBackup.kt` (804), `SheetBackupXlsx.kt` (419) and
`SheetXlsx.kt` (227) were **not** covered here. The XLSX import/export paths are the most
likely place for JSON/zip/XML parsing to land on a thread it should not, and
`SheetBackup.kt` is over the repo's own ~800-line split threshold. That is the obvious next
pass.
