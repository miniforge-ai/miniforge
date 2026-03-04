(ns ai.miniforge.phase.artifact-persistence-test
  "Integration tests for artifact persistence and fail-fast validation.

  Verifies that:
  - Phases fail fast when required artifacts are missing
  - Implement phase propagates agent errors correctly
  - Release phase validates artifacts before execution
  - Full pipeline writes files to disk"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   ;; Required for defmethod side effects (register phase handlers)
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.plan]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.implement]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.verify]
   #_{:clj-kondo/ignore [:unused-namespace]}
   [ai.miniforge.phase.release]
   [ai.miniforge.phase.registry :as registry]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.release-executor.interface :as release-executor]))

;------------------------------------------------------------------------------ Test Fixtures

(def ^:dynamic *test-worktree* nil)

(defn- create-temp-worktree []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "artifact-persist-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    (.getPath temp-dir)))

(defn- cleanup-temp-worktree [dir-path]
  (when dir-path
    (try (fs/delete-tree dir-path) (catch Exception _e nil))))

(defn worktree-fixture [f]
  (let [worktree (create-temp-worktree)]
    (binding [*test-worktree* worktree]
      (try (f)
           (finally (cleanup-temp-worktree worktree))))))

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

;------------------------------------------------------------------------------ Test Helpers

(defn- create-base-context []
  {:execution/id (random-uuid)
   :execution/input {:description "Test implementation"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {}})

(defn- execute-phase-enter [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        enter-fn (:enter interceptor)]
    (enter-fn ctx)))

(defn- execute-phase-leave [phase-name ctx]
  (let [interceptor (registry/get-phase-interceptor {:phase phase-name})
        leave-fn (:leave interceptor)
        updated-ctx (leave-fn ctx)]
    (assoc-in updated-ctx [:execution/phase-results phase-name]
              (:phase updated-ctx))))

;------------------------------------------------------------------------------ Tests

(deftest test-verify-fails-without-artifact
  (testing "verify phase throws when no implement result is present"
    (with-redefs [agent/create-tester (fn [_] {:type :mock-tester})
                  agent/invoke (fn [_ _ _]
                                 (response/success {:result :ok} {:tokens 0 :duration-ms 0}))]
      (let [ctx (create-base-context)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Verify phase received no code artifact"
                              (execute-phase-enter :verify ctx)))
        ;; Verify ex-data contains diagnostic info
        (try
          (execute-phase-enter :verify ctx)
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)]
              (is (= :verify (:phase data)))
              (is (some? (:hint data))))))))))

(deftest test-release-fails-with-nil-artifact
  (testing "release phase throws when implement result is nil"
    (let [ctx (-> (create-base-context)
                  ;; Simulate implement phase that stored nil output
                  (assoc-in [:execution/phase-results :implement :result :output] nil)
                  (assoc :worktree-path *test-worktree*))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Release phase has no code artifact"
                            (execute-phase-enter :release ctx))))))

(deftest test-release-fails-with-empty-files
  (testing "release phase throws when code artifact has empty :code/files"
    (let [ctx (-> (create-base-context)
                  (assoc-in [:execution/phase-results :implement :result :output]
                            {:code/id (random-uuid) :code/files []})
                  (assoc :worktree-path *test-worktree*))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Release phase received code artifact with zero files"
                            (execute-phase-enter :release ctx))))))

(deftest test-full-pipeline-writes-files
  (testing "full pipeline with mock agent writes files to temp dir"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/success mock-code-artifact
                                                   {:tokens 1000 :duration-ms 2000}))
                  release-executor/execute-release-phase
                  (fn [workflow-state exec-context _opts]
                    ;; Actually write files to the worktree
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
      (let [;; Run implement phase
            ctx1 (execute-phase-enter :implement (create-base-context))
            ctx2 (execute-phase-leave :implement ctx1)
            ;; Run release phase
            ctx2-with-path (assoc ctx2 :worktree-path *test-worktree*)
            ctx3 (execute-phase-enter :release ctx2-with-path)]

        ;; Verify release succeeded
        (is (= :success (get-in ctx3 [:phase :result :status]))
            "Release phase should succeed")

        ;; Verify files exist on disk
        (is (.exists (io/file *test-worktree* "src/feature.clj"))
            "Source file should exist on disk")
        (is (.exists (io/file *test-worktree* "test/feature_test.clj"))
            "Test file should exist on disk")

        ;; Verify file contents
        (is (= "(ns feature)\n(defn new-feature [] :implemented)"
               (slurp (io/file *test-worktree* "src/feature.clj")))
            "Source file content should match artifact")))))

(deftest test-implement-fails-on-agent-error
  (testing "leave-implement retries when agent returns :error and within budget"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 ;; Simulate agent returning error status
                                 (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))]
      (let [ctx (create-base-context)
            ctx-entered (execute-phase-enter :implement ctx)
            ctx-left (execute-phase-leave :implement ctx-entered)]
        ;; Agent returned :error status within retry budget — should retry
        (is (= :retrying (get-in ctx-left [:phase :status]))
            "Phase status should be :retrying when agent returns error within budget")
        (is (= :retrying (get-in ctx-left [:execution/phase-results :implement :status]))
            "Stored phase result should also show :retrying"))))

  (testing "leave-implement fails when agent returns :error and budget exhausted"
    (with-redefs [agent/create-implementer (fn [_] {:type :mock-implementer})
                  agent/invoke (fn [_ _ _]
                                 (response/error "LLM timeout" {:tokens 0 :duration-ms 5000}))]
      (let [ctx (-> (create-base-context)
                    (assoc-in [:phase :iterations] 5)) ;; At max budget
            ctx-entered (execute-phase-enter :implement ctx)
            ctx-left (execute-phase-leave :implement ctx-entered)]
        (is (= :failed (get-in ctx-left [:phase :status]))
            "Phase status should be :failed when budget exhausted")
        (is (= :failed (get-in ctx-left [:execution/phase-results :implement :status]))
            "Stored phase result should also show :failed")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase.artifact-persistence-test)
  :leave-this-here)
