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
       discrepancy and the verification fails fast (no silent
       'latest in pack' fallback).

   The verifier is pure over its inputs: it takes a pack map and a
   `lookup-fn` that resolves `(zettel-id, revision-id) → zettel
   map`. Production wiring closes over a real
   `knowledge.store/get-zettel-revision`; tests pass an in-memory
   map without depending on the store machinery."
  (:require
   [ai.miniforge.knowledge-pack.pack :as pack]))

;------------------------------------------------------------------------------ Layer 0
;; Per-ref verification.

(defn- verify-ref
  "Pure: compare a single `:pack/zettels` entry against what the
   store currently holds. Returns nil on success or a structured
   discrepancy map on failure."
  [{:zettel/keys [id revision-id digest] :as ref} lookup-fn]
  (let [stored (lookup-fn id revision-id)]
    (cond
      (nil? stored)
      {:ref ref
       :reason :zettel-not-found
       :detail "lookup returned nil for (zettel-id, revision-id)"}

      (not= digest (:zettel/digest stored))
      {:ref ref
       :reason :digest-mismatch
       :detail "stored zettel's :zettel/digest differs — content was rewritten without rotating the revision-id"
       :stored-digest (:zettel/digest stored)}

      (not= id (:zettel/id stored))
      {:ref ref
       :reason :id-mismatch
       :detail "lookup returned a zettel with a different :zettel/id (lookup-fn invariant violation)"}

      (not= revision-id (:zettel/revision-id stored))
      {:ref ref
       :reason :revision-id-mismatch
       :detail "lookup returned a zettel with a different :zettel/revision-id (lookup-fn invariant violation)"})))

;------------------------------------------------------------------------------ Layer 1
;; Manifest-level verification.

(defn- verify-manifest-digest
  "Pure: recompute the pack's digest from the current content
   projection and compare against the stamped value. Returns nil on
   success or a discrepancy map on failure."
  [pack]
  (let [stamped     (:pack/digest pack)
        recomputed  (pack/compute-digest pack)]
    (cond
      (nil? stamped)
      {:reason :pack/digest-missing
       :detail "pack manifest carries no :pack/digest — was it built via knowledge-pack/build-pack?"}

      (not= stamped recomputed)
      {:reason :pack/digest-mismatch
       :detail "manifest body was edited without going through update-pack"
       :stamped-digest stamped
       :recomputed-digest recomputed})))

(defn verify-pack
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
