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
(ns ai.miniforge.operator.consumer-test
  "Operator-event consumer (Phase D D-2) tests.

   The vendored fixtures under contracts/operator-events/golden are the
   cross-language contract: they are byte-for-byte copies of what the
   Rust control-plane-client's generator emits, so the consumer parsing
   them here IS the consumer parsing production request files. A shape
   change on either side fails the golden test, not a dogfood run.

   Fixtures and stagers live in the sibling consumer-test-support
   namespace so this file's deftests reach them only through
   cross-namespace calls — keeping the deftest call graph flat and the
   file within its stratified-design layer budget."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.consumer-test-support :as support]
   [ai.miniforge.operator.intervention :as intervention]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.channels FileChannel]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} invalid-request-identities-are-rejected
  (let [valid {:event/id (random-uuid)
               :intervention/id (random-uuid)
               :workflow/id (random-uuid)}]
    (is (#'consumer/valid-request-identities? valid))
    (doseq [k [:event/id :intervention/id :workflow/id]]
      (is (false? (#'consumer/valid-request-identities?
                   (assoc valid k "not-a-uuid")))
          (name k)))))

;------------------------------------------------------------------------------ Malformed input is loud, never silent
(deftest ^{:stratum 0} unreadable-file-emits-anomaly-once
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)]
    (support/stage-operator-file! events-dir "garbage.json" "{not json at all")
    (is (= {:routed 0 :skipped 0 :anomalies 1}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))
    (let [anomalies (support/events-of-type stream consumer/anomaly-event-type)]
      (is (= 1 (count anomalies)))
      (is (= "garbage.json" (:source/file (first anomalies))))
      (is (= (get-in (first anomalies) [:anomaly :anomaly/message])
             (:message (first anomalies)))
          "the envelope surfaces the specific anomaly message"))
    (is (= {:routed 0 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir :stream stream}))
        "an anomalous file is remembered, not re-reported every pass")
    (is (.exists (io/file events-dir "operator" "garbage.json"))
        "append-only: the consumer never deletes operator events")))

(deftest ^{:stratum 0} invalid-intervention-emits-anomaly
  (testing "a valid request id is remembered after request validation fails"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          content (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
                       "\"~:intervention/id\":\"~u00000000-0000-4000-8000-00000000dead\","
                       "\"~:intervention/type\":\"~:frobnicate\","
                       "\"~:intervention/target-id\":\"t-1\","
                       "\"~:intervention/requested-by\":\"op@example.invalid\","
                       "\"~:intervention/request-source\":\"~:tui\"}")]
      (support/stage-operator-file!
       events-dir "bad-type.json"
       content)
      (is (= {:routed 0 :skipped 0 :anomalies 1}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (support/stage-operator-file! events-dir "bad-type-redelivery.json" content)
      (is (= {:routed 0 :skipped 1 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (is (= 1 (count (support/events-of-type stream consumer/anomaly-event-type)))
          "a new filename for the same intervention id must not repeat the anomaly"))))

(deftest ^{:stratum 0} foreign-operator-events-are-skipped-quietly
  (testing "meta-loop/train events legitimately share the directory"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)]
      (support/stage-operator-file!
       events-dir "pr-merged.json"
       "{\"~:event/type\":\"~:pr/merged\",\"~:pr/number\":42}")
      (is (= {:routed 0 :skipped 1 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (is (empty? (es/get-events stream))
          "foreign events are neither routed nor flagged")
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))
          "and each file is examined once"))))

