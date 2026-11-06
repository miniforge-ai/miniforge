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
(ns ai.miniforge.gate.policy-pack
  "Phase-scoped policy-pack gate.

   Closes PR #979's enforcement gap: the only pack-derived gate (`:behavioral`)
   was wired solely into the `:observe` phase, so a normal canonical-sdlc run
   NEVER evaluated the standards pack on the path a PR actually takes. This
   gate runs the shipped standards pack's enabled rules — those whose
   `:applies-to {:phases}` includes the current phase — against the phase
   artifact, and BLOCKS on `:hard-halt` enforcement (a failed gate routes to
   the existing repair/redirect path via `apply-gate-validation`).

   Registered as two phase-baked gates, `:policy-verify` and `:policy-review`,
   so the generic `apply-gate-validation` path runs the right rule subset per
   phase. The whole evaluation delegates to compiled policy checks from
   `policy-pack/compile-pack-checks`, preserving phase/applicability
   filtering and severity/enforcement classification — this gate adds no
   parallel enforcement channel (N4 reuse constraint).

   Pack source: `ctx :policy-packs` when provided (tests / repo packs), else
   the classpath-shipped `packs/miniforge-standards.pack.edn`.

   Semantic seam: this gate is the layer that depends on both the policy pack
   and the LLM, so it INJECTS the LLM-judge wiring the policy-pack detector
   needs — `:llm-client` (from the run's `:llm-backend`), `:complete-fn`
   (`llm/complete`), and a `:semantic-analyze-fn` that scopes the judge to the
   run's CHANGED files (not the whole repo) — so heuristic (`:custom` rules with
   no resolvable `:custom-fn`) reach the LLM judge over exactly the artifact
   under review. With compiled checks, missing semantic wiring is reported as a
   policy violation; the pack's enforcement action controls whether that
   violation blocks, requires approval, warns, or audits.

   Cost discipline: the judge runs only for semantic rules the run intends to
   ACT on — `:hard-halt` / `:require-approval`. A semantic rule that only warns
   or audits is guidance (surfaced via behavior injection elsewhere), so the
   gate does not pay for an LLM call that could only yield a non-blocking
   finding. Until a semantic rule is classified to an acting action, the seam
   is wired but dormant.

   Fail-safe: a check that throws is converted to a failed gate by
   `gate.interface/check-gate` (mirrors the reviewer fix in #977) — never a
   silent pass."
  (:require
   ;; Registers mechanical capability checks before compiled pack evaluation.
   [ai.miniforge.gate.capabilities]
   [ai.miniforge.decision-envelope.interface]
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.policy-pack.artifact :as artifact]
   [ai.miniforge.gate.policy-pack.checks :as checks]
   [ai.miniforge.gate.policy-pack.evaluation :as evaluation]
   [ai.miniforge.gate.policy-pack.judge :as judge]
   [ai.miniforge.gate.policy-pack.rules :as rules]
   [ai.miniforge.gate.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} repair-policy-pack
  "Policy violations cannot be fixed in-place; the workflow must redirect to
   :implement for an agent to address the root cause (mirrors the behavioral
   gate)."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors   errors
   :message  (msg/t :policy-pack/repair-required)})

;; Phase-scoped check
(defn ^{:stratum 0} check-policy-pack-for-phase
  "Evaluate the pack against `artifact` for `phase`.

   Selects the enabled rules whose `:applies-to {:phases}` includes `phase`
   (via compiled check phase filtering), runs their executable checks, and
   maps the result to a gate result (Ariadne 1c):
     :errors   — blocking (`:hard-halt`), unknown-enforcement/severity, and
                 require-approval violations (approval DENIES until a
                 workflow clears it)
     :warnings — warn / audit violations (recorded as envelope obligations)
     :envelope — the DecisionEnvelope from decide()
     :passed?  — (decide/allowed? envelope)

   When no pack is available, passes with a no-packs warning (no regression on
   repos without a standards pack)."
  [phase artifact ctx]
  (let [packs (evaluation/packs-for-gate ctx)]
    (if (empty? packs)
      {:passed? true :warnings [(evaluation/no-policy-packs-warning)]}
      (let [artifact (artifact/policy-artifact artifact ctx)
            acting   (rules/acting-semantic-rules packs phase artifact ctx)
            context  (-> (judge/with-semantic-wiring ctx artifact acting) (assoc :phase phase))
            result   (evaluation/compiled-check-result packs phase artifact context)]
        (when (:compiled? result)
          (checks/emit-rule-evidence! ctx phase
                               (checks/classify-rules packs phase artifact context
                                               (:violations result))
                               (get-in result [:envelope :envelope/id])))
        (cond-> {:passed?  (:passed? result)
                 :errors   (vec (concat (:blocking result)
                                        (:require-approval result)))
                 :warnings (vec (concat (:warnings result)
                                        (:audits result)))}
          (:envelope result) (assoc :envelope (:envelope result)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-policy-verify
  "Pack gate for the verify phase."
  [artifact ctx]
  (check-policy-pack-for-phase :verify artifact ctx))

(defn ^{:stratum 1} check-policy-review
  "Pack gate for the review phase."
  [artifact ctx]
  (check-policy-pack-for-phase :review artifact ctx))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} registry/get-gate :policy-verify
  [_]
  {:name        :policy-verify
   :description (msg/t :policy-pack/description-verify)
   :check       check-policy-verify
   :repair      repair-policy-pack})

(defmethod ^{:stratum 2} registry/get-gate :policy-review
  [_]
  {:name        :policy-review
   :description (msg/t :policy-pack/description-review)
   :check       check-policy-review
   :repair      repair-policy-pack})

;; Registry
(registry/register-gate! :policy-verify)

(registry/register-gate! :policy-review)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; No pack available — passes with a warning.
  (check-policy-pack-for-phase :verify {} {:policy-packs []})

  ;; Against the shipped pack for the review phase.
  (check-policy-pack-for-phase
   :review
   {:artifact/content "(def x 1)" :artifact/path "core.clj"}
   {})

  :leave-this-here)
