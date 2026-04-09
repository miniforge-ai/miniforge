(ns ai.miniforge.data-foundry.connector-http.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-foundry.connector-http.interface :as http-conn]
            [ai.miniforge.data-foundry.connector-http.impl :as impl]
            [ai.miniforge.data-foundry.connector.interface :as conn]))

;; ---------------------------------------------------------------------------
;; Connector metadata
;; ---------------------------------------------------------------------------

(deftest connector-metadata-test
  (testing "HTTP connector metadata"
    (let [meta http-conn/connector-metadata]
      (is (= :source (:connector/type meta)))
      (is (contains? (:connector/capabilities meta) :cap/pagination))
      (is (contains? (:connector/auth-methods meta) :api-key)))))

;; ---------------------------------------------------------------------------
;; Connect / close lifecycle
;; ---------------------------------------------------------------------------

(deftest connect-close-lifecycle-test
  (testing "Connect and close lifecycle"
    (let [hc (http-conn/create-http-connector)
          result (conn/connect hc {:http/base-url "https://api.example.com"
                                    :http/endpoint "/data"} {})]
      (is (= :connected (:connector/status result)))
      (is (string? (:connection/handle result)))
      (let [close-result (conn/close hc (:connection/handle result))]
        (is (= :closed (:connector/status close-result)))))))

(deftest connect-missing-base-url-test
  (testing "Connect fails without :http/base-url"
    (let [hc (http-conn/create-http-connector)]
      (is (thrown? Exception (conn/connect hc {:http/endpoint "/data"} {}))))))

(deftest connect-missing-endpoint-test
  (testing "Connect fails without :http/endpoint"
    (let [hc (http-conn/create-http-connector)]
      (is (thrown? Exception (conn/connect hc {:http/base-url "https://api.example.com"} {}))))))

;; ---------------------------------------------------------------------------
;; Extract with stubbed HTTP (override do-request)
;; ---------------------------------------------------------------------------

(deftest extract-simple-test
  (testing "Extract with simple JSON array response"
    (with-redefs [impl/do-request
                  (fn [_url _headers _params]
                    {:success? true
                     :body [{:id 1 :name "alpha"} {:id 2 :name "beta"}]})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"} {}))
            result (conn/extract hc handle "data" {})]
        (is (= 2 (:extract/row-count result)))
        (is (= [{:id 1 :name "alpha"} {:id 2 :name "beta"}] (:records result)))
        (conn/close hc handle)))))

(deftest extract-nested-response-path-test
  (testing "Extract with nested response path"
    (with-redefs [impl/do-request
                  (fn [_url _headers _params]
                    {:success? true
                     :body {:hits {:hits [{:id 1} {:id 2} {:id 3}]
                                   :total 10}}})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/search"
                                      :http/response-path [:hits :hits]} {}))
            result (conn/extract hc handle "search" {})]
        (is (= 3 (:extract/row-count result)))
        (is (= [{:id 1} {:id 2} {:id 3}] (:records result)))
        (conn/close hc handle)))))

(deftest extract-offset-pagination-test
  (testing "Extract with offset-based pagination"
    (let [call-count (atom 0)]
      (with-redefs [impl/do-request
                    (fn [_url _headers params]
                      (swap! call-count inc)
                      (let [offset (get params "from" 0)]
                        {:success? true
                         :body {:hits {:hits (if (< offset 4)
                                              [{:id (+ offset 1)} {:id (+ offset 2)}]
                                              [])
                                       :total 4}}}))]
        (let [hc (http-conn/create-http-connector)
              handle (:connection/handle
                      (conn/connect hc {:http/base-url "https://api.example.com"
                                        :http/endpoint "/search"
                                        :http/response-path [:hits :hits]
                                        :http/pagination {:type :offset
                                                          :param "from"
                                                          :page-size-param "size"
                                                          :total-path [:hits :total]}} {}))
              ;; First page
              r1 (conn/extract hc handle "search" {:extract/batch-size 2})]
          (is (= 2 (:extract/row-count r1)))
          (is (true? (:extract/has-more r1)))
          (is (= 2 (get-in r1 [:extract/cursor :cursor/value])))

          ;; Second page
          (let [r2 (conn/extract hc handle "search"
                                 {:extract/batch-size 2
                                  :extract/cursor (:extract/cursor r1)})]
            (is (= 2 (:extract/row-count r2)))
            (is (false? (:extract/has-more r2))))
          (conn/close hc handle))))))

(deftest extract-cursor-pagination-test
  (testing "Extract with cursor-based pagination"
    (with-redefs [impl/do-request
                  (fn [_url _headers params]
                    (if (get params "cursor")
                      {:success? true
                       :body {:data [{:id 3}] :next_cursor nil}}
                      {:success? true
                       :body {:data [{:id 1} {:id 2}] :next_cursor "abc123"}}))]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"
                                      :http/response-path [:data]
                                      :http/pagination {:type :cursor
                                                        :param "cursor"
                                                        :next-cursor-path [:next_cursor]}} {}))
            ;; First page
            r1 (conn/extract hc handle "data" {:extract/batch-size 2})]
        (is (= 2 (:extract/row-count r1)))
        (is (true? (:extract/has-more r1)))
        (is (= "abc123" (get-in r1 [:extract/cursor :cursor/value])))

        ;; Second page
        (let [r2 (conn/extract hc handle "data"
                               {:extract/batch-size 2
                                :extract/cursor (:extract/cursor r1)})]
          (is (= 1 (:extract/row-count r2)))
          (is (false? (:extract/has-more r2))))
        (conn/close hc handle)))))

(deftest extract-http-error-test
  (testing "Extract throws on HTTP 500"
    (with-redefs [impl/do-request
                  (fn [_url _headers _params]
                    {:success? false :error-type :transient
                     :error "HTTP request failed"})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"} {}))]
        (is (thrown? Exception (conn/extract hc handle "data" {})))
        (conn/close hc handle)))))

