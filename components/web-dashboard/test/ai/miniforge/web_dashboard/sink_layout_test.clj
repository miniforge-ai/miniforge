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
   [ai.miniforge.web-dashboard.server.websocket :as ws]
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

(defn- ^{:stratum 0} envelope
  "An event as `stream` builds it, with its per-workflow sequence number."
  [stream event-type wf-id & {:as extra}]
  (merge (es/create-envelope stream event-type wf-id (name event-type)) extra))

(def ^{:stratum 0} snowflake-epoch-ms
  "2026-01-01T00:00:00Z, the epoch of Snowflake event ids."
  1767225600000)

(defn- ^{:stratum 0} legacy-name
  "A legacy sink file name recorded at epoch `ms`, with UUID part `suffix`."
  [ms suffix]
  (str (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
                (.atOffset (java.time.Instant/ofEpochMilli ms) java.time.ZoneOffset/UTC))
       "-" suffix "-0000-4000-8000-000000000000.json"))

(defn- ^{:stratum 0} write-event!
  [dir file-name event]
  (spit (io/file dir file-name) (es/serialize-event event)))

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
  "Publish a started, phase-started and completed event for `wf-id`, with
   no pause: files named in the same millisecond keep publish order by
   sequence number."
  [stream wf-id run-name]
  (doseq [event [(envelope stream :workflow/started wf-id :workflow/spec {:name run-name})
                 (envelope stream :workflow/phase-started wf-id :workflow/phase :plan)
                 (envelope stream :workflow/completed wf-id :workflow/status :success)]]
    (es/publish! stream event)))

(deftest ^{:stratum 1} files-named-in-the-same-millisecond-keep-publish-order
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        ;; One millisecond; the random-UUID parts sort against publish order.
        names      ["20260101T000000000Z-ffffffff-ffff-4fff-bfff-ffffffffffff.json"
                    "20260101T000000000Z-88888888-8888-4888-8888-888888888888.json"
                    "20260101T000000000Z-00000000-0000-4000-8000-000000000000.json"]
        events     [(envelope stream :workflow/started wf-id :workflow/spec {:name "Tied run"})
                    (envelope stream :workflow/phase-started wf-id :workflow/phase :plan)
                    (envelope stream :workflow/completed wf-id :workflow/status :success)]
        found      (atom {})]
    (doseq [[file-name event] (map vector names events)]
      (spit (io/file dir file-name) (es/serialize-event event)))
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= {:name "Tied run" :status :completed :phase :plan}
           (select-keys (get @found (str wf-id)) [:name :status :phase])))
    (is (= [:workflow/completed :workflow/phase-started :workflow/started]
           (mapv :event/type (state-archive/get-archived-workflow-events
                              (atom {:archived-workflows (atom @found)}) wf-id))))))

(deftest ^{:stratum 1} a-same-millisecond-group-across-a-cutoff-keeps-every-reader-in-order
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        started    (envelope stream :workflow/started wf-id :workflow/spec {:name "Tied run"})
        chunks     (mapv #(envelope stream :agent/chunk wf-id :chunk/delta (str %)) (range 1 204))
        completed  (envelope stream :workflow/completed wf-id :workflow/status :success)
        ;; 204 files in one millisecond, named against publish order, so the
        ;; newest events sort first and every cutoff (10, 200, 5) falls inside it.
        tied-name  #(format "20260101T000000001Z-%08x-0000-4000-8000-000000000000.json"
                            (- 0xffffffff (:event/sequence-number %)))
        found      (atom {})]
    (spit (io/file dir "20260101T000000000Z-00000000-0000-4000-8000-000000000000.json")
          (es/serialize-event started))
    (doseq [event (conj chunks completed)]
      (spit (io/file dir (tied-name event)) (es/serialize-event event)))
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= :completed (:status (get @found (str wf-id)))) "the summary's last 10 files")
    (let [newest (state-archive/get-archived-workflow-events
                  (atom {:archived-workflows (atom @found)}) wf-id)]
      (is (= [:workflow/completed "203" "202"] [(:event/type (first newest))
                                                 (:chunk/delta (second newest))
                                                 (:chunk/delta (nth newest 2))])
          "the archive endpoint's newest 200, newest first")
      (is (= 200 (count newest))))
    (with-redefs [workflows/events-dir-path (str events-dir)
                  workflows/disk-retention (merge es/bounded-retention {:max-events-per-workflow 5})]
      (is (= #{"199" "200" "201" "202" "203"}
             (->> (workflows/get-events (core/create-state {:event-stream stream}) {:limit 1000})
                  (keep :chunk/delta)
                  set))
          "the history's 5-file batches keep the newest 5 chunks"))))