;------------------------------------------------------------------------------ Approval gate + application hook
(deftest ^{:stratum 0} non-operator-source-parks-at-pending-human
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)]
    (support/stage-operator-file!
     events-dir "api-pause.json"
     (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
          "\"~:intervention/id\":\"~u00000000-0000-4000-8000-0000000000f1\","
          "\"~:intervention/type\":\"~:pause\","
          "\"~:intervention/target-id\":\"00000000-0000-4000-8000-0000000000aa\","
          "\"~:intervention/requested-by\":\"delegate@example.invalid\","
          "\"~:intervention/request-source\":\"~:api\"}"))
    (consumer/consume-pass! {:events-dir events-dir :stream stream})
    (let [changes (support/events-of-type stream consumer/state-changed-event-type)]
      (is (= 1 (count changes)))
      (is (= :pending-human (:intervention/state (first changes)))))))

(deftest ^{:stratum 0} request-source-determines-the-approval-gate
  ;; The runner wires this gate: a request's source decides whether it
  ;; auto-approves (the human already performed the gesture — approving
  ;; it again would approve the approver) or parks at :pending-human for
  ;; a human. D-4 added :cli and :dashboard to the auto-approve set
  ;; alongside :tui / :native-app; delegated sources (:api, :meta-agent)
  ;; still park. Assert every arm — the earlier golden test only exercises
  ;; :tui / :native-app, and the park test only :api.
  (doseq [[source expected suffix]
          [[:cli :approved "c1"]
           [:dashboard :approved "d1"]
           [:tui :approved "71"]
           [:native-app :approved "a1"]
           [:api :pending-human "e1"]
           [:meta-agent :pending-human "e2"]]]
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)]
      (support/stage-operator-file!
       events-dir (str "req-" (name source) ".json")
       (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
            "\"~:intervention/id\":\"~u00000000-0000-4000-8000-0000000000" suffix "\","
            "\"~:intervention/type\":\"~:pause\","
            "\"~:intervention/target-id\":\"00000000-0000-4000-8000-0000000000aa\","
            "\"~:intervention/requested-by\":\"caller@example.invalid\","
            "\"~:intervention/request-source\":\"~:" (name source) "\"}"))
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [changes (support/events-of-type stream consumer/state-changed-event-type)]
        (is (= 1 (count changes)) (str source " routes exactly one transition"))
        (is (= expected (:intervention/state (first changes)))
            (str source " must gate to " expected))))))

(deftest ^{:stratum 0} governed-republish-uses-server-lifecycle-and-target
  (testing "client-claimed state, timestamps, and workflow routing are ignored"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          source (es/read-event-file
                  (io/file (support/golden-dir) "pause.transit.json"))
          forged-workflow-id (random-uuid)
          forged-event-id (random-uuid)
          forged-time (java.util.Date/from
                       (java.time.Instant/parse "2000-01-01T00:00:00Z"))
          forged (assoc source
                        :event/id forged-event-id
                        :event/timestamp forged-time
                        :workflow/id forged-workflow-id
                        :intervention/state :verified
                        :intervention/details {:failure/code :forged}
                        :intervention/requested-at forged-time
                        :intervention/updated-at forged-time)]
      (support/stage-operator-file! events-dir "forged.transit.json"
                                    (es/serialize-event forged))
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [requested (first (support/events-of-type
                              stream consumer/intervention-requested-event-type))]
        (is (= (parse-uuid (:intervention/target-id source))
               (:workflow/id requested)))
        (is (not= forged-workflow-id (:workflow/id requested)))
        (is (= :proposed (:intervention/state requested)))
        (is (nil? (get-in requested
                          [:intervention/details :failure/code])))
        (is (not= forged-time (:intervention/requested-at requested)))
        (is (not= forged-time (:intervention/updated-at requested)))
        (is (not= forged-event-id (:event/id requested))
            "the governed event id is server-assigned, not the wire id")
        (is (not= forged-time (:event/timestamp requested))
            "the governed event timestamp is the server audit clock,
             not the client-claimed wire time")))))

(deftest ^{:stratum 0} routed-request-anomalies-stay-on-the-operator-stream
  (let [events-dir (support/temp-events-dir)
        operator-stream (support/memory-stream)
        workflow-stream (support/memory-stream)
        source (es/read-event-file
                (io/file (support/golden-dir) "pause.transit.json"))
        invalid (assoc source :intervention/target-type :degradation)]
    (support/stage-operator-file! events-dir "invalid.transit.json"
                                  (es/serialize-event invalid))
    (is (= {:routed 0 :skipped 0 :anomalies 1}
           (consumer/consume-pass!
            {:events-dir events-dir
             :stream operator-stream
             :stream-for (constantly workflow-stream)})))
    (is (= [consumer/anomaly-event-type]
           (mapv :event/type (es/get-events operator-stream))))
    (is (empty? (es/get-events workflow-stream)))))

(deftest ^{:stratum 0} duplicate-intervention-id-under-new-filename-is-skipped
  (testing "id-level idempotency survives re-delivery as a different file"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          content (slurp (io/file (support/golden-dir) "pause.transit.json")
                         :encoding "UTF-8")]
      (support/stage-operator-file! events-dir "20260101T000000000Z-aaaa.json" content)
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (support/stage-operator-file! events-dir "20260101T000000001Z-bbbb.json" content)
      (is (= {:routed 0 :skipped 1 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))))))

