# Subtitle surface spike — 0.5.0

Physical API 36 phone, bundled LibVLC 3.7.7, Auto and Software preferences:
- Scaling: measured 1.499x width and approximately 1.49x height for requested 1.5x.
- Translation: measured 76/77 pixel movement, reset returned to baseline.
- Alpha: measured 1.0 text luminance ratio for requested 0.3. Unsupported on this native path; fade deferred.
- Video green reference remained unchanged (ratio 1.0), playback continued.

Debug application ID is isolated and SafeTestRunner refuses the release package. Build, unit tests and lint passed. Phone probe passed after removing an overstrict playing-state readiness predicate; tracks and timeline readiness plus visible caption are checked. Lossless captures and raw results are retained locally in outputs/previews/0.5.0. Emulator repeat remains pending.