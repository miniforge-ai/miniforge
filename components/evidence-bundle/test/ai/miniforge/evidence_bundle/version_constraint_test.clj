;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.version-constraint-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.evidence-bundle.schema.version-constraint :as version]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} portable-semver-constraints
  (doseq [[constraint resolved] [["2.0.0" "2.0.0+build"] ["^2.0" "2.1.3"]
                                 ["^0.2.0" "0.2.9"] ["^0.0" "0.0.9"]
                                 ["~2.1.0" "2.1.9"] ["~2" "2.9.0"]
                                 [">=2.0.0 <3.0.0" "2.5.0"]
                                 ["^2.0.0-rc.2" "2.0.0-rc.10"]
                                 ["^2.0.0-rc.2" "2.0.0-rc.2.a"]
                                 ["^999999999999999999999.0.0" "999999999999999999999.1.0"]]]
    (is (version/satisfied? constraint resolved) (pr-str [constraint resolved])))
  (doseq [[constraint resolved] [["2.0.0" "3.0.0"] ["^2.0.0" "3.0.0"]
                                 ["^0.2.0" "0.3.0"] ["^0.0.1" "0.0.2"]
                                 ["~2.1.0" "2.2.0"] ["^2.0.0" "2.1.0-rc.1"]
                                 ["^2.0.0-rc.2" "2.1.0-rc.3"]
                                 ["^2.0.0-rc.10" "2.0.0-rc.2"]
                                 ["^2.0.0-rc.2.a" "2.0.0-rc.2"]
                                 [">=2.0.0 <3.0.0" "3.0.0"]
                                 ["" "2.0.0"] ["garbage" "2.0.0"] ["^2.0.0" nil]]]
    (is (not (version/satisfied? constraint resolved)) (pr-str [constraint resolved]))))
