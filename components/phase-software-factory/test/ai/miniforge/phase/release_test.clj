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

(ns ai.miniforge.phase.release-test
  "Unit tests for the release phase interceptor.

  Tests file writing, persistence validation, and failure modes."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.phase.release :as release]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree* nil)

(defn create-temp-worktree
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "release-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    ;; Create minimal git structure
    (let [git-dir (io/file temp-dir ".git")]
      (.mkdirs git-dir))
    (.getPath temp-dir)))

(defn cleanup-temp-worktree
  [dir-path]
  (when dir-path
    (try
      (fs/delete-tree dir-path)
      (catch Exception _e nil))))

(defn worktree-fixture
  [f]
  (let [worktree (create-temp-worktree)]
    (binding [*test-worktree* worktree]
      (try
        (f)
        (finally
          (cleanup-temp-worktree worktree))))))

(use-fixtures :each worktree-fixture)

;------------------------------------------------------------------------------ Mock Data

(def mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest t (is true))"
                 :action :create}]
   :code/language "clojure"})

(def mock-implement-result
  {:code/id (:code/id mock-code-artifact)
   :code/files (:code/files mock-code-artifact)
   :code/language "clojure"})

(defn create-base-context
  []
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result {:status :success
                                                  :output mock-implement-result}}}
   :worktree-path *test-worktree*})

;------------------------------------------------------------------------------ Layer 0: Defaults Tests

(deftest default-config-test
  (testing "release phase has correct default configuration"
    (is (= :releaser (:agent release/default-config)))
    (is (= [:release-ready] (:gates release/default-config)))
    (is (map? (:budget release/default-config)))
    (is (= 5000 (get-in release/default-config [:budget :tokens])))
    (is (= 2 (get-in release/default-config [:budget :iterations])))
    (is (= 180 (get-in release/default-config [:budget :time-seconds])))))

(deftest phase-defaults-registration-test
  (testing "release phase defaults are registered"
    (let [defaults (registry/phase-defaults :release)]
      (is (some? defaults))
      (is (= :releaser (:agent defaults)))
      (is (= [:release-ready] (:gates defaults))))))

;------------------------------------------------------------------------------ File Writing Tests

(deftest release-writes-files-to-temp-directory-test
  (testing "release phase writes files to temporary worktree"
    (with-redefs [release-executor/execute-release-phase
                  (fn [workflow-state exec-context _opts]
                    ;; Write files directly to temp worktree (skip git staging)
                    (let [worktree (:worktree-path exec-context)
                          code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                          files (mapcat :code/files code-artifacts)]
                      (doseq [{:keys [path content]} files]
                        (let [file (io/file worktree path)]
                          (io/make-parents file)
                          (spit file content)))
                      {:success? true
                       :artifacts [{:artifact/id (random-uuid)
                                    :artifact/type :release
                                    :artifact/content {:files-written (count files)
                                                       :branch "test-branch"
                                                       :commit-sha "abc123"}}]
                       :metrics {:files-written (count files)}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]

        ;; Verify phase completed successfully
        (is (= :success (get-in result [:phase :result :status]))
            "Release phase should succeed")

        ;; Verify files exist
        (is (.exists (io/file *test-worktree* "src/feature.clj"))
            "Source file should exist on disk")

        (is (.exists (io/file *test-worktree* "test/feature_test.clj"))
            "Test file should exist on disk")))))

(deftest release-returns-correct-files-written-count-test
  (testing "release phase returns correct :files-written count"
    (with-redefs [release-executor/execute-release-phase
                  (fn [workflow-state exec-context _opts]
                    ;; Write files directly to temp worktree (skip git staging)
                    (let [worktree (:worktree-path exec-context)
                          code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                          files (mapcat :code/files code-artifacts)]
                      (doseq [{:keys [path content]} files]
                        (let [file (io/file worktree path)]
                          (io/make-parents file)
                          (spit file content)))
                      {:success? true
                       :artifacts [{:artifact/id (random-uuid)
                                  :artifact/type :release
                                  :artifact/content {:files-written (count files)}}]
                       :metrics {:files-written (count files)}}))]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)
            files-written (get-in result [:phase :result :output :release/metrics :files-written])]
        
        (is (= 2 files-written)
            "Should report 2 files written (src + test)")))))

(deftest release-fails-when-write-fails-test
  (testing "release phase fails when file write operation fails"
    (with-redefs [release-executor/execute-release-phase
                  (fn [_workflow-state _exec-context _opts]
                    ;; Simulate write failure
                    {:success? false
                     :errors [{:type :file-write-failed
                              :message "Permission denied writing to src/feature.clj"
                              :file "src/feature.clj"}]
                     :metrics {:files-written 0}})]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        
        (is (false? (get-in result [:phase :result :success]))
            "Release should fail when write fails")

        (is (some? (get-in result [:phase :result :error]))
            "Error should be present in result")))))

