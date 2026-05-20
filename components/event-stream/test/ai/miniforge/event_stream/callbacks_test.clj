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

(ns ai.miniforge.event-stream.callbacks-test
  "Tests for create-streaming-callback paired tool-call event emission.

   Verifies:
     • tool-use path emits :agent/tool-call-started + legacy :agent/tool-call
     • tool-use path does NOT emit :agent/status :tool-calling
     • tool-result path emits :tool/call-completed
     • duration-ms is computed when call-id correlates the pair
     • digest-content is applied to args-preview and result-content
     • nil args/results do not produce a digest key"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ helpers

(defn- make-stream []
  (es/create-event-stream))

(defn- run-callback
  "Creates a stream, attaches a subscriber, fires `events` through the
   callback, and returns the collected published events (in order)."
  [events & {:keys [agent workflow opts]
             :or   {agent    :implementer
                    workflow (random-uuid)
                    opts     {}}}]
  (let [stream (make-stream)
        collected (atom [])
        _ (es/subscribe! stream ::test-collector (fn [ev] (swap! collected conj ev)))
        cb (es/create-streaming-callback stream workflow agent opts)]
    (doseq [ev events]
      (cb ev))
    @collected))

;------------------------------------------------------------------------------ tool-use tests

(deftest tool-use-emits-legacy-and-started-events
  (testing "A tool-use event publishes both :agent/tool-call and :agent/tool-call-started"
    (let [events (run-callback [{:tool-use true
                                 :tool-name "Read"
                                 :tool-call-id "tc-001"
                                 :tool-args-preview "{:file_path \"/foo.clj\"}"}])
          types (mapv :event/type events)]
      (is (some #{:agent/tool-call} types)
          "legacy :agent/tool-call must be present")
      (is (some #{:agent/tool-call-started} types)
          ":agent/tool-call-started must be present"))))

(deftest tool-use-does-not-emit-agent-status-tool-calling
  (testing ":agent/status :tool-calling is NOT emitted — replaced by :agent/tool-call-started"
    (let [events (run-callback [{:tool-use true
                                 :tool-name "Grep"
                                 :tool-call-id "tc-002"}])
          status-tool-calling? (fn [ev]
                                 (and (= :agent/status (:event/type ev))
                                      (= :tool-calling (:agent/status ev))))]
      (is (not (some status-tool-calling? events))
          "bare :agent/status :tool-calling must not be emitted"))))

(deftest tool-call-started-carries-tool-name-and-call-id
  (testing ":agent/tool-call-started carries :tool/name and :tool/call-id"
    (let [events (run-callback [{:tool-use true
                                 :tool-name "Write"
                                 :tool-call-id "tc-003"
                                 :tool-args-preview "{:file_path \"/bar.clj\"}"}])
          started (first (filter #(= :agent/tool-call-started (:event/type %)) events))]
      (is (= "Write" (:tool/name started)))
      (is (= "tc-003" (:tool/call-id started))))))

(deftest tool-call-started-has-args-digest-when-preview-present
  (testing ":agent/tool-call-started includes :tool/args-digest when args-preview provided"
    (let [preview "{:file_path \"/src/core.clj\"}"
          events (run-callback [{:tool-use true
                                 :tool-name "Read"
                                 :tool-call-id "tc-004"
                                 :tool-args-preview preview}])
          started (first (filter #(= :agent/tool-call-started (:event/type %)) events))]
      (is (contains? started :tool/args-digest)
          ":tool/args-digest key must be present when args-preview given")
      (is (contains? (:tool/args-digest started) :digest/sha256)
          "digest must contain :digest/sha256")
      (is (contains? (:tool/args-digest started) :digest/preview)
          "digest must contain :digest/preview")
      (is (contains? (:tool/args-digest started) :digest/original-size)
          "digest must contain :digest/original-size"))))

(deftest tool-call-started-no-digest-when-no-preview
  (testing ":agent/tool-call-started does not add :tool/args-digest when args-preview is nil"
    (let [events (run-callback [{:tool-use true
                                 :tool-name "Glob"
                                 :tool-call-id "tc-005"}])
          started (first (filter #(= :agent/tool-call-started (:event/type %)) events))]
      (is (not (contains? started :tool/args-digest))
          ":tool/args-digest must not appear when no args-preview"))))

;------------------------------------------------------------------------------ tool-result tests

(deftest tool-result-emits-call-completed
  (testing "A tool-result event publishes :tool/call-completed"
    (let [events (run-callback [{:tool-result true
                                 :tool-call-id "tc-006"
                                 :tool-result-content "file contents here"
                                 :tool-result-is-error false}])
          types (mapv :event/type events)]
      (is (some #{:tool/call-completed} types)
          ":tool/call-completed must be published on tool-result"))))

(deftest tool-result-carries-success-flag
  (testing ":tool/call-completed carries :tool/success? true on non-error results"
    (let [events (run-callback [{:tool-result true
                                 :tool-call-id "tc-007"
                                 :tool-result-is-error false}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (true? (:tool/success? completed)))))

  (testing ":tool/call-completed carries :tool/success? false on error results"
    (let [events (run-callback [{:tool-result true
                                 :tool-call-id "tc-008"
                                 :tool-result-is-error true}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (false? (:tool/success? completed))))))

(deftest tool-result-has-result-digest-when-content-present
  (testing ":tool/call-completed includes :tool/result-digest when result-content provided"
    (let [events (run-callback [{:tool-result true
                                 :tool-call-id "tc-009"
                                 :tool-result-content "some tool output"}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (contains? completed :tool/result-digest)
          ":tool/result-digest must be present when result-content given")
      (is (contains? (:tool/result-digest completed) :digest/sha256)))))

(deftest tool-result-no-digest-when-no-content
  (testing ":tool/call-completed omits :tool/result-digest when result-content is nil"
    (let [events (run-callback [{:tool-result true
                                 :tool-call-id "tc-010"}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (not (contains? completed :tool/result-digest))))))

;------------------------------------------------------------------------------ duration tests

(deftest duration-ms-computed-when-call-id-correlates-pair
  (testing ":tool/duration-ms is present and non-negative when tool-use and tool-result share call-id"
    (let [events (run-callback [{:tool-use true
                                 :tool-name "Read"
                                 :tool-call-id "tc-dur-1"}
                                {:tool-result true
                                 :tool-result-call-id "tc-dur-1"}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (contains? completed :tool/duration-ms)
          ":tool/duration-ms must be present when call-ids correlate")
      (is (nat-int? (:tool/duration-ms completed))
          ":tool/duration-ms must be a non-negative integer"))))

(deftest duration-ms-nil-when-no-prior-tool-use
  (testing ":tool/duration-ms is absent when there is no tracked tool-use start"
    (let [events (run-callback [{:tool-result true}])
          completed (first (filter #(= :tool/call-completed (:event/type %)) events))]
      (is (not (contains? completed :tool/duration-ms))
          ":tool/duration-ms must be absent when no tracked start"))))

;------------------------------------------------------------------------------ passthrough tests

(deftest non-tool-events-still-emit-agent-chunk
  (testing "Plain delta events still produce :agent/chunk events"
    (let [events (run-callback [{:delta "hello " :done? false}
                                {:delta "world" :done? true}])
          types (mapv :event/type events)]
      (is (= [:agent/chunk :agent/chunk] types)))))

(deftest heartbeat-produces-no-events
  (testing "Heartbeat events are silently ignored"
    (let [events (run-callback [{:heartbeat true}])]
      (is (empty? events)))))

(deftest nil-stream-atom-does-not-throw
  (testing "Passing nil as stream-atom is safe — no publishes attempted"
    (let [cb (es/create-streaming-callback nil (random-uuid) :implementer {})]
      (is (nil? (cb {:tool-use true :tool-name "Read" :tool-call-id "x"})))
      (is (nil? (cb {:tool-result true :tool-call-id "x"})))
      (is (nil? (cb {:delta "text" :done? false}))))))
