;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-discovery-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.storage-layout :as layout]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-temporary-root [check]
  (let [root (io/file (System/getProperty "java.io.tmpdir") (str (random-uuid)))]
    (.mkdirs root)
    (try+ (check root)
         (finally (doseq [file (reverse (file-seq root))] (.delete ^java.io.File file))))))

(defn- ^{:stratum 0} check-layout-discovery [root]
  (let [live (layout/live-subdir)
        archived (layout/archived-subdir)
        operator (layout/operator-subdir)]
    (is (= [] (events/stored-workflow-ids (io/file root "absent"))))
    (doseq [path [[live "active"] [archived "done"] ["legacy"] [live "legacy"] [operator]]]
      (.mkdirs (apply io/file root path)))
    (.createNewFile (io/file root "not-a-workflow"))
    (is (= ["active" "done" "legacy"] (events/stored-workflow-ids root)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} discovers-canonical-and-legacy-workflows-once
  (with-temporary-root check-layout-discovery))
