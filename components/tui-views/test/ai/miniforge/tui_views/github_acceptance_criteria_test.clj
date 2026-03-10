(ns ai.miniforge.tui-views.github-acceptance-criteria-test
  "Acceptance criteria verification tests that validate all stated requirements
   for the GitHub persistence layer in a single consolidated test file.

   Acceptance criteria:
   AC-1: ai.miniforge.tui-views.persistence.github namespace exists with fetch-pr-diff-and-detail
   AC-2: Calls gh pr diff and gh pr view --json for the given repo/number
   AC-3: Returns {:diff :detail :repo :number} map; nil diff/detail on CLI failure (no exceptions)
   AC-4: Existing github_test.clj and github_msg_contract_test.clj pass (verified by test runner)"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.persistence.github :as github]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.msg :as msg]
   [babashka.process :as process]))

;; ---------------------------------------------------------------------------- Helpers

(defn mock-sh-success [out]
  {:exit 0 :out out :err ""})

(defn mock-sh-failure [exit err]
  {:exit exit :out "" :err err})

(def sample-detail-json
  "{\"title\":\"Fix\\nbugs\",\"body\":\"desc\",\"labels\":[{\"name\":\"bug\"}],\"files\":[{\"path\":\"a.clj\",\"additions\":3,\"deletions\":1}]}")

;; ---------------------------------------------------------------------------- AC-1: Namespace and function existence

(deftest ac1-namespace-exists-test
  (testing "the github namespace is loadable"
    (is (some? (find-ns 'ai.miniforge.tui-views.persistence.github)))))

(deftest ac1-fetch-pr-diff-and-detail-is-public-test
  (testing "fetch-pr-diff-and-detail is a public var"
    (is (some? (resolve 'ai.miniforge.tui-views.persistence.github/fetch-pr-diff-and-detail)))))

(deftest ac1-fetch-pr-diff-is-public-test
  (testing "fetch-pr-diff is a public var"
    (is (some? (resolve 'ai.miniforge.tui-views.persistence.github/fetch-pr-diff)))))

(deftest ac1-fetch-pr-detail-is-public-test
  (testing "fetch-pr-detail is a public var"
    (is (some? (resolve 'ai.miniforge.tui-views.persistence.github/fetch-pr-detail)))))

(deftest ac1-functions-are-fns-test
  (testing "all three are actual functions"
    (is (fn? github/fetch-pr-diff))
    (is (fn? github/fetch-pr-detail))
    (is (fn? github/fetch-pr-diff-and-detail))))

;; ---------------------------------------------------------------------------- AC-2: CLI commands invoked

(deftest ac2-fetch-pr-diff-calls-gh-pr-diff-test
  (testing "fetch-pr-diff invokes `gh pr diff <number> --repo <repo>`"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success "patch"))]
        (github/fetch-pr-diff "owner/repo" 42)
        (is (= ["gh" "pr" "diff" "42" "--repo" "owner/repo"] @captured))))))

(deftest ac2-fetch-pr-detail-calls-gh-pr-view-json-test
  (testing "fetch-pr-detail invokes `gh pr view <number> --repo <repo> --json title,body,labels,files`"
    (let [captured (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                 (reset! captured (vec args))
                                 (mock-sh-success sample-detail-json))]
        (github/fetch-pr-detail "owner/repo" 42)
        (let [args @captured]
          (is (= "gh" (nth args 0)))
          (is (= "pr" (nth args 1)))
          (is (= "view" (nth args 2)))
          (is (= "42" (nth args 3)))
          (is (= "--repo" (nth args 4)))
          (is (= "owner/repo" (nth args 5)))
          (is (= "--json" (nth args 6)))
          (is (= "title,body,labels,files" (nth args 7))))))))

(deftest ac2-composite-calls-both-test
  (testing "fetch-pr-diff-and-detail calls both fetch-pr-diff and fetch-pr-detail"
    (let [diff-called (atom false)
          detail-called (atom false)]
      (with-redefs [github/fetch-pr-diff   (fn [r n] (reset! diff-called true) "d")
                    github/fetch-pr-detail (fn [r n] (reset! detail-called true) {:t 1})]
        (github/fetch-pr-diff-and-detail "r" 1)
        (is (true? @diff-called))
        (is (true? @detail-called))))))

;; ---------------------------------------------------------------------------- AC-3: Return shape and nil on failure

(deftest ac3-return-map-has-four-keys-test
  (testing "returns a map with exactly :diff :detail :repo :number"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "d")
                  github/fetch-pr-detail (fn [_ _] {:title "T"})]
      (let [result (github/fetch-pr-diff-and-detail "org/repo" 99)]
        (is (map? result))
        (is (= #{:diff :detail :repo :number} (set (keys result))))
        (is (= 4 (count (keys result))))))))

(deftest ac3-diff-is-string-on-success-test
  (testing ":diff is a string when gh pr diff succeeds"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "diff content")
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (string? (:diff (github/fetch-pr-diff-and-detail "r" 1)))))))

(deftest ac3-detail-is-map-on-success-test
  (testing ":detail is a map when gh pr view --json succeeds"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] {:title "T" :body "B"})]
      (is (map? (:detail (github/fetch-pr-diff-and-detail "r" 1)))))))

(deftest ac3-diff-nil-on-failure-test
  (testing ":diff is nil when gh pr diff fails (non-zero exit)"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 1 "err"))]
      (is (nil? (github/fetch-pr-diff "r" 1))))))

(deftest ac3-detail-nil-on-failure-test
  (testing ":detail is nil when gh pr view --json fails"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-failure 128 "auth"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

(deftest ac3-detail-nil-on-malformed-json-test
  (testing ":detail is nil when JSON parsing fails"
    (with-redefs [process/shell (fn [_opts & _] (mock-sh-success "NOT JSON"))]
      (is (nil? (github/fetch-pr-detail "r" 1))))))

(deftest ac3-no-exceptions-on-failure-test
  (testing "never throws exceptions to caller on CLI failure"
    ;; fetch-pr-diff: exception in process/shell
    (with-redefs [process/shell (fn [_opts & _] (throw (Exception. "gh not found")))]
      (is (nil? (github/fetch-pr-diff "r" 1))))
    ;; fetch-pr-detail: exception in process/shell
    (with-redefs [process/shell (fn [_opts & _] (throw (java.io.IOException. "broken pipe")))]
      (is (nil? (github/fetch-pr-detail "r" 1))))
    ;; composite: both fail via process/shell
    (with-redefs [process/shell (fn [_opts & _] (throw (RuntimeException. "timeout")))]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (nil? (:diff result)))
        (is (nil? (:detail result)))
        (is (= "r" (:repo result)))
        (is (= 1 (:number result)))))))

(deftest ac3-repo-passthrough-test
  (testing ":repo is the exact input string"
    (with-redefs [github/fetch-pr-diff (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (= "my-org/my-repo" (:repo (github/fetch-pr-diff-and-detail "my-org/my-repo" 1)))))))

(deftest ac3-number-is-long-test
  (testing ":number is always a long, coerced from string or integer"
    (with-redefs [github/fetch-pr-diff (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      ;; Integer input
      (is (= 42 (:number (github/fetch-pr-diff-and-detail "r" 42))))
      (is (instance? Long (:number (github/fetch-pr-diff-and-detail "r" 42))))
      ;; String input
      (is (= 99 (:number (github/fetch-pr-diff-and-detail "r" "99"))))
      (is (instance? Long (:number (github/fetch-pr-diff-and-detail "r" "99")))))))

(deftest ac3-partial-failure-preserves-success-test
  (testing "diff failure does not affect detail success"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] {:title "T"})]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (nil? (:diff result)))
        (is (= {:title "T"} (:detail result))))))

  (testing "detail failure does not affect diff success"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "patch")
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (= "patch" (:diff result)))
        (is (nil? (:detail result)))))))

