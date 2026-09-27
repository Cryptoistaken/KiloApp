# Fresh number check in the SMS tab

Port the KiloSMS fresh-check into the Android app, driven by a `C` suffix on the
typed range, and fix the range-persistence bug that erases the `X` wildcards.

Source of truth for the rule: `Bots/KiloSMS/index.js:2495-2510` (parse),
`generateCheckedNumbers` at `index.js:2542-2638` (retry loop),
`FreshChecker/server.js` (the Facebook probe itself).

Design reference: `docs/sms-fresh-check-mockup.html`.

## The rule

Typed text is parsed into a range plus a check flag:

```
229016XXX      -> range 229016XXX, check off
229016XXXC     -> range 229016XXX, check on
229016XXXC2    -> range 229016XXX, check on, count 2
229016         -> range 229016XXX, check off   (XXX appended when no wildcard)
garbage        -> invalid
```

The `C` is read from the tail *after* the range, so it never reaches the
gateway. A bare range with no `X` gets `XXX` appended, matching the bot.

## Changes

### 1. Range parsing, one home

New `smsParseRange` in `util/SmsWatcher.kt`, beside the existing
`smsIsRangePat`. One function parses once; the field, the checkbox, the
provision call and the pref writer all read the same result. The `C` must be
stripped *before* `smsIsRangePat` runs, because that validator rejects it.

Returns a small data class holding `range`, `check` and `count`.

### 2. The checkbox

`MainPage` in `ui/screens/SmsPages.kt` gains one row under the range field:
a 13dp `Checkbox` plus the label `generate fresh number`.

- Not stored. Derived from the typed range on every recomposition.
- Enabled only when `smsParseRange` returns non-null, so an invalid range
  shows a disabled box rather than a wrong one.
- Custom `colors`: unchecked transparent with a `Color(0xFF555555)` border,
  checked `Color(0xFF000000)` with a white check. Stock M3 is
  white-on-secondaryContainer and does not fit this monochrome palette.
- Tapping it is out of scope for v1: the `C` is the input, the box is the
  read-out. Making the box writable would need the `C` to be inserted at the
  caret, which is more surface than the feature needs.

### 3. The probe, on device

New `util/SmsFresh.kt`. Blocking, `Dispatchers.IO` only, same convention as
`SheetChecker`.

- `POST https://www.facebook.com/api/graphql/`, form-encoded
- `doc_id=26328147246854413`, `variables` carrying
  `context: recover` and `search_query: <phone>`
- No session, no cookies, no LSD token. `FreshChecker` proved the endpoint
  does not validate them; the old cookie path is dead weight.
- Reads `data.caa_ar_fb_account_search.accounts`; empty means fresh.
- No proxy pool. App traffic already leaves through `SocksVpnService`'s
  tunnel, so the active profile supplies the IP rotation the bot gets from
  owlproxy. Fewer exits, and quality varies by profile.

### 4. Retry loop

`SmsWatcher.provision` gains a `check` parameter. When set, it loops up to
`SMS_FRESH_MAX_TRIES` (5):

- provision one number
- probe it
- fresh: keep it and return, as today
- not fresh: discard, never added to `mine`, never saved
- probe failed: `checkerDown`, stop and surface the error

A discarded number costs a provider slot but leaves no trace on the list, so
`Checked`/`Fresh`/`Skipped` are the only record that it happened.

### 5. Fresh marking

`SmsNum` gains `var fresh: Boolean = false`, persisted as `"f"` in the
existing `nums_v1` JSON. `subLine` in `ui/screens/SmsComponents.kt` prefixes
`Fresh - ` in `CodeGreen` when set.

The right-hand slot is untouched: spinner while pending, green code once the
OTP lands, `expired` when stale. No pill, no badge, nothing competing with
the code.

### 6. Counters on the Activity page

`StatsPage` gains a third `Row` of `StatTile`, same shape and 8dp spacing as
the two above it: `Checked`, `Fresh`, `Skipped`.

Session totals persisted in the same prefs store as the numbers, because the
existing tiles all derive from `mine + expired` and a run counter has no such
source.

### 7. Swipe to generate

`MineRow` and `ReceivedRow` keep `SwipeBox`, but both directions generate
instead of the current right-to-open / left-to-regenerate split. The revealed
label is the same on both sides.

- Uses the row's own `range`, already on `SmsNum` and already used by the
  existing Regenerate path.
- Re-checks if the row was fresh, because the check is part of what the row
  means. A plain row stays plain.
- Row tap still opens the sheet, so losing the right-swipe costs nothing.

### 8. The X-persistence bug

`FloatingControlService` strips `X` and writes the stripped value back to
`PREF_SMS_LAST_RANGE` at lines 1990, 2029 and 2036, overwriting what
`SmsScreen.kt:150` correctly saved. One tap of the bubble turns `229016XXX`
into `229016`, which is what the user sees after a restart.

Fix: keep the wildcard through the bubble path. Strip only the check suffix
for provisioning, and save the full typed text, matching what the screen does.
`resolveSmsRange` also stops destroying the wildcards it reads back.

## Applying it

Order matters: the parser and the persistence fix are independent and go
first, the probe next, then the UI that consumes both.

1. `smsParseRange` in `SmsWatcher.kt`; fix the three `FloatingControlService`
   sites. Self-contained, testable by typing a range and restarting.
2. `util/SmsFresh.kt`. No callers yet.
3. `SmsNum.fresh` plus save/load, and the `subLine` prefix.
4. `provision(check=)` and the retry loop; counters in the store.
5. Checkbox in `MainPage`, the `C` routed through `onGet`.
6. `StatTile` row in `StatsPage`.
7. `SwipeBox` both-directions in the two row composables.

Never build locally. Push to `master` and wait on
`go run ./monitor-build.go`. Android has no test suite; verification is CI
green plus a device pass on `localhost:5557`.

## Deliberately not doing

- **`C2` count parsing.** The parser will read a count so the suffix does not
  surprise anyone, but a count above 1 is rejected as invalid. Acting on it
  means provision returning a list, which touches `provision`, `SmsNum` and
  `SmsGateway` for a case nobody has asked for.
- **A proxy pool.** The VPN tunnel is the rotation. Adding owlproxy to the
  app would mean shipping proxy credentials in the APK.
- **Server-side checking.** The whole point is that this runs on device.
