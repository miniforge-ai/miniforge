(ns ai.miniforge.release-executor.sandbox-test
  "Unit tests for sandbox operations.

   Uses a mock executor to verify correct command generation
   without requiring Docker."
  (:require
   [ai.miniforge.dag-executor.protocols.executor]
   [clojure.string]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [ai.miniforge.dag-executor.result :as dag-result]))

;; ============================================================================
;; Mock executor
;; ============================================================================

(defn create-mock-executor
  "Create a mock executor that records commands and returns configurable results.

   Options:
   - :responses - map of command-substring -> {:exit-code :stdout :stderr}
   - :default-response - default response for unmatched commands

   Returns [executor commands-atom] where commands-atom captures all executed commands."
  [& {:keys [responses default-response]
      :or {responses {}
           default-response {:exit-code 0 :stdout "" :stderr ""}}}]
  (let [commands (atom [])]
    [(reify
       ai.miniforge.dag-executor.protocols.executor/TaskExecutor
       (executor-type [_] :mock)
       (available? [_] (dag-result/ok {:available? true}))
       (acquire-environment! [_ _ _] (dag-result/ok {:environment-id "mock-env"}))
       (execute! [_ _env-id command _opts]
         (swap! commands conj command)
         (let [response (or (some (fn [[substr resp]]
                                    (when (clojure.string/includes? (str command) substr)
                                      resp))
                                  responses)
                            default-response)]
           (dag-result/ok response)))
       (copy-to! [_ _ _ _] (dag-result/ok {}))
       (copy-from! [_ _ _ _] (dag-result/ok {}))
       (release-environment! [_ _] (dag-result/ok {:released? true}))
       (environment-status [_ _] (dag-result/ok {:status :running})))
     commands]))

;; ============================================================================
;; check-gh-auth! tests
;; ============================================================================

(deftest check-gh-auth-success-test
  (testing "check-gh-auth! returns authenticated when gh auth status succeeds"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 0 :stdout "Logged in" :stderr ""}})
          result (sandbox/check-gh-auth! exec "env-1")]
      (is (:available? result))
      (is (:authenticated? result)))))

(deftest check-gh-auth-failure-test
  (testing "check-gh-auth! returns unauthenticated when gh auth status fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh auth status" {:exit-code 1 :stdout "" :stderr "not logged in"}})
          result (sandbox/check-gh-auth! exec "env-1")]
      (is (:available? result))
      (is (not (:authenticated? result))))))

;; ============================================================================
;; create-branch! tests
;; ============================================================================

(deftest create-branch-success-test
  (testing "create-branch! issues fetch and checkout commands"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                   "git fetch" {:exit-code 0 :stdout "" :stderr ""}
                                   "git checkout" {:exit-code 0 :stdout "" :stderr ""}})
          result (sandbox/create-branch! exec "env-1" "feat/my-branch")]
      (is (:success? result))
      (is (= "feat/my-branch" (:branch result)))
      (is (= "main" (:base-branch result)))
      ;; Verify commands were issued
      (is (some #(clojure.string/includes? % "git fetch origin main") @cmds))
      (is (some #(clojure.string/includes? % "git checkout -b feat/my-branch") @cmds)))))

(deftest create-branch-fetch-failure-test
  (testing "create-branch! fails when fetch fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"git symbolic-ref" {:exit-code 0 :stdout "refs/remotes/origin/main\n" :stderr ""}
                                    "git fetch" {:exit-code 1 :stdout "" :stderr "fatal: fetch failed"}})
          result (sandbox/create-branch! exec "env-1" "feat/branch")]
      (is (not (:success? result)))
      (is (clojure.string/includes? (:error result) "fetch")))))

;; ============================================================================
;; write-file! tests
;; ============================================================================

(deftest write-file-generates-base64-command-test
  (testing "write-file! encodes content as base64 and creates parent dirs"
    (let [[exec cmds] (create-mock-executor)
          result (sandbox/write-file! exec "env-1" "src/foo.clj" "(ns foo)")]
      (is (:success? result))
      (let [cmd (first @cmds)]
        ;; Should contain mkdir -p for parent dir
        (is (clojure.string/includes? cmd "mkdir -p"))
        ;; Should contain base64 decode
        (is (clojure.string/includes? cmd "base64 -d"))))))

(deftest write-file-roundtrip-base64-test
  (testing "write-file! base64 encoding is valid"
    (let [content "(ns foo)\n(defn bar [x]\n  (* x 2))"
          encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes content "UTF-8"))
          decoded (String. (.decode (java.util.Base64/getDecoder) encoded) "UTF-8")]
      (is (= content decoded)))))

;; ============================================================================
;; delete-file! tests
;; ============================================================================

(deftest delete-file-command-test
  (testing "delete-file! issues rm -f command"
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/delete-file! exec "env-1" "src/old.clj")
      (is (= 1 (count @cmds)))
      (is (clojure.string/includes? (first @cmds) "rm -f")))))

;; ============================================================================
;; stage-files! tests
;; ============================================================================