(deftest ^{:stratum 0} golden-fixtures-route-through-the-lifecycle
  (testing "every vendored Rust-produced fixture parses, validates, and routes"
    (let [manifest (edn/read-string
                    (slurp (io/file (support/golden-dir) "manifest.edn")
                           :encoding "UTF-8"))
          fixture-names (mapv #(str (name %) ".transit.json")
                              (:fixture/scenarios manifest))]
      (is (= support/golden-fixture-count (count fixture-names)))
      ;; One events-dir per scenario: the deterministic generator stamps
      ;; the SAME intervention id on every fixture, and the consumer's
      ;; id-level idempotency (correct per Phase D) would dedup 2..N in
      ;; a shared directory. Isolation tests the contract per scenario.
      (doseq [f fixture-names]
        (testing f
          (let [events-dir (support/temp-events-dir)
                stream (support/memory-stream)]
            (support/stage-golden! events-dir f)
            (is (= {:routed 1 :skipped 0 :anomalies 0}
                   (consumer/consume-pass! {:events-dir events-dir
                                            :stream stream})))
            (is (= 1 (count (support/events-of-type
                             stream
                             consumer/intervention-requested-event-type))))
            ;; Every fixture carries a request-source in
            ;; consumer/auto-approve-request-sources (:tui or
            ;; :native-app) → auto-approved.
            (let [changes (support/events-of-type
                           stream consumer/state-changed-event-type)]
              (is (= 1 (count changes)))
              (is (= :approved (:intervention/state (first changes)))))))))))

(deftest ^{:stratum 0} republished-events-carry-typed-identity
  (testing "tag-stripped strings are revived before republish (schema:
            :event/id uuid?, timestamps inst?, :workflow/id uuid?)"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)]
      (support/stage-golden! events-dir "pause.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [requested (first (support/events-of-type
                              stream consumer/intervention-requested-event-type))
            change (first (support/events-of-type
                           stream consumer/state-changed-event-type))]
        (is (uuid? (:event/id requested)))
        (is (uuid? (:intervention/id requested)))
        (is (uuid? (:workflow/id requested)))
        (is (inst? (:event/timestamp requested)))
        (is (inst? (:intervention/requested-at requested)))
        (is (uuid? (:intervention/id change)))
        (is (uuid? (:workflow/id change))
            "workflow-targeted lifecycle events must key sequence
             numbering by the same UUID the runner uses")
        (is (< (:event/sequence-number requested)
               (:event/sequence-number change))
            "republishing the request advances the workflow sequence")))))

