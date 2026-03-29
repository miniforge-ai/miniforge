(ns ai.miniforge.web-dashboard.state.workflows-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.workflows :as sut]))

(defn temp-events-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-workflow-events-" (random-uuid)))
    .mkdirs))

(defn- parse-ts
  "Parse an ISO-8601 instant string into a java.util.Date."
  [s]
  (java.util.Date/from (java.time.Instant/parse s)))

(defn- wf-event
  "Build a minimal workflow event map.
   Extra key/value pairs are merged in via the rest argument."
  [event-type wf-id timestamp & {:as extra}]
  (merge {:event/type      event-type
          :event/id        (random-uuid)
          :event/timestamp (parse-ts timestamp)
          :workflow/id     wf-id}
         extra))

(deftest get-events-merges-live-and-historical-test
  (testing "queries include archived event-file history and dedupe live duplicates"
    (let [events-dir    (temp-events-dir)
          wf-id         (random-uuid)
          stream        (es/create-event-stream {:sinks []})
          state         (core/create-state {:event-stream stream})
          started       (wf-event :workflow/started     wf-id "2026-03-28T10:00:00Z"
                                  :workflow/spec {:name "Historical"})
          phase-started (wf-event :workflow/phase-started wf-id "2026-03-28T10:01:00Z"
                                  :workflow/phase :verify)
          chunk         (wf-event :agent/chunk           wf-id "2026-03-28T10:02:00Z"
                                  :agent/id :verify :chunk/delta "streaming")
          event-file    (io/file events-dir (str wf-id ".edn"))]
      (io/make-parents event-file)
      (spit event-file (str (pr-str started) "\n" (pr-str phase-started) "\n"))
      (es/publish! stream chunk)
      (with-redefs [sut/events-dir-path (.getPath events-dir)]
        (let [events (sut/get-events state {:workflow-id wf-id :limit 10})
              phase-events (sut/get-events state {:workflow-id wf-id
                                                  :event-type :workflow/phase-started
                                                  :limit 10})
              since-events (sut/get-events state {:workflow-id wf-id
                                                  :since "2026-03-28T10:01:30Z"
                                                  :limit 10})]
          (is (= 3 (count events)))
          (is (= chunk (first events))
              "Newest live event should sort ahead of archived history")
          (is (= 1 (count phase-events)))
          (is (= [chunk] since-events)))))))

(deftest get-workflows-exposes-stream-preview-and-metrics-test
  (testing "live workflow summaries include recent streaming output and aggregated metrics"
    (let [stream (es/create-event-stream {:sinks []})
          state  (core/create-state {:event-stream stream})
          wf-id  (random-uuid)]
      (es/publish! stream (wf-event :workflow/started    wf-id "2026-03-28T11:00:00Z"
                                    :workflow/spec {:name "Telemetry"}))
      (es/publish! stream (wf-event :workflow/phase-started wf-id "2026-03-28T11:00:01Z"
                                    :workflow/phase :review))
      (es/publish! stream (wf-event :agent/chunk           wf-id "2026-03-28T11:00:02Z"
                                    :agent/id :review :chunk/delta "Checking issues..."))
      (es/publish! stream (wf-event :workflow/phase-completed wf-id "2026-03-28T11:00:03Z"
                                    :workflow/phase :review
                                    :phase/tokens 42
                                    :phase/duration-ms 3000
                                    :phase/outcome :success))
      (let [workflow (first (sut/get-workflows state))]
        (is (= wf-id (:id workflow)))
        (is (= :review (:phase workflow)))
        (is (= "Checking issues..." (:latest-output workflow)))
        (is (= 42 (get-in workflow [:metrics :tokens])))
        (is (= 3000 (get-in workflow [:metrics :duration-ms])))))))