(deftest extract-rate-limited-test
  (testing "Extract throws on HTTP 429"
    (with-redefs [impl/do-request
                  (fn [_url _headers _params]
                    {:success? false :error-type :rate-limited
                     :error "Rate limited by server (429)"})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"} {}))]
        (is (thrown-with-msg? Exception #"Rate limited"
                              (conn/extract hc handle "data" {})))
        (conn/close hc handle)))))

;; ---------------------------------------------------------------------------
;; Batch extract
;; ---------------------------------------------------------------------------

(deftest batch-extract-parallel-test
  (testing "Extract with :batch/param-sets fetches all param sets in parallel"
    (let [calls (atom [])]
      (with-redefs [impl/do-request
                    (fn [_url _headers params]
                      (swap! calls conj params)
                      (let [series (get params :series_id)]
                        {:success? true
                         :body {:observations [{:date "2026-01-01" :value "1.23"}
                                               {:date "2026-01-02" :value "4.56"}]}}))]
        (let [hc (http-conn/create-http-connector)
              handle (:connection/handle
                      (conn/connect hc {:http/base-url "https://api.example.com"
                                         :http/endpoint "/series/observations"
                                         :http/query-params {:api_key "test-key"
                                                             :file_type "json"}
                                         :http/response-path [:observations]
                                         :batch/param-sets [{:series_id "DGS2"}
                                                            {:series_id "DGS10"}
                                                            {:series_id "DGS30"}]} {}))
              result (conn/extract hc handle "observations" {})]
          ;; 3 param-sets × 2 records each = 6 total
          (is (= 6 (:extract/row-count result)))
          ;; All 3 series called
          (is (= 3 (count @calls)))
          ;; No pagination in batch mode
          (is (nil? (:extract/cursor result)))
          (is (false? (:extract/has-more result)))
          (conn/close hc handle))))))

(deftest batch-extract-enriches-records-test
  (testing "Batch extract merges param-set keys into each record"
    (with-redefs [impl/do-request
                  (fn [_url _headers params]
                    {:success? true
                     :body {:data [{:date "2026-01-01" :value "1.0"}]}})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                       :http/endpoint "/data"
                                       :http/response-path [:data]
                                       :batch/param-sets [{:series_id "AAA"}
                                                          {:series_id "BBB"}]} {}))
            result (conn/extract hc handle "data" {})
            records (:records result)]
        (is (= 2 (count records)))
        (is (= "AAA" (:series_id (first records))))
        (is (= "BBB" (:series_id (second records))))
        ;; Original fields preserved
        (is (= "2026-01-01" (:date (first records))))
        (conn/close hc handle)))))

(deftest batch-extract-error-propagates-test
  (testing "Batch extract propagates errors from individual fetches"
    (with-redefs [impl/do-request
                  (fn [_url _headers params]
                    (if (= "BAD" (:series_id params))
                      {:success? false :error "Not found" :error-type :permanent}
                      {:success? true :body {:data [{:value "ok"}]}}))]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                       :http/endpoint "/data"
                                       :http/response-path [:data]
                                       :batch/param-sets [{:series_id "GOOD"}
                                                          {:series_id "BAD"}]} {}))]
        (is (thrown? Exception (conn/extract hc handle "data" {})))
        (conn/close hc handle)))))

;; ---------------------------------------------------------------------------
;; Auth header construction
;; ---------------------------------------------------------------------------

(deftest auth-header-api-key-test
  (testing "API key auth sets Bearer header"
    (with-redefs [impl/do-request
                  (fn [_url headers _params]
                    (is (= "Bearer my-api-key" (get headers "Authorization")))
                    {:success? true :body []})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"}
                                 {:auth/method :api-key
                                  :auth/credential-id "my-api-key"}))]
        (conn/extract hc handle "data" {})
        (conn/close hc handle)))))

(deftest auth-header-basic-test
  (testing "Basic auth sets Basic header"
    (with-redefs [impl/do-request
                  (fn [_url headers _params]
                    (is (= "Basic dXNlcjpwYXNz" (get headers "Authorization")))
                    {:success? true :body []})]
      (let [hc (http-conn/create-http-connector)
            handle (:connection/handle
                    (conn/connect hc {:http/base-url "https://api.example.com"
                                      :http/endpoint "/data"}
                                 {:auth/method :basic
                                  :auth/credential-id "dXNlcjpwYXNz"}))]
        (conn/extract hc handle "data" {})
        (conn/close hc handle)))))

;; ---------------------------------------------------------------------------
;; Discover
;; ---------------------------------------------------------------------------

(deftest discover-test
  (testing "Discover returns endpoint info"
    (let [hc (http-conn/create-http-connector)
          handle (:connection/handle
                  (conn/connect hc {:http/base-url "https://api.example.com"
                                    :http/endpoint "/search"} {}))
          result (conn/discover hc handle {})]
      (is (= 1 (:discover/total-count result)))
      (is (= "/search" (:schema/name (first (:schemas result)))))
      (conn/close hc handle))))

;; ---------------------------------------------------------------------------
;; Checkpoint
;; ---------------------------------------------------------------------------

(deftest checkpoint-test
  (testing "Checkpoint returns committed status"
    (let [hc (http-conn/create-http-connector)
          handle (:connection/handle
                  (conn/connect hc {:http/base-url "https://api.example.com"
                                    :http/endpoint "/data"} {}))
          cursor {:cursor/type :offset :cursor/value 100}
          result (conn/checkpoint hc handle "http-conn-1" cursor)]
      (is (= :committed (:checkpoint/status result)))
      (conn/close hc handle))))
