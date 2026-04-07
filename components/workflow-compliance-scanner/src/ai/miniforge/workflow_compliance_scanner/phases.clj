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

(ns ai.miniforge.workflow-compliance-scanner.phases
  "Phase interceptors for the compliance scan and execute workflows.

   Four pure-code phases — no LLM invocation:
     :compliance-scan     — scan repo for violations via scanner-registry
     :compliance-classify — classify each violation as auto-fixable or needs-review
     :compliance-plan     — generate delta report + remediation work spec
     :compliance-execute  — apply auto-fixable fixes, commit, open PRs per rule

   Context threading:
     :compliance-scan     stores :output at [:execution/phase-results :compliance-scan :result :output]
     :compliance-classify reads from that path, stores classified violations
     :compliance-plan     reads classified violations, writes files, stores plan
     :compliance-execute  reads plan, applies fixes, commits, creates PRs"
  (:require
   [ai.miniforge.compliance-scanner.interface :as compliance-scanner]
   [ai.miniforge.phase.phase-result           :as phase-result]
   [ai.miniforge.phase.registry               :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- resolve-repo-path
  "Resolve the repository path from context.
   Prefers explicit [:execution/input :repo-path] when provided (cross-repo scan),
   then :execution/worktree-path (isolation), then '.'."
  [ctx]
  (or (get-in ctx [:execution/input :repo-path])
      (get ctx :execution/worktree-path)
      "."))

(defn- resolve-standards-path
  "Resolve the standards path from context.
   Prefers [:execution/input :standards-path], then '.standards'."
  [ctx]
  (or (get-in ctx [:execution/input :standards-path])
      ".standards"))

(defn- resolve-rules
  "Resolve which rules to apply from context input.
   Defaults to :always-apply."
  [ctx]
  (get-in ctx [:execution/input :rules] :always-apply))

;------------------------------------------------------------------------------ Layer 0
;; Default configs

(def default-scan-config
  {:agent nil
   :gates []
   :budget {:tokens 5000 :iterations 1 :time-seconds 300}})

(def default-classify-config
  {:agent nil
   :gates []
   :budget {:tokens 1000 :iterations 1 :time-seconds 60}})

(def default-plan-config
  {:agent nil
   :gates []
   :budget {:tokens 5000 :iterations 1 :time-seconds 120}})

(def default-execute-config
  {:agent nil
   :gates []
   :budget {:tokens 5000 :iterations 1 :time-seconds 1800}})

;; Register defaults on load
(registry/register-phase-defaults! :compliance-scan     default-scan-config)
(registry/register-phase-defaults! :compliance-classify default-classify-config)
(registry/register-phase-defaults! :compliance-plan     default-plan-config)
(registry/register-phase-defaults! :compliance-execute  default-execute-config)

;------------------------------------------------------------------------------ Layer 1
;; :compliance-scan interceptor

(defn enter-compliance-scan
  "Execute compliance scan phase.

   Calls compliance-scanner/scan and stores ScanResult in context."
  [ctx]
  (let [start-time     (System/currentTimeMillis)
        repo-path      (resolve-repo-path ctx)
        standards-path (resolve-standards-path ctx)
        rules          (resolve-rules ctx)
        scan-result    (compliance-scanner/scan repo-path standards-path {:rules rules})]
    (phase-result/enter-context ctx :compliance-scan nil [] default-scan-config start-time
                                {:status :success :output scan-result})))

(defn leave-compliance-scan
  "Post-processing for compliance scan phase.

   Records violation count in metrics."
  [ctx]
  (let [start-time    (get-in ctx [:phase :started-at])
        end-time      (System/currentTimeMillis)
        duration-ms   (- end-time start-time)
        scan-result   (get-in ctx [:phase :result :output])
        violation-count (count (get scan-result :violations []))]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:violation-count violation-count
                                     :duration-ms     duration-ms})
        (assoc-in [:execution/phase-results :compliance-scan :result]
                  (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :compliance-scan)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-compliance-scan
  "Handle compliance scan phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; :compliance-classify interceptor

(defn enter-compliance-classify
  "Execute compliance classify phase.

   Reads violations from scan phase results, classifies them, stores output."
  [ctx]
  (let [start-time  (System/currentTimeMillis)
        violations  (get-in ctx [:execution/phase-results :compliance-scan :result :output :violations] [])
        classified  (compliance-scanner/classify violations)]
    (phase-result/enter-context ctx :compliance-classify nil [] default-classify-config start-time
                                {:status :success :output {:classified-violations classified}})))

(defn leave-compliance-classify
  "Post-processing for compliance classify phase.

   Records auto-fixable and needs-review counts in metrics."
  [ctx]
  (let [start-time    (get-in ctx [:phase :started-at])
        end-time      (System/currentTimeMillis)
        duration-ms   (- end-time start-time)
        classified    (get-in ctx [:phase :result :output :classified-violations] [])
        auto-fixable  (count (filter :auto-fixable? classified))
        needs-review  (count (remove :auto-fixable? classified))]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:auto-fixable  auto-fixable
                                     :needs-review  needs-review
                                     :duration-ms   duration-ms})
        (assoc-in [:execution/phase-results :compliance-classify :result]
                  (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :compliance-classify)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-compliance-classify
  "Handle compliance classify phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; :compliance-plan interceptor

(defn enter-compliance-plan
  "Execute compliance plan phase.

   Reads classified violations, generates plan, writes work spec and delta
   report to disk, stores plan output in context."
  [ctx]
  (let [start-time     (System/currentTimeMillis)
        repo-path      (resolve-repo-path ctx)
        standards-path (resolve-standards-path ctx)
        classified     (get-in ctx [:execution/phase-results :compliance-classify :result :output :classified-violations] [])
        the-plan       (compliance-scanner/plan classified repo-path)]
    (compliance-scanner/write-work-spec! (:work-spec the-plan) repo-path)
    (compliance-scanner/write-delta-report! repo-path standards-path classified the-plan)
    (phase-result/enter-context ctx :compliance-plan nil [] default-plan-config start-time
                                {:status :success
                                 :output {:plan       the-plan
                                          :task-count (count (:dag-tasks the-plan))}})))

(defn leave-compliance-plan
  "Post-processing for compliance plan phase.

   Records task count in metrics."
  [ctx]
  (let [start-time  (get-in ctx [:phase :started-at])
        end-time    (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        task-count  (get-in ctx [:phase :result :output :task-count] 0)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:task-count  task-count
                                     :duration-ms duration-ms})
        (assoc-in [:execution/phase-results :compliance-plan :result]
                  (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :compliance-plan)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-compliance-plan
  "Handle compliance plan phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 1
;; :compliance-execute interceptor

(defn enter-compliance-execute
  "Execute compliance execute phase.
   Reads the plan from phase results, applies auto-fixable violations to files
   in the worktree, commits per rule, and opens one GitHub PR per rule."
  [ctx]
  (let [start-time (System/currentTimeMillis)
        repo-path  (resolve-repo-path ctx)
        plan       (get-in ctx [:execution/phase-results :compliance-plan :result :output :plan])
        result     (compliance-scanner/execute! plan repo-path)]
    (phase-result/enter-context ctx :compliance-execute nil [] default-execute-config start-time
                                {:status :success :output result})))

(defn leave-compliance-execute
  "Post-processing for compliance execute phase.
   Records PR count and violations-fixed in metrics."
  [ctx]
  (let [start-time       (get-in ctx [:phase :started-at])
        end-time         (System/currentTimeMillis)
        duration-ms      (- end-time start-time)
        output           (get-in ctx [:phase :result :output])
        pr-count         (count (get output :prs []))
        violations-fixed (get output :violations-fixed 0)
        files-changed    (get output :files-changed 0)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] {:pr-count         pr-count
                                     :violations-fixed violations-fixed
                                     :files-changed    files-changed
                                     :duration-ms      duration-ms})
        (assoc-in [:execution/phase-results :compliance-execute :result]
                  (get-in ctx [:phase :result]))
        (update-in [:execution :phases-completed] (fnil conj []) :compliance-execute)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-compliance-execute
  "Handle compliance execute phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] (phase-result/exception-error ex))))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod registry/get-phase-interceptor :compliance-scan
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::compliance-scan
     :config merged
     :enter  (fn [ctx] (enter-compliance-scan (assoc ctx :phase-config merged)))
     :leave  leave-compliance-scan
     :error  error-compliance-scan}))

(defmethod registry/get-phase-interceptor :compliance-classify
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::compliance-classify
     :config merged
     :enter  (fn [ctx] (enter-compliance-classify (assoc ctx :phase-config merged)))
     :leave  leave-compliance-classify
     :error  error-compliance-classify}))

(defmethod registry/get-phase-interceptor :compliance-plan
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::compliance-plan
     :config merged
     :enter  (fn [ctx] (enter-compliance-plan (assoc ctx :phase-config merged)))
     :leave  leave-compliance-plan
     :error  error-compliance-plan}))

(defmethod registry/get-phase-interceptor :compliance-execute
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name   ::compliance-execute
     :config merged
     :enter  (fn [ctx] (enter-compliance-execute (assoc ctx :phase-config merged)))
     :leave  leave-compliance-execute
     :error  error-compliance-execute}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Check registered defaults
  (registry/phase-defaults :compliance-scan)
  (registry/phase-defaults :compliance-classify)
  (registry/phase-defaults :compliance-plan)
  (registry/phase-defaults :compliance-execute)

  ;; Get interceptors
  (registry/get-phase-interceptor {:phase :compliance-scan})
  (registry/get-phase-interceptor {:phase :compliance-classify})
  (registry/get-phase-interceptor {:phase :compliance-plan})
  (registry/get-phase-interceptor {:phase :compliance-execute})

  :leave-this-here)
