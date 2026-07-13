# Changelog

## Unreleased

- Fix `/rope` levitation removal that stole Levitation applied by other sources.
- Fix a duplicate `targetRate` argument in the climb-rate calc (`climbMaxRate` -> `climbVerticalRate`).
- Null-safe `RopeStore` dimension comparison (`Objects.equals`) (deep-review).
