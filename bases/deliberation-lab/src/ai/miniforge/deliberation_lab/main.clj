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
(ns ai.miniforge.deliberation-lab.main
  "Command-line entry point of the standalone development lab. `smoke` runs
   offline with the mock call; `run` makes real model calls; `verify`
   checks a journal. This is the process boundary: results and anomalies
   print as data and an anomaly exits non-zero."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.interface :as harness]
   [ai.miniforge.deliberation-lab.adapter :as adapter]
   [ai.miniforge.deliberation-lab.lab :as lab]
   [ai.miniforge.deliberation-lab.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} failure-exit-code 1)

(def ^{:stratum 0} usage-exit-code 2)

(defn- ^{:stratum 0} read-manifest [path]
  (harness/read-edn (slurp path)))

(defn- ^{:stratum 0} call-for
  "The model call for `command`: the offline mock for smoke runs, the
   isolated Claude client otherwise."
  [command manifest]
  (if (= "smoke" command)
    (lab/mock-call (harness/tasks))
    (adapter/claude-call manifest)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} experiment
  "Run `command` (`smoke` or `run`) for the manifest at `manifest-path`
   into the new directory `output`."
  [command manifest-path output]
  (let [manifest (read-manifest manifest-path)]
    (if (anomaly/anomaly? manifest)
      manifest
      (lab/execute! (assoc manifest :adapter-kind (if (= "smoke" command) :mock :claude-cli))
                    output
                    (call-for command manifest)))))

(defn ^{:stratum 1} dispatch
  "The result of one command, or nil for an unknown command."
  [[command & args]]
  (case command
    ("smoke" "run") (apply experiment command args)
    "verify" (lab/verify! (first args))
    nil))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} -main [& args]
  (let [result (dispatch args)]
    (cond
      (nil? result) (do (println (messages/t :cli/usage)) (System/exit usage-exit-code))
      (anomaly/anomaly? result) (do (prn result) (System/exit failure-exit-code))
      :else (prn result))))
