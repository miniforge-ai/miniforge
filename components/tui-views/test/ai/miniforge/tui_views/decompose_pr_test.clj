(ns ai.miniforge.tui-views.decompose-pr-test
  "Comprehensive tests for handle-decompose-pr in interface.clj.

   Acceptance criteria verified:
   1. Fetches diff and metadata for the selected PR via GitHub CLI
   2. Calls pr-decomposer/decompose (via requiring-resolve) with assembled context
   3. Returns msg/decomposition-started with real sub-PR proposals on success
   4. Returns msg/decomposition-started with error message on any failure (no exceptions leak)
   5. Uses requiring-resolve for pr-decomposer dependency (Babashka compat)"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.tui-views.interface :as iface]
   [ai.miniforge.tui-views.msg :as msg]
   [ai.miniforge.tui-views.persistence.github :as github]))

;; ---------------------------------------------------------------------------- Helpers

(defn msg-type [m] (first m))
(defn msg-payload [m] (second m))

(def ^:private sample-pr
  {:pr/repo "acme/app"
   :pr/number 42
   :pr/title "Add auth and dashboard"
   :pr/body "Adds JWT auth and admin panel."
   :pr/labels ["feature"]
   :pr/additions 650
   :pr/deletions 30
   :pr/head-sha "abc123"
   :pr/status :open
   :pr/ci-status :passing})

(def ^:private sample-detail
  {:title "Add auth and dashboard"
   :body "Adds JWT auth and admin panel."
   :labels [{:name "feature"}]
   :files [{:path "src/auth.clj" :additions 300 :deletions 10}
           {:path "src/dashboard.clj" :additions 350 :deletions 20}]})

(def ^:private sample-diff
  "diff --git a/src/auth.clj b/src/auth.clj\n+auth code\ndiff --git a/src/dashboard.clj b/src/dashboard.clj\n+dashboard code")

(def ^:private sample-plan
  {:sub-prs [{:title "Add auth module"
              :description "JWT and session management."
              :files ["src/auth.clj"]
              :dependency-order 0
              :estimated-size {:additions 300 :deletions 10}}
             {:title "Add dashboard"
              :description "Admin dashboard views."
              :files ["src/dashboard.clj"]
              :dependency-order 1
              :estimated-size {:additions 350 :deletions 20}}]
   :strategy "Split auth (foundation) from dashboard (consumer)."
   :original-size {:additions 650 :deletions 30}
   :coverage 1.0})

(defn- mock-decompose-success
  "Returns a mock decompose fn that succeeds with sample-plan."
  []
  (fn [_pr _diff _files _llm-fn]
    {:ok? true
     :data {:plan sample-plan
            :coverage {:covered? true}
            :warnings []}}))

(defn- mock-github-success
  "Returns a mock fetch fn that returns diff + detail."
  []
  (fn [_ _]
    {:diff sample-diff :detail sample-detail
     :repo "acme/app" :number 42}))

(defn- mock-requiring-resolve
  "Returns a mock requiring-resolve that returns the given fn for decompose symbol."
  [decompose-fn]
  (fn [sym]
    (when (= sym 'ai.miniforge.pr-decompose.interface/decompose)
      decompose-fn)))

;; ---------------------------------------------------------------------------- AC1: Fetches diff and metadata

(deftest handle-decompose-pr-fetches-diff-and-detail-test
  (testing "calls fetch-pr-diff-and-detail with correct repo and number"
    (let [fetched-args (atom nil)]
      (with-redefs [github/fetch-pr-diff-and-detail
                    (fn [repo number]
                      (reset! fetched-args {:repo repo :number number})
                      {:diff sample-diff :detail sample-detail
                       :repo repo :number number})
                    requiring-resolve (mock-requiring-resolve (mock-decompose-success))]
        (iface/handle-decompose-pr {:pr sample-pr})
        (is (= "acme/app" (:repo @fetched-args)))
        (is (= 42 (:number @fetched-args)))))))

(deftest handle-decompose-pr-extracts-changed-files-from-detail-test
  (testing "extracts file paths from the detail response and passes to decompose"
    (let [captured-files (atom nil)]
      (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                    requiring-resolve
                    (mock-requiring-resolve
                     (fn [_pr diff files _llm-fn]
                       (reset! captured-files files)
                       {:ok? true
                        :data {:plan sample-plan
                               :coverage {:covered? true}
                               :warnings []}}))]
        (iface/handle-decompose-pr {:pr sample-pr})
        (is (= ["src/auth.clj" "src/dashboard.clj"] @captured-files))))))

