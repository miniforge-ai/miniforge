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

(ns ai.miniforge.workbench-adapter.core
  "Pure projection from miniforge governance outputs into Minibench
   workbench wire maps.

   Two halves:

   1. REGISTRY GENERATION — `packs->registry` derives the miniforge
      `state_var_registry/v1` mechanically from the loaded policy-pack
      manifests: one state variable per rule
      (`miniforge.policy.<pack>.<rule>`), a `pass_rate` variable per
      pack, and the cross-pack `miniforge.gate.critical_violations_block`
      aggregate. The registry is generated, never hand-maintained — a new
      pack or rule becomes a comparable state variable automatically.

   2. PROJECTION — `policy-eval->snapshot` projects one supervisory
      PolicyEvaluation record (N5-delta-1 §3.1) into a variant-taggable
      `workbench_snapshot/v1`: per-rule pass/fail evaluations for every
      rule of every APPLIED pack, per-pack pass rates, and the critical
      aggregate.

   Semantics worth knowing:
   - A PolicyEvaluation records violations, not the rule universe it
     checked; a rule of an applied pack with no violation is scored
     `pass`. Rules of packs NOT in `:policy-eval/packs-applied` are
     omitted entirely (absent matrix cells), not scored not_applicable.
   - Rule checks are mechanical scanners, so `confidence` is 1.0 —
     deterministic evaluators earn it; nothing here is an LLM judgment.

   No I/O and no validation here — the interface owns the boundary."
  (:require [clojure.string :as str]))

(def ^:private wire-schema-version "workbench_snapshot/v1")
(def ^:private registry-wire-schema-version "state_var_registry/v1")
(def ^:private product "miniforge")
(def registry-id "miniforge-state-vars")

;; Status bands for RATE variables (per-rule vars are binary pass/fail).
;; Mirrored into each generated rate var's :thresholds so the registry
;; entry and the evaluator can never disagree.
(def pass-threshold 0.85)
(def warn-threshold 0.65)

(def ^:private signature-stub
  {:algorithm "sha256"
   :digest "0000000000000000000000000000000000000000000000000000000000000000"
   :canonicalization "json-c14n/1"})

(def critical-block-var-id "miniforge.gate.critical_violations_block")

;; ---------------------------------------------------------------- helpers

(defn- round2 [x]
  (/ (Math/round (* (double x) 100.0)) 100.0))

(defn- ->iso
  "Coerce an inst or string timestamp to an RFC 3339 string."
  [t]
  (if (string? t) t (str (java.time.Instant/ofEpochMilli (inst-ms t)))))

