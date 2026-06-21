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

(ns ai.miniforge.phase-deployment.shell.timeouts
  "Deployment shell command timeouts (ms), loaded from EDN.

   Load is tolerant: a missing, unreadable, malformed, or non-map resource
   yields {} so that call-site literal defaults remain authoritative
   (fail-open). Never throws at namespace load."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Tolerant loader

(defn- load-timeouts
  "Read the timeouts EDN, returning {} if the resource is absent,
   unreadable, malformed, or not a map — so call-site literal defaults
   remain authoritative (fail-open)."
  [path]
  (try
    (let [url    (io/resource path)
          parsed (when url (edn/read-string (slurp url)))]
      (if (map? parsed) parsed {}))
    (catch Exception _ {})))

(def timeouts
  "Validated timeout map; {} when the EDN resource is unusable."
  (load-timeouts "config/phase-deployment/timeouts.edn"))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (load-timeouts "config/phase-deployment/timeouts.edn")
  (load-timeouts "config/phase-deployment/does-not-exist.edn")
  :leave-this-here)