(deftest ^{:stratum 0} workflow-request-routes-to-its-registered-stream
  (let [events-dir (support/temp-events-dir)
        operator-stream (support/memory-stream)
        workflow-stream (support/memory-stream)]
    (support/stage-golden! events-dir "pause.transit.json")
    (is (= {:routed 1 :skipped 0 :anomalies 0}
           (consumer/consume-pass!
            {:events-dir events-dir
             :stream operator-stream
             :stream-for (constantly workflow-stream)})))
    (is (empty? (es/get-events operator-stream)))
    (is (= [consumer/intervention-requested-event-type
            consumer/state-changed-event-type]
           (mapv :event/type (es/get-events workflow-stream))))))

;------------------------------------------------------------------------------ Idempotency
(deftest ^{:stratum 0} second-pass-is-a-no-op
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)]
    (support/stage-golden! events-dir "pause.transit.json")
    (is (= {:routed 1 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))
    (let [event-count (count (es/get-events stream))]
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (is (= event-count (count (es/get-events stream)))
          "a processed file must publish nothing on re-scan"))))

(deftest ^{:stratum 0} cursor-survives-restart
  (testing "a fresh consumer (new process) trusts the on-disk cursor"
    (let [events-dir (support/temp-events-dir)
          stream-a (support/memory-stream)
          stream-b (support/memory-stream)]
      (support/stage-golden! events-dir "cancel.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream-a})
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream-b})))
      (is (empty? (es/get-events stream-b))))))

(deftest ^{:stratum 0} unowned-request-is-deferred-without-advancing-cursors
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)]
    (support/stage-golden! events-dir "pause.transit.json")
    (is (= {:routed 0 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir
                                    :stream stream
                                    :accept? support/reject-every-request?})))
    (is (empty? (:processed-files
                 (consumer/read-cursor (es/operator-dir events-dir)))))
    (is (= {:routed 1 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))))

(deftest ^{:stratum 0} held-consumer-lock-defers-the-entire-pass
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)
        lock-file (io/file events-dir "operator" ".consumer.lock")]
    (support/stage-golden! events-dir "pause.transit.json")
    (with-open [channel (FileChannel/open
                         (.toPath lock-file)
                         (into-array java.nio.file.OpenOption
                                     [java.nio.file.StandardOpenOption/CREATE
                                      java.nio.file.StandardOpenOption/WRITE]))
                _lock (.lock channel)]
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))))
    (is (= {:routed 1 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))))

(deftest ^{:stratum 0} apply-hook-receives-only-approved-interventions
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)
        applied (atom [])]
    (support/stage-golden! events-dir "pause.transit.json")
    (consumer/consume-pass! {:events-dir events-dir
                             :stream stream
                             :apply! (fn [_stream interv]
                                       (swap! applied conj interv))})
    (is (= 1 (count @applied)))
    (is (= :approved (:intervention/state (first @applied))))
    (is (= :pause (:intervention/type (first @applied))))))

(deftest ^{:stratum 0} approval-transition-failure-emits-anomaly
  (let [events-dir (support/temp-events-dir)
        stream (support/memory-stream)]
    (support/stage-golden! events-dir "pause.transit.json")
    (with-redefs [intervention/approve
                  (constantly {:success? false
                               :error :invalid-transition
                               :message "approval transition rejected"})]
      (is (= {:routed 0 :skipped 0 :anomalies 1}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))))
    (let [event (first (support/events-of-type stream consumer/anomaly-event-type))]
      (is (= "approval transition rejected" (:message event)))
      (is (= :invalid-transition
             (get-in event [:anomaly :anomaly/data :error]))))))

(deftest ^{:stratum 0} workflow-targeted-state-changes-carry-workflow-id
  (testing "audit trail lands in the run's own event directory"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)]
      (support/stage-golden! events-dir "pause.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [change (first (support/events-of-type
                           stream consumer/state-changed-event-type))]
        (is (some? (:workflow/id change)))))))

