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
(ns ai.miniforge.event-stream.redaction-test
  "N3 §8 conformance at the stream boundary.

   N3.SD.2 is the requirement under test: redaction happens at
   construction, not at delivery. These tests assert the secret is
   absent from every destination a published event reaches — sink,
   in-memory log, and subscriber — because a redacting sink would
   still leave the other two holding it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private secret "AKIAIOSFODNN7EXAMPLE")

(def ^{:stratum 0} ^:private marker "[REDACTED]")

(defn- ^{:stratum 0} ->text [x] (pr-str x))

(deftest ^{:stratum 0} envelope-identifiers-are-untouched-test
  (testing ":public-class fields pass through unchanged (N3 §8.4)"
    (let [stream (sut/create-event-stream)
          wf-id  (random-uuid)
          event  (sut/create-envelope stream :workflow/started wf-id "started")
          out    (sut/publish! stream event)]
      (is (= (:event/id event) (:event/id out)))
      (is (= (:event/sequence-number event) (:event/sequence-number out)))
      (is (= (:event/type event) (:event/type out)))
      (is (= (:event/version event) (:event/version out))))))

(deftest ^{:stratum 0} every-position-is-covered-at-the-stream-test
  (testing "a secret reaches the log from no position in an event"
    ;; Through publish! rather than redact directly, because that is the
    ;; boundary §8.1 governs. Each position here was a live leak at some
    ;; point: free text and secret-named keys were the original two,
    ;; then key names, key namespaces, and metadata each in turn.
    (let [secret "AKIAIOSFODNN7EXAMPLE"]
      (doseq [[position event]
              [["free text"     {:message (str "ran deploy with " secret)}]
               ["secret key"    {:password "hunter2"}]
               ["key name"      {(keyword secret) :held}]
               ["key namespace" {(keyword secret "field") :held}]
               ["metadata"      {:carrier (with-meta {:a 1} {:note secret})}]]]
        (let [stream (sut/create-event-stream)]
          (sut/publish! stream (assoc event
                                      :event/type :tool/invoked
                                      :workflow/id (str (random-uuid))))
          ;; *print-meta* or a metadata-borne secret is invisible to the
          ;; check that is supposed to catch it.
          (let [log (binding [*print-meta* true]
                      (pr-str (sut/get-events stream)))]
            (is (not (str/includes? log secret))
                (str "secret survived in " position))))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} secret-reaches-no-destination-test
  (let [stream     (sut/create-event-stream)
        wf-id      (random-uuid)
        seen       (atom [])
        _          (sut/subscribe! stream :spy (fn [e] (swap! seen conj e)))
        event      (assoc (sut/create-envelope stream :tool/invoked wf-id
                                               (str "running with " secret))
                          :tool/args {:command "deploy.sh"
                                      :env     {:API_TOKEN secret}}
                          :password  "hunter2")
        published  (sut/publish! stream event)]

    (testing "the returned event is redacted"
      (is (not (str/includes? (->text published) secret)))
      (is (str/includes? (->text published) marker)))

    (testing "the in-memory log holds no secret (N3.SD.2)"
      (is (not (str/includes? (->text (sut/get-events stream wf-id)) secret))))

    (testing "the subscriber received no secret"
      (is (seq @seen) "subscriber should have been called")
      (is (not (str/includes? (->text @seen) secret))))

    (testing "a key-named secret is redacted even with an unmatched value"
      (is (= marker (:password published))))

    (testing "non-secret fields survive — redaction is not deletion"
      (is (= "deploy.sh" (get-in published [:tool/args :command])))
      (is (= wf-id (:workflow/id published)))
      (is (str/includes? (:message published) "running with")))))