;; ---------------------------------------------------------------------------- AC2: Calls decompose with assembled context

(deftest handle-decompose-pr-calls-decompose-with-pr-and-diff-test
  (testing "passes the PR map and diff text to the decompose function"
    (let [captured (atom nil)]
      (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                    requiring-resolve
                    (mock-requiring-resolve
                     (fn [pr diff files llm-fn]
                       (reset! captured {:pr pr :diff diff :files files
                                         :llm-fn-present? (fn? llm-fn)})
                       {:ok? true
                        :data {:plan sample-plan
                               :coverage {:covered? true}
                               :warnings []}}))]
        (iface/handle-decompose-pr {:pr sample-pr})
        (is (= sample-pr (:pr @captured)))
        (is (= sample-diff (:diff @captured)))
        (is (= ["src/auth.clj" "src/dashboard.clj"] (:files @captured)))
        (is (true? (:llm-fn-present? @captured)))))))

;; ---------------------------------------------------------------------------- AC3: Returns decomposition-started with real plan on success

(deftest handle-decompose-pr-success-returns-plan-test
  (testing "returns :msg/decomposition-started with the real plan on success"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve (mock-requiring-resolve (mock-decompose-success))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))))))

(deftest handle-decompose-pr-success-has-sub-prs-test
  (testing "successful decomposition includes sub-PR proposals"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve (mock-requiring-resolve (mock-decompose-success))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})
            payload (msg-payload m)]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (some? (:sub-prs payload)))
        (is (= 2 (count (:sub-prs payload))))))))

(deftest handle-decompose-pr-success-plan-has-expected-fields-test
  (testing "plan payload includes strategy, sub-prs, original-size, coverage"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve (mock-requiring-resolve (mock-decompose-success))]
      (let [payload (msg-payload (iface/handle-decompose-pr {:pr sample-pr}))]
        ;; The plan is merged into the payload
        (is (= 2 (count (:sub-prs payload))))
        (let [first-sub (first (:sub-prs payload))]
          (is (= "Add auth module" (:title first-sub)))
          (is (= ["src/auth.clj"] (:files first-sub))))))))

(deftest handle-decompose-pr-no-longer-returns-stub-message-test
  (testing "does NOT return 'Decomposition analysis not yet wired'"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve (mock-requiring-resolve (mock-decompose-success))]
      (let [payload (msg-payload (iface/handle-decompose-pr {:pr sample-pr}))]
        (is (not= "Decomposition analysis not yet wired"
                  (:message payload)))))))

;; ---------------------------------------------------------------------------- AC4: Returns error on failures, no exceptions leak

(deftest handle-decompose-pr-both-fetches-nil-test
  (testing "returns error message when both diff and detail are nil"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "acme/app" :number 42})]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (string? (:message (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "Failed"))))))

(deftest handle-decompose-pr-diff-nil-detail-present-test
  (testing "proceeds with empty diff when only detail is available"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail sample-detail :repo "acme/app" :number 42})
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr diff _files _llm-fn]
                     ;; diff should be empty string, not nil
                     (is (= "" diff))
                     {:ok? true
                      :data {:plan sample-plan
                             :coverage {:covered? true}
                             :warnings []}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))))))

