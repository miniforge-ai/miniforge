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
(ns ai.miniforge.gate.policy-pack.checks
  "Runs compiled rule checks and records per-rule evidence of the outcome."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.gate.policy-pack.rules :as rules]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} violation-entry
  [compiled violation]
  {:rule (:rule compiled)
   :violation violation
   :timestamp (java.time.Instant/now)})

(defn- ^{:stratum 0} violation-summary
  "Non-sensitive summary of a violation for an evidence event. Drops :matches
   — which can carry source lines or secret material (e.g. a no-secrets rule's
   matched token) and must never reach a stored event. Keeps the message, the
   artifact path, and a match count."
  [violation]
  (when violation
    {:message       (:message violation)
     :artifact-path (:artifact-path violation)
     :match-count   (count (:matches violation))}))

(defn ^{:stratum 0} emit-rule-evidence!
  "Publish one :gate/rule-applied event per classified rule. No-op when the
   run carries no event stream. Fail-safe: evidence emission must never break
   enforcement, so a publish error is swallowed (the gate verdict still
   stands)."
  [ctx phase classified envelope-id]
  (when-let [stream (:event-stream ctx)]
    (try
      (let [wid (:workflow/id ctx)]
        (doseq [{:keys [rule-id status severity enforcement violation]} classified]
          (event-stream/publish!
           stream
           (event-stream/gate-rule-applied stream wid phase rule-id status
                                           {:severity    severity
                                            :enforcement enforcement
                                            :violation   violation
                                            :envelope-id envelope-id}))))
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-compiled-rule
  [artifact context compiled]
  (if-not (rules/semantic-judge-applies? compiled)
    []
    (let [rule   (:rule compiled)
          phase  (:phase context)
          inputs (rules/applicable-artifacts rule phase artifact context)
          result ((:check-fn compiled) inputs context)]
      (mapv #(violation-entry compiled %) (:violations result)))))

(defn ^{:stratum 1} classify-rules
  "Per-rule evidence: classify every enabled rule across `packs` for `phase`.
   `violations` are the {:rule :violation} maps from compiled check results.

     :skipped-by-phase — the rule's :applies-to {:phases} excludes `phase`
     :not-applicable   — phase matches but file-glob/task-type excludes it
     :failed           — considered and violated (carries a violation summary)
     :guidance         — applicable semantic rule the judge skips (non-acting
                         action): surfaced as guidance, not gated. NOT `:passed`
                         — the judge never ran, so evidence must not imply it did
     :passed           — considered and clean

   Rules are resolved first, so override-by-id is honored: evidence is 1:1
   with the rules actually evaluated and reports the effective merged
   :severity/:enforcement, not pre-merge values.

   Emitting all four statuses (not just failures) makes the applied policy set
   for a run reconstructable from the event log."
  [packs phase artifact context violations]
  (let [enabled        (filterv policy-pack/rule-enabled?
                                (policy-pack/resolve-rules (mapcat :pack/rules packs)))
        considered-ids (set (map :rule/id
                                 (filter #(seq (rules/applicable-artifacts
                                                % phase artifact context))
                                         enabled)))
        violated       (into {} (map (fn [{:keys [rule violation]}]
                                       [(:rule/id rule) violation]))
                             violations)]
    (mapv (fn [rule]
            (let [id (:rule/id rule)]
              {:rule-id     id
               :status      (cond
                              (not (policy-pack/rule-applies-to-phase? rule phase)) :skipped-by-phase
                              (contains? violated id)                               :failed
                              (and (contains? considered-ids id)
                                   (rules/guidance-only-semantic? rule))                 :guidance
                              (contains? considered-ids id)                         :passed
                              :else                                                 :not-applicable)
               :severity    (:rule/severity rule)
               :enforcement (get-in rule [:rule/enforcement :action])
               :violation   (violation-summary (get violated id))}))
          enabled)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-compiled-rules
  [compiled-rules artifact context]
  (mapcat #(run-compiled-rule artifact context %) compiled-rules))
