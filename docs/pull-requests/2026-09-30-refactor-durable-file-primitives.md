# refactor: share durable file primitives

## Overview

Extract the file-write and synchronization vocabulary duplicated by artifact and
execution-grant storage. Base branch: main.

## Motivation

N3 event publication needs successful durable recording before consuming a
sequence number. Reusing one filesystem boundary avoids a third implementation
of the same write and durability rules.

## Changes in Detail

Provide a small shared component for complete writes and file/directory durability
barriers. Preserve create-only grant writes, existing temporary artifact writes,
and each store's domain-specific error contract. Keep filesystem effects outside
domain code and public consumption through component interfaces.

## Testing Plan

- Focused JVM and rebuilt packaged runtime: 49 tests, 258 assertions, all pass.
- Polylith: file-durability and execution-grant pass in all four consumers;
  artifact passes in all three consumers. All runs were serial.
- CLI build succeeds; Polylith composition check and touched-file lint pass.
- Adversarial trace covers create-only writes, complete UTF-8 encoding, and
  no-follow final paths. It also covers publication failure ordering, retry
  confirmation, interruption preservation, and fatal error propagation.
- The extraction replaces the grant's internal durability namespace (recoverable
  from Git); domain publication and error contracts remain at the stores.

## Deployment Plan

Normal CLI build. This prerequisite does not change event numbering or migrate
stored records; journal and publisher integration follow separately.

## Related Issues/PRs

N3 §9.3 sequence integrity and workflow resume requirements; OPSV evidence
checkpoint #1956 requires complete, non-colliding event references.

## Checklist

- [x] Shared boundary and existing-consumer integration
- [x] JVM and packaged-runtime verification
- [ ] Adversarial standards review and settled PR review
