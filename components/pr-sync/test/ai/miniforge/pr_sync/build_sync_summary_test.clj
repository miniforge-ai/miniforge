(ns ai.miniforge.pr-sync.build-sync-summary-test
  "Unit tests for build-sync-summary pure function.
   Exercises all boolean flag combinations and arithmetic invariants."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-sync.core :as core]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Empty input
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-empty-test
  (testing "Empty sync-statuses returns zeroed summary with all-ok? true"
    (let [s (core/build-sync-summary [])]
      (is (= 0 (:total s)))
      (is (= 0 (:ok s)))
      (is (= 0 (:errors s)))
      (is (true? (:all-ok? s)))
      (is (false? (:partial? s)))
      (is (false? (:none-ok? s))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; All OK
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-all-ok-test
  (testing "All entries :ok → all-ok? true, partial? false, none-ok? false"
    (let [statuses [{:status :ok :repo "a/b" :pr-count 2 :prs []}
                    {:status :ok :repo "c/d" :pr-count 0 :prs []}]
          s (core/build-sync-summary statuses)]
      (is (= 2 (:total s)))
      (is (= 2 (:ok s)))
      (is (= 0 (:errors s)))
      (is (true? (:all-ok? s)))
      (is (false? (:partial? s)))
      (is (false? (:none-ok? s))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; All errors
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-all-errors-test
  (testing "All entries :error → none-ok? true, all-ok? false"
    (let [statuses [{:status :error :repo "a/b" :error "fail"}
                    {:status :error :repo "c/d" :error "fail"}]
          s (core/build-sync-summary statuses)]
      (is (= 2 (:total s)))
      (is (= 0 (:ok s)))
      (is (= 2 (:errors s)))
      (is (false? (:all-ok? s)))
      (is (false? (:partial? s)))
      (is (true? (:none-ok? s))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Partial (mixed)
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-partial-test
  (testing "Mix of :ok and :error → partial? true"
    (let [statuses [{:status :ok :repo "a/b"}
                    {:status :error :repo "c/d"}
                    {:status :ok :repo "e/f"}]
          s (core/build-sync-summary statuses)]
      (is (= 3 (:total s)))
      (is (= 2 (:ok s)))
      (is (= 1 (:errors s)))
      (is (false? (:all-ok? s)))
      (is (true? (:partial? s)))
      (is (false? (:none-ok? s))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Single entry
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-single-ok-test
  (testing "Single :ok entry"
    (let [s (core/build-sync-summary [{:status :ok :repo "a/b"}])]
      (is (= 1 (:total s)))
      (is (true? (:all-ok? s)))
      (is (false? (:partial? s)))
      (is (false? (:none-ok? s))))))

(deftest build-sync-summary-single-error-test
  (testing "Single :error entry"
    (let [s (core/build-sync-summary [{:status :error :repo "a/b"}])]
      (is (= 1 (:total s)))
      (is (false? (:all-ok? s)))
      (is (false? (:partial? s)))
      (is (true? (:none-ok? s))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Arithmetic invariant: ok + errors = total
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-arithmetic-invariant-test
  (testing "ok + errors = total for various sizes"
    (doseq [n (range 0 8)]
      (let [ok-count (quot n 2)
            err-count (- n ok-count)
            statuses (concat (repeat ok-count {:status :ok :repo "x/y"})
                             (repeat err-count {:status :error :repo "z/w"}))
            s (core/build-sync-summary (vec statuses))]
        (is (= (:total s) (+ (:ok s) (:errors s)))
            (str "invariant broken for n=" n))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Boolean flag mutual exclusivity
;; ─────────────────────────────────────────────────────────────────────────────

(deftest build-sync-summary-boolean-exclusivity-test
  (testing "At most one of all-ok?, partial?, none-ok? is true (when total > 0)"
    (doseq [statuses [;; all ok
                      [{:status :ok} {:status :ok}]
                      ;; all error
                      [{:status :error} {:status :error}]
                      ;; partial
                      [{:status :ok} {:status :error}]]]
      (let [s (core/build-sync-summary statuses)
            flags (filter identity [(:all-ok? s) (:partial? s) (:none-ok? s)])]
        (is (<= (count flags) 1)
            (str "Multiple boolean flags true: " s))))))
