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

(ns ai.miniforge.tui-engine.dedup-test
  "Tests that deduplicated functions in container, table, and tree modules
   delegate to the canonical implementations in buffer and status."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-engine.layout.buffer :as buf]
   [ai.miniforge.tui-engine.layout.container :as container]
   [ai.miniforge.tui-engine.layout.table :as table]
   [ai.miniforge.tui-engine.widget.tree :as tree]
   [ai.miniforge.tui-engine.widget.status :as status]))

;; ============================================================================
;; truncate-str — container and table reuse buf/truncate-str
;; ============================================================================

(deftest container-truncate-str-delegates-to-buffer-test
  (testing "container/truncate-str is the same function as buf/truncate-str"
    (is (= (buf/truncate-str "hello world" 5)
           (#'container/truncate-str "hello world" 5)))
    (is (= (buf/truncate-str "short" 10)
           (#'container/truncate-str "short" 10)))))

(deftest table-truncate-str-delegates-to-buffer-test
  (testing "table/truncate-str is the same function as buf/truncate-str"
    (is (= (buf/truncate-str "abcdefghij" 4)
           (#'table/truncate-str "abcdefghij" 4)))
    (is (= (buf/truncate-str "ok" 10)
           (#'table/truncate-str "ok" 10)))))

;; ============================================================================
;; pad-right — table reuses buf/pad-right
;; ============================================================================

(deftest table-pad-right-delegates-to-buffer-test
  (testing "table/pad-right is the same function as buf/pad-right"
    (is (= (buf/pad-right "hi" 10)
           (#'table/pad-right "hi" 10)))
    (is (= (buf/pad-right "toolong" 3)
           (#'table/pad-right "toolong" 3)))))

;; ============================================================================
;; status-chars / status-colors — tree reuses status module
;; ============================================================================

(deftest tree-status-chars-matches-status-module-test
  (testing "tree/status-chars is identical to status/status-chars"
    (is (= status/status-chars tree/status-chars))))

(deftest tree-status-colors-matches-status-module-test
  (testing "tree/status-colors is identical to status/status-colors"
    (is (= status/status-colors tree/status-colors))))

(deftest status-chars-has-all-statuses-test
  (testing "status-chars covers all expected statuses"
    (doseq [k [:running :success :failed :blocked :pending :skipped :spinning]]
      (is (char? (get tree/status-chars k))
          (str "Missing status-char for " k)))))

(deftest status-colors-has-all-statuses-test
  (testing "status-colors covers all expected statuses"
    (doseq [k [:running :success :failed :blocked :pending :skipped :spinning]]
      (is (keyword? (get tree/status-colors k))
          (str "Missing status-color for " k)))))
