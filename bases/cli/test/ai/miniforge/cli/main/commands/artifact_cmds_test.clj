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

(ns ai.miniforge.cli.main.commands.artifact-cmds-test
  "Unit tests for artifact CLI commands."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.artifact-cmds :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0: Factory helpers

(defn make-provenance
  "Build a minimal provenance map for testing."
  ([]
   (make-provenance {}))
  ([overrides]
   (merge {:artifact/workflow-id "wf-123"
           :artifact/phase :implement
           :artifact/agent-id "agent-1"
           :artifact/git-commit "abc123"
           :artifact/created-at "2026-04-13T10:00:00Z"
           :artifact/parent-ids []
           :artifact/files ["src/core.clj"]}
          overrides)))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest format-file-size-test
  (testing "bytes under 1KB display as bytes"
    (is (= "512B" (sut/format-file-size 512))))

  (testing "bytes between 1KB and 1MB display as KB"
    (is (= "1.0KB" (sut/format-file-size shared/bytes-per-kb)))
    (is (= "10.0KB" (sut/format-file-size (* 10 shared/bytes-per-kb)))))

  (testing "bytes above 1MB display as MB"
    (is (= "1.0MB" (sut/format-file-size shared/bytes-per-mb)))
    (is (= "2.5MB" (sut/format-file-size (* 2.5 shared/bytes-per-mb))))))

(deftest artifact-list-cmd-no-component-empty-dir-test
  (testing "list command shows 'no artifacts' when dir is empty"
    (with-redefs [shared/try-resolve-fn (constantly nil)
                  app-config/artifacts-dir (constantly "/tmp/nonexistent-artifacts")]
      (let [output (with-out-str (sut/artifact-list-cmd {}))]
        (is (re-find #"(?i)no artifacts" output))))))

(deftest artifact-list-cmd-component-result-test
  (testing "list command displays component results when available"
    (with-redefs [shared/try-resolve-fn
                  (constantly [{:artifact/id "art-1"
                                :artifact/type :code
                                :artifact/workflow-id "wf-1"}])
                  app-config/artifacts-dir (constantly "/tmp/test")]
      (let [output (with-out-str (sut/artifact-list-cmd {}))]
        (is (.contains output "art-1"))))))

(deftest artifact-provenance-cmd-missing-id-test
  (testing "provenance command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/artifact-provenance-cmd {}))
        (is @exited?)))))

(deftest artifact-provenance-cmd-with-provenance-test
  (testing "provenance command displays provenance data"
    (let [prov (make-provenance)]
      (with-redefs [shared/try-resolve-fn (constantly prov)
                    app-config/artifacts-dir (constantly "/tmp/test")]
        (let [output (with-out-str (sut/artifact-provenance-cmd {:id "art-1"}))]
          (is (.contains output "wf-123"))
          (is (.contains output "agent-1")))))))

(deftest artifact-provenance-cmd-not-found-test
  (testing "provenance command shows error when artifact not found"
    (let [exited? (atom false)]
      (with-redefs [shared/try-resolve-fn (constantly nil)
                    app-config/artifacts-dir (constantly "/tmp/nonexistent")
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/artifact-provenance-cmd {:id "missing"}))
        (is @exited?)))))
