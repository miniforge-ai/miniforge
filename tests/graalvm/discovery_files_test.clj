;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns graalvm.discovery-files-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [graalvm.discovery-files :as discovery]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} namespace-discovery-normalizes-native-path-separators
  (doseq [[root file] [["/repo/src" "/repo/src/ai/miniforge/some_file.clj"]
                       ["C:\\repo\\src" "C:\\repo\\src\\ai\\miniforge\\some_file.clj"]]]
    (is (= 'ai.miniforge.some-file
           (discovery/path->namespace (io/file root) (io/file file))))))

(comment ::portable-namespace-discovery)
