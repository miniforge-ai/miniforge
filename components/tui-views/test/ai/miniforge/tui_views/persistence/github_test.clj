(ns ai.miniforge.tui-views.persistence.github-test
  "Tests for the GitHub CLI wrapper (persistence/github.clj).

   All tests mock babashka.process/shell to avoid real CLI calls.
   Verifies:
   - fetch-pr-diff returns diff string on success, nil on failure
   - fetch-pr-detail returns parsed JSON map on success, nil on failure
   - fetch-pr-diff-and-detail composes both + coerces number
   - Graceful handling of non-zero exit, empty output, and exceptions"
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.tui-views.persistence.github :as github]
   [babashka.process :as process]))

;; ---------------------------------------------------------------------------- Helpers

(defn mock-shell-success
  "Return a mock shell result that looks like a successful process."
  [out]
  {:exit 0 :out out :err ""})

(defn mock-shell-failure
  "Return a mock shell result with non-zero exit code."
  ([exit err] {:exit exit :out "" :err err})
  ([exit] (mock-shell-failure exit "something went wrong")))

;; ---------------------------------------------------------------------------- fetch-pr-diff

(deftest fetch-pr-diff-success-test
  (testing "returns trimmed diff string when gh exits 0"
    (let [diff-text "diff --git a/foo.clj b/foo.clj\n+new line"]
      (with-redefs [process/shell (fn [_opts & args]
                                    (is (= ["gh" "pr" "diff" "42" "--repo" "owner/repo"]
                                           (vec args)))
                                    (mock-shell-success (str diff-text "\n")))]
        (is (= diff-text (github/fetch-pr-diff "owner/repo" 42)))))))

(deftest fetch-pr-diff-string-number-test
  (testing "coerces number to string in CLI args via (str number)"
    (with-redefs [process/shell (fn [_opts & args]
                                  (is (= "99" (nth (vec args) 3)))
                                  (mock-shell-success "patch"))]
      (is (= "patch" (github/fetch-pr-diff "owner/repo" 99))))))

(deftest fetch-pr-diff-failure-exit-code-test
  (testing "returns nil when gh exits non-zero"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-failure 1 "not found"))]
      (is (nil? (github/fetch-pr-diff "owner/repo" 42))))))

(deftest fetch-pr-diff-exception-test
  (testing "returns nil when process/shell throws"
    (with-redefs [process/shell (fn [_opts & _] (throw (Exception. "gh not installed")))]
      (is (nil? (github/fetch-pr-diff "owner/repo" 42))))))

(deftest fetch-pr-diff-empty-output-test
  (testing "returns empty string when diff is empty but exit 0"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-success ""))]
      (is (= "" (github/fetch-pr-diff "owner/repo" 1))))))

(deftest fetch-pr-diff-whitespace-trimming-test
  (testing "trims leading/trailing whitespace from output"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-success "  diff text  "))]
      (is (= "diff text" (github/fetch-pr-diff "r" 1))))))

(deftest fetch-pr-diff-nil-out-test
  (testing "handles nil :out gracefully via (get result :out \"\")"
    (with-redefs [process/shell (fn [_opts & _] {:exit 0 :out nil :err ""})]
      ;; str/trim on (:out result "") → trims empty string
      (is (= "" (github/fetch-pr-diff "r" 1))))))

;; ---------------------------------------------------------------------------- fetch-pr-detail

(def sample-detail-json
  "{\"title\":\"Fix bug\",\"body\":\"Description\",\"labels\":[{\"name\":\"bugfix\"}],\"files\":[{\"path\":\"src/a.clj\",\"additions\":5,\"deletions\":2}]}")

(deftest fetch-pr-detail-success-test
  (testing "returns parsed map with keyword keys on success"
    (with-redefs [process/shell (fn [_opts & args]
                                  (let [a (vec args)]
                                    (is (= "gh" (first a)))
                                    (is (= "pr" (second a)))
                                    (is (= "view" (nth a 2)))
                                    (is (= "7" (nth a 3)))
                                    (mock-shell-success sample-detail-json)))]
      (let [result (github/fetch-pr-detail "acme/lib" 7)]
        (is (map? result))
        (is (= "Fix bug" (:title result)))
        (is (= "Description" (:body result)))
        (is (= 1 (count (:labels result))))
        (is (= "bugfix" (:name (first (:labels result)))))
        (is (= 1 (count (:files result))))
        (is (= "src/a.clj" (:path (first (:files result)))))))))

(deftest fetch-pr-detail-failure-exit-code-test
  (testing "returns nil when gh exits non-zero"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-failure 128 "auth error"))]
      (is (nil? (github/fetch-pr-detail "owner/repo" 1))))))

(deftest fetch-pr-detail-exception-test
  (testing "returns nil when process/shell throws"
    (with-redefs [process/shell (fn [_opts & _] (throw (RuntimeException. "timeout")))]
      (is (nil? (github/fetch-pr-detail "owner/repo" 5))))))

