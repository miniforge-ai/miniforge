Our standards are located in miniforge-standards, that is pulled in as a submodule. Agents.md will have the entry point to that. A summary below of what agents tend to miss:

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
- schemas and validation at the boundaries (interface boundaries, external data) and inside components no validation (not needed).

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
- When constructing maps, map construction should be key-value. Move any calculations for the value into a `let` form above the map construction. This allows the map construction to read very simply and the intent is clear. 