(deftest handle-decompose-pr-detail-nil-diff-present-test
  (testing "proceeds with empty changed-files when only diff is available"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff sample-diff :detail nil :repo "acme/app" :number 42})
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff files _llm-fn]
                     ;; changed-files should be empty when detail is nil
                     (is (= [] files))
                     {:ok? true
                      :data {:plan sample-plan
                             :coverage {:covered? true}
                             :warnings []}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))))))

(deftest handle-decompose-pr-decompose-fn-not-found-test
  (testing "returns error when requiring-resolve returns nil (component not on classpath)"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff sample-diff :detail sample-detail
                             :repo "acme/app" :number 42})
                  requiring-resolve (fn [_] nil)]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "not available"))))))

(deftest handle-decompose-pr-decompose-returns-error-test
  (testing "returns error message when decompose pipeline returns {:ok? false}"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff _files _llm-fn]
                     {:ok? false
                      :error {:code :llm-error
                              :message "Rate limited by provider"}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "Rate limited"))))))

(deftest handle-decompose-pr-decompose-returns-parse-error-test
  (testing "returns error message when LLM response fails to parse"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff _files _llm-fn]
                     {:ok? false
                      :error {:code :parse-error
                              :message "Could not parse EDN from LLM response"}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "parse"))))))

(deftest handle-decompose-pr-exception-in-github-fetch-test
  (testing "catches exception from GitHub fetch and returns error msg"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (Exception. "gh CLI not found")))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "gh CLI not found"))))))

(deftest handle-decompose-pr-exception-in-decompose-fn-test
  (testing "catches exception from decompose function and returns error msg"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff _files _llm-fn]
                     (throw (RuntimeException. "LLM connection timeout"))))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "LLM connection timeout"))))))

(deftest handle-decompose-pr-exception-in-requiring-resolve-test
  (testing "catches exception from requiring-resolve itself"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve
                  (fn [_] (throw (Exception. "Class not found")))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= [] (:sub-prs (msg-payload m))))
        (is (string? (:message (msg-payload m))))))))

(deftest handle-decompose-pr-no-exception-leaks-test
  (testing "no scenario leaks exceptions — all return decomposition-started messages"
    (let [scenarios
          [{:name "github throws"
            :redefs {#'github/fetch-pr-diff-and-detail
                     (fn [_ _] (throw (Exception. "boom")))}}
           {:name "both nil"
            :redefs {#'github/fetch-pr-diff-and-detail
                     (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})}}
           {:name "decompose fn nil"
            :redefs {#'github/fetch-pr-diff-and-detail
                     (fn [_ _] {:diff "d" :detail {:files []} :repo "r" :number 1})
                     #'clojure.core/requiring-resolve (fn [_] nil)}}
           {:name "decompose throws"
            :redefs {#'github/fetch-pr-diff-and-detail
                     (fn [_ _] {:diff "d" :detail {:files []} :repo "r" :number 1})
                     #'clojure.core/requiring-resolve
                     (fn [_] (fn [& _] (throw (Error. "OOM"))))}}]]
      (doseq [{:keys [name redefs]} scenarios]
        (testing (str "scenario: " name)
          (with-redefs-fn redefs
            (fn []
              (let [m (iface/handle-decompose-pr {:pr sample-pr})]
                (is (= :msg/decomposition-started (msg-type m))
                    (str "Expected decomposition-started for: " name))))))))))

;; ---------------------------------------------------------------------------- AC5: Uses requiring-resolve (Babashka compat)

(deftest handle-decompose-pr-uses-requiring-resolve-test
  (testing "uses requiring-resolve to load pr-decompose.interface/decompose"
    (let [resolved-sym (atom nil)]
      (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                    requiring-resolve
                    (fn [sym]
                      (reset! resolved-sym sym)
                      ;; Return a mock decompose fn
                      (mock-decompose-success))]
        (iface/handle-decompose-pr {:pr sample-pr})
        (is (= 'ai.miniforge.pr-decompose.interface/decompose @resolved-sym))))))

;; ---------------------------------------------------------------------------- PR-ID construction

(deftest handle-decompose-pr-id-is-repo-number-tuple-test
  (testing "pr-id in the result message is [repo number]"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "org/repo" :number 99})]
      (let [m (iface/handle-decompose-pr {:pr {:pr/repo "org/repo" :pr/number 99}})]
        (is (= ["org/repo" 99] (:pr-id (msg-payload m))))))))

;; ---------------------------------------------------------------------------- Edge: detail with no files key

(deftest handle-decompose-pr-detail-with-no-files-key-test
  (testing "handles detail map that has no :files key gracefully"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff sample-diff :detail {:title "T"}
                             :repo "acme/app" :number 42})
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff files _llm-fn]
                     (is (= [] files))
                     {:ok? true
                      :data {:plan sample-plan
                             :coverage {:covered? true}
                             :warnings []}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))))))

