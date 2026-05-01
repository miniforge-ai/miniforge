<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Writing Specs

A spec tells miniforge what you want built. You describe the intent, constraints,
and acceptance criteria — miniforge handles the how.

## Format

Specs can be written in **EDN** or **Markdown**.

### EDN Format

```clojure
{:spec/title "One-line summary of what you want"

 :spec/description
 "Detailed prose description. Include context, motivation, and
  technical details the implementer needs to know."

 :spec/intent {:type :feature         ;; :feature, :bugfix, :refactor, :chore, :test
               :scope ["src/auth/"]}  ;; optional: hint at which files/dirs

 :spec/constraints
 ["No breaking changes to the public API"
  "All existing tests must pass"
  "Use the existing authentication framework"]

 :spec/acceptance-criteria
 ["Users can reset their password via email"
  "Reset tokens expire after 24 hours"
  "Rate limiting prevents abuse (max 3 requests per hour)"]}
```

### Markdown Format

```markdown
---
title: Add password reset flow
description: |
  Implement a password reset flow with email verification.
  The user requests a reset, receives an email with a token,
  and can set a new password.
intent:
  type: feature
  scope: ["src/auth/"]
constraints:
  - No breaking changes to the public API
  - All existing tests must pass
---

## Context

We currently have login and signup but no way for users to recover
a forgotten password. Support tickets for password resets are our
#2 most common request.

## Technical Notes

The email service is already configured in `src/email/client.clj`.
Use the existing `send-template` function with a new "reset" template.
```

The Markdown body (everything after the frontmatter) is appended to the
description, giving agents additional context.

## Required Fields

| Field | Description |
|-------|-------------|
| `:spec/title` | One-line summary (max ~80 chars) |
| `:spec/description` | Detailed prose description |

Everything else is optional but recommended.

## Recommended Fields

| Field | Description |
|-------|-------------|
| `:spec/intent` | `{:type :feature}` — helps select the right workflow |
| `:spec/constraints` | Boundaries the implementer must respect |
| `:spec/acceptance-criteria` | How to verify the work is correct |

## Optional Fields

| Field | Description |
|-------|-------------|
| `:spec/tags` | `[:auth :security]` — for organization |
| `:spec/repo-url` | Target a different repo |
| `:spec/branch` | Target a specific branch |
| `:spec/plan-tasks` | Pre-decomposed task list (skip planning) |

## Intent Types

| Type | When to Use |
|------|-------------|
| `:feature` | New functionality |
| `:bugfix` | Fixing broken behavior |
| `:refactor` | Improving code without changing behavior |
| `:chore` | Maintenance, dependencies, config |
| `:test` | Adding tests to existing code |
| `:docs` | Documentation changes |
| `:performance` | Performance improvements |

## Tips

**Be specific about acceptance criteria.** Vague criteria like "it should work"
give the agent no way to verify success. Concrete criteria like "GET /health
returns 200 with a JSON body containing :service and :version" are testable.

**Scope constraints narrowly.** "No changes outside src/auth/" is more useful
than "don't break anything." Narrow constraints help the agent focus.

**Let miniforge plan.** Don't over-specify implementation details. Describe
WHAT you want, not HOW to build it. The planner is good at decomposing work.

**Provide context.** If there's a relevant design doc, prior art, or technical
constraint the agent should know about, put it in the description.

## Examples

See `examples/workflows/` for complete spec files:

- `simple-refactor.edn` — Basic refactoring with constraints
- `implement-feature.edn` — Feature with acceptance criteria
- `examples/demo/add-utility-function.edn` — Self-improvement demo