(deftest stage-all-files-test
  (testing "stage-files! with :all issues git add ."
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/stage-files! exec "env-1" :all)
      (is (= "git add ." (first @cmds))))))

(deftest stage-specific-files-test
  (testing "stage-files! with specific paths issues git add with paths"
    (let [[exec cmds] (create-mock-executor)]
      (sandbox/stage-files! exec "env-1" ["src/a.clj" "src/b.clj"])
      (let [cmd (first @cmds)]
        (is (clojure.string/includes? cmd "git add"))
        (is (clojure.string/includes? cmd "src/a.clj"))
        (is (clojure.string/includes? cmd "src/b.clj"))))))

;; ============================================================================
;; commit-changes! tests
;; ============================================================================

(deftest commit-changes-success-test
  (testing "commit-changes! commits and returns sha"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git commit" {:exit-code 0 :stdout "1 file changed" :stderr ""}
                                   "git rev-parse" {:exit-code 0 :stdout "abc1234\n" :stderr ""}})
          result (sandbox/commit-changes! exec "env-1" "feat: add feature")]
      (is (:success? result))
      (is (= "abc1234" (:commit-sha result)))
      (is (some #(clojure.string/includes? % "git commit") @cmds)))))

(deftest commit-changes-escapes-quotes-test
  (testing "commit-changes! escapes single quotes in commit message"
    (let [[exec cmds] (create-mock-executor
                       :responses {"git commit" {:exit-code 0 :stdout "" :stderr ""}
                                   "git rev-parse" {:exit-code 0 :stdout "def5678\n" :stderr ""}})]
      (sandbox/commit-changes! exec "env-1" "fix: it's working")
      (let [cmd (first @cmds)]
        ;; Should contain escaped single quote
        (is (clojure.string/includes? cmd "it'\\''s working"))))))

;; ============================================================================
;; push-branch! tests
;; ============================================================================

(deftest push-branch-command-test
  (testing "push-branch! issues git push -u origin"
    (let [[exec cmds] (create-mock-executor)
          result (sandbox/push-branch! exec "env-1" "feat/branch")]
      (is (:success? result))
      (is (clojure.string/includes? (first @cmds) "git push -u origin feat/branch")))))

;; ============================================================================
;; create-pr! tests
;; ============================================================================

(deftest create-pr-success-test
  (testing "create-pr! calls gh pr create and parses PR URL"
    (let [[exec cmds] (create-mock-executor
                       :responses {"gh pr create" {:exit-code 0
                                                   :stdout "https://github.com/org/repo/pull/42\n"
                                                   :stderr ""}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "Add feature"
                                      :body "Description here"
                                      :base-branch "main"})]
      (is (:success? result))
      (is (= 42 (:pr-number result)))
      (is (= "https://github.com/org/repo/pull/42" (:pr-url result)))
      (let [cmd (first @cmds)]
        (is (clojure.string/includes? cmd "gh pr create"))
        (is (clojure.string/includes? cmd "--title"))
        (is (clojure.string/includes? cmd "--base main"))))))

(deftest create-pr-failure-test
  (testing "create-pr! returns failure when gh pr create fails"
    (let [[exec _cmds] (create-mock-executor
                        :responses {"gh pr create" {:exit-code 1
                                                    :stdout ""
                                                    :stderr "not authenticated"}})
          result (sandbox/create-pr! exec "env-1"
                                     {:title "PR" :body "" :base-branch "main"})]
      (is (not (:success? result)))
      (is (some? (:error result))))))

;; ============================================================================
;; write-and-stage-files! tests
;; ============================================================================

(deftest write-and-stage-files-success-test
  (testing "write-and-stage-files! processes all code artifacts"
    (let [[exec cmds] (create-mock-executor)
          code-artifacts [{:code/files [{:action :create :path "src/a.clj" :content "(ns a)"}
                                        {:action :modify :path "src/b.clj" :content "(ns b)"}
                                        {:action :delete :path "src/old.clj"}]}]
          result (sandbox/write-and-stage-files! exec "env-1" code-artifacts)]
      (is (:success? result))
      (is (= 1 (get-in result [:metrics :files-written])))
      (is (= 1 (get-in result [:metrics :files-modified])))
      (is (= 1 (get-in result [:metrics :files-deleted])))
      (is (= 3 (get-in result [:metrics :total-operations])))
      ;; Should have: write, write, delete, stage = 4 commands
      (is (= 4 (count @cmds)))
      ;; Last command should stage the specific files
      (is (clojure.string/starts-with? (last @cmds) "git add "))
      (is (clojure.string/includes? (last @cmds) "src/a.clj")))))

(deftest write-and-stage-files-failure-test
  (testing "write-and-stage-files! reports errors from failed operations"
    (let [[exec _cmds] (create-mock-executor
                        :default-response {:exit-code 1 :stdout "" :stderr "permission denied"})
          code-artifacts [{:code/files [{:action :create :path "src/a.clj" :content "(ns a)"}]}]
          result (sandbox/write-and-stage-files! exec "env-1" code-artifacts)]
      (is (not (:success? result)))
      (is (seq (:errors result))))))
