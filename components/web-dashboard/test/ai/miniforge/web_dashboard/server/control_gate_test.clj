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
(ns ai.miniforge.web-dashboard.server.control-gate-test
  "The control gate (issue #1460): state-mutating requests require an
   authenticated operator session even when browse-auth is disabled,
   while read-only views and the intentional machine endpoints stay
   open. These tests drive the assembled server handler so the gate is
   exercised exactly as a live request would hit it."
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.web-dashboard.server :as server]
   [ai.miniforge.web-dashboard.server.auth :as auth]
   [ai.miniforge.web-dashboard.state.core :as state-core]))

;------------------------------------------------------------------------------ Layer 0

;; Test helpers
(defn- ^{:stratum 0} no-auth-state
  "A dashboard with no users configured — the default deployment. An
   empty `:users` list keeps `build-auth-state` from reading the ambient
   MINIFORGE_WEB_* env, so `enabled?` is deterministically false."
  []
  (state-core/create-state
   {:auth (auth/build-auth-state {:users []})}))

(defn- ^{:stratum 0} authed-state []
  (state-core/create-state
   {:auth (auth/build-auth-state
           {:users [{:username "alice"
                     :password "wonderland"
                     :role :operator}]})}))

(defn- ^{:stratum 0} body-stream [content]
  (java.io.ByteArrayInputStream. (.getBytes (str content) "UTF-8")))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} login-cookie
  "Drive the login flow against `handler` and return the session cookie."
  [handler]
  (-> (handler {:uri "/login"
                :request-method :post
                :headers {"content-type" "application/x-www-form-urlencoded"}
                :body (body-stream "username=alice&password=wonderland&return-to=%2F")})
      (get-in [:headers "Set-Cookie"])))

(defn- ^{:stratum 1} command-req
  [workflow-id & [cookie]]
  {:uri (str "/api/workflow/" workflow-id "/command")
   :request-method :post
   :headers (cond-> {"content-type" "application/json"}
              cookie (assoc "cookie" cookie))
   :body (body-stream (json/generate-string {:command "pause"}))})

(deftest ^{:stratum 1} no-auth-keeps-read-only-views-open
  (testing "browsing stays open without a session"
    (let [handler (server/create-handler (no-auth-state))]
      (is (= 200 (:status (handler {:uri "/health" :request-method :get :headers {}})))
          "health is reachable")
      (is (not= 401 (:status (handler {:uri "/fleet" :request-method :get :headers {}})))
          "a read-only view is not gated"))))

(deftest ^{:stratum 1} no-auth-keeps-machine-endpoints-open
  (testing "intentional machine ingest endpoints are not treated as operator control"
    (let [handler (server/create-handler (no-auth-state))
          response (handler {:uri "/api/events/ingest"
                             :request-method :post
                             :headers {"content-type" "application/json"}
                             :body (body-stream "{}")})]
      (is (not= 401 (:status response))
          "event ingest stays public — it carries no operator authority to gate"))))

(deftest ^{:stratum 1} no-auth-refuses-other-mutations
  (testing "the gate covers every mutation, not just the workflow command endpoint"
    (let [handler (server/create-handler (no-auth-state))]
      (doseq [{:keys [uri query-string method]}
              [{:uri "/api/train/action" :query-string "train-id=t1&action=pause" :method :post}
               {:uri "/api/fleet/prs/sync" :method :post}
               {:uri (str "/api/archive/" (random-uuid) "/delete") :method :post}
               {:uri (str "/api/control-plane/agents/" (random-uuid) "/command") :method :post}]]
        (let [response (handler {:uri uri
                                 :query-string query-string
                                 :request-method method
                                 :headers {"content-type" "application/json"}
                                 :body (body-stream "{}")})]
          (is (= 401 (:status response)) (str uri " must require a session")))))))

;------------------------------------------------------------------------------ Layer 2

;; No-auth deployment: control is refused, browsing is open
(deftest ^{:stratum 2} no-auth-refuses-control-mutation
  (testing "an unauthenticated POST to the command endpoint is 401'd and no
            intervention is ever written (#1460 acceptance)"
    (let [written (atom [])]
      (with-redefs [event-stream/request-intervention!
                    (fn [request] (swap! written conj request) request)]
        (let [handler (server/create-handler (no-auth-state))
              response (handler (command-req (str (random-uuid))))]
          (is (= 401 (:status response)))
          (is (= "authentication-required"
                 (:error (json/parse-string (:body response) true))))
          (is (empty? @written)
              "the gate blocks before any intervention reaches the operator channel"))))))

;; Auth enabled: session passes, missing session is challenged
(deftest ^{:stratum 2} authed-session-passes-the-gate
  (testing "an authenticated operator reaches the control handler and the
            intervention is written to the governed channel"
    (let [written (atom [])]
      (with-redefs [event-stream/request-intervention!
                    (fn [request]
                      (swap! written conj request)
                      (assoc request :intervention/id (random-uuid)))]
        (let [handler (server/create-handler (authed-state))
              cookie (login-cookie handler)
              response (handler (command-req (str (random-uuid)) cookie))
              request (first @written)]
          (is (= 200 (:status response)))
          (is (= "requested" (:status (json/parse-string (:body response) true))))
          (is (= :dashboard (:intervention/request-source request))
              "the session-gated dashboard source reaches the operator channel"))))))

(deftest ^{:stratum 2} enabled-without-session-is-challenged
  (testing "auth enabled but no cookie: the mutation is refused"
    (let [written (atom [])]
      (with-redefs [event-stream/request-intervention!
                    (fn [request] (swap! written conj request) request)]
        (let [handler (server/create-handler (authed-state))
              response (handler (command-req (str (random-uuid))))]
          (is (= 401 (:status response)))
          (is (empty? @written)))))))

;; A mutation must not be reachable by GET
(deftest ^{:stratum 2} train-action-is-post-only
  (testing "the sole mutating endpoint that lacked a method guard no longer
            answers GET, so the gate's method check cannot be bypassed"
    (let [handler (server/create-handler (authed-state))
          cookie (login-cookie handler)
          response (handler {:uri "/api/train/action"
                             :query-string "train-id=t1&action=pause"
                             :request-method :get
                             :headers {"cookie" cookie}})]
      (is (= 404 (:status response))
          "GET no longer routes to the train action handler"))))
