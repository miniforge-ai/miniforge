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

(ns ai.miniforge.workflow-security-compliance.phases
  "Phase interceptors for the 5-phase security compliance workflow (issue #552).

   Pipeline:
     :sec-parse-scan      — parse SARIF/CSV files into unified violations
     :sec-trace-source    — trace each violation to actual API call (LLM-assisted stub)
     :sec-verify-docs     — verify API documentation status (mixed/stub)
     :sec-classify        — classify violations as true/false/needs-investigation
     :sec-generate-exclusions — generate and write exclusion list

   Context threading:
     Results stored at [:execution/phase-results :phase-keyword :result :output]
     Subsequent phases read from prior phase results at the same path."
  (:require
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow-security-compliance.config :as config]
   [ai.miniforge.workflow-security-compliance.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Config + context helpers

(defn- register-defaults!
  "Register phase defaults for every phase declared in the workflow resource."
  []
  (doseq [phase-kw (config/registered-phase-keys)]
    (phase/register-phase-defaults! phase-kw (config/phase-defaults phase-kw))))

(defn- phase-config
  [ctx phase-kw]
  (get ctx :phase-config (config/phase-defaults phase-kw)))

(defn- enter-success
  [ctx phase-kw phase-config output]
  (let [start-time (System/currentTimeMillis)
        agent (get phase-config :agent)
        gates (get phase-config :gates [])
        budget (get phase-config :budget {})]
    (phase/enter-context ctx
                         phase-kw
                         agent
                         gates
                         budget
                         start-time
                         {:status :success
                          :output output})))

(defn- complete-phase
  [ctx phase-kw metrics]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        phase-result (get-in ctx [:phase :result])
        phase-metrics (assoc metrics :duration-ms duration-ms)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] phase-metrics)
        (assoc-in [:execution/phase-results phase-kw :result] phase-result)
        (update-in [:execution :phases-completed] (fnil conj []) phase-kw)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn- fail-phase
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase/exception-error ex))))

(register-defaults!)

;------------------------------------------------------------------------------ Layer 1
;; Phase implementation

(defn enter-sec-parse-scan
  [ctx]
  (let [config (phase-config ctx :sec-parse-scan)
        scan-paths (get-in ctx [:execution/input :scan-paths] [])
        violations (core/parse-scan-paths scan-paths)]
    (enter-success ctx :sec-parse-scan config {:violations violations})))

(defn leave-sec-parse-scan [ctx]
  (let [violations (get-in ctx [:phase :result :output :violations] [])
        violation-count (count violations)]
    (complete-phase ctx :sec-parse-scan {:violation-count violation-count})))

(defn error-sec-parse-scan [ctx ex]
  (fail-phase ctx ex))

(defn enter-sec-trace-source [ctx]
  (let [config (phase-config ctx :sec-trace-source)
        violations (get-in ctx [:execution/phase-results :sec-parse-scan :result :output :violations] [])
        traced (core/trace-violations violations)]
    (enter-success ctx :sec-trace-source config {:traced-violations traced})))

(defn leave-sec-trace-source [ctx]
  (let [traced (get-in ctx [:phase :result :output :traced-violations] [])
        traced-count (count traced)]
    (complete-phase ctx :sec-trace-source {:traced-count traced-count})))

(defn error-sec-trace-source [ctx ex]
  (fail-phase ctx ex))

(defn enter-sec-verify-docs [ctx]
  (let [phase-config (phase-config ctx :sec-verify-docs)
        traced (get-in ctx [:execution/phase-results :sec-trace-source :result :output :traced-violations] [])
        known-apis (get-in ctx [:execution/input :known-apis] (config/known-apis))
        verified (core/verify-violations traced known-apis)]
    (enter-success ctx :sec-verify-docs phase-config {:verified-violations verified})))

