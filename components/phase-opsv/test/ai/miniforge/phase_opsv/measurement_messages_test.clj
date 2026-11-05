;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-messages-test
  (:require [ai.miniforge.messages.interface :as messages]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} measurement-errors-have-packaged-catalog-copy
  (let [catalog (messages/load-catalog "config/phase-opsv/messages/system.edn"
                                        :phase-opsv/system)
        entries (messages/all catalog)]
    (doseq [key [:measurement/invalid-request :measurement/invalid-response
                :prometheus/invalid-settings :prometheus/unavailable
                :prometheus/timeout :prometheus/http-status]]
      (is (string? (get entries key)))
      (is (not= (str key) (messages/t catalog key))))))

(comment ::measurement-catalog-composition)
