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
(ns ai.miniforge.agent-environment.file-ledger-writer
  "The child process for the file ledger's cross-process tests. `race`
   reads the key's revision and prints it, waits for a line on stdin, then
   writes against what it read and prints the outcome; `read` prints what
   the store holds for the key."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} outcome [result] (if (anomaly/anomaly? result) (anomaly/subtype result) result))

(defn- ^{:stratum 0} emit!
  [value]
  (println (pr-str value))
  (flush))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} race
  [s k]
  (let [seen (store/read-entry s k)]
    (emit! {:read (:ledger/revision seen)})
    (read-line)
    (emit! (outcome (store/write-entry! s k (:ledger/revision seen) {:entry/writer "race"})))))

(defn- ^{:stratum 1} show [s k] (emit! (outcome (store/read-entry s k))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} -main
  [mode dir key-edn]
  (let [s (env/file-ledger dir)
        k (edn/read-string key-edn)]
    (case mode
      "race" (race s k)
      "read" (show s k))
    (shutdown-agents)))