(deftest ^{:stratum 1} sidecar-files-do-not-count-toward-the-endpoint-limit
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        found      (atom {})]
    (write-event! dir (legacy-name snowflake-epoch-ms "00000000")
                  (envelope stream :workflow/started wf-id :workflow/spec {:name "Long run"}))
    (doseq [i (range 1 205)]
      (write-event! dir (legacy-name (+ snowflake-epoch-ms i) "00000000")
                    (envelope stream :agent/chunk wf-id :chunk/delta (str i))))
    (spit (io/file dir "manifest.json") "{\"workflow_id\":\"x\"}")
    (spit (io/file dir "snapshot.transit.json") "{}")
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= 200 (count (state-archive/get-archived-workflow-events
                       (atom {:archived-workflows (atom @found)}) wf-id)))
        "the manifest and snapshot take no place among the newest 200")))

(deftest ^{:stratum 1} legacy-and-snowflake-names-in-one-directory-order-by-time
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        found      (atom {})]
    (write-event! dir (legacy-name (+ snowflake-epoch-ms 500) "ffffffff")
                  (envelope stream :workflow/started wf-id :workflow/spec {:name "Mixed run"}))
    (write-event! dir (legacy-name (+ snowflake-epoch-ms 600) "ffffffff")
                  (envelope stream :workflow/phase-started wf-id :workflow/phase :plan))
    ;; Recorded later, but "00…" sorts before "2026…".
    (write-event! dir (format "%016x__%012d.transit.json" (bit-shift-left 1000 22) 2)
                  (envelope stream :workflow/completed wf-id :workflow/status :success))
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= {:name "Mixed run" :status :completed}
           (select-keys (get @found (str wf-id)) [:name :status])))
    (is (= [:workflow/completed :workflow/phase-started :workflow/started]
           (mapv :event/type (state-archive/get-archived-workflow-events
                              (atom {:archived-workflows (atom @found)}) wf-id))))))

(deftest ^{:stratum 1} seconds-resolution-file-names-are-read
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        dashboard  (core/create-state {:event-stream (es/create-event-stream {:sinks []})})
        wf-id      (random-uuid)
        ;; The older flat layout and seconds-resolution names an earlier sink wrote.
        dir        (doto (io/file events-dir (str wf-id)) .mkdirs)
        found      (atom {})]
    (write-event! dir "20260420T000001Z-eventA.json"
                  (envelope stream :workflow/started wf-id :workflow/spec {:name "Seconds run"}))
    (write-event! dir "20260420T000002Z-eventB.json"
                  (envelope stream :workflow/phase-started wf-id :workflow/phase :plan))
    (write-event! dir "20260420T000003Z-eventC.json"
                  (envelope stream :workflow/completed wf-id :workflow/status :success))
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= {:name "Seconds run" :status :completed}
           (select-keys (get @found (str wf-id)) [:name :status])))
    (with-redefs [workflows/events-dir-path (str events-dir)]
      (is (= [:workflow/started :workflow/phase-started :workflow/completed]
             (->> (workflows/get-events dashboard {:workflow-id wf-id})
                  (sort-by :event/sequence-number)
                  (mapv :event/type)))))))

(deftest ^{:stratum 1} a-json-ingested-event-the-stream-persists-is-one-event
  (let [events-dir (temp-events-dir)
        dashboard  (core/create-state {:event-stream (file-sink-stream events-dir)})
        wf-id      (random-uuid)]
    ;; JSON ingest keeps :event/id a string; the persisted copy reads back as a UUID.
    (ws/handle-workflow-event dashboard (str "{\"event/type\": \"workflow/started\", "
                                             "\"event/id\": \"" (random-uuid) "\", "
                                             "\"workflow/id\": \"" wf-id "\", "
                                             "\"workflow/spec\": {\"name\": \"Ingested\"}}"))
    (with-redefs [workflows/events-dir-path (str events-dir)]
      (is (= 1 (count (workflows/get-events dashboard {:workflow-id wf-id}))))
      (is (= ["Ingested"] (->> (workflows/get-workflows dashboard)
                               (filter #(= wf-id (:id %)))
                               (mapv :name)))))))

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
      (is (= wf-id (:workflow/id (first @published))) "ids come back as UUIDs")
      (finally (stop!)))))

