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

(ns ai.miniforge.lsp-mcp-bridge.lsp.client-test
  "Tests for LSP client (promise-based, bb-compatible)."
  (:require
   [ai.miniforge.lsp-mcp-bridge.lsp.client :as client]
   [clojure.test :refer [deftest is testing]]))

(def ^:private load-config #'client/load-config)
(def ^:private required-keys
  [:request-timeout-ms :init-timeout-ms :shutdown-timeout-ms])

(deftest load-config-test
  (testing "valid resource parses to the timeout map"
    (is (= {:request-timeout-ms 30000
            :init-timeout-ms 60000
            :shutdown-timeout-ms 10000}
           (load-config "fixtures/lsp_config/valid.edn" required-keys))))

  (testing "missing resource fails fast with the resource path in ex-data"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (load-config "fixtures/lsp_config/does-not-exist.edn"
                                       required-keys)))]
      (is (= "fixtures/lsp_config/does-not-exist.edn"
             (:config/resource (ex-data ex))))))

  (testing "non-map resource fails fast"
    (is (thrown? clojure.lang.ExceptionInfo
                 (load-config "fixtures/lsp_config/not-a-map.edn" required-keys))))

  (testing "missing key fails fast and names the missing key"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (load-config "fixtures/lsp_config/missing-key.edn"
                                       required-keys)))]
      (is (= [:shutdown-timeout-ms] (:config/missing-keys (ex-data ex))))))

  (testing "string and negative timeout values fail fast at load (not later at deref)"
    (doseq [fixture ["fixtures/lsp_config/bad-string.edn"
                     "fixtures/lsp_config/negative.edn"]]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                            (load-config fixture required-keys)))]
        (is (= [:request-timeout-ms] (:config/invalid-keys (ex-data ex))))
        ;; Message is catalog-sourced (param-substituted), not the bare key name.
        (is (re-find #"\Qfixtures/lsp_config/\E" (ex-message ex))))))

  (testing "the shipped resource loads with the documented values"
    (is (= {:request-timeout-ms 30000
            :init-timeout-ms 60000
            :shutdown-timeout-ms 10000}
           (load-config "config/lsp-mcp-bridge/lsp.edn" required-keys)))))

;; Note: Full client tests require a running LSP server.
;; These tests verify the protocol layer and message building
;; that the client depends on.

(deftest promise-based-pending-requests-test
  (testing "atom+promise pattern works for request tracking"
    (let [pending (atom {})
          p (promise)]
      ;; Simulate registering a pending request
      (swap! pending assoc 1 p)
      (is (= 1 (count @pending)))

      ;; Simulate delivering a response (as the reader thread would)
      (let [id 1
            msg {:id 1 :result {:hover "info"}}
            stored-promise (let [m @pending]
                             (when (contains? m id)
                               (swap! pending dissoc id)
                               (get m id)))]
        (when stored-promise
          (deliver stored-promise msg)))

      ;; Verify the promise was delivered
      (is (= {:id 1 :result {:hover "info"}}
             (deref p 100 ::timeout)))
      ;; Verify pending was cleaned up
      (is (= 0 (count @pending))))))

(deftest promise-timeout-test
  (testing "deref with timeout returns sentinel on timeout"
    (let [p (promise)
          result (deref p 50 ::timeout)]
      (is (= ::timeout result)))))

(deftest send-request-sync-timeout-pattern-test
  (testing "timeout handling pattern cleans up pending request"
    (let [pending (atom {})
          p (promise)
          request-id 42]
      ;; Register pending
      (swap! pending assoc request-id p)
      ;; Simulate timeout
      (let [result (deref p 50 ::timeout)]
        (when (= result ::timeout)
          (swap! pending dissoc request-id)))
      ;; Verify cleanup
      (is (= 0 (count @pending))))))

(deftest diagnostics-buffer-pattern-test
  (testing "diagnostics buffer accumulates per URI"
    (let [buffer (atom {})]
      ;; Simulate receiving diagnostics notifications
      (swap! buffer assoc "file:///foo.clj"
             [{:range {:start {:line 0}} :message "Error" :severity 1}])
      (swap! buffer assoc "file:///bar.clj"
             [{:range {:start {:line 5}} :message "Warning" :severity 2}])

      (is (= 1 (count (get @buffer "file:///foo.clj"))))
      (is (= 1 (count (get @buffer "file:///bar.clj"))))
      (is (= [] (get @buffer "file:///baz.clj" [])))

      ;; Clear diagnostics for one URI
      (swap! buffer dissoc "file:///foo.clj")
      (is (= [] (get @buffer "file:///foo.clj" []))))))
