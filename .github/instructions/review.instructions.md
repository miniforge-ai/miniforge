Our standards are located in miniforge-standards, that is pulled in as a submodule. Agents.md will have the entry point
to that. A summary below of what agents tend to miss:

## Pinned standards access

Read the full applicable rules, not only the summary below. Start with
`standards/miniforge/agents.md` and `standards/miniforge/index.mdc`.
If the review checkout has not initialized the submodule, resolve its exact
gitlink with `git ls-tree HEAD standards/miniforge`. Read the public pinned
files from `https://raw.githubusercontent.com/miniforge-ai/miniforge-standards/<gitlink-sha>/<path>`.
For example, the entry points are `<path>` values `agents.md` and `index.mdc`.
Follow the catalog to read every applicable rule at that same commit.
Use the raw-content host directly when the GitHub API is unavailable.
Never substitute the standards repository's moving main branch, a prose
summary, or an author's assertion for the actual pinned rule contents.
If both the checkout and exact pinned public files are inaccessible, report
that review limitation. All existing adversarial, standards and merge gates remain.

## Commonly missed requirements

 Principles:

- follow stratified design
- choose simplicity
- configuration is data, not code. If there is config, pull it to a .edn file.
- Always localize — starting with en-US.edn. No raw strings.
- no magic numbers, use well named constants that demonstrate intent
- DRY .. do not duplicate functionality. Create a component for common logic and import the interface.
- Code changes without test changes is rare. Re-examine.

Hygiene

- PR DAG over giant PRs
- Test files need to adhere to the same standards.
- schemas and validation at the boundaries (interface boundaries, external data) and inside components no validation
  (not needed).

DRY

- factory functions over duplicate maps
- success? failed? predicates instead of reaching into data structures for status
- success and anomaly fns instead of hand build maps

Idiomatic use

- extract any anon fn or let / cond type forms that are longer than a single line
- (get m k default) over (or (:key m) default)
- Only use create composable functions that compose *up* to larger pipelines of functionality that are *still small*.
- Do not nest conditionals. Avoid them where possible via composition or data structure designs.
- Functions must read as pipelines.
- Construct maps as key-value pairs. Move value calculations into a `let`
  form above the map so its structure and intent remain clear.
