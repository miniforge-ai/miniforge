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

(ns ai.miniforge.event-stream.timeline-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.timeline :as sut]))

;------------------------------------------------------------------------------ helpers

(defn- epoch->date
  "Coerce epoch-ms long to a java.util.Date."
  [ms]
  (java.util.Date. (long ms)))

(def ^:private base-ms
  "A fixed epoch-ms (2025-01-01T00:00:00Z) for deterministic timestamps."
  1735689600000)

(defn- mk-event
  "Build a minimal event map with optional extra keys merged in."
  [event-type ts-offset-ms & {:as extra}]
  (merge {:event/type      event-type
          :event/timestamp (epoch->date (+ base-ms ts-offset-ms))
          :workflow/phase  :implement}
         extra))

;------------------------------------------------------------------------------ tests

(deftest empty-events-returns-empty-string
  (testing "empty / nil input yields empty string — consistent return type so callers don't have to nil-check"
    (is (= "" (sut/render-timeline [])))
    (is (= "" (sut/render-timeline nil)))
    (is (= "" (sut/render-timeline [] {})))))

(deftest tool-call-started-renders-correctly
  (testing "agent/tool-call-started shows tool name and args digest preview"
    (let [event  (mk-event :agent/tool-call-started 0
                            :tool/name "Write"
                            :tool/args-digest {:digest/preview "Writing src/foo.clj"})
          result (sut/render-timeline [event])]
      (is (string? result))
      (is (str/includes? result "Write"))
      (is (str/includes? result "Writing src/foo.clj"))
      (is (str/includes? result "implement")))))

(deftest tool-call-completed-uses-tool-success-flag-not-error-presence
  (testing "tool/call-completed shows success status from :tool/success? per ToolCallCompleted schema"
    ;; ToolCallCompleted in schema.clj does NOT carry :tool/name; the
    ;; name is correlated via :tool/call-id back to the started event
    ;; (see render-timeline/index-tool-names). Status comes from
    ;; :tool/success? (boolean), with :tool/error presence as a
    ;; secondary signal — fixtures here match the real emitted shape.
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Write"
                            :tool/call-id "tc-1"
                            :tool/args-digest {:digest/preview "Writing src/foo.clj"})
                  (mk-event :tool/call-completed 1000
                            :tool/call-id   "tc-1"
                            :tool/duration-ms 423
                            :tool/success?  true)]
          result (sut/render-timeline events)]
      (is (str/includes? result "Write")
          "tool name resolved via :tool/call-id correlation, not from a non-existent :tool/name on the completed event")
      (is (str/includes? result "success"))
      (is (str/includes? result "0s"))))

  (testing "tool/call-completed shows error status when :tool/success? is false"
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Read"
                            :tool/call-id "tc-2")
                  (mk-event :tool/call-completed 2000
                            :tool/call-id   "tc-2"
                            :tool/duration-ms 100
                            :tool/success?  false
                            :tool/error     {:type :not-found :path "/missing.clj"})]
          result (sut/render-timeline events)]
      (is (str/includes? result "error")
          "status is sourced from :tool/success? false, not from :tool/error string presence")
      (is (str/includes? result "Read")
          "tool name still resolves via correlation in the error path"))))

(deftest tool-call-completed-falls-back-to-call-id-when-no-started-event
  (testing "if the started event is missing, the completed event renders with :tool/call-id as the tool label"
    ;; Defensive: prevents <unknown> from showing up when there's a
    ;; correlatable id but no preceding started event (e.g. truncated
    ;; event log, replay starting mid-call).
    (let [event  (mk-event :tool/call-completed 0
                            :tool/call-id  "tc-orphan"
                            :tool/success? true)
          result (sut/render-timeline [event])]
      (is (str/includes? result "tc-orphan")
          "call-id substitutes for tool name when correlation is impossible")
      (is (not (str/includes? result "<unknown>"))))))

(deftest tool-call-pair-renders-both-lines
  (testing "started + completed pair produces two lines, name correlated via :tool/call-id"
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Bash"
                            :tool/call-id "tc-3"
                            :tool/args-digest {:digest/preview "ls -la"})
                  (mk-event :tool/call-completed 500
                            :tool/call-id   "tc-3"
                            :tool/duration-ms 500
                            :tool/success?  true)]
          result (sut/render-timeline events)
          lines  (str/split-lines result)]
      (is (= 2 (count lines)))
      (is (str/includes? (first lines) "Bash"))
      (is (str/includes? (second lines) "Bash")
          "completed line carries the same tool name as started, via correlation")
      (is (str/includes? (second lines) "success")))))

(deftest gap-detection-inserts-stall-line
  (testing "gap above threshold inserts a gap line between events"
    ;; Put events 90 seconds apart (above default 60s threshold)
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Read")
                  (mk-event :agent/tool-call-started 90000
                            :tool/name "Write")]
          result (sut/render-timeline events)
          lines  (str/split-lines result)]
      (is (= 3 (count lines)))
      (is (str/includes? (second lines) "event-stream gap (stalled)"))))

  (testing "gap below threshold does not insert a gap line"
    ;; 30 seconds — under default 60s
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Read")
                  (mk-event :agent/tool-call-started 30000
                            :tool/name "Write")]
          result (sut/render-timeline events)
          lines  (str/split-lines result)]
      (is (= 2 (count lines)))))

  (testing "custom gap threshold is respected"
    ;; 15 seconds gap, threshold set to 10s — should trigger
    (let [events [(mk-event :agent/tool-call-started 0
                            :tool/name "Read")
                  (mk-event :agent/tool-call-started 15000
                            :tool/name "Write")]
          result (sut/render-timeline events {:gap-threshold-ms 10000})
          lines  (str/split-lines result)]
      (is (= 3 (count lines)))
      (is (str/includes? (second lines) "event-stream gap (stalled)")))))

