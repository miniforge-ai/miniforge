;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.transport-test
  (:require [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.opsv-provider-github.transport :as transport]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} command-port-captures-output-without-a-shell-test
  (let [result (provider/run-command! {:out :string :err :string}
                                     ["bb" "-e" "(println :provider-ok)"])]
    (is (= 0 (:exit result)))
    (is (= ":provider-ok\n" (:out result)))))

(deftest ^{:stratum 0} command-port-terminates-on-deadline-test
  (with-redefs [transport/request-timeout-ms 10]
    (let [result (provider/run-command! {:out :string :err :string}
                                       ["bb" "-e" "(Thread/sleep 60000)"])]
      (is (= -1 (:exit result))))))

(comment
  transport/request-timeout-ms)