;------------------------------------------------------------------------------ Operator decision channel (U-6 §8.2)
;;
;; A delegated source proposes and parks at `:pending-human`. Without an
;; inbound decision channel the gate is one-way: the operator sees an
;; approval card with nowhere to send the answer.
(deftest ^{:stratum 0} delegated-request-parks-then-approves-across-passes
  (testing "a :meta-agent request waits for a human, then applies"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          intervention-id (random-uuid)
          applied (atom [])
          apply-fn (fn [_ i] (swap! applied conj i))]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream :apply! apply-fn})

      (testing "pass one parks it and does NOT apply"
        (is (= [:pending-human]
               (mapv :intervention/state
                     (support/events-of-type
                      stream :supervisory/intervention-state-changed))))
        (is (empty? @applied) "a delegated write must not act before a human answers"))

      (testing "pass two applies the operator's approval"
        (support/stage-operator-file!
         events-dir "decide.transit.json"
         (es/serialize-event (support/decision-event intervention-id :approve)))
        (let [result (consumer/consume-pass! {:events-dir events-dir
                                              :stream stream
                                              :apply! apply-fn})]
          (is (= 1 (:routed result)))
          (is (= [:pending-human :approved]
                 (mapv :intervention/state
                       (support/events-of-type
                        stream :supervisory/intervention-state-changed))))
          (is (= 1 (count @applied)))
          (is (= intervention-id (:intervention/id (first @applied)))))))))

(deftest ^{:stratum 0} a-decision-routes-to-the-workflows-registered-stream
  (testing "the verdict lands where the run lives, not on the operator stream"
    ;; The router reads `:intervention/type` and
    ;; `:intervention/target-id`. A decision event carries neither — it
    ;; is deliberately thin — so routing off the EVENT returns nil for
    ;; every decision and silently falls back to the operator stream.
    ;; Routing off the parked intervention is what makes this pass.
    (let [events-dir (support/temp-events-dir)
          operator-stream (support/memory-stream)
          workflow-stream (support/memory-stream)
          intervention-id (random-uuid)
          applied (atom [])
          ;; Mirror the production router: workflow-targeted only, and
          ;; keyed off fields a decision event does not carry.
          stream-for (fn [event]
                       (when (= :workflow (:intervention/target-type event))
                         workflow-stream))]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir
                               :stream operator-stream
                               :stream-for stream-for
                               :apply! (fn [dest i] (swap! applied conj [dest i]))})
      (support/stage-operator-file!
       events-dir "decide.transit.json"
       (es/serialize-event (support/decision-event intervention-id :approve)))
      (consumer/consume-pass! {:events-dir events-dir
                               :stream operator-stream
                               :stream-for stream-for
                               :apply! (fn [dest i] (swap! applied conj [dest i]))})

      (is (= [:pending-human :approved]
             (mapv :intervention/state
                   (support/events-of-type
                    workflow-stream :supervisory/intervention-state-changed)))
          "both transitions belong on the workflow's own stream")
      (is (empty? (support/events-of-type
                   operator-stream :supervisory/intervention-state-changed))
          "nothing should land on the operator stream")
      (is (= [workflow-stream] (mapv first @applied))
          "and the application must run against the workflow's stream"))))

(deftest ^{:stratum 0} a-rejection-never-applies
  (testing "reject transitions and records the reason without applying"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          intervention-id (random-uuid)
          applied (atom [])
          apply-fn (fn [_ i] (swap! applied conj i))]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream :apply! apply-fn})
      (support/stage-operator-file!
       events-dir "decide.transit.json"
       (es/serialize-event
        (assoc (support/decision-event intervention-id :reject)
               :intervention/reason "not while the train is red")))
      (consumer/consume-pass! {:events-dir events-dir :stream stream :apply! apply-fn})

      (let [last-change (last (support/events-of-type
                               stream :supervisory/intervention-state-changed))]
        (is (= :rejected (:intervention/state last-change)))
        (is (= "not while the train is red" (:intervention/reason last-change)))
        (is (empty? @applied))))))

