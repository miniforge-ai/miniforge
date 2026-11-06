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
(ns ai.miniforge.gate.policy-pack.evaluation
  "Evaluates a phase's compiled pack checks and maps the outcome to a
   decision envelope and gate errors."
  (:require
   [ai.miniforge.gate.decide :as decide]
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.policy-pack.checks :as checks]
   [ai.miniforge.gate.policy-pack.packs :as packs]
   [ai.miniforge.gate.policy-pack.rules :as rules]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [clojure.string]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} compile-anomaly?
  [result]
  (contains? result :anomaly/type))

(defn- ^{:stratum 0} compile-anomaly-message
  [anomaly]
  (or (:anomaly/message anomaly)
      (:message anomaly)
      (name (:anomaly/type anomaly))))

(defn- ^{:stratum 0} compile-pack-results
  [packs]
  (mapv policy-pack/compile-pack-checks packs))

(defn- ^{:stratum 0} pack-pins
  "Revision pins for the envelope: the pack ids+versions evaluated."
  [packs]
  {:pins/pack-revision (clojure.string/join ","
                        (map #(str (name (or (:pack/id %) :unknown)) "@"
                                   (or (:pack/version %) "?")) packs))
   :pins/rule-ids (vec (keep :rule/id (mapcat :pack/rules packs)))
   :pins/event-watermark nil})

(defn ^{:stratum 0} no-policy-packs-warning
  []
  {:type :no-policy-packs
   :message (msg/t :policy-pack/no-policy-packs)})

(defn ^{:stratum 0} packs-for-gate
  "Packs to evaluate. When ctx carries an explicit `:policy-packs` key, that
   value is authoritative — even an empty vector (a run that disabled all
   packs is respected, not silently re-seeded). When the key is absent (the
   normal SDLC path), the shipped standards pack is used so the gate actually
   evaluates. Empty when no pack resource is present."
  [ctx]
  (if (contains? ctx :policy-packs)
    (vec (:policy-packs ctx))
    (if-let [pack @packs/standards-pack] [pack] [])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} compile-anomaly-result
  [anomaly]
  {:passed?  false
   :compiled? false
   :blocking [{:code    :policy-pack/compile-failed
               :message (msg/t :policy-pack/compile-error
                               {:message (compile-anomaly-message anomaly)})
               :data    (:anomaly/data anomaly)}]
   :warnings []})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} compiled-check-result
  [packs phase artifact context]
  (let [compile-results (compile-pack-results packs)]
    (if-let [anomaly (first (filter compile-anomaly? compile-results))]
      (compile-anomaly-result anomaly)
      (let [violations (vec (checks/run-compiled-rules
                             (rules/phase-compiled-rules compile-results phase)
                             artifact
                             context))
            classified (policy-pack/classify-violations violations)
            {:keys [blocking require-approval warnings audits unknown]} classified
            envelope   (decide/decide classified (pack-pins packs))]
        {:passed?          (decide/allowed? envelope)
         :compiled?        true
         :envelope         envelope
         :violations       violations
         :blocking         (mapv policy-pack/violation->error (concat blocking unknown))
         :require-approval (mapv policy-pack/violation->error require-approval)
         :warnings         (mapv policy-pack/violation->warning warnings)
         :audits           (mapv policy-pack/violation->warning audits)}))))
