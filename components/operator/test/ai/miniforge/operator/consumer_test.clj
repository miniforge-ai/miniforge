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
   change on either side fails the golden test, not a dogfood run."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.intervention :as intervention]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Helpers

(def ^:const max-workspace-walk-hops
  "Upper bound on parent-directory hops when locating workspace.edn —
   deep enough for any worktree nesting, small enough to fail fast when
   the tests run outside the workspace entirely."
  8)

(defn- find-workspace-root
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops max-workspace-walk-hops))
      (throw (ex-info "workspace.edn not found walking up from user.dir"
                      {:user-dir (System/getProperty "user.dir")}))
      :else (recur (.getParentFile dir) (inc hops)))))

(defn- golden-dir
  ^java.io.File []
  (io/file (find-workspace-root) "contracts" "operator-events" "golden"))

(defn- temp-events-dir
  ^java.io.File []
  (.toFile (Files/createTempDirectory "operator-consumer-test"
                                      (make-array FileAttribute 0))))

(defn- stage-operator-file!
  "Copy fixture content into `{events-dir}/operator/{file-name}`."
  [events-dir file-name content]
  (let [target (io/file events-dir "operator" file-name)]
    (io/make-parents target)
    (spit target content :encoding "UTF-8")
    target))

(defn- stage-golden!
  [events-dir fixture-name]
  (stage-operator-file! events-dir fixture-name
                        (slurp (io/file (golden-dir) fixture-name)
                               :encoding "UTF-8")))

(defn- memory-stream
  "An event stream with no sinks: published events accumulate in the
   in-memory log only, so assertions read `es/get-events`."
  []
  (es/create-event-stream {:sinks []}))

(defn- events-of-type
  [stream event-type]
  (filterv #(= event-type (:event/type %)) (es/get-events stream)))

;------------------------------------------------------------------------------ Golden contract gate

(def ^:const golden-fixture-count
  "Fixture scenarios the Rust generator commits (see manifest.edn).
   Pinned so a silently shrunken vendored corpus fails loudly."
  6)

(deftest golden-fixtures-route-through-the-lifecycle
  (testing "every vendored Rust-produced fixture parses, validates, and routes"
    (let [manifest (edn/read-string
                    (slurp (io/file (golden-dir) "manifest.edn")
                           :encoding "UTF-8"))
          fixture-names (mapv #(str (name %) ".transit.json")
                              (:fixture/scenarios manifest))]
      (is (= golden-fixture-count (count fixture-names)))
      ;; One events-dir per scenario: the deterministic generator stamps
      ;; the SAME intervention id on every fixture, and the consumer's
      ;; id-level idempotency (correct per Phase D) would dedup 2..N in
      ;; a shared directory. Isolation tests the contract per scenario.
      (doseq [f fixture-names]
        (testing f
          (let [events-dir (temp-events-dir)
                stream (memory-stream)]
            (stage-golden! events-dir f)
            (is (= {:routed 1 :skipped 0 :anomalies 0}
                   (consumer/consume-pass! {:events-dir events-dir
                                            :stream stream})))
            (is (= 1 (count (events-of-type
                             stream
                             consumer/intervention-requested-event-type))))
            ;; Fixtures carry request-source :tui → auto-approved.
            (let [changes (events-of-type
                           stream consumer/state-changed-event-type)]
              (is (= 1 (count changes)))
              (is (= :approved (:intervention/state (first changes)))))))))))

(deftest republished-events-carry-typed-identity
  (testing "tag-stripped strings are revived before republish (schema:
            :event/id uuid?, timestamps inst?, :workflow/id uuid?)"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)]
      (stage-golden! events-dir "pause.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [requested (first (events-of-type
                              stream consumer/intervention-requested-event-type))
            change (first (events-of-type
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

(deftest invalid-request-identities-are-rejected
  (let [valid {:event/id (random-uuid)
               :intervention/id (random-uuid)
               :workflow/id (random-uuid)}]
    (is (#'consumer/valid-request-identities? valid))
    (doseq [k [:event/id :intervention/id :workflow/id]]
      (is (false? (#'consumer/valid-request-identities?
                   (assoc valid k "not-a-uuid")))
          (name k)))))

;------------------------------------------------------------------------------ Idempotency

(deftest second-pass-is-a-no-op
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-golden! events-dir "pause.transit.json")
    (is (= {:routed 1 :skipped 0 :anomalies 0}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))
    (let [event-count (count (es/get-events stream))]
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (is (= event-count (count (es/get-events stream)))
          "a processed file must publish nothing on re-scan"))))

(deftest duplicate-intervention-id-under-new-filename-is-skipped
  (testing "id-level idempotency survives re-delivery as a different file"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)
          content (slurp (io/file (golden-dir) "pause.transit.json")
                         :encoding "UTF-8")]
      (stage-operator-file! events-dir "20260101T000000000Z-aaaa.json" content)
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (stage-operator-file! events-dir "20260101T000000001Z-bbbb.json" content)
      (is (= {:routed 0 :skipped 1 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))))))

(deftest cursor-survives-restart
  (testing "a fresh consumer (new process) trusts the on-disk cursor"
    (let [events-dir (temp-events-dir)
          stream-a (memory-stream)
          stream-b (memory-stream)]
      (stage-golden! events-dir "cancel.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream-a})
      (is (= {:routed 0 :skipped 0 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream-b})))
      (is (empty? (es/get-events stream-b))))))

;------------------------------------------------------------------------------ Malformed input is loud, never silent

(deftest unreadable-file-emits-anomaly-once
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-operator-file! events-dir "garbage.json" "{not json at all")
    (is (= {:routed 0 :skipped 0 :anomalies 1}
           (consumer/consume-pass! {:events-dir events-dir :stream stream})))
    (let [anomalies (events-of-type stream consumer/anomaly-event-type)]
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

(deftest invalid-intervention-emits-anomaly
  (testing "invalid request id is remembered after its first anomaly"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)
          content (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
                       "\"~:intervention/id\":\"~u00000000-0000-4000-8000-00000000dead\","
                       "\"~:intervention/type\":\"~:frobnicate\","
                       "\"~:intervention/target-id\":\"t-1\","
                       "\"~:intervention/requested-by\":\"op@example.invalid\","
                       "\"~:intervention/request-source\":\"~:tui\"}")]
      (stage-operator-file!
       events-dir "bad-type.json"
       content)
      (is (= {:routed 0 :skipped 0 :anomalies 1}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (stage-operator-file! events-dir "bad-type-redelivery.json" content)
      (is (= {:routed 0 :skipped 1 :anomalies 0}
             (consumer/consume-pass! {:events-dir events-dir :stream stream})))
      (is (= 1 (count (events-of-type stream consumer/anomaly-event-type)))
          "a new filename for the same invalid id must not repeat the anomaly"))))

(deftest foreign-operator-events-are-skipped-quietly
  (testing "meta-loop/train events legitimately share the directory"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)]
      (stage-operator-file!
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

(deftest non-operator-source-parks-at-pending-human
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-operator-file!
     events-dir "api-pause.json"
     (str "{\"~:event/type\":\"~:supervisory/intervention-requested\","
          "\"~:intervention/id\":\"~u00000000-0000-4000-8000-0000000000f1\","
          "\"~:intervention/type\":\"~:pause\","
          "\"~:intervention/target-id\":\"00000000-0000-4000-8000-0000000000aa\","
          "\"~:intervention/requested-by\":\"delegate@example.invalid\","
          "\"~:intervention/request-source\":\"~:api\"}"))
    (consumer/consume-pass! {:events-dir events-dir :stream stream})
    (let [changes (events-of-type stream consumer/state-changed-event-type)]
      (is (= 1 (count changes)))
      (is (= :pending-human (:intervention/state (first changes)))))))

