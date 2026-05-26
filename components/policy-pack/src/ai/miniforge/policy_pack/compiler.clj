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
   - a :custom rule with no resolvable :custom-fn binds to :semantic (the
     LLM-as-judge, always an available mechanism per N4-delta's :heuristic
     class);
   - anything else is :none.

   All public fns return data: a failed compilation yields an :invalid-input
   anomaly map (N4 §3.1 — anomalies are data at the component interface),
   never a throw."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Per-rule classification

(def ^{:doc "Detection types that bind to a mechanism directly by their type."}
  by-type-detectors
  #{:content-scan :diff-analysis :plan-output :state-comparison :ast-analysis})

(defn rule-enabled?
  "True unless the rule explicitly sets `:rule/enabled?` to false.
   A missing `:rule/enabled?` means enabled (the shipped pack omits it)."
  [rule]
  (not (false? (:rule/enabled? rule))))

(defn resolve-detector
  "Return the detection-mechanism keyword that `rule` binds to.

   One of the `by-type-detectors`, or `:capability` / `:custom` / `:semantic`,
   or `:none` when the rule binds to no available mechanism.

   - :capability binds only if its `:capability` keyword is registered.
   - :custom binds to :custom when its `:custom-fn` resolves, else :semantic.
   - An unknown/missing detection type is :none."
  [rule]
  (let [detection-type (get-in rule [:rule/detection :type])]
    (cond
      (contains? by-type-detectors detection-type)
      detection-type

      (= :capability detection-type)
      (let [capability-kw (get-in rule [:rule/detection :capability])]
        (if (and capability-kw (capability/capability-available? capability-kw))
          :capability
          :none))

      (= :custom detection-type)
      (if (detection/custom-fn-resolvable? rule)
        :custom
        :semantic)

      :else
      :none)))

;------------------------------------------------------------------------------ Layer 1
;; Pack-level validation

(defn compile-pack
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
  (let [enabled    (filter rule-enabled? (:pack/rules pack))
        resolved   (map (fn [rule]
                          {:rule/id  (:rule/id rule)
                           :detector (resolve-detector rule)})
                        enabled)
        unbindable (->> resolved
                        (filter #(= :none (:detector %)))
                        (map :rule/id)
                        vec)]
    (if (seq unbindable)
      (anomaly/anomaly :invalid-input
                       (str "Policy pack has " (count unbindable)
                            " enabled rule(s) that bind to no detection mechanism")
                       {:unbindable-rule-ids unbindable
                        :rule-count          (count enabled)})
      {:ok              true
       :rule-count      (count enabled)
       :detector-counts (frequencies (map :detector resolved))})))

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
