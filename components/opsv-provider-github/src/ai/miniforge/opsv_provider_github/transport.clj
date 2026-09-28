;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.transport
  "GitHub CLI process/JSON boundary; no shell interpolation or mutation retries."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-provider-github.messages :as msg]
            [babashka.process :as process]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} api-headers ["--header" "Accept: application/vnd.github+json"
                  "--header" "X-GitHub-Api-Version: 2022-11-28"])

(def ^{:stratum 0} request-timeout-ms 60000)

(defn- ^{:stratum 0} process-options [directory body]
  (cond-> {:dir directory :out :string :err :string :continue true}
    body (assoc :in (json/generate-string body))))

(defn- ^{:stratum 0} unavailable []
  (anomaly/anomaly :unavailable (msg/t :transport/unavailable) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-command! [options arguments]
  (let [child (apply process/process options arguments)
        result (deref child request-timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do (process/destroy-tree child) {:exit -1})
      result)))

(defn- ^{:stratum 1} request-arguments [hostname method path body paginate?]
  (cond-> (into ["gh" "api" path "--hostname" hostname "--method" method] api-headers)
    body (into ["--input" "-"])
    paginate? (into ["--paginate" "--slurp"])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} request!
  "Convert transport, response-decoding and command-port exceptions to data.
   Do not expose stderr: authentication/provider diagnostics may contain secrets."
  [{:keys [directory hostname run-command]} method path body paginate?]
  (try
    (let [args (request-arguments hostname method path body paginate?)
          options (process-options directory body)
          result (run-command options args)]
      (if (= 0 (:exit result))
        (json/parse-string-strict (:out result) true)
        (unavailable)))
    (catch Exception _ (unavailable))))

(comment
  (request-arguments "github.com" "GET" "repos/example/opsv" nil false))
