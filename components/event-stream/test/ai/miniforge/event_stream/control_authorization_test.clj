;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-authorization-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.event-stream.control-authorization :as authorization]
            [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} authorization-rejects-unknown-inputs-without-throwing
  (doseq [[role target action permitted?] [[:operator :workflow :pause true]
                                         [:unknown :workflow :pause false]
                                         [:operator :unknown :pause false]
                                         [:operator :workflow nil false]]]
    (let [control (events/create-control-action action {:target-type target} {:role role})
          result (authorization/authorize-action events/default-roles control {:role role})]
      (is (= permitted? (:authorized? result)))
      (is (string? (:reason result))))))
