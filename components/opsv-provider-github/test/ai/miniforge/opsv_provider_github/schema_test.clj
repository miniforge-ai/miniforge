;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.schema-test
  (:require [ai.miniforge.opsv-provider-github.interface :as provider]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} runtime-contract-refuses-implicit-directory-and-host-test
  (let [runtime {:directory "." :hostname "github.com" :run-command identity}]
    (is (m/validate provider/ProviderRuntime runtime))
    (doseq [field (keys runtime)]
      (is (not (m/validate provider/ProviderRuntime (dissoc runtime field)))))
    (doseq [host ["" " " "https://github.com" "-bad"]]
      (is (not (m/validate provider/ProviderRuntime (assoc runtime :hostname host)))))))

(comment
  (m/validate provider/Payload {}))