(deftest apply-hook-receives-only-approved-interventions
  (let [events-dir (temp-events-dir)
        stream (memory-stream)
        applied (atom [])]
    (stage-golden! events-dir "pause.transit.json")
    (consumer/consume-pass! {:events-dir events-dir
                             :stream stream
                             :apply! (fn [_stream interv]
                                       (swap! applied conj interv))})
    (is (= 1 (count @applied)))
    (is (= :approved (:intervention/state (first @applied))))
    (is (= :pause (:intervention/type (first @applied))))))

(deftest approval-transition-failure-emits-anomaly
  (let [events-dir (temp-events-dir)
        stream (memory-stream)]
    (stage-golden! events-dir "pause.transit.json")
    (with-redefs [intervention/approve
                  (constantly {:success? false
                               :error :invalid-transition
                               :message "approval transition rejected"})]
      (is (= {:routed 0 :skipped 0 :anomalies 1}
             (consumer/consume-pass! {:events-dir events-dir :stream stream}))))
    (let [event (first (events-of-type stream consumer/anomaly-event-type))]
      (is (= "approval transition rejected" (:message event)))
      (is (= :invalid-transition
             (get-in event [:anomaly :anomaly/data :error]))))))

(deftest workflow-targeted-state-changes-carry-workflow-id
  (testing "audit trail lands in the run's own event directory"
    (let [events-dir (temp-events-dir)
          stream (memory-stream)]
      (stage-golden! events-dir "pause.transit.json")
      (consumer/consume-pass! {:events-dir events-dir :stream stream})
      (let [change (first (events-of-type
                           stream consumer/state-changed-event-type))]
        (is (some? (:workflow/id change)))))))