(defn- pack-short
  "`\"miniforge/foundations\"` -> `\"foundations\"`."
  [pack]
  (last (str/split (:pack/id pack) #"/")))

(defn rule-state-var-id
  "Dotted, product-qualified id for one pack rule:
   `miniforge.policy.<pack>.<rule-name>`."
  [pack rule]
  (str "miniforge.policy." (pack-short pack) "." (name (:rule/id rule))))

(defn- pass-rate-var-id [pack]
  (str "miniforge.policy." (pack-short pack) ".pass_rate"))

(defn- enforcement->gate-effect
  "Resolve a rule's fail-time gate effect from its enforcement action,
   falling back to severity."
  [rule]
  (let [action (get-in rule [:rule/enforcement :action])
        severity (:rule/severity rule)]
    (cond
      (= action :hard-halt) "blocks_transition"
      (= action :require-approval) "requires_review"
      (contains? #{:critical} severity) "blocks_transition"
      (contains? #{:high :major} severity) "requires_review"
      :else "marks_low_confidence")))

(defn- rate-status [score]
  (cond (>= score pass-threshold) "pass"
        (>= score warn-threshold) "warn"
        :else "fail"))

;; ---------------------------------------------------------------- registry

(defn- rule->state-var [pack rule]
  {:id (rule-state-var-id pack rule)
   :version (:pack/version pack)
   :product product
   :area "policy"
   :kind "compliance"
   :description (or (:rule/description rule) (:rule/title rule) (name (:rule/id rule)))
   :value_type "boolean"
   :thresholds {}
   :evidence_requirements {:required_refs []}
   :score_components []
   :gate_effects {:pass "none"
                  :fail (enforcement->gate-effect rule)}
   :lifecycle "active"
   :owner (:pack/id pack)
   :notes (str "Generated from " (:pack/id pack) "@" (:pack/version pack)
               " rule " (:rule/id rule)
               " (severity " (name (:rule/severity rule)) ").")})

(defn- pack->pass-rate-var [pack]
  {:id (pass-rate-var-id pack)
   :version (:pack/version pack)
   :product product
   :area "policy"
   :kind "compliance"
   :description (str "Share of " (:pack/id pack) " rules passing in one evaluation.")
   :value_type "number"
   :thresholds {:pass pass-threshold :warn warn-threshold}
   :evidence_requirements {:required_refs []}
   :score_components ["rules_total" "rules_passed" "rules_failed"]
   :gate_effects {:pass "none"
                  :warn "marks_low_confidence"
                  :fail "requires_review"}
   :lifecycle "active"
   :owner (:pack/id pack)
   :notes (str "Generated aggregate over " (:pack/id pack) "@" (:pack/version pack) ".")})

(def ^:private critical-block-var
  {:id critical-block-var-id
   :version "1"
   :product product
   :area "gate"
   :kind "compliance"
   :description "No critical-severity policy violations in the evaluation (N4 gate semantics: criticals block progression)."
   :value_type "boolean"
   :thresholds {}
   :evidence_requirements {:required_refs []}
   :score_components []
   :gate_effects {:pass "none" :fail "blocks_transition"}
   :lifecycle "active"
   :notes "Cross-pack aggregate; generated."})

(defn packs->registry
  "Derive the miniforge `state_var_registry/v1` from loaded pack
   manifests. `version` is the registry DateVer (`YYYY.MM.DD.N`)."
  [packs version]
  {:schema_version registry-wire-schema-version
   :registry_id registry-id
   :version version
   :product product
   :state_vars (vec (concat (mapcat (fn [pack]
                                      (map #(rule->state-var pack %)
                                           (:pack/rules pack)))
                                    packs)
                            (map pack->pass-rate-var packs)
                            [critical-block-var]))})

;; ---------------------------------------------------------------- projection

(defn- applied?
  "Is `pack` named by the evaluation's `:policy-eval/packs-applied`?
   Accepts the full pack id (\"miniforge/foundations\") or its short
   name (\"foundations\")."
  [pack applied-set]
  (or (contains? applied-set (:pack/id pack))
      (contains? applied-set (pack-short pack))))

(defn- violation->finding [v]
  {:severity (name (:violation/severity v))
   :message (:violation/message v)})

(defn- violation->evidence-ref [rule-var-id idx v]
  (cond-> {:id (str rule-var-id ".violation." idx)
           :source_role "policy-violation"}
    (:violation/location v) (assoc :quote (:violation/location v))))

(defn- rule-evaluation
  "One per-rule StateEvaluation: fail when the evaluation carries a
   violation of this rule, pass otherwise. Mechanical check -> confidence
   1.0."
  [pack rule violations evaluated-at]
  (let [var-id (rule-state-var-id pack rule)
        failed? (seq violations)]
    {:state_var_id var-id
     :product product
     :status (if failed? "fail" "pass")
     :value (not failed?)
     :score (if failed? 0.0 1.0)
     :confidence 1.0
     :evidence_refs (vec (map-indexed (partial violation->evidence-ref var-id) violations))
     :findings (mapv violation->finding violations)
     :gate_effect (if failed? (enforcement->gate-effect rule) "none")
     :evaluated_at evaluated-at}))

(defn- pack-rate-evaluation [pack rule-evals evaluated-at]
  (let [total (count rule-evals)
        passed (count (filter #(= "pass" (:status %)) rule-evals))
        score (round2 (if (pos? total) (/ passed (double total)) 0.0))
        status (rate-status score)]
    {:state_var_id (pass-rate-var-id pack)
     :product product
     :status status
     :value score
     :score score
     :confidence 1.0
     :score_components {:rules_total (double total)
                        :rules_passed (double passed)
                        :rules_failed (double (- total passed))}
     :evidence_refs []
     :findings (if (= passed total)
                 []
                 [{:severity "warn"
                   :message (str (- total passed) " of " total " "
                                 (:pack/id pack) " rule(s) failing")}])
     :gate_effect (case status "pass" "none" "warn" "marks_low_confidence" "requires_review")
     :evaluated_at evaluated-at}))

(defn- critical-block-evaluation [policy-eval evaluated-at]
  (let [criticals (filter #(= :critical (:violation/severity %))
                          (:policy-eval/violations policy-eval))
        blocked? (seq criticals)]
    {:state_var_id critical-block-var-id
     :product product
     :status (if blocked? "fail" "pass")
     :value (not blocked?)
     :score (if blocked? 0.0 1.0)
     :confidence 1.0
     :evidence_refs []
     :findings (mapv violation->finding criticals)
     :gate_effect (if blocked? "blocks_transition" "none")
     :evaluated_at evaluated-at}))

(defn policy-eval->evaluations
  "Project one PolicyEvaluation against the loaded `packs` into the full
   evaluation vector: per-rule for every applied pack, per-pack pass
   rates, and the cross-pack critical aggregate."
  [policy-eval packs]
  (let [applied-set (set (:policy-eval/packs-applied policy-eval))
        applied-packs (filter #(applied? % applied-set) packs)
        evaluated-at (->iso (:policy-eval/evaluated-at policy-eval))
        by-rule (group-by :violation/rule-id (:policy-eval/violations policy-eval))
        per-pack (for [pack applied-packs]
                   (let [rule-evals (mapv #(rule-evaluation pack % (get by-rule (:rule/id %)) evaluated-at)
                                          (:pack/rules pack))]
                     (conj rule-evals (pack-rate-evaluation pack rule-evals evaluated-at))))]
    (vec (concat (mapcat identity per-pack)
                 [(critical-block-evaluation policy-eval evaluated-at)]))))

(defn- entity-bag [policy-eval]
  {:miniforge.policy_eval
   {:policy_eval_id (str (:policy-eval/id policy-eval))
    :workflow_run_id (some-> (:policy-eval/workflow-run-id policy-eval) str)
    :gate_id (some-> (:policy-eval/gate-id policy-eval) name)
    :target_type (some-> (:policy-eval/target-type policy-eval) name)
    :target_id (some-> (:policy-eval/target-id policy-eval) str)
    :passed (:policy-eval/passed? policy-eval)
    :packs_applied (vec (:policy-eval/packs-applied policy-eval))
    :violation_count (count (:policy-eval/violations policy-eval))}})

(defn policy-eval->snapshot
  "Project one PolicyEvaluation into a `workbench_snapshot/v1` map.
   `opts`: `:snapshot-id :run-id :generated-at :registry-version`
   (required), `:variant :source-hashes :metadata` (optional provenance —
   variant tags the run for the comparison matrix; source-hashes pin the
   input artifacts; metadata carries emitter/pack digests)."
  [policy-eval packs {:keys [snapshot-id run-id generated-at registry-version
                             variant source-hashes metadata]}]
  (cond-> {:schema_version wire-schema-version
           :snapshot_id snapshot-id
           :generated_at generated-at
           :product product
           :run_id run-id
           :registry_ref {:registry_id registry-id :version registry-version}
           :evaluations (policy-eval->evaluations policy-eval packs)
           :entities (entity-bag policy-eval)
           :signature signature-stub}
    variant (assoc :variant variant)
    (seq source-hashes) (assoc :source_hashes (vec source-hashes))
    (some? metadata) (assoc :metadata metadata)))
