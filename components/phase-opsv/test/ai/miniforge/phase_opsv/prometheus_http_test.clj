;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.prometheus-http-test
  (:require [ai.miniforge.phase-opsv.boundary.prometheus-http :as transport]
            [ai.miniforge.phase-opsv.boundary.prometheus-settings :as settings]
            [ai.miniforge.phase-opsv.measurement-test-support :as measurement]
            [ai.miniforge.phase-opsv.prometheus-test-support :as fixture]
            [babashka.http-client :as http]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture! [calls request]
  (swap! calls conj request)
  (fixture/completed nil))

(deftest ^{:stratum 0} range-query-options-preserve-the-grid-and-bound-ambiguity
  (let [options (settings/load-settings (fixture/settings {:prometheus/bearer-token "opaque-token"}))
        request (transport/request-options options (measurement/request))]
    (is (= "https://metrics.invalid/prometheus/api/v1/query_range" (:uri request)))
    (is (= :get (:method request)))
    (is (= "identity" (get-in request [:headers "Accept-Encoding"])))
    (is (= "opaque-token" (:oauth-token request)))
    (is (= (:prometheus/query (measurement/request)) (get-in request [:query-params :query])))
    (is (= [100 120 10 2 "15000ms"]
           (mapv (:query-params request) [:start :end :step :limit :timeout])))
    (is (true? (:raw request)))
    (is (true? (:async request)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} actual-shared-client-never-follows-redirects
  (let [calls (atom [])]
    (with-redefs [http/request (partial capture! calls)]
      (transport/start! (settings/load-settings (fixture/settings)) (measurement/request)))
    (is (= "NEVER" (str (.followRedirects (get-in (first @calls) [:client :client])))))))

(comment ::credential-safe-request-options)
