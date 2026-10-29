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
(ns ai.miniforge.web-dashboard.sink-layout-test
  "The dashboard reads what the event-stream file sink writes:
   `{events-dir}/live/<workflow-id>/` and `archived/<workflow-id>/`, one
   transit-JSON file per event."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.web-dashboard.archive :as archive]
   [ai.miniforge.web-dashboard.state.archive :as state-archive]
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.workflows :as workflows]
   [ai.miniforge.web-dashboard.watcher :as watcher]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-events-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-sink-layout-" (random-uuid)))
    .mkdirs))

(defn- ^{:stratum 0} file-sink-stream
  "A stream whose only sink is the real file sink, rooted at `events-dir`."
  [events-dir]
  (es/create-event-stream
   {:config {:observability {:event-sinks [{:type :file :base-dir (str events-dir)}]}}}))

(defn- ^{:stratum 0} wf-event
  [event-type wf-id & {:as extra}]
  (merge {:event/type      event-type
          :event/id        (random-uuid)
          :event/timestamp (java.util.Date.)
          :workflow/id     wf-id}
         extra))

(defn- ^{:stratum 0} write-temp-file!
  "Leave a sink temp file holding `event` in the workflow's live directory."
  [events-dir wf-id event]
  (spit (io/file (es/workflow-dir (str events-dir) wf-id) ".es-1.transit.tmp")
        (es/serialize-event event)))

(defn- ^{:stratum 0} archive-dir!
  "Move the workflow's live directory to archived/, as the archive does."
  [events-dir wf-id]
  (let [to (io/file events-dir "archived" (str wf-id))]
    (.mkdirs (.getParentFile to))
    (.renameTo (es/workflow-dir (str events-dir) wf-id) to)))

(defn- ^{:stratum 0} await-count
  "Wait up to 10 s for `events` to hold at least `n` items."
  [events n]
  (loop [deadline (+ (System/currentTimeMillis) 10000)]
    (cond
      (>= (count @events) n) true
      (> (System/currentTimeMillis) deadline) false
      :else (do (Thread/sleep 50) (recur deadline)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-run!
  "Publish a started, phase-started and completed event for `wf-id`. The
   sink names files by millisecond, so the pauses keep their order."
  [stream wf-id run-name]
  (doseq [event [(wf-event :workflow/started wf-id :workflow/spec {:name run-name})
                 (wf-event :workflow/phase-started wf-id :workflow/phase :plan)
                 (wf-event :workflow/completed wf-id :workflow/status :success)]]
    (es/publish! stream event)
    (Thread/sleep 5)))

(deftest ^{:stratum 1} watcher-publishes-a-run-the-file-sink-writes
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        stop!      (watcher/start-watcher! (str events-dir) #(swap! published conj %))]
    (try
      (es/publish! stream (wf-event :workflow/started wf-id :workflow/spec {:name "Live run"}))
      (write-temp-file! events-dir wf-id (wf-event :workflow/failed wf-id))
      (es/publish! stream (wf-event :workflow/phase-started wf-id :workflow/phase :plan))
      (is (await-count published 2) "the watcher publishes events written under live/")
      (Thread/sleep 1200)
      (is (= #{:workflow/started :workflow/phase-started}
             (set (map :event/type @published))))
      (is (= 2 (count @published)) "each event once; the temp file is not read")
      (is (= (str wf-id) (:workflow/id (first @published))))
      (finally (stop!)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} archive-scan-lists-live-archived-and-edn-workflows
  (let [events-dir  (temp-events-dir)
        stream      (file-sink-stream events-dir)
        archived-id (random-uuid)
        live-id     (random-uuid)
        legacy-id   (random-uuid)
        found       (atom {})]
    (publish-run! stream archived-id "Archived run")
    (archive-dir! events-dir archived-id)
    (es/publish! stream (wf-event :workflow/started live-id :workflow/spec {:name "Running"}))
    (write-temp-file! events-dir live-id (wf-event :workflow/completed live-id
                                                   :workflow/status :success))
    (spit (io/file events-dir (str legacy-id ".edn"))
          (str (pr-str (wf-event :workflow/started legacy-id :workflow/spec {:name "Legacy"})) "\n"))
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= #{(str archived-id) (str live-id) (str legacy-id)} (set (keys @found))))
    (is (= {:name "Archived run" :status :completed}
           (select-keys (get @found (str archived-id)) [:name :status])))
    (is (= {:name "Running" :status :stale}
           (select-keys (get @found (str live-id)) [:name :status]))
        "the temp file's completed event is not read")
    (is (= "Legacy" (:name (get @found (str legacy-id)))) "old .edn history still shows")
    (testing "an archived workflow's events load on demand, newest first"
      (let [state (atom {:archived-workflows (atom @found)})]
        (is (= [:workflow/completed :workflow/phase-started :workflow/started]
               (mapv :event/type (state-archive/get-archived-workflow-events state archived-id))))
        (state-archive/delete-archived-workflow! state archived-id)
        (is (not (contains? @(:archived-workflows @state) (str archived-id))))
        (is (.isDirectory (io/file events-dir "archived" (str archived-id)))
            "deleting from the list leaves the event directory to event-stream cleanup")))))

(deftest ^{:stratum 2} workflow-queries-read-the-file-sink-layout
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        dashboard  (core/create-state {:event-stream (es/create-event-stream {:sinks []})})
        types      (fn [events] (set (map :event/type events)))]
    (publish-run! stream wf-id "Disk run")
    (write-temp-file! events-dir wf-id (wf-event :workflow/failed wf-id))
    (with-redefs [workflows/events-dir-path (str events-dir)]
      (is (= #{:workflow/started :workflow/phase-started :workflow/completed}
             (types (workflows/get-events dashboard {:workflow-id wf-id})))
          "one workflow's history, without the temp file")
      (let [workflow (->> (workflows/get-workflows dashboard)
                          (filter #(= (str wf-id) (str (:id %))))
                          first)]
        (is (= "Disk run" (:name workflow)))
        (is (= :completed (:status workflow))))
      (testing "the archive-wide history picks up files added since the last read"
        (is (= 3 (count (workflows/get-events dashboard {}))))
        (es/publish! stream (wf-event :agent/chunk wf-id :chunk/delta "more"))
        (is (= #{:workflow/started :workflow/phase-started :workflow/completed :agent/chunk}
               (types (workflows/get-events dashboard {}))))))))
