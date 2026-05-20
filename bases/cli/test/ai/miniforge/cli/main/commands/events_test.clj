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

(ns ai.miniforge.cli.main.commands.events-test
  "Unit tests for the `events show` CLI command."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.events :as sut]
   [ai.miniforge.event-stream.interface :as es]))

;;------------------------------------------------------------------------------ Fixtures

(def ^:private wf-id "test-workflow-123")

(defn- make-event
  ([type ts]
   {:event/type      type
    :event/timestamp ts})
  ([type ts extra]
   (merge (make-event type ts) extra)))

(def ^:private sample-events
  [(make-event :workflow/started          "2026-05-19T10:00:00Z")
   (make-event :agent/tool-call-started   "2026-05-19T10:00:05Z" {:tool/name "Read"})
   (make-event :agent/tool-call-completed "2026-05-19T10:00:06Z" {:tool/name "Read"
                                                                   :phase/duration-ms 1000})
   (make-event :agent/heartbeat           "2026-05-19T10:00:30Z")
   ;; 30s gap: heartbeat (T+30s) -> phase-started (T+60s)
   (make-event :workflow/phase-started    "2026-05-19T10:01:00Z" {:workflow/phase :implement})
   (make-event :workflow/phase-completed  "2026-05-19T10:02:00Z" {:workflow/phase :implement
                                                                   :phase/outcome :success
                                                                   :phase/duration-ms 60000})
   ;; 180s gap: phase-completed (T+120s) -> workflow/completed (T+300s)
   (make-event :workflow/completed        "2026-05-19T10:05:00Z" {:workflow/status :success})])

;;------------------------------------------------------------------------------ Test helpers

