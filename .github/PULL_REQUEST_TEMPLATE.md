## Summary

<!-- 1-3 bullet points describing what this PR does -->

-

## Motivation

<!-- Why is this change needed? What problem does it solve? -->

## Changes

<!-- List key changes, organized by file or category -->

| File/Area | Change |
|-----------|--------|
| | |

## Testing

<!-- How was this tested? What should reviewers check? -->

- [ ]

## Checklist

### Required

- [ ] All tests pass (`bb test`)
- [ ] Pre-commit validation passes (no `--no-verify`)
- [ ] No commented-out tests (see [guidelines](../docs/development-guidelines.md#testing-discipline))
- [ ] No giant functions (see [guidelines](../docs/development-guidelines.md#function-design))
- [ ] Functions are small and composable (target: 5-15 lines, max: 30 lines)
- [ ] New behavior has tests

### Best Practices

- [ ] Code follows [development guidelines](../docs/development-guidelines.md)
- [ ] Linting passes (`bb lint:clj:all`)
- [ ] README/docs updated if needed
- [ ] PR doc created in `docs/pull-requests/YYYY-MM-DD-branch-name.md`
- [ ] Focused PR (single concern, not mixing features with test fixes)

## Related

<!-- Link to related issues, PRs, or docs -->

- Closes #
- Related to #