(deftest fetch-pr-detail-malformed-json-test
  (testing "returns nil when JSON parsing fails (exception caught)"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-success "NOT JSON"))]
      ;; cheshire will throw — caught by the outer try/catch
      (is (nil? (github/fetch-pr-detail "owner/repo" 1))))))

(deftest fetch-pr-detail-empty-json-object-test
  (testing "returns empty-ish map for minimal JSON"
    (with-redefs [process/shell (fn [_opts & _] (mock-shell-success "{}"))]
      (let [result (github/fetch-pr-detail "r" 1)]
        (is (map? result))
        (is (nil? (:title result)))))))

(deftest fetch-pr-detail-passes-json-fields-arg-test
  (testing "passes --json title,body,labels,files to gh CLI"
    (let [captured-args (atom nil)]
      (with-redefs [process/shell (fn [_opts & args]
                                    (reset! captured-args (vec args))
                                    (mock-shell-success "{}"))]
        (github/fetch-pr-detail "r" 1)
        (let [args @captured-args
              json-idx (.indexOf args "--json")]
          (is (pos? json-idx))
          (is (= "title,body,labels,files" (nth args (inc json-idx)))))))))

;; ---------------------------------------------------------------------------- fetch-pr-diff-and-detail

(deftest fetch-pr-diff-and-detail-success-test
  (testing "returns combined map when both succeed"
    (with-redefs [github/fetch-pr-diff   (fn [r n] (is (= "o/r" r)) (is (= 10 n)) "diff-text")
                  github/fetch-pr-detail (fn [r n] (is (= "o/r" r)) (is (= 10 n)) {:title "T"})]
      (let [result (github/fetch-pr-diff-and-detail "o/r" 10)]
        (is (= "diff-text" (:diff result)))
        (is (= {:title "T"} (:detail result)))
        (is (= "o/r" (:repo result)))
        (is (= 10 (:number result)))))))

(deftest fetch-pr-diff-and-detail-string-number-coercion-test
  (testing "coerces string number to long"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= 42 n)) "d")
                  github/fetch-pr-detail (fn [_ n] (is (= 42 n)) {:title "X"})]
      (let [result (github/fetch-pr-diff-and-detail "r" "42")]
        (is (= 42 (:number result)))))))

(deftest fetch-pr-diff-and-detail-int-number-test
  (testing "passes integer number through unchanged"
    (with-redefs [github/fetch-pr-diff   (fn [_ n] (is (= 7 n)) nil)
                  github/fetch-pr-detail (fn [_ n] (is (= 7 n)) nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 7)]
        (is (= 7 (:number result)))))))

(deftest fetch-pr-diff-and-detail-partial-failure-test
  (testing "diff nil but detail ok"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] {:title "T"})]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (nil? (:diff result)))
        (is (= {:title "T"} (:detail result))))))

  (testing "diff ok but detail nil"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] "patch")
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (= "patch" (:diff result)))
        (is (nil? (:detail result)))))))

(deftest fetch-pr-diff-and-detail-both-fail-test
  (testing "both nil when gh is unavailable"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (let [result (github/fetch-pr-diff-and-detail "r" 1)]
        (is (nil? (:diff result)))
        (is (nil? (:detail result)))
        (is (= "r" (:repo result)))
        (is (= 1 (:number result)))))))

(deftest fetch-pr-diff-and-detail-return-shape-invariant-test
  (testing "always returns exactly 4 keys: :diff :detail :repo :number"
    (doseq [[label mock-diff mock-detail]
            [["both ok"    (fn [_ _] "d")  (fn [_ _] {:title "T"})]
             ["diff nil"   (fn [_ _] nil)  (fn [_ _] {:title "T"})]
             ["detail nil" (fn [_ _] "d") (fn [_ _] nil)]
             ["both nil"   (fn [_ _] nil)  (fn [_ _] nil)]]]
      (with-redefs [github/fetch-pr-diff   mock-diff
                    github/fetch-pr-detail mock-detail]
        (let [result (github/fetch-pr-diff-and-detail "r" 1)]
          (is (= #{:diff :detail :repo :number} (set (keys result)))
              (str "Keys invariant failed for: " label)))))))

(deftest fetch-pr-diff-and-detail-number-type-invariant-test
  (testing ":number is always a long regardless of input type"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (is (instance? Long (:number (github/fetch-pr-diff-and-detail "r" 42))))
      (is (instance? Long (:number (github/fetch-pr-diff-and-detail "r" "42"))))
      (is (instance? Long (:number (github/fetch-pr-diff-and-detail "r" 0)))))))

(deftest fetch-pr-diff-and-detail-repo-passthrough-invariant-test
  (testing ":repo is always the exact input string"
    (with-redefs [github/fetch-pr-diff   (fn [_ _] nil)
                  github/fetch-pr-detail (fn [_ _] nil)]
      (doseq [repo ["owner/repo" "my-org/my-repo" "gitlab:group/project"
                    "org/repo-with-dashes" "ORG/REPO"]]
        (is (= repo (:repo (github/fetch-pr-diff-and-detail repo 1)))
            (str "Repo passthrough failed for: " repo))))))