;; ---------------------------------------------------------------------------- Edge: decompose result has no :error :message

(deftest handle-decompose-pr-error-without-message-test
  (testing "uses fallback message when :error has no :message key"
    (with-redefs [github/fetch-pr-diff-and-detail (mock-github-success)
                  requiring-resolve
                  (mock-requiring-resolve
                   (fn [_pr _diff _files _llm-fn]
                     {:ok? false
                      :error {:code :unknown-error}}))]
      (let [m (iface/handle-decompose-pr {:pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))
        (is (= [] (:sub-prs (msg-payload m))))
        ;; Should have the fallback message
        (is (string? (:message (msg-payload m))))
        (is (str/includes? (:message (msg-payload m)) "failed"))))))

;; ---------------------------------------------------------------------------- dispatch-effect routing for :decompose-pr

(deftest dispatch-effect-routes-decompose-pr-test
  (testing ":decompose-pr effect type routes to handle-decompose-pr"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})]
      (let [m (iface/dispatch-effect nil {:type :decompose-pr :pr sample-pr})]
        (is (= :msg/decomposition-started (msg-type m)))))))

;; ---------------------------------------------------------------------------- handle-fetch-pr-diff (related)

(deftest handle-fetch-pr-diff-success-test
  (testing "returns :msg/pr-diff-fetched with diff and detail"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [repo number]
                    {:diff sample-diff :detail sample-detail
                     :repo repo :number number})]
      (let [m (iface/handle-fetch-pr-diff {:repo "acme/app" :number 42})]
        (is (= :msg/pr-diff-fetched (msg-type m)))
        (is (= ["acme/app" 42] (:pr-id (msg-payload m))))
        (is (= sample-diff (:diff (msg-payload m))))
        (is (= sample-detail (:detail (msg-payload m))))
        (is (nil? (:error (msg-payload m))))))))

(deftest handle-fetch-pr-diff-both-nil-test
  (testing "returns error when both diff and detail are nil"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] {:diff nil :detail nil :repo "r" :number 1})]
      (let [m (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched (msg-type m)))
        (is (string? (:error (msg-payload m))))))))

(deftest handle-fetch-pr-diff-exception-test
  (testing "returns error when fetch throws"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ _] (throw (Exception. "network error")))]
      (let [m (iface/handle-fetch-pr-diff {:repo "r" :number 1})]
        (is (= :msg/pr-diff-fetched (msg-type m)))
        (is (nil? (:diff (msg-payload m))))
        (is (str/includes? (:error (msg-payload m)) "network error"))))))

(deftest handle-fetch-pr-diff-string-number-coercion-test
  (testing "coerces string number to long"
    (with-redefs [github/fetch-pr-diff-and-detail
                  (fn [_ number]
                    (is (= 42 number))
                    {:diff "d" :detail {:title "T"} :repo "r" :number number})]
      (let [m (iface/handle-fetch-pr-diff {:repo "r" :number "42"})]
        (is (= :msg/pr-diff-fetched (msg-type m)))))))