(deftest ^{:stratum 1} the-archive-wide-history-keeps-a-bounded-window-per-workflow
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        dashboard  (core/create-state {:event-stream (es/create-event-stream {:sinks []})})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        ;; One file per millisecond. The real sink, on a fast machine, puts
        ;; several in one, and tracking keeps every name of its floor's
        ;; millisecond, so the name count would depend on timing.
        write!     #(write-event! dir (legacy-name (+ snowflake-epoch-ms %1) "ffffffff") %2)
        chunk!     #(write! (+ 3 (parse-long %)) (envelope stream :agent/chunk wf-id :chunk/delta %))
        disk-events #(filter (comp #{wf-id} :workflow/id) (workflows/get-events dashboard {:limit 1000}))]
    (write! 0 (envelope stream :workflow/started wf-id :workflow/spec {:name "Long run"}))
    (write! 1 (envelope stream :workflow/phase-started wf-id :workflow/phase :implement))
    (run! chunk! (map str (range 20)))
    (write! 30 (envelope stream :workflow/completed wf-id :workflow/status :success))
    (with-redefs [workflows/events-dir-path (str events-dir)
                  workflows/disk-retention (merge es/bounded-retention {:max-events-per-workflow 5})]
      (let [events (disk-events)]
        (is (= 8 (count events)) "3 lifecycle events and the newest 5 others")
        (is (= #{"15" "16" "17" "18" "19"} (set (keep :chunk/delta events)))))
      (chunk! "20")
      (is (contains? (set (keep :chunk/delta (disk-events))) "20") "a later file is still read")
      (is (<= (count (get-in @@#'workflows/recent-dir-reads
                             [(str (es/workflow-dir (str events-dir) wf-id)) :tracked :names]))
              5)
          "file tracking is bounded too"))))

(deftest ^{:stratum 1} files-that-appear-late-reach-the-history
  (let [events-dir (temp-events-dir)
        stream     (es/create-event-stream {:sinks []})
        dashboard  (core/create-state {:event-stream (es/create-event-stream {:sinks []})})
        wf-id      (random-uuid)
        dir        (doto (es/workflow-dir (str events-dir) wf-id) .mkdirs)
        at         #(+ snowflake-epoch-ms (* 1000 %))
        history    #(set (map (some-fn :chunk/delta :event/type)
                              (workflows/get-events dashboard {:limit 1000})))]
    (write-event! dir (legacy-name (at 0) "ffffffff")
                  (envelope stream :workflow/started wf-id :workflow/spec {:name "Late"}))
    (doseq [i (range 1 11)]
      (write-event! dir (legacy-name (at i) "ffffffff")
                    (envelope stream :agent/chunk wf-id :chunk/delta (str i))))
    (with-redefs [workflows/events-dir-path (str events-dir)
                  workflows/disk-retention (merge es/bounded-retention {:max-events-per-workflow 5})]
      (is (= #{:workflow/started "6" "7" "8" "9" "10"} (history)))
      (testing "a file in the oldest tracked millisecond whose name sorts first"
        (write-event! dir (legacy-name (at 6) "00000000")
                      (envelope stream :agent/chunk wf-id :chunk/delta "late in 6"))
        (is (contains? (history) "late in 6")))
      (testing "a file recorded before every tracked one (a delayed rename)"
        (write-event! dir (legacy-name (at 3) "11111111")
                      (envelope stream :workflow/completed wf-id :workflow/status :success))
        (is (contains? (history) :workflow/completed))))))

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

(deftest ^{:stratum 2} archived-events-follow-a-run-moved-after-the-scan
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        found      (atom {})]
    (publish-run! stream wf-id "Moved run")
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (archive-dir! events-dir wf-id)
    (is (= 3 (count (state-archive/get-archived-workflow-events
                     (atom {:archived-workflows (atom @found)}) wf-id)))
        "scanned under live/, read from archived/")))

(deftest ^{:stratum 2} a-run-both-in-the-stream-and-on-disk-is-one-workflow
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        dashboard  (core/create-state {:event-stream stream})
        wf-id      (random-uuid)]
    (publish-run! stream wf-id "Persisted run")
    (with-redefs [workflows/events-dir-path (str events-dir)]
      (is (= 1 (count (filter #(= (str wf-id) (str (:id %))) (workflows/get-workflows dashboard))))
          "one card, not one per source")
      (is (= 3 (count (workflows/get-events dashboard {:workflow-id wf-id})))
          "no event twice"))))

(deftest ^{:stratum 2} archived-events-of-a-keyword-workflow-id-load
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        found      (atom {})]
    (publish-run! stream :canonical-sdlc "Keyword run")
    (archive/scan-archive! (str events-dir) #(swap! found assoc (str (:id %)) %) (constantly nil))
    (is (= "Keyword run" (:name (get @found ":canonical-sdlc"))))
    (is (= 3 (count (state-archive/get-archived-workflow-events
                     (atom {:archived-workflows (atom @found)}) ":canonical-sdlc")))
        "the URL's string id finds the summary; its keyword id finds canonical-sdlc/")))
