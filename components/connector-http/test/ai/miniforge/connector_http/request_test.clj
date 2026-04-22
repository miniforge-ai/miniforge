(ns ai.miniforge.connector-http.request-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.connector-http.etag :as etag]
            [ai.miniforge.connector-http.request :as request]
            [babashka.http-client :as http]))

;;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(use-fixtures :each (fn [f] (etag/clear-cache!) (f)))

(def ^:private error-msgs
  {:rate-limited   "Rate limited"
   :request-failed (fn [s e] (str "Failed " s ": " e))})

;;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest ok?-test
  (testing "200 is ok"  (is (true?  (request/ok? 200))))
  (testing "299 is ok"  (is (true?  (request/ok? 299))))
  (testing "404 is not" (is (false? (request/ok? 404))))
  (testing "500 is not" (is (false? (request/ok? 500))))
  (testing "304 is not" (is (false? (request/ok? 304)))))

(deftest classify-error-test
  (testing "429 is :rate-limited"  (is (= :rate-limited  (request/classify-error 429))))
  (testing "500 is :server-error"  (is (= :server-error  (request/classify-error 500))))
  (testing "503 is :server-error"  (is (= :server-error  (request/classify-error 503))))
  (testing "404 is :client-error"  (is (= :client-error  (request/classify-error 404))))
  (testing "401 is :client-error"  (is (= :client-error  (request/classify-error 401)))))

(deftest success-response-test
  (testing "parses JSON body and carries headers"
    (let [url    "https://api.example.com/repos"
          resp   {:status 200 :body "[{\"id\":1}]" :headers {"content-type" "application/json"}}
          result (request/success-response url resp)]
      (is (true? (:success? result)))
      (is (= [{:id 1}] (:body result)))
      (is (= {"content-type" "application/json"} (:headers result)))))

  (testing "caches ETag from response headers"
    (let [url  "https://api.example.com/etag-resource"
          resp {:status 200 :body "{}" :headers {"etag" "W/\"abc123\""}}]
      (request/success-response url resp)
      (is (= "W/\"abc123\"" (etag/get-etag url)))))

  (testing "no error when response has no ETag header"
    (let [url  "https://api.example.com/no-etag"
          resp {:status 200 :body "[]" :headers {}}]
      (is (some? (request/success-response url resp))))))

(deftest not-modified-response-test
  (testing "returns success with nil body and :not-modified true"
    (let [result (request/not-modified-response {:status 304 :headers {"etag" "W/\"abc\""}})]
      (is (true? (:success? result)))
      (is (nil? (:body result)))
      (is (true? (:not-modified result))))))

(deftest error-response-test
  (testing "rate-limited response has :rate-limited error-type"
    (let [result (request/error-response 429 {:body ""} error-msgs)]
      (is (false? (:success? result)))
      (is (= :rate-limited (:error-type result)))))

  (testing "server error response has :transient error-type"
    (let [result (request/error-response 500 {:body "internal error"} error-msgs)]
      (is (false? (:success? result)))
      (is (= :transient (:error-type result)))))

  (testing "client error response has :permanent error-type"
    (let [result (request/error-response 404 {:body "not found"} error-msgs)]
      (is (false? (:success? result)))
      (is (= :permanent (:error-type result)))))

  (testing "server error calls :request-failed fn with 'server error' string"
    (let [calls (atom [])
          msgs  {:rate-limited   ""
                 :request-failed (fn [s e] (swap! calls conj {:status s :error e}) "")}]
      (request/error-response 503 {:body "gateway timeout"} msgs)
      (is (= 1 (count @calls)))
      (is (= "server error" (:error (first @calls))))))

  (testing "client error calls :request-failed fn with response body"
    (let [calls (atom [])
          msgs  {:rate-limited   ""
                 :request-failed (fn [s e] (swap! calls conj {:status s :error e}) "")}]
      (request/error-response 403 {:body "forbidden"} msgs)
      (is (= "forbidden" (:error (first @calls)))))))

(deftest do-request-test
  (testing "returns success for 2xx"
    (with-redefs [http/get (fn [_ _] {:status 200 :body "[{\"id\":1}]" :headers {}})]
      (let [result (request/do-request "https://example.com" {} {} (fn [_ _] nil))]
        (is (true? (:success? result)))
        (is (= [{:id 1}] (:body result))))))

  (testing "returns not-modified for 304"
    (with-redefs [http/get (fn [_ _] {:status 304 :body "" :headers {}})]
      (let [result (request/do-request "https://example.com" {} {} (fn [_ _] nil))]
        (is (true? (:success? result)))
        (is (nil? (:body result)))
        (is (true? (:not-modified result))))))

  (testing "delegates non-2xx non-304 to error-fn"
    (with-redefs [http/get (fn [_ _] {:status 429 :body "" :headers {}})]
      (let [called (atom nil)]
        (request/do-request "https://example.com" {} {}
                            (fn [s r] (reset! called s) {:success? false :error-type :rate-limited}))
        (is (= 429 @called)))))

  (testing "adds If-None-Match when ETag is cached"
    (etag/store-etag! "https://example.com/cached" "W/\"xyz\"")
    (let [received-headers (atom nil)]
      (with-redefs [http/get (fn [_ opts] (reset! received-headers (:headers opts))
                              {:status 200 :body "[]" :headers {}})]
        (request/do-request "https://example.com/cached" {} {} (fn [_ _] nil))
        (is (= "W/\"xyz\"" (get @received-headers "If-None-Match")))))))

(deftest throw-on-failure!-test
  (testing "returns result unchanged on success"
    (let [result {:success? true :body [{:id 1}]}]
      (is (= result (request/throw-on-failure! result)))))

  (testing "throws ex-info on failure"
    (is (thrown? clojure.lang.ExceptionInfo
          (request/throw-on-failure! {:success? false :error "failed" :error-type :permanent}))))

  (testing "thrown exception carries error-type in ex-data"
    (try
      (request/throw-on-failure! {:success? false :error "transient" :error-type :transient})
      (catch clojure.lang.ExceptionInfo e
        (is (= :transient (:error-type (ex-data e))))))))

(deftest next-url-test
  (testing "returns next URL from Link header"
    (let [link "<https://api.example.com?page=2>; rel=\"next\", <https://api.example.com?page=5>; rel=\"last\""
          resp {:headers {"link" link}}]
      (is (= "https://api.example.com?page=2" (request/next-url resp)))))

  (testing "returns nil when no next rel in Link header"
    (let [resp {:headers {"link" "<https://api.example.com?page=5>; rel=\"last\""}}]
      (is (nil? (request/next-url resp)))))

  (testing "returns nil when no Link header"
    (is (nil? (request/next-url {:headers {}}))))

  (testing "returns nil when headers are absent"
    (is (nil? (request/next-url {})))))

(deftest coerce-records-test
  (testing "vector body returned as vec"
    (is (= [{:a 1} {:b 2}] (request/coerce-records [{:a 1} {:b 2}]))))

  (testing "map body wrapped in vector"
    (is (= [{:id 1}] (request/coerce-records {:id 1}))))

  (testing "list body coerced to vector"
    (is (= [{:a 1}] (request/coerce-records (list {:a 1})))))

  (testing "empty vector stays empty"
    (is (= [] (request/coerce-records [])))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector-http.request-test
  )
