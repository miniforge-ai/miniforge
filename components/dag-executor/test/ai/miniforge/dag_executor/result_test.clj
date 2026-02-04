(ns ai.miniforge.dag-executor.result-test
  "Unit tests for result monad helpers."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.result :as result]))

;; ============================================================================
;; Result creation tests
;; ============================================================================

(deftest ok-test
  (testing "ok creates success result"
    (let [r (result/ok {:value 42})]
      (is (map? r))
      (is (:ok? r))
      (is (= {:value 42} (:data r))))))

(deftest err-test
  (testing "err creates error result with code and message"
    (let [r (result/err :not-found "Item not found")]
      (is (map? r))
      (is (not (:ok? r)))
      (is (= :not-found (get-in r [:error :code])))
      (is (= "Item not found" (get-in r [:error :message])))))

  (testing "err accepts optional data"
    (let [r (result/err :validation-failed "Invalid input" {:field :email})]
      (is (= {:field :email} (get-in r [:error :data]))))))

;; ============================================================================
;; Result predicate tests
;; ============================================================================

(deftest ok?-test
  (testing "ok? returns true for success results"
    (is (result/ok? (result/ok {})))
    (is (result/ok? (result/ok {:x 1}))))

  (testing "ok? returns false for error results"
    (is (not (result/ok? (result/err :error "failed"))))))

(deftest err?-test
  (testing "err? returns true for error results"
    (is (result/err? (result/err :error "failed"))))

  (testing "err? returns false for success results"
    (is (not (result/err? (result/ok {}))))))

;; ============================================================================
;; Unwrap tests
;; ============================================================================

(deftest unwrap-test
  (testing "unwrap returns data from ok result"
    (is (= {:value 42} (result/unwrap (result/ok {:value 42})))))

  (testing "unwrap throws on error result"
    (is (thrown? clojure.lang.ExceptionInfo
                 (result/unwrap (result/err :error "failed"))))))

(deftest unwrap-or-test
  (testing "unwrap-or returns data from ok result"
    (is (= {:value 42} (result/unwrap-or (result/ok {:value 42}) :default))))

  (testing "unwrap-or returns default on error result"
    (is (= :default (result/unwrap-or (result/err :error "failed") :default)))))

;; ============================================================================
;; Transform tests
;; ============================================================================

(deftest map-ok-test
  (testing "map-ok transforms data in ok result"
    (let [r (result/ok {:value 10})
          r2 (result/map-ok r #(update % :value inc))]
      (is (result/ok? r2))
      (is (= {:value 11} (:data r2)))))

  (testing "map-ok passes through error"
    (let [r (result/err :error "failed")
          r2 (result/map-ok r #(update % :value inc))]
      (is (result/err? r2)))))

(deftest map-err-test
  (testing "map-err transforms error in err result"
    (let [r (result/err :error "failed")
          r2 (result/map-err r #(assoc % :wrapped true))]
      (is (result/err? r2))
      (is (:wrapped (:error r2)))))

  (testing "map-err passes through ok result"
    (let [r (result/ok {:value 42})
          r2 (result/map-err r #(assoc % :wrapped true))]
      (is (result/ok? r2))
      (is (= {:value 42} (:data r2))))))

;; ============================================================================
;; Chaining tests
;; ============================================================================

(deftest and-then-test
  (testing "and-then applies function to ok result"
    (let [r (result/ok {:value 10})
          r2 (result/and-then r (fn [data]
                                  (result/ok {:value (* 2 (:value data))})))]
      (is (result/ok? r2))
      (is (= {:value 20} (:data r2)))))

  (testing "and-then passes through error result"
    (let [r (result/err :error "failed")
          r2 (result/and-then r (fn [_] (result/ok {:value 999})))]
      (is (result/err? r2))
      (is (= :error (get-in r2 [:error :code]))))))

(deftest or-else-test
  (testing "or-else applies function to error result"
    (let [r (result/err :not-found "not found")
          r2 (result/or-else r (fn [_] (result/ok {:default true})))]
      (is (result/ok? r2))
      (is (= {:default true} (:data r2)))))

  (testing "or-else passes through ok result"
    (let [r (result/ok {:value 42})
          r2 (result/or-else r (fn [_] (result/ok {:default true})))]
      (is (result/ok? r2))
      (is (= {:value 42} (:data r2))))))

;; ============================================================================
;; Collection helpers
;; ============================================================================

(deftest collect-test
  (testing "collect returns ok with all data when all results are ok"
    (let [results [(result/ok {:a 1}) (result/ok {:b 2}) (result/ok {:c 3})]
          collected (result/collect results)]
      (is (result/ok? collected))
      (is (= [{:a 1} {:b 2} {:c 3}] (:data collected)))))

  (testing "collect returns first error when any result is error"
    (let [results [(result/ok {:a 1})
                   (result/err :error "failed")
                   (result/ok {:c 3})]
          collected (result/collect results)]
      (is (result/err? collected))
      (is (= :error (get-in collected [:error :code]))))))

;; ============================================================================
;; Error codes
;; ============================================================================

(deftest error-codes-test
  (testing "error-codes contains standard codes"
    (is (contains? result/error-codes :invalid-state))
    (is (contains? result/error-codes :task-not-found))
    (is (contains? result/error-codes :budget-exhausted))
    (is (contains? result/error-codes :ci-failed))
    (is (contains? result/error-codes :merge-failed))))

;; ============================================================================
;; Rich comment for REPL testing
;; ============================================================================

(comment
  (result/ok {:value 42})
  (result/err :not-found "not found")

  (-> (result/ok {:value 10})
      (result/map-ok #(update % :value * 2))
      (result/and-then (fn [{:keys [value]}]
                         (if (> value 15)
                           (result/ok {:result :big})
                           (result/ok {:result :small})))))

  :leave-this-here)