;; ---------------------------------------------------------------------------- AC-4: Integration with msg and interface

(deftest ac4-handle-fetch-pr-diff-wires-to-msg-test
  (testing "handle-fetch-pr-diff produces :msg/pr-diff-fetched"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [r n] {:diff "d" :detail {:title "T"} :repo r :number n})]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["r" 1] (:pr-id payload)))
        (is (= "d" (:diff payload)))
        (is (= {:title "T"} (:detail payload)))
        (is (nil? (:error payload)))))))

(deftest ac4-handle-fetch-pr-diff-total-failure-sets-error-test
  (testing "both nil diff and detail triggers error message"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})]
      (let [[_ payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (string? (:error payload)))))))

(deftest ac4-handle-fetch-pr-diff-exception-caught-test
  (testing "exception is caught and wrapped in error"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (Exception. "network error")))]
      (let [[msg-type payload] (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (nil? (:diff payload)))
        (is (nil? (:detail payload)))
        (is (str/includes? (:error payload) "network error"))))))

(deftest ac4-dispatch-effect-routes-to-handler-test
  (testing "dispatch-effect :fetch-pr-diff reaches handle-fetch-pr-diff"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [r n] {:diff "patch" :detail {:title "X"} :repo r :number n})]
      (let [[msg-type payload] (iface/dispatch-effect nil {:type :fetch-pr-diff
                                                           :repo "org/lib"
                                                           :number 55})]
        (is (= :msg/pr-diff-fetched msg-type))
        (is (= ["org/lib" 55] (:pr-id payload)))))))

(deftest ac4-msg-pr-diff-fetched-contract-test
  (testing "msg/pr-diff-fetched produces correct shape"
    ;; No error
    (let [[t p] (msg/pr-diff-fetched ["r" 1] "d" {:t 1} nil)]
      (is (= :msg/pr-diff-fetched t))
      (is (contains? p :pr-id))
      (is (contains? p :diff))
      (is (contains? p :detail))
      (is (not (contains? p :error))))
    ;; With error
    (let [[t p] (msg/pr-diff-fetched ["r" 1] nil nil "err")]
      (is (= :msg/pr-diff-fetched t))
      (is (contains? p :error))
      (is (= "err" (:error p))))))
