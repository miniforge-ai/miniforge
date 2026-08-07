# fix(policy-pack): resolve pack signatures against configured trust roots

MINIFORGE_PR_BUDGET_OVERRIDE: security fix; non-test change is 370 reportable
lines, within dewey 722's 400-line limit for meaningful change. The remainder
is the test coverage the fix needs — a signature verifier is not reviewable
without tests that sign real packs with real Ed25519 keys.

## Overview

Policy pack signature verification took its verification key from the pack
being verified. `registry/verify-signature` read `:pack/signed-by`, base64
decoded it, and passed the result to `verify-ed25519`. Verification therefore
established only that the pack was signed by whoever holds the key the pack
itself names.

This adds a configured trust-root store, resolves `:pack/signed-by` against it
as a key identifier, and fails when it resolves to nothing. It also implements
the canonical serialization the signature is computed over, which was not
reproducible across runs.

## Motivation

N4 §8.2.1 (0.7.0-draft, [#1658](https://github.com/miniforge-ai/miniforge/pull/1658)):

> Implementations MUST maintain an explicit set of trusted publisher keys, and
> MUST NOT accept a key supplied by the pack being verified. A pack that
> carries its own verification key is self-certifying and establishes nothing.

N4 Annex A.4 records the implementation gap and calls it the highest-priority
item in that annex. §8.1 types `:pack/signed-by` as a key identifier.

Two further defects surfaced while building the fix, both of which had to be
addressed for the trust-root store to be usable:

1. **`pack-signable-bytes` was not reproducible.** `(pr-str (into (sorted-map)
   signable))` sorts only the top-level map, renders sets in iteration order,
   and prints a `java.time.Instant` as `#object[java.time.Instant 0x… "…"]`
   with an identity hash. The same pack serialized to different bytes across
   runs, so a signature valid on one machine failed on another. N4 Annex A.3
   records the first two; the `Instant` case is the same class of defect.

2. **`verify-ed25519` could not decode a standard Ed25519 public key.** It read
   the key bytes as one big-endian magnitude and hardcoded `x-odd? = false`.
   RFC 8032 §5.1.2 stores y little-endian with the sign of x in the top bit of
   the final byte. Measured against the old decoder over 200 generated
   keypairs: the standard raw encoding verified 0 of 200; feeding it a
   big-endian y instead verified 94 of 200 — exactly the 94 whose x was even,
   and none of the 106 whose x was odd. A trust root holding real publisher
   keys is unusable against that decoder.

## Changes in Detail

### Trust-root store — `policy_pack/trust_roots{,_config}.clj` (new)

`trust_roots` is the store as a value — schema, key decoding, entry
validation, lookup, construction. `trust_roots_config` loads one from two
sources, merged by identifier with the operator's entry winning:

1. `config/policy-pack/trust-roots.edn` on the classpath — **ships empty**, so
   a deployment that configures nothing trusts nothing.
2. `~/.miniforge/config.edn` under `[:policy-pack :trust-roots]` — the
   out-of-band, auditable store §8.2.1 asks for.

Both sources validate against the `TrustRootConfig` Malli schema at load, and
each entry's public key must base64-decode to exactly 32 bytes. A malformed
entry throws rather than being dropped: a trust root that silently disappears
reads at the gate as an untrusted publisher.

Entries carry `:trust-root/key-id` and `:trust-root/public-key` only. No
per-key algorithm field — §8.1 fixes Ed25519 for the pack format.

### Verification — `policy_pack/registry.clj`

`verify-signature` now resolves `:pack/signed-by` through the store and fails
when it resolves to nothing. Distinct outcomes: unsigned, signature present
with no key identifier, identifier not in the trust roots, signature not
base64, signature invalid. `:signer` remains the identifier the pack *claims*
— verified only when `:verified? true`, which the protocol docstring now says.

`InMemoryPackRegistry` gains a `trust-roots` field, outside `state` because it
is fixed configuration. `create-registry` takes `:trust-roots` for injection
and otherwise loads the configured store.

### Canonical serialization — `policy_pack/canonical_{order,edn}.clj` (new)

`pack-signable-bytes` renders N4 §8.1.1 rather than deferring to `pr-str`:
maps key-ordered at every depth, sets as sorted vectors, sequences in declared
order, instants as millisecond-precision UTC, single spaces between entries,
key comparison byte-wise over UTF-8 with an absent namespace sorting first.

A value with no EDN reader form now throws instead of serializing an identity
hash — a pack carrying a live `:rule/check-fn` rather than the symbol §8.1.1
requires cannot produce a signature that verifies, and failing loudly beats
failing mysteriously.

`verify-ed25519` decodes the RFC 8032 raw encoding and rejects a key that is
not 32 bytes. `crypto` keeps only the Ed25519 concern; the serialization moved
out to keep each namespace within rule 210's layer budget. `canonical-compare`
takes its renderer as an argument so ordering and rendering are not mutually
recursive — which is also what makes them stratifiable.

### Schema — `policy_pack/schema.clj`

`:pack/signed-by` field docs record that it is a key identifier resolved
through the trust roots, never key material. The type is unchanged (`string?`).

### Localization

Verification and trust-root diagnostics go through a new system-locale
catalog, `config/policy-pack/messages/system.edn`. Adds a `messages` component
dependency to policy-pack.

## Testing Plan

New: `crypto_test`, `trust_roots_test`, `registry_signature_test`, and a
`signing_fixtures` helper that generates real Ed25519 keypairs and signs packs
over the canonical bytes. Stubbed crypto cannot distinguish a verifier that
resolves the right key from one that accepts whatever the pack hands it.

The three cases the fix exists for:

| Case | Expected |
|---|---|
| Pack signed by a trusted key | verifies |
| `:pack/signed-by` names an unknown identifier | fails, unresolved signer |
| Pack re-signed with an untrusted key, internally consistent | fails |

`self-certifying-pack-test` reproduces the original attack: modify the pack,
sign it with the attacker's key, write that public key into `:pack/signed-by`.
It fails, and the same pack verifies against a store that trusts that key —
so the signature is genuinely self-consistent and only trust rejects it.

Canonicalization tests build the same value two ways and compare bytes,
including nested maps above the array-map threshold where host hash order
takes over. `both-x-parities-verify-test` draws keypairs until it holds one of
each x parity, because one keypair covers the decoder only half the time.

Run:

- policy-pack: 294 tests, 2677 assertions, 0 failures, 0 errors
- policy-pack + its 8 reverse dependencies: 1353 tests, 6417 assertions, 0
  failures, 0 errors
- `bb poly:check` clean; `bb lint:clj:all` 0 errors, no policy-pack findings
- `bb lint:stratum` clean on every file this PR adds. `registry.clj` still
  reports SL003 (5 layers, max 3) — verified to report the same at
  `bade0222f` before this change, is documented as a Wave 2 split in its own
  namespace docstring, and appears in `work/stratum-lint-baseline-2026-07-24`.
  This PR adds one Layer 0 function to it and no new stratum, so the commits
  touching it were made with the documented
  `MINIFORGE_STRATUM_BUDGET_MODE=warn` opt-out.
- `bb test:graalvm` passes; `canonical-edn` verified under Babashka directly
  (the graalvm suite does not cover policy-pack namespaces)

## Review rounds

Three Copilot rounds, all findings real and fixed:

1. `decode-public-key` threw an NPE on a nil or non-string key. Reachable —
   `create-registry` accepts an injected store, so `resolve-key` sees whatever
   a caller holds, not only entries `->store` validated. A fail-closed path
   should not throw.
2. The protocol docstring promised `:signer` and `:timestamp` unconditionally
   while the unsigned branch omits both. The behaviour is right — reporting a
   signer for an unsigned pack would imply something signed it — so the
   docstring moved to match and the tests now assert both presence and absence.
3. `verify-ed25519` returned a bare `{:verified? false}` when `.verify` said
   no: the one verification outcome an operator would read as blank. Added
   `:crypto/invalid-signature` and a test that every failure path carries a
   reason.
4. Two follow-ons from (3): the catch-all used `(.getMessage e)`, which is nil
   for some exceptions, and `:crypto/undecodable-signature` said "not valid
   base64" while `decode-signature` also rejects a non-string. Both fixed, with
   a test that forces the nil-message path.

## Deployment Plan

Ships with the trust-root resource empty. Signature verification is fail-closed
from this commit: until an operator configures a publisher key, every signed
pack reports `:verified? false`. Nothing regresses, because verification was
not previously reachable — see below.

## Breaking Changes

Signatures produced against the old `pack-signable-bytes` no longer verify.
The old bytes were not reproducible, so no signature depended on them.

## Not in this change

- **No production caller.** `verify-signature` has no caller outside the
  component. §5.1.8's `require-signature-verification` and
  `enforce-publisher-allowlist` rules are declared with
  `:rule/detection {:type :custom}` and no `:custom-fn`, so nothing runs them.
  Wiring the pack-trust gate is separate work.
- **§8.2 steps 4 and 5** — key validity windows and per-pack publisher
  permission — remain unimplemented. §8.2.1 puts key distribution and rotation
  out of scope, and a validity field nothing enforced would be worse than none.
- **N4 Annex A** still records A.4 and the A.3 canonicalization gap as open.
  The annex lives on the unmerged #1658 branch; editing it from here would
  conflict. It should be updated when #1658 lands.

## Related Issues/PRs

- [#1658](https://github.com/miniforge-ai/miniforge/pull/1658) — N4 0.7.0-draft, the spec text this implements
- N4 §8.1, §8.1.1, §8.2, §8.2.1, Annex A.3, Annex A.4

## Checklist

- [x] Trust-root store loaded from config, not inline code (dewey 007)
- [x] Malli schema on the config file, validated at load
- [x] `verify-signature` resolves an identifier and fails closed
- [x] `:pack/signed-by` field docs corrected
- [x] Tests for trusted, unknown-identifier, and re-signed packs
- [x] `pack-signable-bytes` implements §8.1.1 rendering
- [x] Diagnostics routed through a message catalog (dewey 050)
- [x] `bb poly:check`, `bb lint:clj:all`, `bb test:graalvm`
