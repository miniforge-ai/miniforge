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
(ns ai.miniforge.tui-views.file-subscription-layout-test
  "The standalone TUI follows runs the event-stream file sink writes
   under live/<workflow-id>/."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.tui-views.file-subscription :as file-sub]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} await-message
  "Wait up to 10 s for `pred` to hold for one of `msgs`."
  [msgs pred]
  (loop [deadline (+ (System/currentTimeMillis) 10000)]
    (cond
      (some pred @msgs) true
      (> (System/currentTimeMillis) deadline) false
      :else (do (Thread/sleep 50) (recur deadline)))))

(defn- ^{:stratum 0} write-old-start!
  "A temp events dir holding run `wf-id`'s start event, written long ago
   (the tail skipped it as history)."
  [wf-id]
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "tui-layout-" (random-uuid)))
              .mkdirs)]
    (spit (doto (io/file dir "live" (str wf-id) "20260101T000000000Z-start.json") io/make-parents)
          (es/serialize-event {:event/type :workflow/started :event/id (random-uuid)
                               :event/timestamp (java.util.Date.) :workflow/id wf-id
                               :workflow/spec {:name "Old run"}}))
    dir))

(defn- ^{:stratum 0} new-runs [] (atom {:known #{} :waiting {}}))

(deftest ^{:stratum 0} tail-warnings-go-to-a-log-file-not-the-screen
  (let [home (doto (io/file (System/getProperty "java.io.tmpdir") (str "tui-home-" (random-uuid))) .mkdirs)
        out  (with-out-str
               (with-redefs [config/miniforge-home (constantly (str home))]
                 (log/warn (file-sub/tail-logger) :event-stream :event-tail/gap {:message "gap"})))]
    (is (= "" out))
    (is (re-find #":event-tail/gap" (slurp (io/file home "logs" "tui.log"))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-run-first-seen-after-its-start-gets-its-row-from-disk
  (let [wf-id     (random-uuid)
        dir       (write-old-start! wf-id)
        msgs      (atom [])
        dispatch! (partial file-sub/dispatch-event! #(swap! msgs conj %) dir (new-runs))]
    (dispatch! {:event/type :workflow/phase-started :workflow/id wf-id :workflow/phase :implement})
    (is (= [:msg/workflow-added :msg/phase-changed] (map first @msgs)))
    (is (= "Old run" (:name (second (first @msgs)))) "the row, from the start event on disk")
    (reset! msgs [])
    (dispatch! {:event/type :workflow/phase-started :workflow/id wf-id :workflow/phase :verify})
    (is (= [:msg/phase-changed] (map first @msgs)) "built once")
    (reset! msgs [])
    (dispatch! {:event/type :workflow/resumed :workflow/id wf-id})
    (is (= [:msg/workflow-added] (map first @msgs)) "a resume shows the run as running again")))

(deftest ^{:stratum 1} a-run-the-file-sink-writes-reaches-the-tui
  (let [dir    (doto (io/file (System/getProperty "java.io.tmpdir") (str "tui-layout-" (random-uuid)))
                 .mkdirs)
        stream (es/create-event-stream
                {:config {:observability {:event-sinks [{:type :file :base-dir (str dir)}]}}})
        wf-id  (random-uuid)
        msgs   (atom [])]
    (with-redefs [file-sub/events-dir (constantly dir)]
      (let [stop! (file-sub/subscribe-to-files! #(swap! msgs conj %)
                                                {:poll-ms 50 :scan-ms 200 :hydrate-existing? false})]
        (try
          (es/publish! stream (assoc (es/create-envelope stream :workflow/started wf-id "started")
                                     :workflow/spec {:name "Live run"}))
          (is (await-message msgs #(= [:msg/workflow-added
                                        {:workflow-id wf-id :name "Live run" :spec {:name "Live run"}}]
                                       %))
              "the TUI hears of the run, with its UUID id and name")
          (finally (stop!)))))))

(deftest ^{:stratum 1} a-failed-start-lookup-holds-the-runs-events-for-the-next-poll
  (let [wf-id  (random-uuid)
        dir    (write-old-start! wf-id)
        msgs   (atom [])
        send!  #(swap! msgs conj %)
        runs   (new-runs)
        fail?  (atom true)
        lookup es/read-layout-start-event]
    (with-redefs [es/read-layout-start-event #(when-not @fail? (lookup %1 %2))]
      (file-sub/dispatch-event! send! dir runs {:event/type :workflow/phase-started :workflow/id wf-id
                                                :workflow/phase :implement})
      (file-sub/retry-bootstraps! send! dir runs)
      (is (= [] @msgs) "the event waits while the start cannot be read")
      (is (not (contains? (:known @runs) wf-id)))
      (reset! fail? false)
      (file-sub/retry-bootstraps! send! dir runs)
      (is (= [:msg/workflow-added :msg/phase-changed] (map first @msgs)) "row first, then the event")
      (is (contains? (:known @runs) wf-id)))))

(deftest ^{:stratum 1} a-run-whose-start-never-reads-gets-a-bare-row
  (let [wf-id (random-uuid)
        msgs  (atom [])
        send! #(swap! msgs conj %)
        runs  (new-runs)
        dir   (doto (io/file (System/getProperty "java.io.tmpdir") (str "tui-layout-" (random-uuid))) .mkdirs)]
    (file-sub/dispatch-event! send! dir runs {:event/type :workflow/phase-started :workflow/id wf-id
                                              :workflow/phase :implement})
    (dotimes [_ file-sub/max-bootstrap-attempts] (file-sub/retry-bootstraps! send! dir runs))
    (is (= [[:msg/workflow-added {:workflow-id wf-id :name nil :spec nil}] :msg/phase-changed]
           [(first @msgs) (first (second @msgs))])
        "its events are not held forever")))
