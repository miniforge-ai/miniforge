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
(ns ai.miniforge.pipeline-runner.processing-stage
  "Transform and validate stage executors: stages that process the records
   collected from upstream stages without a connector."
  (:require [ai.miniforge.data-quality.interface :as dq]
            [ai.miniforge.pipeline-runner.messages :as msg]
            [ai.miniforge.pipeline-runner.preflight.stage-checks :refer [callable?]]
            [ai.miniforge.pipeline-runner.stage-result
             :refer [stage-result timestamps exception-context]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pass-through-declared? [config]
  (true? (:stage/pass-through? config)))

(defn- ^{:stratum 0} pass-through-result
  "Completed result for a stage that declared :stage/pass-through? true. The
   :pass-through? flag stays on the stage run as the receipt that processing
   was skipped on purpose."
  [id name input-records]
  (stage-result id name :completed
                (assoc (timestamps) :records input-records :pass-through? true)))

(defn- ^{:stratum 0} unresolved-processing-result
  "Failed result for a stage that reached execution with no resolved
   processing and no pass-through declaration. Preflight refuses these before
   a run starts; a spawned stage is created mid-run, so the executor refuses
   it here."
  [id name]
  (stage-result id name :failed
                {:error-message (msg/t :run/processing-unresolved {:name name})
                 :error-kind    :configuration}))

(defn- ^{:stratum 0} configuration-failure
  "Failed result for a stage whose configuration cannot run as declared."
  [id name message-key]
  (stage-result id name :failed
                {:error-message (msg/t message-key {:name name})
                 :error-kind    :configuration}))

(defn- ^{:stratum 0} declares-processing?
  "True when the stage config declares a transform or carries a quality
   pack, whatever the stage's family."
  [config]
  (or (some? (:stage/transform config))
      (some? (:stage/quality-pack config))))

(defn- ^{:stratum 0} runnable-rules?
  "True when a quality pack's :pack/rules is a non-empty list."
  [quality-pack]
  (let [rules (:pack/rules quality-pack)]
    (and (sequential? rules) (seq rules))))

(defn- ^{:stratum 0} every-rule-callable?
  "True when every rule carries a check fn the executor can call. Checked
   before evaluation, so an unbuilt rule fails even with no input records."
  [quality-pack]
  (every? #(callable? (:rule/check-fn %)) (:pack/rules quality-pack)))

(defn- ^{:stratum 0} apply-transform
  [id name transform-fn config input-records]
  (try
    (let [result-records (transform-fn input-records config)]
      (stage-result id name :completed
                    (assoc (timestamps) :records result-records)))
    (catch Exception e
      (stage-result id name :failed (exception-context e)))))

(defn- ^{:stratum 0} rule-error-result
  "Failed result for a validate stage whose quality rules did not all run.
   An execution error, kept apart from records a rule rejected."
  [id name errors]
  (let [message (msg/t :run/quality-rule-errors {:name name :count (count errors)})]
    (stage-result id name :failed
                  {:error-message      message
                   :error-kind         :execution
                   :quality/rule-errors errors})))

(defn- ^{:stratum 0} quality-pack-result
  [id name quality-pack evaluation]
  (let [passed (dq/filter-passed evaluation)
        report (dq/generate-report (:pack/id quality-pack) evaluation)]
    (stage-result id name :completed
                  (merge (timestamps)
                         {:records passed
                          :quality-report report}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} evaluate-quality-pack
  "Evaluate the pack's rules over the input records. A rule that threw or
   returned a malformed verdict fails the stage; otherwise records that fail
   an error-severity rule are filtered out and reported."
  [id name quality-pack input-records]
  (let [evaluation (dq/evaluate-records (:pack/rules quality-pack) input-records)
        errors     (dq/rule-errors evaluation)]
    (if (seq errors)
      (rule-error-result id name errors)
      (quality-pack-result id name quality-pack evaluation))))

(defn- ^{:stratum 1} pass-through-conflict?
  "Pass-through declared beside a transform or a quality pack. Preflight
   refuses it for declared stages; a spawned stage reaches the executor
   unchecked."
  [config]
  (and (pass-through-declared? config) (declares-processing? config)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-transform-stage
  "Execute a non-connector stage (normalize, transform, aggregate, etc.).
   Calls the context :transforms function named by :stage/transform
   :transform/type. A stage that declares :stage/pass-through? true and no
   processing passes its records through. A named transform with no
   registered fn fails, pass-through or not; so does a stage declaring
   neither, or pass-through beside processing."
  [{:stage/keys [id name config]} context input-records]
  (let [transform-type (get-in config [:stage/transform :transform/type])
        registered     (when transform-type
                         (get-in context [:transforms transform-type]))
        transform-fn   (when (callable? registered) registered)]
    (cond
      (pass-through-conflict? config) (configuration-failure id name :run/pass-through-conflict)
      transform-fn                    (apply-transform id name transform-fn config input-records)
      (pass-through-declared? config) (pass-through-result id name input-records)
      :else                           (unresolved-processing-result id name))))

(defn ^{:stratum 2} execute-validate-stage
  "Execute a validate stage with its :stage/quality-pack rules. A pack with
   no list of rules, or with a rule that has no check fn, fails before any
   record is evaluated, as does pass-through beside processing. A stage that
   declares :stage/pass-through? true and no processing passes its records
   through; a stage with neither fails."
  [{:stage/keys [id name config]} input-records]
  (let [quality-pack (:stage/quality-pack config)]
    (cond
      (pass-through-conflict? config)          (configuration-failure id name :run/pass-through-conflict)
      (and (nil? quality-pack)
           (pass-through-declared? config))    (pass-through-result id name input-records)
      (nil? quality-pack)                      (unresolved-processing-result id name)
      (not (runnable-rules? quality-pack))     (configuration-failure id name :run/quality-pack-empty)
      (not (every-rule-callable? quality-pack)) (configuration-failure id name :run/quality-rule-unresolved)
      :else                                    (evaluate-quality-pack id name quality-pack input-records))))

(comment
  (execute-transform-stage {:stage/id (random-uuid) :stage/name "Normalize" :stage/config {:stage/pass-through? true}}
                           {}
                           [{:id 1}]))
