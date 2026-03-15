(ns ai.miniforge.pr-sync.fleet-parallel-test
  "Tests for fleet sync with :parallel? option and fetch-all-fleet-prs :state."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.shell]
   [ai.miniforge.pr-sync.core :as core]))

(defn temp-config-path []
  (let [f (java.io.File/createTempFile "miniforge-parallel-test" ".edn")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(def sample-pr-json
  "[{\"number\":1,\"title\":\"PR 1\",\"url\":\"https://github.com/a/b/pull/1\",\"state\":\"OPEN\",\"headRefName\":\"feat/x\",\"isDraft\":false,\"reviewDecision\":null,\"statusCheckRollup\":[]}]")

(defn make-sh-router
  [routes]
  (fn [& args]
    (let [match (some (fn [[substr response]]
                        (when (some #(and (string? %) (.contains ^String % substr)) args)
                          response))
                      routes)]
      (or match {:exit 1 :out "" :err "no route matched"}))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-fleet-with-status :parallel? true
;; ─────────────────────────────────────────────────────────────────────────────

(deftest fetch-fleet-with-status-parallel-test
  (testing "Parallel mode produces same results as sequential"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["acme/app" "other/lib"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"acme/app"  {:exit 0 :out sample-pr-json :err ""}
                      "other/lib" {:exit 0 :out "[]" :err ""}})]
        (let [seq-result (core/fetch-fleet-with-status {:config-path path :parallel? false})
              par-result (core/fetch-fleet-with-status {:config-path path :parallel? true})]
          ;; Same PR count
          (is (= (count (:prs seq-result)) (count (:prs par-result))))
          ;; Same summary
          (is (= (:summary seq-result) (:summary par-result)))
          ;; Same sync status count
          (is (= (count (:sync-statuses seq-result))
                 (count (:sync-statuses par-result)))))))))

(deftest fetch-fleet-with-status-parallel-with-errors-test
  (testing "Parallel mode handles partial failures correctly"
    (let [path (temp-config-path)]
      (spit path (pr-str {:fleet {:repos ["ok/repo" "bad/repo"]}}))
      (with-redefs [clojure.java.shell/sh
                    (make-sh-router
                     {"ok/repo"  {:exit 0 :out sample-pr-json :err ""}
                      "bad/repo" {:exit 1 :out "" :err "HTTP 500 Internal Server Error"}})]
        (let [result (core/fetch-fleet-with-status {:config-path path :parallel? true})]
          (is (= 1 (count (:prs result))))
          (is (= 2 (count (:sync-statuses result))))
          (is (true? (get-in result [:summary :partial?]))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; fetch-all-fleet-prs with :state option
;; ─────────────────────────────────────────────────────────────────────────────

(def sample-merged-pr-json
  "[{\"number\":99,\"title\":\"Merged\",\"url\":\"https://github.com/a/b/pull/99\",\"state\":\"MERGED\",\"headRefName\":\"feat/done\",\"isDraft\":false,\"mergedAt\":\"2026-01-01T00:00:00Z\",\"reviewDecision\":null,\"statusCheckRollup\":[]}]")

(deftest fetch-all-fleet-prs-with-state-test
  (testing ":state :merged passes through to fetch-prs-by-state"
    (let [path (temp-config-path)
          captured-state (atom nil)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      ;; Capture the state arg passed to gh
                      (when-let [s (some #(when (and (string? %) (#{ "merged" "closed" "all"} %)) %)
                                         args)]
                        (reset! captured-state s))
                      {:exit 0 :out sample-merged-pr-json :err ""})]
        (let [prs (core/fetch-all-fleet-prs {:config-path path :state :merged})]
          (is (vector? prs))
          (is (= "merged" @captured-state)))))))

(deftest fetch-all-fleet-prs-default-state-is-open-test
  (testing "Default state is :open"
    (let [path (temp-config-path)
          captured-state (atom nil)]
      (spit path (pr-str {:fleet {:repos ["acme/app"]}}))
      (with-redefs [clojure.java.shell/sh
                    (fn [& args]
                      (when-let [s (some #(when (and (string? %) (#{ "open" "merged" "closed" "all"} %)) %)
                                         args)]
                        (reset! captured-state s))
                      {:exit 0 :out "[]" :err ""})]
        (core/fetch-all-fleet-prs {:config-path path})
        (is (= "open" @captured-state))))))