(defn leave-sec-verify-docs [ctx]
  (let [verified (get-in ctx [:phase :result :output :verified-violations] [])
        verified-count (count verified)
        documented-count (count (filter :verified/documented? verified))]
    (complete-phase ctx :sec-verify-docs {:verified-count verified-count
                                          :documented-count documented-count})))

(defn error-sec-verify-docs [ctx ex]
  (fail-phase ctx ex))

(defn enter-sec-classify [ctx]
  (let [config (phase-config ctx :sec-classify)
        verified (get-in ctx [:execution/phase-results :sec-verify-docs :result :output :verified-violations] [])
        classified (core/classify-violations verified)]
    (enter-success ctx :sec-classify config {:classified-violations classified})))

(defn leave-sec-classify [ctx]
  (let [classified (get-in ctx [:phase :result :output :classified-violations] [])
        true-positives (count (filter #(= :true-positive (get % :classification/category)) classified))
        false-positives (count (filter #(= :false-positive (get % :classification/category)) classified))
        needs-review (count (filter #(= :needs-investigation (get % :classification/category)) classified))]
    (complete-phase ctx :sec-classify {:true-positives true-positives
                                       :false-positives false-positives
                                       :needs-review needs-review})))

(defn error-sec-classify [ctx ex]
  (fail-phase ctx ex))

(defn enter-sec-generate-exclusions [ctx]
  (let [config (phase-config ctx :sec-generate-exclusions)
        classified (get-in ctx [:execution/phase-results :sec-classify :result :output :classified-violations] [])
        output-dir (get-in ctx [:execution/input :output-dir] ".")
        exclusion-output (core/build-exclusion-output classified)
        output-file (core/write-exclusions! output-dir exclusion-output)
        output (assoc exclusion-output :output-file output-file)]
    (enter-success ctx :sec-generate-exclusions config output)))

(defn leave-sec-generate-exclusions [ctx]
  (let [output (get-in ctx [:phase :result :output])
        excluded (get output :total-excluded 0)
        flagged (get output :total-flagged 0)]
    (complete-phase ctx :sec-generate-exclusions {:excluded excluded
                                                  :flagged flagged})))

(defn error-sec-generate-exclusions [ctx ex]
  (fail-phase ctx ex))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod phase/get-phase-interceptor-method :sec-parse-scan
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name   ::sec-parse-scan
     :config merged
     :enter  (fn [ctx] (enter-sec-parse-scan (assoc ctx :phase-config merged)))
     :leave  leave-sec-parse-scan
     :error  error-sec-parse-scan}))

(defmethod phase/get-phase-interceptor-method :sec-trace-source
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name   ::sec-trace-source
     :config merged
     :enter  (fn [ctx] (enter-sec-trace-source (assoc ctx :phase-config merged)))
     :leave  leave-sec-trace-source
     :error  error-sec-trace-source}))

(defmethod phase/get-phase-interceptor-method :sec-verify-docs
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name   ::sec-verify-docs
     :config merged
     :enter  (fn [ctx] (enter-sec-verify-docs (assoc ctx :phase-config merged)))
     :leave  leave-sec-verify-docs
     :error  error-sec-verify-docs}))

(defmethod phase/get-phase-interceptor-method :sec-classify
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name   ::sec-classify
     :config merged
     :enter  (fn [ctx] (enter-sec-classify (assoc ctx :phase-config merged)))
     :leave  leave-sec-classify
     :error  error-sec-classify}))

(defmethod phase/get-phase-interceptor-method :sec-generate-exclusions
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name   ::sec-generate-exclusions
     :config merged
     :enter  (fn [ctx] (enter-sec-generate-exclusions (assoc ctx :phase-config merged)))
     :leave  leave-sec-generate-exclusions
     :error  error-sec-generate-exclusions}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/phase-defaults :sec-parse-scan)
  (phase/phase-defaults :sec-trace-source)
  (phase/phase-defaults :sec-verify-docs)
  (phase/phase-defaults :sec-classify)
  (phase/phase-defaults :sec-generate-exclusions))
