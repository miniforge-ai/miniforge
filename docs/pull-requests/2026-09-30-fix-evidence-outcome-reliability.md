# fix: validate evidence outcome reliability fields

## Overview

Validate the optional N6 outcome reliability fields using the existing reliability
and failure-classifier domains. Base branch: main.

## Motivation

The canonical evidence review found that malformed degradation modes, failure
classes, and SLI measurements could survive structural validation and rehashing.

## Changes in Detail

Add a small outcome reliability schema vocabulary and compose its predicates into
the shared outcome schema. Reuse public component schemas rather than copying
enumerations. Missing optional fields remain compatible with unsealed assembly;
publication still owns the requirement for a workflow tier.
Reject non-finite measured values and targets. Root outcome validation rejects a
successful outcome with a non-nil failure class; failed legacy outcomes may omit
the optional class. N6's vector measurement shape takes precedence over the older
RN-06 work brief's map shape.

## Testing Plan

All three evidence-bundle consumers passed serially. The new regressions passed
on the JVM and rebuilt CLI jar: 7 tests, 156 assertions. Component standards scan:
55 files, no violations. clj-kondo reported no errors or warnings.

Adversarial review checked the shared enum dependencies, optional-field presence,
and the N6 measurement shape (which does not require a rolling SLI window).
The legacy schema engine still skips optional nil values; #1959 supplies its
presence-aware validation and freshly rehashed publication regressions.

## Deployment Plan

Normal CLI build; no live provider or deployment changes.

## Related Issues/PRs

Prerequisite for #1959 review comment 4151894478.

## Checklist

- [x] Schema and regression tests
- [x] JVM and packaged-runtime verification
- [ ] Adversarial standards review and settled PR review
