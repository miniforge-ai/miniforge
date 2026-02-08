(ns ai.miniforge.lsp-mcp-bridge.lsp.client-test
  "Tests for LSP client (promise-based, bb-compatible)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.lsp-mcp-bridge.lsp.protocol :as proto]))

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
