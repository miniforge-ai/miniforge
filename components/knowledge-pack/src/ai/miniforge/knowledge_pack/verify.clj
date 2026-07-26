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
(ns ai.miniforge.knowledge-pack.verify
  "Pack manifest verification — the boundary that turns a stored
   pack back into a trusted citation.

   Decision 6 of miniforge-fleet's Phase E plan is structural: each
   `:pack/zettels` entry carries an exact `(zettel-id, revision-id,
   digest)` triple, so a pack loaded against a zettel store can be
   verified end-to-end:

     - The pack's own `:pack/digest` recomputes to the value stamped
       on the manifest. A drift means the manifest body was edited
       without going through `update-pack` — surfaces as
       `:pack/digest-mismatch`.

     - For each zettel ref, the store contains a zettel with the
       same `:zettel/id` AND `:zettel/revision-id` AND
       `:zettel/digest`. Any divergence — missing zettel, wrong
       revision, mutated content — surfaces as a structured per-ref
       discrepancy. The verifier ACCUMULATES every per-ref problem
       it finds rather than short-circuiting, so an operator
       diagnosing a partial-failure can see the full set in one
       result map. There is no silent 'latest in pack' fallback —
       any divergence is reported.

   The verifier is pure over its inputs: it takes a pack map and a
   `lookup-fn` that resolves `(zettel-id, revision-id) → zettel
   map`. Production wiring closes over a real
   `knowledge.store/get-zettel-revision`; tests pass an in-memory
   map without depending on the store machinery."
  (:require
   [ai.miniforge.knowledge-pack.pack :as pack]))

;------------------------------------------------------------------------------ Layer 0

;; Discrepancy factory.
(defn- ^{:stratum 0} discrepancy
  "Pure factory: build a discrepancy map. `extras` is an optional
   map merged onto the `{:reason :detail}` base; per-ref callers
   pass `{:ref ref :stored-digest ...}` and manifest-level callers
   pass the stamped-vs-computed values an operator needs to triage."
  ([reason detail]
   {:reason reason :detail detail})
  ([reason detail extras]
   (merge {:reason reason :detail detail} extras)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-ref
  "Pure: compare a single `:pack/zettels` entry against what the
   store currently holds. Returns nil on success or a structured
   discrepancy map on failure."
  [{:zettel/keys [id revision-id digest] :as ref} lookup-fn]
  (let [stored (lookup-fn id revision-id)]
    (cond
      (nil? stored)
      (discrepancy :zettel-not-found
                   "lookup returned nil for (zettel-id, revision-id)"
                   {:ref ref})

      (not= digest (:zettel/digest stored))
      (discrepancy :digest-mismatch
                   "stored zettel's :zettel/digest differs — content was rewritten without rotating the revision-id"
                   {:ref ref :stored-digest (:zettel/digest stored)})

      (not= id (:zettel/id stored))
      (discrepancy :id-mismatch
                   "lookup returned a zettel with a different :zettel/id (lookup-fn invariant violation)"
                   {:ref ref})

      (not= revision-id (:zettel/revision-id stored))
      (discrepancy :revision-id-mismatch
                   "lookup returned a zettel with a different :zettel/revision-id (lookup-fn invariant violation)"
                   {:ref ref}))))

;; Manifest-level verification.
(defn- ^{:stratum 1} verify-manifest-digest
  "Pure: recompute the pack's digest from the current content
   projection, compare against the stamped digest, AND verify the
   stamped `:pack/revision-id` is the UUID derived from that
   recomputed digest. Returns nil on success or a discrepancy map
   on failure.

   Both checks matter: an attacker who tampers with manifest body
   AND re-stamps `:pack/digest` would still trip the revision-id
   check, because revision-id is `nameUUIDFromBytes(digest)`. A
   tamper that goes through `update-pack` is not really tampering
   — the revision rotates by design — but a bypass attempt that
   forges either field independently is caught here."
  [pack]
  (let [stamped-digest (:pack/digest pack)
        recomputed     (pack/compute-digest pack)
        stamped-rev    (:pack/revision-id pack)
        derived-rev    (when recomputed
                         (pack/revision-id-from-digest recomputed))]
    (cond
      (nil? stamped-digest)
      (discrepancy :pack/digest-missing
                   "pack manifest carries no :pack/digest — was it built via knowledge-pack/build-pack?")

      (not= stamped-digest recomputed)
      (discrepancy :pack/digest-mismatch
                   "manifest body was edited without going through update-pack"
                   {:stamped-digest    stamped-digest
                    :recomputed-digest recomputed})

      (nil? stamped-rev)
      (discrepancy :pack/revision-id-missing
                   "pack manifest carries :pack/digest but no :pack/revision-id — partial stamping should never happen via the constructors")

      (not= stamped-rev derived-rev)
      (discrepancy :pack/revision-id-mismatch
                   "manifest's :pack/revision-id does not match the UUID derived from :pack/digest — someone forged one without the other"
                   {:stamped-revision-id stamped-rev
                    :derived-revision-id derived-rev}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-pack
  "Verify a pack against a zettel store.

   Arguments:
     pack       — the pack map (must carry `:pack/zettels` triples
                  and `:pack/digest`).
     lookup-fn  — `(fn [zettel-id revision-id] -> zettel-map | nil)`.
                  Production callers close over the real zettel
                  store; tests pass an in-memory shim.

   Returns:
     {:valid?       boolean
      :pack/discrepancy   map | nil   ; manifest-level digest
                                       ;   mismatch, if any
      :ref/discrepancies  vector       ; per-ref problems, may be
                                       ;   empty}

   `:valid?` is true iff `:pack/discrepancy` is nil AND
   `:ref/discrepancies` is empty. Callers wanting to fail closed on
   any divergence can branch on `:valid?` directly; callers wanting
   to surface every problem at once read both fields."
  [pack lookup-fn]
  (let [pack-disc (verify-manifest-digest pack)
        ref-discs (->> (:pack/zettels pack)
                       (keep #(verify-ref % lookup-fn))
                       vec)]
    {:valid?             (and (nil? pack-disc) (empty? ref-discs))
     :pack/discrepancy   pack-disc
     :ref/discrepancies  ref-discs}))
