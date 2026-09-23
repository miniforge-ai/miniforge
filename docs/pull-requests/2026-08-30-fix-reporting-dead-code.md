# fix(reporting): delete two #_-commented-out dead functions

## Branch

`fix/reporting-dead-code`

## Summary

Removes two `#_`-commented-out function definitions from
`reporting/core.clj` that violated rule 008 (No Dead Code).

## What was deleted

- `count-by-phase` — commented out with "Note: Keep for future
  phase-based filtering". No callsites anywhere in the workspace;
  the live equivalent `count-by-status` already covers the
  generalised case.

- `add-event-to-subscriptions` — commented out with "Note: Keep for
  future event broadcasting support". No callsites anywhere in the
  workspace; the subscription machinery it would have touched is
  polling-based (not push), making the function structurally
  incompatible with current design.

## Why it matters

Dead code with "keep for future" guard-comments is the pattern rule
008 explicitly prohibits. Left in, it:

- Misleads readers about the component's live surface
- Generates stale grep hits when searching for symbol definitions
- Grows silently stale as surrounding types evolve, making any
  future attempt to un-comment it a guaranteed source of bugs

Git history preserves the removed code if it is ever needed.

## Testing

No callers reference the deleted symbols (confirmed by codebase-wide
grep). The two functions were never callable (guarded by `#_`), so
no runtime behaviour changes and no test updates are required.
