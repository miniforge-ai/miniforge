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
(ns ai.miniforge.policy-pack.core
  "Core policy pack operations: validation-layer ordering and artifact
   checking orchestration.

   Was over the rule 210 budget at 7 real layers (Wave 2, SL003); split into
   four namespaces along the real reference graph:
   - `enforcement.clj` — severity/enforcement comparison
   - `applicability.clj` — rule-applies-to-* predicates + filter-applicable-rules
   - `builders.clj` — pack/rule construction + merge-rules/resolve-rules
   - `core.clj` (this file) — the two, above, wired together into checks

   Layer 0: ordered-validation (self-contained validation-layer runner);
     check-artifact (orchestrates builders/resolve-rules,
     applicability/filter-applicable-rules, and detection/*, all external —
     no same-file dependents)
   Layer 1: check-artifacts (over check-artifact)"
  (:require
   [ai.miniforge.policy-pack.applicability :as applicability]
   [ai.miniforge.policy-pack.builders :as builders]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0

;; Validation layer ordering (N4 §3, five-layer model)
(defn ^{:stratum 0} ordered-validation
  "Apply validation layers in strict order: L0 → L1 → L2 → L3 → L4.
   A failure at a lower layer short-circuits higher layers.

   Arguments:
   - layers — Vector of {:layer keyword :validate-fn (fn [] -> {:passed? bool ...})}

   Returns:
   - {:passed? bool :results [{:layer :passed? :violations ...}] :short-circuited-at keyword?}"
  [layers]
  (loop [[{:keys [layer validate-fn]} & remaining] layers
         results []]
    (if (nil? layer)
      {:passed? (every? :passed? results)
       :results results}
      (let [result (assoc (validate-fn) :layer layer)
            results' (conj results result)]
        (if (:passed? result)
          (recur remaining results')
          {:passed?            false
           :results            results'
           :short-circuited-at layer})))))

;; Rule checking orchestration
(defn ^{:stratum 0} check-artifact
  "Check an artifact against all applicable rules from a pack.

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifact - Artifact to check
   - context - Execution context

   Returns:
   - {:passed? bool
      :violations [...]
      :warnings [...]
      :errors [...]}

   Example:
     (check-artifact pack
                     {:artifact/content \"...\" :artifact/path \"main.tf\"}
                     {:phase :implement})"
  [pack artifact context]
  (let [;; Handle single pack or vector of packs
        packs (if (vector? pack) pack [pack])
        all-rules (mapcat :pack/rules packs)
        resolved (builders/resolve-rules all-rules)
        ctx (assoc context :artifact artifact)
        applicable (applicability/filter-applicable-rules resolved ctx)
        violations (detection/check-rules applicable artifact context)

        ;; Exhaustive classification - unknown enforcement/severity and
        ;; require-approval violations fail the check (Ariadne 1c; the
        ;; old shape passed everything but :hard-halt)
        {:keys [blocking require-approval warnings audits unknown]}
        (detection/classify-violations violations)]

    {:passed? (and (empty? blocking) (empty? require-approval) (empty? unknown))
     :violations violations
     :blocking (mapv detection/violation->error (concat blocking unknown))
     :require-approval (mapv detection/violation->error require-approval)
     :warnings (mapv detection/violation->warning warnings)
     :audits (mapv detection/violation->warning audits)
     :read-only? (boolean (:read-only? context))}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-artifacts
  "Check multiple artifacts against pack rules.

   Arguments:
   - pack - PackManifest (or vector of packs)
   - artifacts - Vector of artifacts
   - context - Execution context

   Returns:
   - Vector of check results, one per artifact"
  [pack artifacts context]
  (mapv #(check-artifact pack % context) artifacts))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a pack and check an artifact
  (def test-pack
    (builders/create-pack "test" "Test Pack" "A test" "author"
                          :rules
                          [(builders/create-rule :no-todos
                                                 "No TODOs"
                                                 "Don't leave TODOs in code"
                                                 :low "800"
                                                 (builders/content-scan-detection "TODO")
                                                 (builders/warn-enforcement "Found TODO comment"))]))

  (check-artifact test-pack
                  {:artifact/content "# TODO: fix this"
                   :artifact/path "main.py"}
                  {})

  :leave-this-here)
