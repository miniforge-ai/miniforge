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
(ns ai.miniforge.webhook-intake.listener-test
  "The listener needs a secret, binds loopback by default, and serves
   signed deliveries over real HTTP."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.webhook-intake.handler :as handler]
   [ai.miniforge.webhook-intake.listener :as sut]
   [ai.miniforge.webhook-intake.signature :as signature]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.client :as http-client]
   [org.httpkit.server :as http]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} secret
  "Webhook secret the tests sign with."
  "test-webhook-secret")

(def ^{:stratum 0} merged-body
  "A real-shaped pull_request (closed, merged) delivery body."
  (slurp (io/resource "ai/miniforge/webhook_intake/github-fixtures/pull_request.closed.merged.json")))

(deftest ^{:stratum 0} start-requires-a-secret-test
  (testing "the intake does not start without a secret"
    (is (= :invalid-input
           (try+ (sut/start! {:stream (event-stream/create-event-stream {:sinks []})})
                 nil
                 (catch [:anomaly/type :invalid-input] refusal (:anomaly/type refusal)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} signed-headers
  "Headers GitHub sends with `body`, signed with `secret`."
  [body delivery-id]
  {"X-GitHub-Event" "pull_request"
   "X-GitHub-Delivery" delivery-id
   "X-Hub-Signature-256" (signature/sign secret (.getBytes (str body) "UTF-8"))
   "Content-Type" "application/json"})

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} started-intake-binds-loopback-test
  (testing "a started intake listens on loopback and publishes a signed delivery sent over HTTP"
    (let [stream (event-stream/create-event-stream {:sinks []})
          published (atom [])
          _ (event-stream/subscribe! stream :collector #(swap! published conj %))
          bound (atom nil)
          real-run-server http/run-server
          {:keys [host port stop-fn]} (with-redefs [http/run-server (fn [handler opts]
                                                                      (reset! bound opts)
                                                                      (real-run-server handler opts))]
                                        (sut/start! {:secret secret :stream stream}))]
      (try
        (is (= sut/default-host host))
        (is (= "127.0.0.1" (:ip @bound)) "run-server is told to bind loopback")
        (let [url (str "http://127.0.0.1:" port handler/github-path)
              response @(http-client/post url {:headers (signed-headers merged-body "d-1")
                                               :body merged-body})
              unsigned @(http-client/post url {:headers {"X-GitHub-Delivery" "d-2"}
                                               :body merged-body})]
          (is (= 202 (:status response)))
          (is (= 401 (:status unsigned))))
        (is (= [:pr/merged] (mapv :event/type @published)))
        (finally (stop-fn))))))