(defmacro with-output
  "Capture both stdout (*out*) and stderr (*err*) during body, return combined
   output as a string.  Ensures error-path assertions catch display/print-error
   output even when it is written to stderr."
  [& body]
  `(let [baos# (java.io.ByteArrayOutputStream.)
         pw#   (java.io.OutputStreamWriter. baos#)]
     (binding [*out* pw#
               *err* pw#]
       ~@body)
     (.flush pw#)
     (.toString baos# "UTF-8")))

;;------------------------------------------------------------------------------ Private function tests

(deftest ts->instant-test
  (testing "parses ISO-8601 string"
    (let [inst (#'sut/ts->instant "2026-05-19T10:00:00Z")]
      (is (instance? java.time.Instant inst))
      (is (= 1747648800000 (.toEpochMilli inst)))))

  (testing "accepts java.time.Instant passthrough"
    (let [now (java.time.Instant/now)]
      (is (= now (#'sut/ts->instant now)))))

  (testing "accepts java.util.Date"
    (let [d (java.util.Date. 1747648800000)]
      (is (= (.toInstant d) (#'sut/ts->instant d)))))

  (testing "returns nil on nil"
    (is (nil? (#'sut/ts->instant nil))))

  (testing "returns nil on garbage"
    (is (nil? (#'sut/ts->instant "not-a-timestamp")))))

(deftest format-ts-test
  (testing "formats ISO-8601 string to HH:MM:SS.mmm"
    (let [result (#'sut/format-ts "2026-05-19T10:00:00Z")]
      (is (string? result))
      ;; should look like HH:MM:SS.mmm - at minimum 8 chars
      (is (>= (count result) 8))))

  (testing "returns placeholder on nil"
    (is (= "??:??:??.???" (#'sut/format-ts nil))))

  (testing "returns placeholder on garbage"
    (is (= "??:??:??.???" (#'sut/format-ts "garbage")))))

(deftest event-type-name-test
  (testing "namespaced keyword event type"
    (is (= "workflow/started"
           (#'sut/event-type-name {:event/type :workflow/started}))))

  (testing "bare keyword event type - no spurious leading slash"
    (is (= "started"
           (#'sut/event-type-name {:event/type :started}))))

  (testing "string event type"
    (is (= "some/event"
           (#'sut/event-type-name {:event/type "some/event"}))))

  (testing "nil event type returns stringified nil"
    (is (string? (#'sut/event-type-name {})))))

(deftest event-component-test
  (testing "returns event/component when present"
    (is (= "planner"
           (#'sut/event-component {:event/component :planner}))))

  (testing "falls back to workflow/phase"
    (is (= "implement"
           (#'sut/event-component {:workflow/phase :implement}))))

  (testing "returns empty string when nothing available"
    (is (= "" (#'sut/event-component {})))))

(deftest summarize-test
  (testing "tool-call-started"
    (is (str/starts-with?
         (#'sut/summarize {:event/type :agent/tool-call-started :tool/name "Write"})
         "-> Write")))

  (testing "tool-call-completed"
    (is (str/starts-with?
         (#'sut/summarize {:event/type :agent/tool-call-completed :tool/name "Write"})
         "<- Write")))

  (testing "heartbeat"
    (is (str/includes?
         (#'sut/summarize {:event/type :agent/heartbeat})
         "heartbeat")))

  (testing "workflow/started"
    (is (str/includes?
         (#'sut/summarize {:event/type :workflow/started})
         "workflow started")))

  (testing "workflow/completed"
    (is (str/includes?
         (#'sut/summarize {:event/type :workflow/completed :workflow/status :success})
         "success")))

  (testing "workflow/failed includes reason"
    (is (str/includes?
         (#'sut/summarize {:event/type :workflow/failed :workflow/failure-reason "OOM"})
         "OOM")))

  (testing "dag-considered uses str not name for :dag/reason"
    ;; numeric reason must not throw ClassCastException
    (is (string?
         (#'sut/summarize {:event/type :workflow/dag-considered
                           :dag/outcome :skip
                           :dag/reason 42}))))

  (testing "unknown type falls back to type name"
    (is (str/includes?
         (#'sut/summarize {:event/type :some/custom})
         "some/custom"))))

(deftest row-color-test
  (testing "tool-call events are cyan"
    (is (= :cyan (#'sut/row-color {:event/type :agent/tool-call-started})))
    (is (= :cyan (#'sut/row-color {:event/type :agent/tool-call-completed})))
    (is (= :cyan (#'sut/row-color {:event/type :agent/tool-call}))))

  (testing "heartbeat is dim"
    (is (= :dim (#'sut/row-color {:event/type :agent/heartbeat}))))

  (testing "workflow/failed is red"
    (is (= :red (#'sut/row-color {:event/type :workflow/failed}))))

  (testing "workflow/started is green"
    (is (= :green (#'sut/row-color {:event/type :workflow/started}))))

  (testing "unknown returns nil"
    (is (nil? (#'sut/row-color {:event/type :some/unknown})))))

(deftest compute-gap-ms-test
  (testing "computes millisecond difference between two events"
    (let [ev1 (make-event :workflow/started   "2026-05-19T10:00:00Z")
          ev2 (make-event :agent/heartbeat    "2026-05-19T10:01:30Z")]  ; 90s later
      (is (= 90000 (#'sut/compute-gap-ms ev1 ev2)))))

  (testing "returns nil when first event has no parseable timestamp"
    (let [ev1 {:event/type :workflow/started}
          ev2 (make-event :agent/heartbeat "2026-05-19T10:01:30Z")]
      (is (nil? (#'sut/compute-gap-ms ev1 ev2))))))

(deftest inject-gaps-test
  (testing "inserts a gap row between events with large time gap"
    (let [ev1    (make-event :workflow/started   "2026-05-19T10:00:00Z")
          ev2    (make-event :workflow/completed "2026-05-19T10:02:00Z") ; 120s later
          result (#'sut/inject-gaps [ev1 ev2] 60000)] ; threshold = 60s
      (is (= 3 (count result)))
      (is (true? (:gap? (second result))))
      (is (> (:gap-secs (second result)) 100))))

  (testing "no gap row when events are close together"
    (let [ev1    (make-event :workflow/started  "2026-05-19T10:00:00Z")
          ev2    (make-event :agent/heartbeat   "2026-05-19T10:00:05Z") ; 5s later
          result (#'sut/inject-gaps [ev1 ev2] 60000)]
      (is (= 2 (count result)))
      (is (not-any? :gap? result))))

  (testing "detects ALL back-to-back large gaps (not just the first)"
    ;; A(T=0s) -> B(T=90s) -> C(T=180s) with threshold=60s
    ;; must produce [A GAP B GAP C] — 5 items, not [A GAP B C]
    (let [ev-a   (make-event :workflow/started          "2026-05-19T10:00:00Z")
          ev-b   (make-event :agent/heartbeat            "2026-05-19T10:01:30Z") ; +90s
          ev-c   (make-event :workflow/completed        "2026-05-19T10:03:00Z") ; +90s
          result (#'sut/inject-gaps [ev-a ev-b ev-c] 60000)]
      (is (= 5 (count result)))
      (is (:gap? (nth result 1)))
      (is (:gap? (nth result 3)))))

  (testing "empty seq passes through unchanged"
    (is (= [] (#'sut/inject-gaps [] 60000))))

  (testing "single event passes through unchanged"
    (let [ev (make-event :workflow/started "2026-05-19T10:00:00Z")]
      (is (= [ev] (#'sut/inject-gaps [ev] 60000))))))

(deftest apply-filters-test
  (let [events [(make-event :workflow/started "T")
                (make-event :agent/tool-call-started "T" {:tool/name "Read"})
                (make-event :agent/chunk "T")
                (make-event :agent/status "T")
                (make-event :agent/heartbeat "T")]]

    (testing "type-filter by substring keeps only matching events"
      (let [result (#'sut/apply-filters events {:type-filter "tool-call"})]
        (is (= 1 (count result)))
        (is (= :agent/tool-call-started (:event/type (first result))))))

    (testing "no-chunks=true removes :agent/chunk"
      (let [result (#'sut/apply-filters events {:no-chunks true})]
        (is (not-any? #(= :agent/chunk (:event/type %)) result))))

    (testing "no-chunks=false keeps :agent/chunk"
      (let [result (#'sut/apply-filters events {:no-chunks false})]
        (is (some #(= :agent/chunk (:event/type %)) result))))

    (testing "no-status=true removes :agent/status"
      (let [result (#'sut/apply-filters events {:no-status true})]
        (is (not-any? #(= :agent/status (:event/type %)) result))))

    (testing "combined type-filter + no-chunks"
      (let [result (#'sut/apply-filters events {:type-filter "workflow" :no-chunks true})]
        (is (= 1 (count result)))
        (is (= :workflow/started (:event/type (first result))))))))

(deftest prep-for-json-test
  (testing "keywords become strings"
    (let [result (#'sut/prep-for-json {:event/type :workflow/started})]
      (is (= {":event/type" ":workflow/started"} result))))

  (testing "nil values stay nil (not the string \"nil\")"
    (let [result (#'sut/prep-for-json {:workflow/id nil})]
      (is (nil? (get result ":workflow/id")))))

  (testing "Instants become strings"
    (let [inst   (java.time.Instant/parse "2026-05-19T10:00:00Z")
          result (#'sut/prep-for-json {:event/timestamp inst})]
      (is (string? (get result ":event/timestamp")))))

  (testing "nested maps are recursively converted"
    (let [result (#'sut/prep-for-json {:dag/info {:outcome :skip}})]
      (is (= {":outcome" ":skip"}
             (get result ":dag/info"))))))

;;------------------------------------------------------------------------------ Integration tests (events-show-cmd)

(deftest events-show-cmd-missing-workflow-id-test
  (testing "prints error when workflow-id is nil"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/no-events")]
                     (sut/events-show-cmd {})))]
      (is (str/includes? output "workflow-id is required")))))

(deftest events-show-cmd-no-events-test
  (testing "prints error when no events exist"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/no-events-dir")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] nil)]
                     (sut/events-show-cmd {:workflow-id wf-id})))]
      (is (str/includes? output "No events found")))))

(deftest events-show-cmd-happy-path-test
  (testing "renders timeline header and rows for sample events"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] sample-events)]
                     (sut/events-show-cmd {:workflow-id wf-id
                                           :no-chunks   true
                                           :no-status   false})))]
      (is (str/includes? output "Timeline"))
      (is (str/includes? output "TIMESTAMP"))
      (is (str/includes? output "EVENT-TYPE"))
      (is (str/includes? output "COMPONENT"))
      (is (str/includes? output "SUMMARY"))
      ;; at least one data row
      (is (str/includes? output "workflow/started")))))

(deftest events-show-cmd-type-filter-flag-test
  (testing "--type-filter tool-call shows only tool events"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] sample-events)]
                     (sut/events-show-cmd {:workflow-id  wf-id
                                           :type-filter  "tool-call"
                                           :no-chunks    false})))]
      ;; tool-call events should appear
      (is (str/includes? output "tool-call"))
      ;; workflow/started should NOT appear in the data rows
      (is (not (str/includes? output "workflow started"))))))

(deftest events-show-cmd-gap-detection-test
  ;; sample-events has:
  ;;   30s gap:  heartbeat (T+30s) -> phase-started (T+60s)
  ;;   180s gap: phase-completed (T+120s) -> workflow/completed (T+300s)
  ;; threshold=20s triggers on the first gap already.
  (testing "injects gap markers when gap exceeds threshold"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] sample-events)]
                     (sut/events-show-cmd {:workflow-id   wf-id
                                           :gap-threshold 20.0
                                           :no-chunks     true})))]
      (is (str/includes? output "gap:")))))

(deftest events-show-cmd-json-flag-test
  (testing "--json emits one record per line without table headers"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] [(first sample-events)])]
                     (sut/events-show-cmd {:workflow-id wf-id
                                           :json        true
                                           :no-chunks   true})))]
      ;; JSON output contains event type as a string value
      (is (str/includes? output "workflow/started"))
      ;; Should NOT contain the table header
      (is (not (str/includes? output "TIMESTAMP"))))))

(deftest events-show-cmd-consecutive-gaps-test
  (testing "detects multiple consecutive large gaps"
    (let [ev-a (make-event :workflow/started  "2026-05-19T10:00:00Z")
          ev-b (make-event :agent/heartbeat   "2026-05-19T10:02:00Z") ; +120s gap
          ev-c (make-event :workflow/completed "2026-05-19T10:04:00Z") ; +120s gap
          output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] [ev-a ev-b ev-c])]
                     (sut/events-show-cmd {:workflow-id   wf-id
                                           :gap-threshold 60.0
                                           :no-chunks     true})))
          gap-count (count (re-seq #"gap:" output))]
      ;; Both A->B and B->C gaps should be reported
      (is (= 2 gap-count)))))

(deftest events-show-cmd-large-gap-threshold-test
  (testing "no gap rows when threshold exceeds all gaps"
    (let [output (with-output
                   (with-redefs [app-config/events-dir (constantly "/tmp/events")
                                 es/read-workflow-events-by-id
                                 (fn [_dir _id] sample-events)]
                     (sut/events-show-cmd {:workflow-id   wf-id
                                           :gap-threshold 9999.0
                                           :no-chunks     true})))]
      (is (not (str/includes? output "gap:"))))))

(deftest ansi-esc-byte-test
  (testing "ANSI escape sequences start with the ESC byte (char 27)"
    ;; Verify the ESC byte construction is correct and not empty string
    (let [esc-byte-str #'sut/esc-byte]
      (is (= 1 (count @esc-byte-str)))
      (is (= 27 (int (first @esc-byte-str)))))))
