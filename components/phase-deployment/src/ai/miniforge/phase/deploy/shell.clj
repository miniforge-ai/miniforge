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

(ns ai.miniforge.phase.deploy.shell
  "CLI execution utilities for deployment tool invocation.

   Wraps clojure.java.shell/sh with timeout support, structured results,
   and JSON/YAML output parsing. Provides typed wrappers for pulumi, kubectl,
   and kustomize."
  (:require [ai.miniforge.schema.interface :as schema]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0
;; Core execution + output parsing

(defn sh-with-timeout
  "Execute shell command with timeout and structured result.

   Arguments:
     cmd        - Command string (e.g. \"pulumi\")
     args       - Vector of argument strings
     opts       - Optional map:
                  :dir        - Working directory (default: current)
                  :timeout-ms - Timeout in milliseconds (default: 300000 = 5min)
                  :env        - Environment variable overrides map

   Returns:
     {:success?    boolean
      :stdout      string
      :stderr      string
      :exit-code   int
      :duration-ms long
      :command     string (for evidence/audit)}"
  [cmd args & {:keys [dir timeout-ms env]
               :or {timeout-ms 300000}}]
  (let [start-time (System/currentTimeMillis)
        full-cmd   (into [cmd] args)
        cmd-str    (str/join " " full-cmd)
        sh-args    (cond-> full-cmd
                     dir (into [:dir dir])
                     env (into [:env (merge (into {} (System/getenv)) env)]))]
    (try
      (let [;; Launch process with timeout
            future-result (future (apply shell/sh sh-args))
            result        (deref future-result timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do (future-cancel future-result)
              (schema/failure :stdout (str "Command timed out after " timeout-ms "ms")
                              {:stderr      (str "Command timed out after " timeout-ms "ms")
                               :exit-code   -1
                               :duration-ms timeout-ms
                               :command     cmd-str
                               :error-type  :timeout}))
          (let [duration (- (System/currentTimeMillis) start-time)
                stdout   (get result :out "")
                stderr   (get result :err "")
                exit     (:exit result)]
            (if (zero? exit)
              (schema/success :stdout stdout
                              {:stderr      stderr
                               :exit-code   exit
                               :duration-ms duration
                               :command     cmd-str})
              (schema/failure :stdout (str "Command failed with exit code " exit)
                              {:stderr      stderr
                               :exit-code   exit
                               :duration-ms duration
                               :command     cmd-str})))))
      (catch Exception e
        (schema/failure :stdout (str "Execution failed: " (ex-message e))
                        {:stderr      (str "Execution failed: " (ex-message e))
                         :exit-code   -1
                         :duration-ms (- (System/currentTimeMillis) start-time)
                         :command     cmd-str
                         :error-type  :execution-error})))))

;------------------------------------------------------------------------------ Layer 1
;; Output parsing + CLI wrappers

(defn- try-parse-json
  "Attempt to parse JSON output. Returns parsed data or nil on failure."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try
      ;; Use requiring-resolve to avoid hard dep on cheshire
      (let [parse-fn (requiring-resolve 'cheshire.core/parse-string)]
        (parse-fn s true))
      (catch Exception _ nil))))

(defn- with-parsed-json
  "Add :parsed key to result if stdout is valid JSON."
  [result]
  (if-let [parsed (try-parse-json (:stdout result))]
    (assoc result :parsed parsed)
    result))

;; Pulumi CLI wrapper

(defn pulumi!
  "Execute Pulumi CLI command.

   Arguments:
     subcommand - Pulumi subcommand (\"preview\", \"up\", \"stack\", \"destroy\")
     stack-dir  - Directory containing Pulumi.yaml
     opts       - Optional map:
                  :stack       - Stack name (e.g. \"dev\", \"prod\")
                  :json?       - Request JSON output (default: true for preview/up)
                  :yes?        - Auto-approve (skip confirmation; default: false)
                  :extra-args  - Additional CLI arguments vector
                  :timeout-ms  - Override default timeout
                  :env         - Extra environment variables

   Returns:
     sh-with-timeout result, with :parsed key if JSON output available."
  [subcommand stack-dir & {:keys [stack json? yes? extra-args timeout-ms env]
                           :or {json? (contains? #{"preview" "up" "destroy" "stack"} subcommand)
                                yes? false}}]
  (let [args (cond-> [subcommand]
               stack      (into ["--stack" stack])
               json?      (conj "--json")
               yes?       (conj "--yes")
               extra-args (into extra-args))
        result (sh-with-timeout "pulumi" args
                                :dir stack-dir
                                :timeout-ms (or timeout-ms 900000) ;; 15 min default for infra
                                :env env)]
    (if json?
      (with-parsed-json result)
      result)))

(defn pulumi-preview!
  "Execute pulumi preview with JSON output.
   Convenience wrapper around pulumi! for the most common operation."
  [stack-dir & {:keys [stack extra-args timeout-ms env]}]
  (pulumi! "preview" stack-dir
           :stack stack :json? true
           :extra-args extra-args :timeout-ms timeout-ms :env env))

(defn pulumi-up!
  "Execute pulumi up with auto-approve and JSON output."
  [stack-dir & {:keys [stack extra-args timeout-ms env]}]
  (pulumi! "up" stack-dir
           :stack stack :json? true :yes? true
           :extra-args extra-args :timeout-ms timeout-ms :env env))

(defn pulumi-outputs!
  "Get stack outputs as JSON."
  [stack-dir & {:keys [stack]}]
  (let [args (cond-> ["stack" "output" "--json"]
               stack (into ["--stack" stack]))]
    (with-parsed-json
      (sh-with-timeout "pulumi" args :dir stack-dir :timeout-ms 30000))))

;; Kubectl wrapper

(defn kubectl!
  "Execute kubectl command.

   Arguments:
     subcommand - kubectl subcommand (\"apply\", \"get\", \"rollout\", etc.)
     opts       - Optional map:
                  :namespace  - K8s namespace
                  :context    - K8s context name
                  :output     - Output format (\"json\", \"yaml\", \"wide\")
                  :extra-args - Additional arguments
                  :timeout-ms - Override default timeout
                  :stdin      - String to pipe as stdin

   Returns:
     sh-with-timeout result, with :parsed if JSON output."
  [subcommand & {:keys [namespace context output extra-args timeout-ms]
                 :or {timeout-ms 120000}}]
  (let [args (cond-> [subcommand]
               namespace  (into ["--namespace" namespace])
               context    (into ["--context" context])
               output     (into ["-o" output])
               extra-args (into extra-args))
        result (sh-with-timeout "kubectl" args :timeout-ms timeout-ms)]
    (if (= output "json")
      (with-parsed-json result)
      result)))

(defn kubectl-rollout-status!
  "Wait for a rollout to complete.

   Arguments:
     resource  - Resource spec (e.g. \"deployment/myapp\")
     opts      - :namespace, :context, :timeout-s (default 300)"
  [resource & {:keys [namespace context timeout-s]
               :or {timeout-s 300}}]
  (kubectl! "rollout"
            :namespace namespace
            :context context
            :extra-args ["status" resource (str "--timeout=" timeout-s "s")]
            :timeout-ms (* (+ timeout-s 30) 1000)))

(defn kubectl-get-pods!
  "Get pods matching a label selector as JSON.

   Arguments:
     selector  - Label selector (e.g. \"app=myapp\")
     opts      - :namespace, :context"
  [selector & {:keys [namespace context]}]
  (kubectl! "get"
            :namespace namespace
            :context context
            :output "json"
            :extra-args ["pods" "-l" selector]))

;; Kustomize wrapper

(defn kustomize-build!
  "Render Kustomize overlay to YAML.

   Arguments:
     kustomize-dir - Directory containing kustomization.yaml

   Returns:
     sh-with-timeout result with rendered YAML in :stdout"
  [kustomize-dir & {:keys [timeout-ms] :or {timeout-ms 60000}}]
  (sh-with-timeout "kustomize" ["build" kustomize-dir] :timeout-ms timeout-ms))

(defn kustomize-apply!
  "Build and apply Kustomize overlay to cluster.
   Equivalent to: kustomize build <dir> | kubectl apply -f -

   Arguments:
     kustomize-dir - Directory containing kustomization.yaml
     opts          - :namespace, :context, :dry-run? (default false)

   Returns:
     {:success? bool :build-result <kustomize-build-result> :apply-result <kubectl-result>
      :rendered-yaml string (the manifests that were/would be applied)}"
  [kustomize-dir & {:keys [namespace context dry-run?]}]
  (let [build-result (kustomize-build! kustomize-dir)]
    (if (schema/failed? build-result)
      (schema/failure :rendered-yaml (str "kustomize build failed: " (:stderr build-result))
                      {:build-result  build-result
                       :apply-result  nil})
      (let [rendered-yaml (:stdout build-result)
            apply-args    (cond-> ["apply" "-f" "-"]
                            namespace  (into ["--namespace" namespace])
                            context    (into ["--context" context])
                            dry-run?   (conj "--dry-run=client"))
            ;; Pipe rendered YAML to kubectl via a temp file
            ;; (shell/sh doesn't support stdin piping directly)
            tmp-file      (File/createTempFile "kustomize-" ".yaml")
            _             (spit tmp-file rendered-yaml)
            apply-result  (sh-with-timeout "kubectl"
                                           (-> (subvec apply-args 0 1)
                                               (into ["-f" (.getAbsolutePath tmp-file)])
                                               (into (subvec apply-args 3)))
                                           :timeout-ms 120000)]
        (try (.delete tmp-file) (catch Exception _))
        (if (schema/succeeded? apply-result)
          (schema/success :rendered-yaml rendered-yaml
                          {:build-result build-result
                           :apply-result apply-result})
          (schema/failure :rendered-yaml (or (:stderr apply-result) "kubectl apply failed")
                          {:build-result build-result
                           :apply-result apply-result}))))))

;------------------------------------------------------------------------------ Layer 2
;; Error classification

(defn classify-error
  "Classify a shell execution error for retry decisions.

   Returns:
     :transient  - Retryable (rate limit, quota, network timeout)
     :state-lock - Retryable with delay (concurrent state modification)
     :permanent  - Not retryable (auth, invalid config, missing resource)"
  [result]
  (let [stderr (str (:stderr result) (:stdout result))]
    (cond
      ;; Transient / retryable
      (re-find #"(?i)rate.?limit|quota|429|503|timeout|DEADLINE_EXCEEDED" stderr)
      :transient

      ;; State lock (Pulumi/Terraform concurrent access)
      (re-find #"(?i)conflict|lock|already.?in.?progress|ConcurrentAccessError" stderr)
      :state-lock

      ;; Auth failures
      (re-find #"(?i)unauthorized|unauthenticated|403|credential|permission.?denied" stderr)
      :permanent

      ;; Config/validation errors
      (re-find #"(?i)invalid|not.?found|does.?not.?exist|missing.?required" stderr)
      :permanent

      ;; Default to transient (safer to retry)
      :else :transient)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test shell execution
  (sh-with-timeout "echo" ["hello world"])

  ;; Test pulumi preview (dry run)
  (pulumi-preview! "/path/to/project" :stack "dev")

  ;; Test kubectl
  (kubectl! "get" :extra-args ["pods"] :namespace "default" :output "json")

  ;; Test kustomize
  (kustomize-build! "/path/to/overlay")

  :leave-this-here)
