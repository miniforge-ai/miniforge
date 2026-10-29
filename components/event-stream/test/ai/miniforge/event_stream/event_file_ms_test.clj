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
(ns ai.miniforge.event-stream.event-file-ms-test
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} epoch-ms
  "2026-01-01T00:00:00Z, the Snowflake epoch."
  1767225600000)

(deftest ^{:stratum 0} a-file-the-sink-writes-parses
  (let [base   (doto (io/file (System/getProperty "java.io.tmpdir") (str "event-file-ms-" (random-uuid))) .mkdirs)
        stream (es/create-event-stream {:config {:observability {:event-sinks [{:type :file :base-dir (str base)}]}}})
        wf-id  (random-uuid)
        before (System/currentTimeMillis)]
    (es/publish! stream {:event/type :workflow/started :event/id (random-uuid) :workflow/id wf-id})
    (let [ms (es/event-file-ms (.getName ^java.io.File (first (.listFiles (es/workflow-dir (str base) wf-id)))))]
      (is (<= before ms (System/currentTimeMillis))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} both-name-grammars-give-the-recorded-time
  (is (= (+ epoch-ms 500)
         (es/event-file-ms "20260101T000000500Z-0f0e0d0c-0000-4000-8000-000000000000.json")))
  (is (= (+ epoch-ms 1000)
         (es/event-file-ms (format "%016x__%012d.transit.json" (bit-shift-left 1000 22) 7)))
      "a Snowflake name's timestamp bits, though it sorts before every legacy name")
  (is (every? nil? (map es/event-file-ms ["manifest.json" "snapshot.transit.json"
                                          ".es-1.transit.tmp" "archived.marker"]))
      "a sidecar, temp or marker file is not an event file"))
