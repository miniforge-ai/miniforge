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
   [ai.miniforge.event-stream.interface :as es]
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

;------------------------------------------------------------------------------ Layer 1

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
