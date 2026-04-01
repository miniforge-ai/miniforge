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

(ns ai.miniforge.tui-engine.log
  "Minimal file-based logger for the TUI engine.

   Writes structured log lines to ~/.miniforge/logs/tui.log.
   Each line is: ISO-timestamp LEVEL message [exception-detail]

   Designed to be zero-dependency and safe to call from any thread,
   including the render watcher and input polling thread. All writes
   are wrapped in try/catch so logging can never crash the TUI.

   Usage:
     (log/error \"render failed\" e)   ;; with exception
     (log/warn  \"unexpected input\")  ;; message only
     (log/info  \"started\")

   Layer 0: No dependencies on other tui namespaces."
  (:require
   [clojure.string :as str])
  (:import
   [java.io File FileWriter BufferedWriter]
   [java.time Instant]
   [java.time.format DateTimeFormatter]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Log file setup

(def ^:private log-dir
  (str (System/getProperty "user.home") "/.miniforge/logs"))

(def ^:private log-path
  (str log-dir "/tui.log"))

(def ^:private fmt
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))

(defn- ensure-log-dir! []
  (let [d (File. ^String log-dir)]
    (when-not (.exists d)
      (.mkdirs d))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Core write

(defn- format-exception [^Throwable e]
  (when e
    (let [msg  (str (class e) ": " (.getMessage e))
          frames (->> (.getStackTrace e)
                      (take 12)
                      (map str)
                      (str/join "\n    "))]
      (str "\n  " msg "\n    " frames))))

(defn- write-line! [level msg ^Throwable e]
  (try
    (ensure-log-dir!)
    (let [ts  (.format fmt (Instant/now))
          ex  (format-exception e)
          line (str ts " " level " " msg (or ex "") "\n")]
      (with-open [w (BufferedWriter. (FileWriter. ^String log-path true))]
        (.write w ^String line)))
    (catch Throwable _)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Public API

(defn error
  "Log an error, optionally with an exception."
  ([msg] (write-line! "ERROR" msg nil))
  ([msg ^Throwable e] (write-line! "ERROR" msg e)))

(defn warn
  "Log a warning."
  ([msg] (write-line! "WARN " msg nil))
  ([msg ^Throwable e] (write-line! "WARN " msg e)))

(defn info
  "Log an informational message."
  [msg]
  (write-line! "INFO " msg nil))

(defn debug
  "Log a debug message. No-op unless the tui.debug system property is set."
  [msg]
  (when (System/getProperty "tui.debug")
    (write-line! "DEBUG" msg nil)))
