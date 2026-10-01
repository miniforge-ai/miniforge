;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.edn-codec-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.evidence-bundle.edn-codec :as codec]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files OpenOption]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} canonical-round-trip-preserves-instant-and-hash-test
  (let [value {:at (java.time.Instant/parse "2026-09-30T00:00:00.123456789Z")}
        encoded (evidence/encode-bundle-edn value)
        decoded (evidence/decode-bundle-edn encoded)]
    (is (= value decoded))
    (is (= (evidence/content-hash value) (evidence/content-hash decoded)))
    (is (= value (evidence/decode-bundle-edn (str encoded " ; trailing comment\n"))))
    (doseq [suffix [" {}" " nil" " :eof" " trailing garbage" " #unknown 1" " ]"]]
      (is (nil? (evidence/decode-bundle-edn (str encoded suffix)))))))

(deftest ^{:stratum 0} malformed-and-oversized-text-is-rejected-test
  (doseq [text [nil "" " ; empty" "{" "#=(+ 1 2)" "#inst \"invalid\""]]
    (is (nil? (evidence/decode-bundle-edn text))))
  (with-redefs [codec/maximum-bytes 2]
    (is (= {} (evidence/decode-bundle-edn "{}")))
    (is (nil? (evidence/decode-bundle-edn "{} ")))))

(deftest ^{:stratum 0} file-read-limits-bytes-and-rejects-malformed-utf8-test
  (let [file (java.io.File/createTempFile "evidence-codec-" ".edn")]
    (try
      (with-redefs [codec/maximum-bytes 4]
        (doseq [[encoded expected] [["{}  " {}] ["{}   " nil] ["\"é\"" "é"] ["\"éé\"" nil]]]
          (spit file encoded :encoding "UTF-8")
          (is (= expected (evidence/read-bundle-edn file))))
        (Files/write (.toPath file) (byte-array [34 -1 34]) (make-array OpenOption 0))
        (is (nil? (evidence/read-bundle-edn file))))
      (finally (Files/deleteIfExists (.toPath file))))
    (is (nil? (evidence/read-bundle-edn file)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.edn-codec-test))