(deftest release-verifies-files-exist-after-writing-test
  (testing "release phase verifies files exist on disk after writing"
    (let [verification-performed (atom false)]
      (with-redefs [release-executor/execute-release-phase
                    (fn [workflow-state exec-context _opts]
                      ;; Write files directly to temp worktree (skip git staging)
                      (let [worktree (:worktree-path exec-context)
                            code-artifacts (map :artifact/content (:workflow/artifacts workflow-state))
                            files (mapcat :code/files code-artifacts)]
                        (doseq [{:keys [path content action]} files]
                          (when (#{:create :modify} action)
                            (let [file (io/file worktree path)]
                              (io/make-parents file)
                              (spit file content))))
                        ;; Verify files exist after write
                        (doseq [{:keys [path action]} files]
                          (when (#{:create :modify} action)
                            (let [file-path (io/file worktree path)]
                              (reset! verification-performed true)
                              (when-not (.exists file-path)
                                (throw (ex-info "File verification failed"
                                               {:file path}))))))
                        {:success? true
                         :artifacts [{:artifact/id (random-uuid)
                                    :artifact/type :release
                                    :artifact/content {:files-written (count files)
                                                     :files-verified (count (filter #(#{:create :modify} (:action %)) files))}}]
                         :metrics {:files-written (count files)}}))]
        (let [ctx (create-base-context)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (registry/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)]

          (is @verification-performed
              "File verification should be performed")

          (is (= :success (get-in result [:phase :result :status]))
              "Release should succeed when verification passes"))))))

(deftest release-handles-zero-files-artifact-test
  (testing "release phase fails fast when code artifact has zero files"
    (let [ctx (-> (create-base-context)
                  (assoc-in [:execution/phase-results :implement :result :output :code/files] []))
          ctx-with-config (assoc ctx :phase-config {:phase :release})
          interceptor (registry/get-phase-interceptor {:phase :release})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Release phase received code artifact with zero files"
                            ((:enter interceptor) ctx-with-config))
          "Release should fail fast when code artifact has empty files"))))

(deftest release-includes-pr-info-test
  (testing "release phase includes PR info in result"
    (with-redefs [release-executor/execute-release-phase
                  (fn [_workflow-state _exec-context _opts]
                    {:success? true
                     :artifacts [{:artifact/id (random-uuid)
                                :artifact/type :release
                                :artifact/content {:files-written 2
                                                 :branch "feature/test-123"
                                                 :commit-sha "def456"
                                                 :pr-number 42
                                                 :pr-url "https://github.com/org/repo/pull/42"}}]
                     :metrics {:files-written 2}})]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            result ((:enter interceptor) ctx-with-config)]
        
        (is (some? (get-in result [:workflow/pr-info]))
            "PR info should be available at top level")
        
        (let [pr-info (get-in result [:workflow/pr-info])]
          (is (= 42 (:pr-number pr-info))
              "PR number should be captured")
          (is (= "https://github.com/org/repo/pull/42" (:pr-url pr-info))
              "PR URL should be captured")
          (is (= "feature/test-123" (:branch pr-info))
              "Branch name should be captured"))))))

(deftest release-propagates-streaming-callback-test
  (testing "release phase passes event-stream-backed on-chunk callback to the executor"
    (let [stream (es/create-event-stream {:sinks []})]
      (with-redefs [release-executor/execute-release-phase
                    (fn [_workflow-state exec-context _opts]
                      (is (fn? (:on-chunk exec-context))
                          "Release executor should receive an on-chunk callback")
                      ((:on-chunk exec-context) {:delta "release chunk" :done? false})
                      {:success? true
                       :artifacts [{:artifact/id (random-uuid)
                                    :artifact/type :release
                                    :artifact/content {:files-written 1}}]
                       :metrics {:files-written 1}})]
        (let [ctx (assoc (create-base-context) :event-stream stream)
              ctx-with-config (assoc ctx :phase-config {:phase :release})
              interceptor (registry/get-phase-interceptor {:phase :release})
              result ((:enter interceptor) ctx-with-config)
              chunk-events (es/get-events stream {:event-type :agent/chunk})]
          (is (= :success (get-in result [:phase :result :status])))
          (is (= 1 (count chunk-events)))
          (is (= "release chunk" (:chunk/delta (first chunk-events))))
          (is (= :release (:agent/id (first chunk-events)))))))))

;------------------------------------------------------------------------------ Layer 2: Interceptor Leave Tests

(deftest leave-release-records-metrics-test
  (testing "leave-release records duration and completion"
    (with-redefs [release-executor/execute-release-phase
                  (fn [_workflow-state _exec-context _opts]
                    {:success? true
                     :artifacts [{:artifact/id (random-uuid)
                                :artifact/type :release
                                :artifact/content {:files-written 2}}]
                     :metrics {:files-written 2 :tokens 300 :duration-ms 800}})]
      (let [ctx (create-base-context)
            ctx-with-config (assoc ctx :phase-config {:phase :release})
            interceptor (registry/get-phase-interceptor {:phase :release})
            enter-result ((:enter interceptor) ctx-with-config)
            final-result ((:leave interceptor) enter-result)]
        
        (is (= :completed (get-in final-result [:phase :status]))
            "Phase status should be completed")
        
        (is (number? (get-in final-result [:phase :duration-ms]))
            "Duration should be recorded")
        
        (is (= 300 (get-in final-result [:phase :metrics :tokens]))
            "Token metrics should be recorded")
        
        (is (= :release (first (get-in final-result [:execution :phases-completed])))
            "Release should be added to phases-completed")))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (clojure.test/run-tests 'ai.miniforge.phase.release-test)
  :leave-this-here)