(deftest ^{:stratum 0} a-decision-with-a-malformed-identity-is-rejected
  (testing "an unparseable :workflow/id must not route to a default stream"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          intervention-id (random-uuid)]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      ;; `serialize-event` would reject a non-uuid :workflow/id, so the
      ;; malformed value is staged directly — the shape a foreign or
      ;; half-migrated producer would actually leave on disk.
      (support/stage-operator-file!
       events-dir "decide.transit.json"
       (str/replace (es/serialize-event (support/decision-event intervention-id :approve))
                    "\"~:intervention/id\""
                    "\"~:workflow/id\":\"not-a-uuid\",\"~:intervention/id\""))
      (let [result (consumer/consume-pass! {:events-dir events-dir :stream stream})]
        (is (= 1 (:anomalies result)))
        (is (= [:pending-human]
               (mapv :intervention/state
                     (support/events-of-type
                      stream :supervisory/intervention-state-changed)))
            "the parked intervention must not have moved")))))

(deftest ^{:stratum 0} a-decision-for-an-unparked-intervention-is-an-anomaly
  (testing "approving something never parked must not pass silently"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)]
      (support/stage-operator-file!
       events-dir "decide.transit.json"
       (es/serialize-event (support/decision-event (random-uuid) :approve)))
      (let [result (consumer/consume-pass! {:events-dir events-dir :stream stream})]
        (is (= 1 (:anomalies result)))
        (is (= 1 (count (support/events-of-type
                         stream :operator/intervention-anomaly))))))))

(deftest ^{:stratum 0} an-unknown-verdict-is-an-anomaly
  (testing "only :approve and :reject are verdicts"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          intervention-id (random-uuid)]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (support/stage-operator-file!
       events-dir "decide.transit.json"
       (es/serialize-event (support/decision-event intervention-id :maybe)))
      (is (= 1 (:anomalies (consumer/consume-pass!
                            {:events-dir events-dir :stream stream})))))))

(deftest ^{:stratum 0} deciding-twice-is-not-honoured-twice
  (testing "the parked record is retired on the first decision"
    (let [events-dir (support/temp-events-dir)
          stream (support/memory-stream)
          intervention-id (random-uuid)
          applied (atom [])
          apply-fn (fn [_ i] (swap! applied conj i))]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream :apply! apply-fn})
      (support/stage-operator-file!
       events-dir "decide-1.transit.json"
       (es/serialize-event (support/decision-event intervention-id :approve)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream :apply! apply-fn})
      (support/stage-operator-file!
       events-dir "decide-2.transit.json"
       (es/serialize-event (support/decision-event intervention-id :approve)))
      (let [result (consumer/consume-pass! {:events-dir events-dir
                                            :stream stream
                                            :apply! apply-fn})]
        (is (= 1 (:anomalies result)) "the second decision has nothing to decide")
        (is (= 1 (count @applied)) "and must not apply the intervention again")))))

(deftest ^{:stratum 0} a-v1-cursor-upgrades-without-losing-its-processed-sets
  (testing "an in-place upgrade keeps idempotency and gains the parked map"
    (let [events-dir (support/temp-events-dir)
          operator-dir (io/file events-dir "operator")
          _ (io/make-parents (io/file operator-dir ".keep"))
          _ (spit (io/file operator-dir ".processed")
                  (pr-str {:schema-version 1
                           :processed-intervention-ids #{}
                           :processed-files #{"already-seen.transit.json"}})
                  :encoding "UTF-8")
          stream (support/memory-stream)
          intervention-id (random-uuid)]
      (support/stage-operator-file!
       events-dir "req.transit.json"
       (es/serialize-event (support/meta-agent-request intervention-id)))
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [cursor (edn/read-string (slurp (io/file operator-dir ".processed")
                                           :encoding "UTF-8"))]
        (is (= 2 (:schema-version cursor)))
        (is (contains? (:processed-files cursor) "already-seen.transit.json")
            "the v1 processed set must survive the upgrade")
        (is (contains? (:pending-interventions cursor) intervention-id))))))
