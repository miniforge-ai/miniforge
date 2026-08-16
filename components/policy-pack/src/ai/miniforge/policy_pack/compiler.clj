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
(ns ai.miniforge.policy-pack.compiler
  "Compile/validate the detection binding of a policy pack.

   Miniforge's thesis is policy-as-code that is APPLIED and GUARANTEED. A rule
   whose detection cannot bind to ANY mechanism is a silent no-op — it reaches
   agents only as advisory prompt text and can never gate a run (PR #979).

   This namespace answers one question per rule: which detection MECHANISM
   does this rule bind to? — and fails LOUD at compile time if any ENABLED
   rule binds to nothing.

   `resolve-detector` reflects registry state honestly:
   - by-type mechanisms (:content-scan / :diff-analysis / :plan-output /
     :state-comparison / :ast-analysis) bind by their declared type;
   - a :capability rule binds only when its capability keyword is registered
     (mechanical capabilities are injected by the gate layer at load), else
     it is :none (unbindable — correct fail-loud);
   - a :custom rule with a resolvable :custom-fn binds to :custom;
   - a :custom rule that DECLARES a :custom-fn which does not resolve is :none
     (unbindable — fail-loud; a broken registration, not a judge rule);
   - a :custom rule with NO :custom-fn binds to :semantic (the LLM-as-judge,
     always an available mechanism per N4-delta's :heuristic class);
   - anything else is :none.

   All public fns return data: a failed compilation yields an :invalid-input
   anomaly map (N4 §3.1 — anomalies are data at the component interface),
   never a throw.

   Split (rule 210: this namespace originally measured 8 real layers, max 3)
   across three siblings, each an earlier stage of the same pipeline:
   `compiler.check` (detector resolution + result/violation construction),
   `compiler.artifacts` (N4 check-input normalization, required by
   `compiler.check`), and `compiler.rule` (the executable check-fn built on
   top of `compiler.check`). `resolve-detector`, `rule-enabled?`, and
   `compile-rule-check` stay part of this namespace's public surface as thin
   re-exports so every existing caller (`ai.miniforge.policy-pack.compiler/…`)
   is unaffected; pack-level compilation — this namespace's own reason to
   exist — stays here with its real implementation.

   Layer 0: resolve-detector, rule-enabled?, compile-rule-check (re-exports),
     anomaly-rule-id, compiled-entry-detector
   Layer 1: compile-pack-checks
   Layer 2: compile-pack"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.compiler.check :as check]
   [ai.miniforge.policy-pack.compiler.rule :as rule]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} resolve-detector
  "Return the detection-mechanism keyword that `rule` binds to.
   See `ai.miniforge.policy-pack.compiler.check/resolve-detector`."
  check/resolve-detector)

(def ^{:stratum 0} rule-enabled?
  "True unless the rule explicitly sets `:rule/enabled?` to false.
   See `ai.miniforge.policy-pack.compiler.check/rule-enabled?`."
  check/rule-enabled?)

(def ^{:stratum 0} compile-rule-check
  "Compile one enabled rule into an executable N4 check entry.
   See `ai.miniforge.policy-pack.compiler.rule/compile-rule-check`."
  rule/compile-rule-check)

(defn- ^{:stratum 0} anomaly-rule-id
  [a]
  (get-in a [:anomaly/data :rule/id]))

(defn- ^{:stratum 0} compiled-entry-detector
  [entry]
  (:detector entry))

;------------------------------------------------------------------------------ Layer 1

;; Pack-level validation
(defn ^{:stratum 1} compile-pack-checks
  "Compile every enabled rule in `pack` into executable check entries.

   Returns:
     {:ok true
      :rule-count n
      :detector-counts {...}
      :compiled-rules [{:rule ... :detector ... :check-fn fn} ...]}

   Returns an :invalid-input anomaly when pack shape is malformed or any
   enabled rule is unbindable."
  [pack]
  (if-not (sequential? (:pack/rules pack))
    (anomaly/anomaly :invalid-input
                     "Policy pack :pack/rules must be a sequential collection of rules"
                     {:pack-rules-type (some-> (:pack/rules pack) type str)})
    (let [enabled    (filter rule-enabled? (:pack/rules pack))
          compiled   (mapv compile-rule-check enabled)
          anomalies  (filter anomaly/anomaly? compiled)
          unbindable (mapv anomaly-rule-id anomalies)]
      (if (seq anomalies)
        (anomaly/anomaly :invalid-input
                         (str "Policy pack has " (count anomalies)
                              " enabled rule(s) that bind to no detection mechanism")
                         {:unbindable-rule-ids unbindable
                          :rule-count          (count enabled)})
        {:ok              true
         :rule-count      (count enabled)
         :detector-counts (frequencies (map compiled-entry-detector compiled))
         :compiled-rules  compiled}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compile-pack
  "Validate that every ENABLED rule in `pack` binds to a detection mechanism.

   `pack` is a PackManifest map carrying `:pack/rules`.

   Returns on success a summary:
     {:ok true
      :rule-count <enabled-rule-count>
      :detector-counts {<detector-kw> n ...}}

   Returns an `:invalid-input` anomaly (data, not a throw) when one or more
   enabled rules resolve to `:none`; its `:anomaly/data` names
   `:unbindable-rule-ids` so the failure is actionable and fails loud."
  [pack]
  (let [result (compile-pack-checks pack)]
    (if (anomaly/anomaly? result)
      result
      (dissoc result :compiled-rules))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[clojure.edn :as edn])
  ;; Compile the real shipped pack — must report zero unbindable rules.
  (let [pack (edn/read-string
              (slurp "components/phase/resources/packs/miniforge-standards.pack.edn"))]
    (compile-pack pack))
  ;; => {:ok true :rule-count 49 :detector-counts {:semantic 46 :content-scan 3}}

  ;; A synthetic enabled rule with an unbindable detector fails loud.
  (compile-pack {:pack/rules [{:rule/id :bad :rule/detection {:type :nope}}]})
  ;; => {:anomaly/type :invalid-input :anomaly/data {:unbindable-rule-ids [:bad] ...}}

  :leave-this-here)
