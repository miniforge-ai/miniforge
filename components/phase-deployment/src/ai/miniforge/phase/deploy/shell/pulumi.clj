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

(ns ai.miniforge.phase.deploy.shell.pulumi
  "Pulumi CLI wrappers for deployment phases."
  (:require [ai.miniforge.phase.deploy.shell.exec :as exec]))

;------------------------------------------------------------------------------ Layer 0
;; Pulumi wrappers

(defn pulumi!
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
                                     :timeout-ms (or timeout-ms 900000)
                                     :env env)]
    (if json?
      (exec/with-parsed-json result)
      result)))

(defn pulumi-preview!
  [stack-dir & {:keys [stack extra-args timeout-ms env]}]
  (pulumi! "preview"
           stack-dir
           :stack stack
           :json? true
           :extra-args extra-args
           :timeout-ms timeout-ms
           :env env))

(defn pulumi-up!
  [stack-dir & {:keys [stack extra-args timeout-ms env]}]
  (pulumi! "up"
           stack-dir
           :stack stack
           :json? true
           :yes? true
           :extra-args extra-args
           :timeout-ms timeout-ms
           :env env))

(defn pulumi-outputs!
  [stack-dir & {:keys [stack]}]
  (let [args (cond-> ["stack" "output" "--json"]
               stack (into ["--stack" stack]))]
    (exec/with-parsed-json
     (exec/sh-with-timeout "pulumi" args :dir stack-dir :timeout-ms 30000))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (pulumi-preview! "/path/to/project" :stack "dev")
  :leave-this-here)
