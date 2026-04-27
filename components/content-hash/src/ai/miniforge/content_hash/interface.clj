;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.content-hash.interface
  "Public API for the content-hash component.

   Provides domain-free deterministic content hashing primitives:
   - canonical-edn: serialize a value to a deterministic EDN string
   - content-hash: SHA-256 hex digest of the canonical EDN of a value

   This component carries no workflow/SDLC schema and is safe for any
   miniforge-family repo (thesium-workflows, miniforge-fleet, etc.) that
   needs tamper-evident hashing of arbitrary EDN-shaped data."
  (:require
   [ai.miniforge.content-hash.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical EDN

(defn canonical-edn
  "Return a deterministic EDN string for `x`.

   Maps are serialized with keys sorted under a stable, type-aware
   comparator; sets are sorted; sequential collections preserve order;
   scalars pass through. Logically equal inputs produce identical output
   regardless of original insertion order.

   Example:
     (canonical-edn {:b 2 :a 1}) ;; => \"{:a 1, :b 2}\"
     (canonical-edn {:a 1 :b 2}) ;; => \"{:a 1, :b 2}\""
  [x]
  (core/canonical-edn x))

;------------------------------------------------------------------------------ Layer 1
;; SHA-256 content hash

(defn content-hash
  "Compute the SHA-256 hex digest of `x`'s canonical EDN.

   Returns a lowercase 64-character hex string. Equal data produces equal
   hashes; distinct data produces distinct hashes (with cryptographic
   probability).

   Example:
     (content-hash {:a 1 :b 2})
     ;; => \"c7e0...\" (64-char hex string)"
  [x]
  (core/content-hash x))
