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
(ns ai.miniforge.cli.main.util
  "Dependency-light leaf helpers shared across `ai.miniforge.cli.main` and
   its sibling command-dispatch namespaces: process/time/opts primitives,
   the optional-composition-var late-binding helper, and status-label
   formatting. Optional loading preserves fatal causes across exception wrappers."
  (:require
   [babashka.fs :as fs]
   [ai.miniforge.cli.messages :as messages]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fatal? [failure]
  (or (instance? Error failure) (instance? InterruptedException failure)))

(defn- ^{:stratum 0} rethrow-fatal! [failure]
  (when (instance? InterruptedException failure) (.interrupt (Thread/currentThread)))
  (throw failure))

(defn ^{:stratum 0} caught-message
  [caught throwable]
  (cond
    (instance? Throwable caught)
    (or (.getMessage ^Throwable caught)
        (some-> caught class .getName)
        (messages/t :error/unknown-exception))

    throwable
    (or (.getMessage ^Throwable throwable)
        (some-> throwable class .getName)
        (messages/t :error/unknown-exception))

    :else
    (str caught)))

(defn ^{:stratum 0} current-time-ms
  "Current epoch time in milliseconds. Public so CLI tests can rebind it."
  []
  (System/currentTimeMillis))

(defn ^{:stratum 0} get-opts
  "Extract opts from dispatch result."
  [m]
  (if (contains? m :opts)
    (:opts m)
    m))

(defn ^{:stratum 0} check-command
  "Check if a command is available."
  [cmd]
  (boolean (fs/which cmd)))

(defn ^{:stratum 0} timestamp->epoch-ms
  [timestamp]
  (cond
    (instance? java.util.Date timestamp)
    (.getTime ^java.util.Date timestamp)

    (instance? java.time.Instant timestamp)
    (.toEpochMilli ^java.time.Instant timestamp)

    (string? timestamp)
    (try+
      (.toEpochMilli (java.time.Instant/parse timestamp))
      (catch Exception _ nil))

    :else nil))

(defn ^{:stratum 0} status-label
  [status]
  (messages/t (keyword "status" (str "value-" (name status)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fatal-cause [failure]
  (some #(when (fatal? %) %) (take-while some? (iterate ex-cause failure))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} optional-composition-var
  "Resolve optional product components; preserve fatal and interrupted loading failures."
  [ns-sym var-sym]
  (try+
    (require ns-sym)
    (ns-resolve ns-sym var-sym)
    (catch Object _
      (when-let [fatal (fatal-cause (:throwable &throw-context))]
        (rethrow-fatal! fatal)))))

(comment
  ::optional-composition-var)
