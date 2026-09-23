# Semantic accuracy corpus

This corpus measures semantic behavior independently from implementation details.

## Layout

- Every case has a `case.properties` manifest.
- Java sources use the `.java.fixture` suffix so repository analysis does not index them.
- Tests materialize fixtures as `.java` files under a temporary directory.
- `baseline.properties` contains minimum precision/recall and maximum false-positive/
  false-negative counts per capability.

## Canonical identities

Methods use `fully.qualified.Class#method(ParameterTypes)`. Call edges use
`caller->callee`. Lambda bodies use stable synthetic names such as `$lambda$1`.

## Metrics

- `precision = TP / (TP + FP)`
- `recall = TP / (TP + FN)`
- Empty predicted sets have precision `1.0`.
- Empty expected sets have recall `1.0`.

The gate permits improvements without changing the baseline and fails when a ratio drops or an
FP/FN count increases. Raising thresholds after a semantic fix is an explicit reviewed change.
