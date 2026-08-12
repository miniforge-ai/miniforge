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
(ns ai.miniforge.policy-pack.compiler.check
  "Resolve a rule's detection mechanism and produce the violations/result
   shapes an executable check-fn is built from.

   Split out of `ai.miniforge.policy-pack.compiler` (rule 210: the
   combined namespace measured 8 real layers, max 3). This namespace
   holds two of the original chains that fed the compiler's outermost
   functions:
   - detector resolution (`resolve-detector`, `by-type-detectors`,
     `rule-enabled?`) — which mechanism a rule binds to;
   - result/violation construction (`rule-metadata`, `check-result`,
     `detect-rule-violations`, and the plain violation-map builders) —
     what running that mechanism against an artifact produces.

   Kept together these are 3 layers; `ai.miniforge.policy-pack.compiler.rule`
   builds the executable check-fn on top of this namespace's outputs, and
   `ai.miniforge.policy-pack.compiler.artifacts` (required here for
   `detect-rule-violations`) normalizes N4 check input into the artifact
   maps detectors accept.

   Layer 0: by-type-detectors, rule-enabled?, detector-class,
     semantic-context-ready?, violation-with-rule, exception-violation,
     missing-semantic-wiring-violation
   Layer 1: resolve-detector, rule-metadata, detect-rule-violations
   Layer 2: check-result"
  (:require
   [ai.miniforge.policy-pack.capability :as capability]
   [ai.miniforge.policy-pack.compiler.artifacts :as artifacts]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0

;; Per-rule classification
(def ^{:stratum 0} ^{:doc "Detection types that bind to a mechanism directly by their type."}
  by-type-detectors
  #{:content-scan :diff-analysis :plan-output :state-comparison :ast-analysis})

(defn ^{:stratum 0} rule-enabled?
  "True unless the rule explicitly sets `:rule/enabled?` to false.
   A missing `:rule/enabled?` means enabled (the shipped pack omits it)."
  [rule]
  (not (false? (:rule/enabled? rule))))

(defn- ^{:stratum 0} detector-class
  [detector]
  (if (= :semantic detector)
    :heuristic
    :deterministic))

(defn ^{:stratum 0} semantic-context-ready?
  [context]
  (and (fn? (:semantic-analyze-fn context))
       (some? (:llm-client context))
       (fn? (:complete-fn context))))

(defn- ^{:stratum 0} violation-with-rule
  [rule violation]
  (assoc violation
         :rule-id  (or (:rule-id violation) (:rule/id rule))
         :severity (or (:severity violation) (:rule/severity rule))))

(defn ^{:stratum 0} exception-violation
  [rule detector e]
  {:type     :check-error
   :rule-id  (:rule/id rule)
   :severity (:rule/severity rule)
   :detector detector
   :error    (ex-message e)
   :message  (str "Policy check failed: " (ex-message e))})

(defn ^{:stratum 0} missing-semantic-wiring-violation
  [rule]
  {:type     :semantic-error
   :rule-id  (:rule/id rule)
   :severity (:rule/severity rule)
   :message  "Semantic policy check requires :semantic-analyze-fn, :llm-client, and :complete-fn"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-detector
  "Return the detection-mechanism keyword that `rule` binds to.

   One of the `by-type-detectors`, or `:capability` / `:custom` / `:semantic`,
   or `:none` when the rule binds to no available mechanism.

   - :capability binds only if its `:capability` keyword is registered.
   - :custom binds to :custom when its `:custom-fn` resolves; to :none when a
     `:custom-fn` is declared but unresolvable (fail-loud); to :semantic when no
     `:custom-fn` is declared.
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
      (cond
        (detection/custom-fn-resolvable? rule) :custom
        ;; A rule that DECLARES a :custom-fn symbol which does not resolve is a
        ;; broken registration (the detector namespace never registered it), not
        ;; an intentional judge rule. Bind to :none so the compiler fails loud
        ;; (`compile-rule-check` returns an :invalid-input anomaly) instead of
        ;; silently routing it to the LLM judge — the failure mode from #1381.
        (get-in rule [:rule/detection :custom-fn]) :none
        :else :semantic)

      :else
      :none)))

(defn- ^{:stratum 1} rule-metadata
  [rule detector]
  {:rule/id       (:rule/id rule)
   :rule/severity (:rule/severity rule)
   :detector      detector
   :class         (detector-class detector)})

(defn ^{:stratum 1} detect-rule-violations
  [rule detector artifacts-input context]
  (->> (artifacts/artifact-inputs detector artifacts-input)
       (keep #(detection/detect-violation rule % context))
       (mapv #(violation-with-rule rule %))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check-result
  [rule detector violations]
  {:passed?    (empty? violations)
   :violations violations
   :metadata   (rule-metadata rule detector)})
