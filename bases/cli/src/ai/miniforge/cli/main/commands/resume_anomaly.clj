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
(ns ai.miniforge.cli.main.commands.resume-anomaly
  "Escalate a resume anomaly returned as a value into the CLI's thrown
   anomaly shape, keeping the original anomaly fields."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} anomaly-category
  [a]
  (case (:anomaly/type a)
    :not-found :anomalies/not-found
    :invalid-input :anomalies/incorrect
    :anomalies/fault))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} throw-resume-anomaly!
  [a]
  (when (anomaly/anomaly? a)
    (response/throw-anomaly! (anomaly-category a)
                             (:anomaly/message a)
                             (merge (:anomaly/data a)
                                    (select-keys a [:anomaly/type
                                                    :anomaly/subtype
                                                    :anomaly/at])))))

(comment
  (throw-resume-anomaly! nil))
