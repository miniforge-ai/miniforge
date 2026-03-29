(ns ai.miniforge.cli.backends-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.backends :as backends]))

;------------------------------------------------------------------------------ Layer 0
;; Resource-backed backend config

(deftest backend-specs-loaded-from-resource-test
  (testing "backend specs include codex from resource config"
    (is (= "Codex" (get-in backends/backend-specs [:codex :provider]))))

  (testing "current backend fallback comes from resource defaults"
    (is (= :codex (backends/get-current-backend {})))))
