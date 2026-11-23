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
(ns ai.miniforge.phase-deployment.shell.pulumi
  "Pulumi CLI wrappers for deployment phases. `pulumi up` applies only the
   plan a gated preview saved (`--save-plan`, then `--plan`)."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.phase-deployment.shell.exec :as exec]
            [ai.miniforge.phase-deployment.shell.timeouts :as timeouts]
            [ai.miniforge.schema.interface :as schema]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

;; Pulumi wrappers
(defn ^{:stratum 0} pulumi!
  "Execute a Pulumi CLI command."
  [subcommand stack-dir & {:keys [stack json? yes? extra-args timeout-ms env]
                           :or {json? (contains? #{"preview" "up" "destroy" "stack"} subcommand)
                                yes? false}}]
  (let [args (cond-> [subcommand]
               stack      (into ["--stack" stack])
               json?      (conj "--json")
               yes?       (conj "--yes")
               extra-args (into extra-args))
        result (exec/sh-with-timeout "pulumi"
                                     args
                                     :dir stack-dir
                                     :timeout-ms (or timeout-ms (get timeouts/timeouts :pulumi-default-ms 900000))
                                     :env env)]
    (if json?
      (exec/with-parsed-json result)
      result)))

(defn ^{:stratum 0} saved-plan?
  "True when `plan-file` names a non-empty file: the plan a preview saved."
  [plan-file]
  (let [file (some-> plan-file io/file)]
    (boolean (and file (.isFile file) (pos? (.length file))))))

(defn ^{:stratum 0} plan-missing
  "The failure `pulumi-up!` returns, without running anything, when there is
   no saved plan to apply."
  [plan-file]
  (let [message (msg/t :shell/pulumi-plan-missing {:plan-file (str plan-file)})]
    (schema/failure :stdout message {:stderr message
                                     :exit-code -1
                                     :duration-ms 0
                                     :command "pulumi up"
                                     :error-type :plan-missing})))

(defn ^{:stratum 0} pulumi-outputs!
  [stack-dir & {:keys [stack]}]
  (let [args (cond-> ["stack" "output" "--json"]
               stack (into ["--stack" stack]))]
    (exec/with-parsed-json
     (exec/sh-with-timeout "pulumi" args :dir stack-dir
                           :timeout-ms (get timeouts/timeouts :pulumi-outputs-ms 30000)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pulumi-preview!
  "Preview the stack. With `:plan-file`, Pulumi also saves the proposed
   operations there (`--save-plan`), for `pulumi-up!` to apply."
  [stack-dir & {:keys [stack plan-file extra-args timeout-ms env]}]
  (pulumi! "preview"
           stack-dir
           :stack stack
           :json? true
           :extra-args (cond-> (vec extra-args) plan-file (into ["--save-plan" plan-file]))
           :timeout-ms timeout-ms
           :env env))

(defn ^{:stratum 1} pulumi-up!
  "Apply exactly the plan a preview saved: `pulumi up --plan <plan-file>`.
   Pulumi refuses any operation the plan does not contain. Without a saved
   plan at `plan-file` this returns a failure and runs nothing."
  [stack-dir & {:keys [stack plan-file extra-args timeout-ms env]}]
  (if-not (saved-plan? plan-file)
    (plan-missing plan-file)
    (pulumi! "up"
             stack-dir
             :stack stack
             :json? true
             :yes? true
             :extra-args (into ["--plan" plan-file] extra-args)
             :timeout-ms timeout-ms
             :env env)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (pulumi-preview! "/path/to/project" :stack "dev")
  :leave-this-here)
