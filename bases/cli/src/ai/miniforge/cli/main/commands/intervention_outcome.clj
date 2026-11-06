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
(ns ai.miniforge.cli.main.commands.intervention-outcome
  "What became of an intervention request this CLI wrote.

   `es/request-intervention!` only queues the request in the operator
   directory. The process running the targeted workflow consumes it and
   publishes each lifecycle step as a `:supervisory/intervention-state-changed`
   event into that workflow's event directory. Reading those events back
   is how the CLI learns whether its request took effect, rather than
   reporting a queued file as done."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.interface :as operator]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private mtime-slack-ms
  "File modification times can be coarser than a millisecond, so files
   modified up to this long before the request are read too."
  2000)

(defn- ^{:stratum 0} event-files-since
  "The event files in `dir` modified at or after `since-ms`. Only these
   are parsed: a long run's directory holds thousands of older events."
  [dir since-ms]
  (filter (fn [^java.io.File f]
            (and (.isFile f)
                 (.endsWith (.getName f) ".json")
                 (>= (.lastModified f) since-ms)))
          (.listFiles (io/file dir))))

(defn- ^{:stratum 0} transition-of?
  "True for a lifecycle event of `intervention-id`. Read events carry ids
   as strings."
  [intervention-id event]
  (and (= :supervisory/intervention-state-changed (:event/type event))
       (= (str intervention-id) (str (:intervention/id event)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} latest-transition
  "The newest lifecycle event of `intervention-id` in `workflow-id`'s event
   directory under `events-dir`, among files written since `since-ms`; nil
   when there is none (the request has not been consumed)."
  [events-dir workflow-id intervention-id since-ms]
  (when-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (->> (event-files-since dir (- since-ms mtime-slack-ms))
         (keep es/read-event-file)
         (filter (partial transition-of? intervention-id))
         (sort-by es/event-order-key)
         last)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} await-transition
  "Poll every `poll-ms` for the lifecycle of `intervention-id` until it
   reaches a terminal state or `timeout-ms` has passed. Returns the last
   lifecycle event seen (`:intervention/state` is a keyword), or nil when
   no process consumed the request in time."
  [{:keys [events-dir workflow-id intervention-id since-ms timeout-ms poll-ms]}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [seen (latest-transition events-dir workflow-id intervention-id since-ms)]
        (if (or (operator/intervention-terminal-state? (:intervention/state seen))
                (>= (System/currentTimeMillis) deadline))
          seen
          (do (Thread/sleep (long poll-ms))
              (recur)))))))
