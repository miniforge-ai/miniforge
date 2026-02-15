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

(ns ai.miniforge.web-dashboard.server.handlers-approval-test
  "Tests for the multi-party approval API handlers (N8).

   Tests cover:
   - POST /api/approvals — create approval request
   - GET  /api/approvals/:id — get approval status
   - POST /api/approvals/:id/sign — submit signature

   Handlers are tested at the function level with a real atom state
   and real event-stream approval functions (in-memory, no I/O)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.server.handlers :as sut]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Helpers

(defn- fresh-state
  "Create a fresh atom state for handler tests."
  []
  (atom {}))

(defn- parse-body
  "Parse a JSON response body into a Clojure map."
  [response]
  (json/parse-string (:body response) true))

(defn- create-approval-body
  "Build a JSON body string for POST /api/approvals."
  [action-id signers quorum & [extra]]
  (json/generate-string
   (merge {:action-id (str action-id)
           :required-signers signers
           :quorum quorum}
          extra)))

(defn- sign-body
  "Build a JSON body string for POST /api/approvals/:id/sign."
  [signer decision & [reason]]
  (json/generate-string
   (cond-> {:signer signer :decision (name decision)}
     reason (assoc :reason reason))))

;------------------------------------------------------------------------------ Tests

(deftest create-approval-test
  (testing "POST /api/approvals — creates approval and returns id"
    (let [state (fresh-state)
          action-id (random-uuid)
          body (create-approval-body action-id ["alice" "bob"] 2)
          response (sut/handle-api-approval-create state body)
          data (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "created" (:status data)))
      (is (string? (:approval-id data)))
      (is (some? (parse-uuid (:approval-id data))))
      (is (string? (:expires-at data)))))

  (testing "POST /api/approvals — lazily creates approval manager"
    (let [state (fresh-state)
          _ (sut/handle-api-approval-create
             state (create-approval-body (random-uuid) ["alice"] 1))]
      (is (some? (:approval-manager @state)))))

  (testing "POST /api/approvals — default quorum is signer count"
    (let [state (fresh-state)
          body (json/generate-string {:action-id (str (random-uuid))
                                      :required-signers ["a" "b" "c"]})
          response (sut/handle-api-approval-create state body)
          data (parse-body response)]
      (is (= "created" (:status data))))))

(deftest get-approval-test
  (testing "GET /api/approvals/:id — returns approval status"
    (let [state (fresh-state)
          action-id (random-uuid)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body action-id ["alice" "bob"] 2))
          approval-id (:approval-id (parse-body create-resp))
          response (sut/handle-api-approval-get state approval-id)
          data (parse-body response)]
      (is (= 200 (:status response)))
      (is (= approval-id (:approval-id data)))
      (is (= "pending" (:status data)))
      (is (= 2 (:quorum data)))
      (is (= 0 (:signatures data)))
      (is (= ["alice" "bob"] (:required-signers data)))
      (is (string? (:expires-at data)))))

  (testing "GET /api/approvals/:id — not found returns anomaly"
    (let [state (fresh-state)
          ;; Ensure manager exists
          _ (sut/handle-api-approval-create
             state (create-approval-body (random-uuid) ["x"] 1))
          response (sut/handle-api-approval-get state (str (random-uuid)))
          data (parse-body response)]
      (is (= 404 (:status response)))
      (is (= "not-found" (get-in data [:error :code])))))

  (testing "GET /api/approvals/:id — no manager returns not found"
    (let [state (fresh-state)
          response (sut/handle-api-approval-get state (str (random-uuid)))]
      (is (= 404 (:status response))))))

(deftest sign-approval-test
  (testing "POST /api/approvals/:id/sign — first signature, still pending"
    (let [state (fresh-state)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body (random-uuid) ["alice" "bob"] 2))
          approval-id (:approval-id (parse-body create-resp))
          response (sut/handle-api-approval-sign
                    state approval-id (sign-body "alice" :approve "LGTM"))
          data (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "signed" (:status data)))
      (is (= "pending" (:approval-status data)))))

  (testing "POST /api/approvals/:id/sign — quorum reached, approved"
    (let [state (fresh-state)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body (random-uuid) ["alice" "bob"] 2))
          approval-id (:approval-id (parse-body create-resp))
          _ (sut/handle-api-approval-sign
             state approval-id (sign-body "alice" :approve))
          response (sut/handle-api-approval-sign
                    state approval-id (sign-body "bob" :approve))
          data (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "approved" (:approval-status data)))))

  (testing "POST /api/approvals/:id/sign — rejection immediately rejects"
    (let [state (fresh-state)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body (random-uuid) ["alice" "bob"] 2))
          approval-id (:approval-id (parse-body create-resp))
          response (sut/handle-api-approval-sign
                    state approval-id (sign-body "alice" :reject "not safe"))
          data (parse-body response)]
      (is (= 200 (:status response)))
      (is (= "rejected" (:approval-status data)))))

  (testing "POST /api/approvals/:id/sign — not found returns anomaly"
    (let [state (fresh-state)
          _ (sut/handle-api-approval-create
             state (create-approval-body (random-uuid) ["x"] 1))
          response (sut/handle-api-approval-sign
                    state (str (random-uuid)) (sign-body "x" :approve))]
      (is (= 404 (:status response)))))

  (testing "POST /api/approvals/:id/sign — unauthorized signer rejected"
    (let [state (fresh-state)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body (random-uuid) ["alice"] 1))
          approval-id (:approval-id (parse-body create-resp))
          response (sut/handle-api-approval-sign
                    state approval-id (sign-body "mallory" :approve))
          data (parse-body response)]
      (is (= 400 (:status response)))
      (is (= "incorrect" (get-in data [:error :code]))))))

(deftest approval-round-trip-test
  (testing "Full lifecycle: create → sign → get shows updated status"
    (let [state (fresh-state)
          create-resp (sut/handle-api-approval-create
                       state
                       (create-approval-body (random-uuid) ["alice" "bob"] 2))
          approval-id (:approval-id (parse-body create-resp))

          ;; Get before signing
          before (parse-body (sut/handle-api-approval-get state approval-id))
          _ (is (= "pending" (:status before)))
          _ (is (= 0 (:signatures before)))

          ;; First signature
          _ (sut/handle-api-approval-sign
             state approval-id (sign-body "alice" :approve))
          after-one (parse-body (sut/handle-api-approval-get state approval-id))
          _ (is (= "pending" (:status after-one)))
          _ (is (= 1 (:signatures after-one)))

          ;; Second signature — quorum
          _ (sut/handle-api-approval-sign
             state approval-id (sign-body "bob" :approve))
          after-two (parse-body (sut/handle-api-approval-get state approval-id))]
      (is (= "approved" (:status after-two)))
      (is (= 2 (:signatures after-two))))))
