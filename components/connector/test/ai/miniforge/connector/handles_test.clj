(ns ai.miniforge.connector.handles-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector.handles :as h]))

;;------------------------------------------------------------------------------ Layer 0
;; Tests

(deftest create-test
  (testing "creates an empty store atom"
    (let [store (h/create)]
      (is (instance? clojure.lang.Atom store))
      (is (= {} @store)))))

(deftest get-handle-test
  (testing "returns nil for unknown handle"
    (let [store (h/create)]
      (is (nil? (h/get-handle store "missing")))))

  (testing "returns stored state"
    (let [store (h/create)]
      (h/store-handle! store "h1" {:config {:url "https://example.com"}})
      (is (= {:config {:url "https://example.com"}} (h/get-handle store "h1")))))

  (testing "stores are independent"
    (let [s1 (h/create)
          s2 (h/create)]
      (h/store-handle! s1 "h1" {:data 1})
      (is (nil? (h/get-handle s2 "h1"))))))

(deftest store-handle!-test
  (testing "stores and overwrites handle state"
    (let [store (h/create)]
      (h/store-handle! store "h1" {:v 1})
      (h/store-handle! store "h1" {:v 2})
      (is (= {:v 2} (h/get-handle store "h1")))))

  (testing "stores multiple handles independently"
    (let [store (h/create)]
      (h/store-handle! store "h1" {:a 1})
      (h/store-handle! store "h2" {:b 2})
      (is (= {:a 1} (h/get-handle store "h1")))
      (is (= {:b 2} (h/get-handle store "h2"))))))

(deftest remove-handle!-test
  (testing "removes an existing handle"
    (let [store (h/create)]
      (h/store-handle! store "h1" {:data 1})
      (h/remove-handle! store "h1")
      (is (nil? (h/get-handle store "h1")))))

  (testing "no-op for unknown handle"
    (let [store (h/create)]
      (h/remove-handle! store "nonexistent")
      (is (= {} @store)))))

(deftest touch-handle!-test
  (testing "sets :last-request-at to a recent epoch-ms"
    (let [store  (h/create)
          before (System/currentTimeMillis)]
      (h/store-handle! store "h1" {:config {}})
      (h/touch-handle! store "h1")
      (let [ts (get-in @store ["h1" :last-request-at])]
        (is (some? ts))
        (is (>= ts before))
        (is (<= ts (System/currentTimeMillis))))))

  (testing "does not disturb other handle state"
    (let [store (h/create)]
      (h/store-handle! store "h1" {:config {:url "x"}})
      (h/touch-handle! store "h1")
      (is (= {:url "x"} (:config (h/get-handle store "h1")))))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector.handles-test
  )
