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
(ns ai.miniforge.agent-worker.native.stream
  "Read a native harness's stream file with that backend's own llm parser:
   the native session id, and usage only as reported."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} parsed-chunks
  [parse-line text]
  (keep parse-line (str/split-lines (str text))))

(defn- ^{:stratum 0} native-id
  "The session a turn ran in: the reported one, else (a first turn cut
   before its result) the one the harness announced at start."
  [chunks]
  (or (some #(or (:session-id %) (:invocation-id %)) chunks) (some :announced-session-id chunks)))

(defn- ^{:stratum 0} add-message
  "Add one assistant frame's usage, once per message (frames of one message
   repeat its id and its usage). Only reported quantities are added, so one
   nobody reported stays absent."
  [acc {:keys [message-id message-usage]}]
  (if (and message-id (= message-id (::message-id acc)))
    acc
    (cond-> (assoc acc ::message-id message-id)
      (:input-tokens message-usage) (update :input-tokens (fnil + 0) (:input-tokens message-usage))
      (:output-tokens message-usage) (update :output-tokens (fnil + 0) (:output-tokens message-usage)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} reported-usage
  "The final usage, else (a cancelled or crashed turn) the tokens its calls
   billed, summed per message; and the reported cost. A quantity nobody
   reported is absent."
  [chunks]
  (let [frames (seq (filter :message-usage chunks))
        usage (or (last (keep :usage chunks))
                  (when frames (dissoc (reduce add-message {} frames) ::message-id)))
        cost (last (keep :cost-usd chunks))]
    (cond-> {}
      (number? (:input-tokens usage)) (assoc :usage/input-tokens (:input-tokens usage))
      (number? (:output-tokens usage)) (assoc :usage/output-tokens (:output-tokens usage))
      (number? cost) (assoc :usage/cost-usd cost))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-stream
  "`{:stream/native-id s-or-nil :stream/usage {...}}` from a stream file's text."
  [parse-line text]
  (let [chunks (parsed-chunks parse-line text)]
    {:stream/native-id (native-id chunks)
     :stream/usage (reported-usage chunks)}))
