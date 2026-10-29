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
(ns ai.miniforge.web-dashboard.server.watcher-replay-test
  "`mf web` keeps the file sink on its stream, so its control and
   supervisory events stay on disk. Events its watcher reads from disk
   must reach the dashboard without being written a second time."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.web-dashboard.server :as server]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [org.httpkit.server :as http]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-home
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-watcher-replay-" (random-uuid)))
    .mkdirs))

(defn- ^{:stratum 0} file-sink-stream
  "A stream whose only sink is the real file sink, rooted at `events-dir`."
  [events-dir]
  (es/create-event-stream
   {:config {:observability {:event-sinks [{:type :file :base-dir (str events-dir)}]}}}))

(defn- ^{:stratum 0} envelope
  [stream event-type wf-id]
  (es/create-envelope stream event-type wf-id (name event-type)))

(defn- ^{:stratum 0} await-true
  "Wait up to 10 s for `pred` to return truthy."
  [pred]
  (loop [deadline (+ (System/currentTimeMillis) 10000)]
    (cond
      (pred) true
      (> (System/currentTimeMillis) deadline) false
      :else (do (Thread/sleep 50) (recur deadline)))))

(defn- ^{:stratum 0} file-count
  [dir]
  (count (.listFiles (io/file dir))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} watched-events-are-not-written-back
  (let [home             (temp-home)
        events-dir       (io/file home "events")
        dashboard-stream (file-sink-stream events-dir)
        cli-stream       (file-sink-stream events-dir)
        wf-id            (random-uuid)
        run-dir          (es/workflow-dir (str events-dir) wf-id)
        replayed?        #(some (comp #{wf-id} :workflow/id) (es/get-events dashboard-stream))]
    (with-redefs [config/miniforge-home (constantly (str home))
                  http/run-server       (constantly :fake)
                  http/server-port      (constantly 7878)
                  http/server-stop!     (fn [_] (delay true))]
      (binding [*out* (java.io.StringWriter.)]
        (let [handle (server/start-server! {:event-stream dashboard-stream})]
          (try
            (es/publish! cli-stream (envelope cli-stream :workflow/started wf-id))
            (is (await-true replayed?) "the watcher delivers the run's event to the dashboard")
            (Thread/sleep 1500)
            (is (= 1 (file-count run-dir)) "the run's file is the only copy")
            (testing "an event the dashboard publishes itself is stored, once"
              (es/publish! dashboard-stream (envelope dashboard-stream :control-action/requested wf-id))
              (Thread/sleep 1500)
              (is (= 2 (file-count run-dir))))
            (finally (server/stop-server! handle))))))))
