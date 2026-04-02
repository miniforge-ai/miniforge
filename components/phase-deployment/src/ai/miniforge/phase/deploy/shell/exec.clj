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

(ns ai.miniforge.phase.deploy.shell.exec
  "Shared deployment shell execution helpers."
  (:require [ai.miniforge.phase.deploy.messages :as msg]
            [ai.miniforge.schema.interface :as schema]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Command result schemas + parsing

(def CommandResult
  [:map
   [:success? :boolean]
   [:stdout [:maybe :string]]
   [:stderr :string]
   [:exit-code integer?]
   [:duration-ms integer?]
   [:command :string]
   [:error-type {:optional true} keyword?]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(def ParsedCommandResult
  [:map
   [:success? :boolean]
   [:stdout [:maybe :string]]
   [:stderr :string]
   [:exit-code integer?]
   [:duration-ms integer?]
   [:command :string]
   [:parsed {:optional true} any?]
   [:error-type {:optional true} keyword?]
   [:error {:optional true} any?]
   [:anomaly {:optional true} map?]])

(defn- validate-result!
  [result-schema result]
  (schema/validate result-schema result))

(defn with-parsed-json
  "Add :parsed to a command result when stdout contains JSON."
  [result]
  (validate-result!
   ParsedCommandResult
   (if-let [parsed (when (and (string? (:stdout result))
                              (not (str/blank? (:stdout result))))
                     (try
                       (let [parse-fn (requiring-resolve 'cheshire.core/parse-string)]
                         (parse-fn (:stdout result) true))
                       (catch Exception _ nil)))]
     (assoc result :parsed parsed)
     result)))

(defn- command-failure
  [error-message extra]
  (validate-result!
   CommandResult
   (schema/failure :stdout error-message extra)))

;------------------------------------------------------------------------------ Layer 1
;; Shell execution + classification

(defn sh-with-timeout
  "Execute a shell command with timeout and structured results."
  [cmd args & {:keys [dir timeout-ms env]
               :or {timeout-ms 300000}}]
  (let [start-time (System/currentTimeMillis)
        full-cmd   (into [cmd] args)
        cmd-str    (str/join " " full-cmd)
        sh-args    (cond-> full-cmd
                     dir (into [:dir dir])
                     env (into [:env (merge (into {} (System/getenv)) env)]))]
    (try
      (let [future-result (future (apply shell/sh sh-args))
            result        (deref future-result timeout-ms ::timeout)]
        (if (= result ::timeout)
          (do
            (future-cancel future-result)
            (command-failure
             (msg/t :shell/command-timeout {:timeout-ms timeout-ms})
             {:stderr      (msg/t :shell/command-timeout {:timeout-ms timeout-ms})
              :exit-code   -1
              :duration-ms timeout-ms
              :command     cmd-str
              :error-type  :timeout}))
          (let [duration (- (System/currentTimeMillis) start-time)
                stdout   (get result :out "")
                stderr   (get result :err "")
                exit     (:exit result)]
            (if (zero? exit)
              (validate-result!
               CommandResult
               (schema/success :stdout stdout
                               {:stderr      stderr
                                :exit-code   exit
                                :duration-ms duration
                                :command     cmd-str}))
              (command-failure
               (msg/t :shell/command-failed-exit {:exit-code exit})
               {:stderr      stderr
                :exit-code   exit
                :duration-ms duration
                :command     cmd-str})))))
      (catch Exception e
        (command-failure
         (msg/t :shell/execution-failed {:error (ex-message e)})
         {:stderr      (msg/t :shell/execution-failed {:error (ex-message e)})
          :exit-code   -1
          :duration-ms (- (System/currentTimeMillis) start-time)
          :command     cmd-str
          :error-type  :execution-error})))))

(defn classify-error
  "Classify a shell execution error for retry decisions."
  [result]
  (let [stderr (str (get result :stderr "") (get result :stdout ""))]
    (cond
      (re-find #"(?i)rate.?limit|quota|429|503|timeout|DEADLINE_EXCEEDED" stderr)
      :transient

      (re-find #"(?i)conflict|lock|already.?in.?progress|ConcurrentAccessError" stderr)
      :state-lock

      (re-find #"(?i)unauthorized|unauthenticated|403|credential|permission.?denied" stderr)
      :permanent

      (re-find #"(?i)invalid|not.?found|does.?not.?exist|missing.?required" stderr)
      :permanent

      :else
      :transient)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (sh-with-timeout "echo" ["hello world"])
  (classify-error {:stderr "429 rate limit" :stdout ""})
  :leave-this-here)
