;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.peer-configuration-test
  (:require [ai.miniforge.file-durability.peer-spec :as spec]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} absolute-file [name] (.getAbsoluteFile (File. ^String name)))

(defn- ^{:stratum 0} classpath [files]
  (str/join File/pathSeparator (map str files)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} peer-config []
  (let [executable (absolute-file "peer runtime")
        paths (classpath [(absolute-file "runtime classes") (absolute-file "peer libraries")])]
    {:peer/executable executable
     :peer/classpath paths}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-absolute-producer-values-accept-spaces-and-multiple-classpath-entries
  (let [config (peer-config)]
    (is (m/validate spec/Peer config))
    (is (m/validate spec/Peer (assoc config :peer/classpath (str (:peer/executable config)))))))

(deftest ^{:stratum 2} test-executable-is-an-absolute-file-not-a-string-or-relative-path
  (let [config (peer-config)]
    (doseq [invalid [nil "bb" (File. "bb") (.toPath ^File (:peer/executable config))]]
      (is (false? (m/validate spec/Peer (assoc config :peer/executable invalid)))))))

(deftest ^{:stratum 2} test-every-classpath-entry-is-absolute-nonempty-and-a-string
  (let [config (peer-config)
        valid (:peer/classpath config)]
    (doseq [invalid [nil "" "." (File. "classes")
                    (str valid File/pathSeparator)
                    (str valid File/pathSeparator File/pathSeparator valid)
                    (str "." File/pathSeparator valid)
                    (str valid File/pathSeparator ".")]]
      (is (false? (m/validate spec/Peer (assoc config :peer/classpath invalid)))))))

(deftest ^{:stratum 2} test-missing-fields-or-unrecognized-launch-options-are-not-peer-configuration
  (let [config (peer-config)]
    (doseq [invalid [(dissoc config :peer/executable)
                    (dissoc config :peer/classpath)
                    (assoc config :peer/arguments ["--eval"])]]
      (is (false? (m/validate spec/Peer invalid))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.peer-configuration-test))