(deftest terminal-event-renders-terminated-marker
  (testing "workflow/completed renders TERMINATED marker"
    (let [event  (mk-event :workflow/completed 5000
                            :message "all phases passed")
          result (sut/render-timeline [event])]
      (is (str/includes? result "TERMINATED"))
      (is (str/includes? result "all phases passed"))))

  (testing "workflow/failed renders TERMINATED marker"
    (let [event  (mk-event :workflow/failed 5000
                            :message "phase implement failed")
          result (sut/render-timeline [event])]
      (is (str/includes? result "TERMINATED"))
      (is (str/includes? result "phase implement failed")))))

(deftest phase-lifecycle-events-render
  (testing "workflow/phase-started renders phase started label"
    (let [event  (mk-event :workflow/phase-started 0
                            :workflow/phase :plan
                            :message "starting plan phase")
          result (sut/render-timeline [event])]
      (is (str/includes? result "phase started"))
      (is (str/includes? result "starting plan phase"))))

  (testing "workflow/phase-completed renders phase completed label"
    (let [event  (mk-event :workflow/phase-completed 1000
                            :workflow/phase :plan)
          result (sut/render-timeline [event])]
      (is (str/includes? result "phase completed")))))

(deftest stream-stall-event-renders
  (testing "agent/stream-stalled renders stall marker"
    (let [event  (mk-event :agent/stream-stalled 3000
                            :message "no output for 120s")
          result (sut/render-timeline [event])]
      (is (str/includes? result "[stall]"))
      (is (str/includes? result "no output for 120s")))))

(deftest generic-event-renders-fallback
  (testing "unknown event type renders event-type + message"
    (let [event  (mk-event :some/unknown-event 1000
                            :message "something happened")
          result (sut/render-timeline [event])]
      (is (str/includes? result ":some/unknown-event"))
      (is (str/includes? result "something happened")))))

(deftest non-string-event-messages-render-as-empty-test
  (testing "phase, generic, and tool renderers tolerate a missing message key"
    (doseq [event-type [:workflow/phase-started
                        :some/unknown-event
                        :agent/tool-call-started]]
      (is (string? (sut/render-timeline [(mk-event event-type 0)]))
          (str event-type " missing :message"))))
  (testing "non-string messages are omitted rather than stringified"
    (doseq [event-type [:workflow/phase-started
                        :some/unknown-event
                        :agent/tool-call-started]
            message    [false :not-a-string 42]
            :let       [result (sut/render-timeline
                                [(mk-event event-type 0 :message message)])]]
      (is (not (str/includes? result (str message)))
          (str event-type " did not render " (pr-str message)))))
  (testing "a malformed args preview falls back to a valid event message"
    (let [result (sut/render-timeline
                  [(mk-event :agent/tool-call-started 0
                             :message "safe fallback"
                             :tool/args-digest {:digest/preview 42})])]
      (is (str/includes? result "safe fallback"))
      (is (not (str/includes? result "42"))))))

(deftest events-without-timestamps-handled-gracefully
  (testing "events with nil timestamp render with ?? placeholders but do not throw"
    (let [event  {:event/type     :agent/tool-call-started
                  :workflow/phase :implement
                  :tool/name      "Read"}
          result (sut/render-timeline [event])]
      (is (string? result))
      (is (str/includes? result "??:??:??"))))

  (testing "gap detection is skipped when timestamps are absent"
    (let [events [{:event/type     :agent/tool-call-started
                   :tool/name      "Read"}
                  {:event/type     :agent/tool-call-started
                   :tool/name      "Write"}]
          result (sut/render-timeline events)
          lines  (str/split-lines result)]
      ;; No gap line expected — timestamps are nil so gap cannot be computed
      (is (= 2 (count lines))))))

(deftest args-summary-truncated-at-60-chars
  (testing "args preview longer than 60 chars is truncated"
    (let [long-preview (apply str (repeat 80 "x"))
          event        (mk-event :agent/tool-call-started 0
                                 :tool/name "Write"
                                 :tool/args-digest {:digest/preview long-preview})
          result       (sut/render-timeline [event])]
      ;; Must not contain the full 80-char string
      (is (not (str/includes? result long-preview)))
      ;; Must contain truncation indicator
      (is (str/includes? result "…")))))

(deftest message-fallback-when-no-args-digest
  (testing "falls back to :message when :tool/args-digest is absent"
    (let [event  (mk-event :agent/tool-call-started 0
                            :tool/name "Bash"
                            :message "running tests")
          result (sut/render-timeline [event])]
      (is (str/includes? result "running tests")))))

(deftest render-timeline-returns-string
  (testing "render-timeline with multiple events returns a single string"
    (let [events [(mk-event :workflow/phase-started 0 :workflow/phase :plan)
                  (mk-event :agent/tool-call-started 1000
                            :tool/name "Write"
                            :tool/args-digest {:digest/preview "foo"})
                  (mk-event :tool/call-completed 2000
                            :tool/name "Write"
                            :tool/duration-ms 1000)
                  (mk-event :workflow/completed 3000 :message "done")]
          result (sut/render-timeline events)]
      (is (string? result))
      (is (= 4 (count (str/split-lines result)))))))
