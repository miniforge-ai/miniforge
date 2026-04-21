(ns ai.miniforge.connector-http.etag-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.connector-http.etag :as etag]))

;;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(use-fixtures :each (fn [f] (etag/clear-cache!) (f)))

;;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest store-and-retrieve-etag-test
  (testing "stores and retrieves ETag for URL"
    (etag/store-etag! "https://api.github.com/repos/org/repo/pulls" "W/\"abc123\"")
    (is (= "W/\"abc123\"" (etag/get-etag "https://api.github.com/repos/org/repo/pulls"))))

  (testing "returns nil for unknown URL"
    (is (nil? (etag/get-etag "https://unknown.example.com"))))

  (testing "ignores nil etag"
    (etag/store-etag! "https://example.com" nil)
    (is (nil? (etag/get-etag "https://example.com")))))

(deftest add-etag-header-test
  (testing "adds If-None-Match when ETag cached"
    (etag/store-etag! "https://example.com" "W/\"xyz\"")
    (let [headers (etag/add-etag-header {"Accept" "application/json"} "https://example.com")]
      (is (= "W/\"xyz\"" (get headers "If-None-Match")))
      (is (= "application/json" (get headers "Accept")))))

  (testing "returns headers unchanged when no cached ETag"
    (let [headers (etag/add-etag-header {"Accept" "text/html"} "https://uncached.com")]
      (is (nil? (get headers "If-None-Match")))
      (is (= "text/html" (get headers "Accept"))))))

(deftest extract-etag-test
  (testing "extracts etag from response headers"
    (is (= "W/\"abc\"" (etag/extract-etag {"etag" "W/\"abc\""}))))

  (testing "returns nil when no etag header"
    (is (nil? (etag/extract-etag {"content-type" "application/json"})))))

(deftest not-modified-test
  (testing "304 is not-modified"
    (is (true? (etag/not-modified? 304))))

  (testing "200 is not not-modified"
    (is (false? (etag/not-modified? 200)))))

(deftest clear-cache-test
  (testing "clear-cache! removes all entries"
    (etag/store-etag! "https://a.com" "e1")
    (etag/store-etag! "https://b.com" "e2")
    (etag/clear-cache!)
    (is (nil? (etag/get-etag "https://a.com")))
    (is (nil? (etag/get-etag "https://b.com")))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector-http.etag-test
  )
