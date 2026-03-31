(ns ai.miniforge.dag-primitives.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-primitives.interface :as dp]))

;;------------------------------------------------------------------------------ Topological sort

(deftest topological-sort-linear-chain
  (testing "A → B → C returns [A B C]"
    (let [result (dp/topological-sort {:a #{} :b #{:a} :c #{:b}})]
      (is (dp/ok? result))
      (is (= [:a :b :c] (:data result))))))

(deftest topological-sort-parallel-roots
  (testing "Two independent roots both precede their shared dependent"
    (let [result (dp/topological-sort {:a #{} :b #{} :c #{:a :b}})]
      (is (dp/ok? result))
      (let [order (:data result)]
        (is (= 3 (count order)))
        (is (< (.indexOf order :a) (.indexOf order :c)))
        (is (< (.indexOf order :b) (.indexOf order :c)))))))

(deftest topological-sort-diamond
  (testing "Diamond: A → B, A → C, B → D, C → D"
    (let [result (dp/topological-sort {:a #{} :b #{:a} :c #{:a} :d #{:b :c}})]
      (is (dp/ok? result))
      (let [order (:data result)]
        (is (= 4 (count order)))
        (is (= :a (first order)))
        (is (= :d (last order)))))))

(deftest topological-sort-single-node
  (testing "Single node with no deps"
    (let [result (dp/topological-sort {:a #{}})]
      (is (dp/ok? result))
      (is (= [:a] (:data result))))))

(deftest topological-sort-empty
  (testing "Empty graph"
    (let [result (dp/topological-sort {})]
      (is (dp/ok? result))
      (is (= [] (:data result))))))

(deftest topological-sort-cycle-detected
  (testing "Cycle returns err with :cycle-detected"
    (let [result (dp/topological-sort {:a #{:c} :b #{:a} :c #{:b}})]
      (is (dp/err? result))
      (is (= :cycle-detected (get-in result [:error :code])))
      (is (= #{:a :b :c} (get-in result [:error :data :cycle-nodes]))))))

(deftest topological-sort-partial-cycle
  (testing "Cycle in part of graph; acyclic nodes still processed"
    (let [result (dp/topological-sort {:root #{} :a #{:b} :b #{:a}})]
      (is (dp/err? result))
      (is (= :cycle-detected (get-in result [:error :code])))
      (is (= #{:a :b} (get-in result [:error :data :cycle-nodes]))))))

;;------------------------------------------------------------------------------ Result monad

(deftest ok-construction
  (is (dp/ok? (dp/ok {:x 1})))
  (is (= {:x 1} (:data (dp/ok {:x 1})))))

(deftest err-construction
  (is (dp/err? (dp/err :bad "oops")))
  (is (= :bad (get-in (dp/err :bad "oops") [:error :code])))
  (is (= {:detail "x"} (get-in (dp/err :bad "oops" {:detail "x"}) [:error :data]))))

(deftest unwrap-ok
  (is (= 42 (dp/unwrap (dp/ok 42)))))

(deftest unwrap-err-throws
  (is (thrown? Exception (dp/unwrap (dp/err :e "e")))))

(deftest unwrap-or-default
  (is (= :fallback (dp/unwrap-or (dp/err :e "e") :fallback))))

(deftest map-ok-transforms-data
  (let [result (dp/map-ok (dp/ok 5) inc)]
    (is (dp/ok? result))
    (is (= 6 (:data result)))))

(deftest map-ok-passes-through-err
  (let [e (dp/err :e "e")]
    (is (= e (dp/map-ok e inc)))))

(deftest and-then-chains
  (let [result (dp/and-then (dp/ok 5) #(dp/ok (* % 2)))]
    (is (dp/ok? result))
    (is (= 10 (:data result)))))

(deftest and-then-short-circuits-on-err
  (let [e      (dp/err :e "e")
        result (dp/and-then e #(dp/ok (inc %)))]
    (is (= e result))))

(deftest collect-all-ok
  (let [result (dp/collect [(dp/ok 1) (dp/ok 2) (dp/ok 3)])]
    (is (dp/ok? result))
    (is (= [1 2 3] (:data result)))))

(deftest collect-first-err-short-circuits
  (let [e      (dp/err :bad "bad")
        result (dp/collect [(dp/ok 1) e (dp/ok 3)])]
    (is (= e result))))
