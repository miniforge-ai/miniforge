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

(ns ai.miniforge.self-healing.backend-health
  "Backend health tracking and automatic failover.
   Storage: ~/.miniforge/backend_health.edn"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;;------------------------------------------------------------------------------ Layer 0
;; File paths and utilities

(defn- backend-health-path
  "Get path to backend health tracking file.

   Returns: String path to ~/.miniforge/backend_health.edn"
  []
  (let [home (System/getProperty "user.home")
        miniforge-dir (io/file home ".miniforge")]
    (.getPath (io/file miniforge-dir "backend_health.edn"))))

(defn- ensure-directory-exists
  "Ensure parent directory exists for a file path.

   Arguments:
     file-path - String path to file

   Returns: nil"
  [file-path]
  (let [parent-dir (.getParentFile (io/file file-path))]
    (when-not (.exists parent-dir)
      (.mkdirs parent-dir))))

(defn- safe-read-edn
  "Safely read EDN from file, returning default on error.

   Arguments:
     file-path - String path to file
     default - Default value if read fails

   Returns: Parsed EDN or default"
  [file-path default]
  (try
    (when (.exists (io/file file-path))
      (edn/read-string (slurp file-path)))
    (catch Exception _
      default)))

(defn- atomic-write-edn
  "Atomically write EDN to file using temp file + rename.

   Arguments:
     file-path - String path to file
     data - Data to write as EDN

   Returns: nil"
  [file-path data]
  (ensure-directory-exists file-path)
  (let [temp-file (str file-path ".tmp")]
    (spit temp-file (pr-str data))
    (.renameTo (io/file temp-file) (io/file file-path))))

;;------------------------------------------------------------------------------ Layer 1
;; Default health data structure

(defn- default-health-data
  "Create default health data structure.

   Returns: Map with default backends, cooldowns, and fallback order"
  []
  {:backends {}
   :switch-cooldowns {}
   :default-backend :anthropic
   :fallback-order [:anthropic :openai :codex :ollama :google]})

;;------------------------------------------------------------------------------ Layer 2
;; Backend health operations

(defn load-health
  "Load backend health data from persistent storage.

   Returns: Map with :backends, :switch-cooldowns, :default-backend, :fallback-order"
  []
  (or (safe-read-edn (backend-health-path) nil)
      (default-health-data)))

(defn save-health!
  "Save backend health data to persistent storage.

   Arguments:
     health-data - Map with :backends, :switch-cooldowns, etc.

   Returns: nil"
  [health-data]
  (atomic-write-edn (backend-health-path) health-data))

(defn record-backend-call!
  "Record a backend API call and its result.

   Arguments:
     backend - Keyword backend name (:anthropic, :openai, etc.)
     success? - Boolean indicating if call succeeded

   Returns: Updated backend health map"
  [backend success?]
  (let [health (load-health)
        backend-key (keyword backend)
        current-stats (get-in health [:backends backend-key]
                              {:total-calls 0
                               :successful-calls 0
                               :success-rate 0.0
                               :last-failure nil})
        new-total (inc (:total-calls current-stats))
        new-successful (if success?
                        (inc (:successful-calls current-stats))
                        (:successful-calls current-stats))
        new-success-rate (if (> new-total 0)
                          (double (/ new-successful new-total))
                          0.0)
        updated-stats (-> current-stats
                         (assoc :total-calls new-total)
                         (assoc :successful-calls new-successful)
                         (assoc :success-rate new-success-rate)
                         (assoc :last-failure (when-not success?
                                                (java.time.Instant/now))))
        new-health (assoc-in health [:backends backend-key] updated-stats)]
    (save-health! new-health)
    updated-stats))

(defn get-backend-success-rate
  "Get current success rate for a backend.

   Arguments:
     backend - Keyword backend name

   Returns: Double success rate (0.0-1.0) or nil if no data"
  [backend]
  (let [health (load-health)
        backend-key (keyword backend)
        stats (get-in health [:backends backend-key])]
    (:success-rate stats)))

(defn should-switch-backend?
  "Check if backend should be switched due to low success rate.

   Arguments:
     backend - Keyword backend name
     threshold - Double threshold (default 0.90)

   Returns: Boolean true if should switch"
  ([backend]
   (should-switch-backend? backend 0.90))
  ([backend threshold]
   (if-let [success-rate (get-backend-success-rate backend)]
     (< success-rate threshold)
     false)))

(defn in-cooldown?
  "Check if backend is in cooldown period after a switch.

   Arguments:
     backend - Keyword backend name
     cooldown-ms - Cooldown period in milliseconds (default 1800000 = 30 min)

   Returns: Boolean true if in cooldown"
  ([backend]
   (in-cooldown? backend 1800000))
  ([backend cooldown-ms]
   (let [health (load-health)
         backend-key (keyword backend)
         cooldown-until (get-in health [:switch-cooldowns backend-key])]
     (when cooldown-until
       (let [now (java.time.Instant/now)
             cooldown-end (.plusMillis cooldown-until cooldown-ms)]
         (.isBefore now cooldown-end))))))

(defn select-best-backend
  "Select the best available backend that is not unhealthy or in cooldown.

   Arguments:
     current-backend - Keyword current backend
     threshold - Success rate threshold (default 0.90)
     cooldown-ms - Cooldown period in milliseconds (default 1800000)

   Returns: Keyword backend name or nil if none available"
  ([current-backend]
   (select-best-backend current-backend 0.90 1800000))
  ([current-backend threshold cooldown-ms]
   (let [health (load-health)
         fallback-order (:fallback-order health)
         current-key (keyword current-backend)]
     ;; Find first backend in fallback order that is:
     ;; 1. Not the current backend
     ;; 2. Not in cooldown
     ;; 3. Either has no data or has good success rate
     (first
      (filter
       (fn [backend]
         (and (not= backend current-key)
              (not (in-cooldown? backend cooldown-ms))
              (if-let [rate (get-backend-success-rate backend)]
                (>= rate threshold)
                true))) ;; No data = eligible
       fallback-order)))))

(defn trigger-backend-switch!
  "Trigger a backend switch and record cooldown.

   Arguments:
     from-backend - Keyword current backend
     to-backend - Keyword new backend
     cooldown-ms - Cooldown period in milliseconds (default 1800000)

   Returns: Map with :from, :to, :cooldown-until"
  ([from-backend to-backend]
   (trigger-backend-switch! from-backend to-backend 1800000))
  ([from-backend to-backend cooldown-ms]
   (let [health (load-health)
         now (java.time.Instant/now)
         from-key (keyword from-backend)
         to-key (keyword to-backend)
         cooldown-until now
         updated-health (-> health
                           (assoc-in [:switch-cooldowns from-key] cooldown-until)
                           (assoc :default-backend to-key))]
     (save-health! updated-health)
     {:from from-key
      :to to-key
      :cooldown-until cooldown-until
      :cooldown-ms cooldown-ms})))

;;------------------------------------------------------------------------------ Layer 3
;; High-level health check

(defn check-and-switch-if-needed
  "Check current backend health and switch if necessary.

   Arguments:
     current-backend - Keyword current backend
     threshold - Success rate threshold (default 0.90)
     cooldown-ms - Cooldown period in milliseconds (default 1800000)

   Returns: Map with :should-switch?, :from, :to, or nil if no switch"
  ([current-backend]
   (check-and-switch-if-needed current-backend 0.90 1800000))
  ([current-backend threshold cooldown-ms]
   (when (and (should-switch-backend? current-backend threshold)
              (not (in-cooldown? current-backend cooldown-ms)))
     (when-let [new-backend (select-best-backend current-backend threshold cooldown-ms)]
       (let [switch-result (trigger-backend-switch! current-backend new-backend cooldown-ms)]
         (assoc switch-result :should-switch? true))))))
