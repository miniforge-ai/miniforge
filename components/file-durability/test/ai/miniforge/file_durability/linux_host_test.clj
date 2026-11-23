;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.linux-host-test
  (:require [ai.miniforge.file-durability.boundary.linux-host :as host]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} native-reply [output arguments input accepted-exits]
  (is (= ["/usr/bin/systemd-detect-virt"] arguments))
  (is (nil? input))
  (is (= #{1} accepted-exits))
  output)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-only-exact-no-virtualization-evidence-is-accepted
  (doseq [[output expected] [["none\n" true] [nil false] ["" false] ["kvm\n" false]
                              ["docker\n" false] ["none\nunexpected\n" false]]]
    (with-redefs [probe/read-output (partial native-reply output)]
      (is (= expected (host/non-virtual?))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.linux-host-test))
