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
(ns ai.miniforge.webhook-intake.handler-test
  "The intake publishes signed GitHub deliveries to its event stream and
   refuses everything else."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.webhook-intake.deliveries :as deliveries]
   [ai.miniforge.webhook-intake.handler :as sut]
   [ai.miniforge.webhook-intake.signature :as signature]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} secret
  "Webhook secret the tests sign with."
  "test-webhook-secret")

(def ^{:stratum 0} two-pr-failed-suite-body
  "A failed check_suite delivery listing two pull requests, so it maps to
   two :pr/ci-failed events."
  (-> "ai/miniforge/webhook_intake/github-fixtures/check_suite.completed.failure.json"
      io/resource slurp (json/parse-string true)
      (assoc-in [:check_suite :pull_requests]
                (for [n [2244 2251]] {:number n :head {:repo {:id 1}} :base {:repo {:id 1}}}))
      json/generate-string))

(def ^{:stratum 0} merged-body
  "A real-shaped pull_request (closed, merged) delivery body."
  (slurp (io/resource "ai/miniforge/webhook_intake/github-fixtures/pull_request.closed.merged.json")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} delivery-request
  "A ring request delivering `body` to the intake, signed with `secret`
   unless :signature overrides it."
  [body & {:keys [event delivery signature method]
           :or {event "pull_request" delivery "d-1" method :post}}]
  (let [bytes (.getBytes (str body) "UTF-8")]
    {:uri sut/github-path
     :request-method method
     :headers {"x-github-event" event
               "x-github-delivery" delivery
               "x-hub-signature-256" (or signature (signature/sign secret bytes))}
     :body (java.io.ByteArrayInputStream. bytes)}))

(defn ^{:stratum 1} intake-with-collector
  "An intake on a fresh event stream, and an atom collecting the events
   published to it."
  []
  (let [stream (event-stream/create-event-stream {:sinks []})
        published (atom [])]
    (event-stream/subscribe! stream :collector #(swap! published conj %))
    {:intake {:secret secret :stream stream :seen (deliveries/new-seen)}
     :published published}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} signed-delivery-is-published-test
  (testing "a signed merged-PR delivery is published as :pr/merged and answered 202"
    (let [{:keys [intake published]} (intake-with-collector)
          response ((sut/create-handler intake) (delivery-request merged-body))]
      (is (= 202 (:status response)))
      (is (= [:pr/merged] (mapv :event/type @published)))
      (is (= "miniforge-ai/miniforge" (:pr/repo (first @published)))))))

(deftest ^{:stratum 2} refused-requests-publish-nothing-test
  (let [{:keys [intake published]} (intake-with-collector)
        handler (sut/create-handler intake)]
    (testing "a bad signature is 401"
      (is (= 401 (:status (handler (delivery-request merged-body :signature "sha256=00"))))))
    (testing "an unsigned delivery is 401"
      (is (= 401 (:status (handler (update (delivery-request merged-body) :headers
                                           dissoc "x-hub-signature-256"))))))
    (testing "a GET is 405 and another path is 404"
      (is (= 405 (:status (handler (delivery-request merged-body :method :get)))))
      (is (= 404 (:status (handler (assoc (delivery-request merged-body) :uri "/other"))))))
    (testing "a signed body that is not JSON is 400"
      (is (= 400 (:status (handler (delivery-request "not json" :delivery "d-2"))))))
    (is (empty? @published))))

(deftest ^{:stratum 2} redelivery-is-dropped-test
  (testing "the same delivery id is published once"
    (let [{:keys [intake published]} (intake-with-collector)
          handler (sut/create-handler intake)]
      (is (= 202 (:status (handler (delivery-request merged-body)))))
      (is (= 200 (:status (handler (delivery-request merged-body)))))
      (is (= 1 (count @published))))))

(deftest ^{:stratum 2} thrown-publish-is-not-retried-test
  (testing "a publish that throws after a subscriber got the event is 500, and a redelivery is 409, not a second publish"
    (let [{:keys [intake published]} (intake-with-collector)
          handler (sut/create-handler intake)]
      (is (= 500 (:status (with-redefs [event-stream/publish! (fn [_ event]
                                                                (swap! published conj event)
                                                                (throw (Exception. "filter threw")))]
                            (handler (delivery-request merged-body))))))
      (is (= 409 (:status (handler (delivery-request merged-body)))))
      (is (= 1 (count @published))))))

(deftest ^{:stratum 2} in-flight-redelivery-is-not-acknowledged-test
  (testing "a redelivery while the delivery is still being published is 409, not 200"
    (let [{:keys [intake published]} (intake-with-collector)]
      (deliveries/claim! (:seen intake) (get-in (delivery-request merged-body) [:headers "x-hub-signature-256"]))
      (is (= 409 (:status ((sut/create-handler intake) (delivery-request merged-body)))))
      (is (empty? @published)))))

(deftest ^{:stratum 2} rejected-publish-is-a-failure-test
  (testing "publish! returning a rejection, not throwing, is still 500 and retryable"
    (let [{:keys [intake published]} (intake-with-collector)
          handler (sut/create-handler intake)]
      (is (= 500 (:status (with-redefs [event-stream/publish! (fn [& _] {:rejected? true :reason :workflow-quiesced})]
                            (handler (delivery-request merged-body))))))
      (is (= 202 (:status (handler (delivery-request merged-body)))))
      (is (= 1 (count @published))))))

(deftest ^{:stratum 2} partial-publish-resumes-test
  (testing "a retry after the second of two events was refused publishes only the second"
    (let [{:keys [intake published]} (intake-with-collector)
          handler (sut/create-handler intake)
          real-publish event-stream/publish!
          calls (atom 0)]
      (is (= 500 (:status (with-redefs [event-stream/publish! (fn [stream event]
                                                                (if (= 2 (swap! calls inc))
                                                                  {:rejected? true :reason :workflow-quiesced}
                                                                  (real-publish stream event)))]
                            (handler (delivery-request two-pr-failed-suite-body :event "check_suite"))))))
      (is (= 202 (:status (handler (delivery-request two-pr-failed-suite-body :event "check_suite")))))
      (is (= [2244 2251] (mapv :pr/id @published))))))

(deftest ^{:stratum 2} replay-under-a-fresh-delivery-id-test
  (testing "a captured delivery replayed with a new X-GitHub-Delivery is a redelivery, not a new event"
    (let [{:keys [intake published]} (intake-with-collector)
          handler (sut/create-handler intake)]
      (is (= 202 (:status (handler (delivery-request merged-body :delivery "d-1")))))
      (is (= 200 (:status (handler (delivery-request merged-body :delivery "d-replayed")))))
      (is (= 1 (count @published))))))
