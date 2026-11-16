;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.macos-acl
  "Additional macOS guard for canonical paths already checked by POSIX policy."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} listing-command
  "Inspect named objects without descending or following a final symbolic link.
   Numeric IDs and escaped filenames keep fields and record boundaries stable."
  ["/bin/ls" "-l" "-n" "-d" "-e" "-b" "--"])

(def ^{:stratum 0 :private true} mode-header
  "Only directories and regular files are eligible. Capture the metadata marker."
  #"^[d-][r-][w-][xsS-][r-][w-][xsS-][r-][w-][xtT-]([+@ ]) +[0-9]+ +[0-9]+ +[0-9]+ .*$")

(def ^{:stratum 0 :private true} denial
  "An ordered ACE may include an inherited flag. Never accept an allow ACE."
  #"^ +[0-9]+: +\S+ +(?:inherited +)?deny +[a-z_]+(?:,[a-z_]+)*$")

(defn ^{:stratum 0} macos? []
  (= "Mac OS X" (System/getProperty "os.name")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} non-granting-listing? [output expected-count]
  (when (string? output)
    (loop [lines (seq (str/split-lines output)) remaining expected-count]
      (if (zero? remaining)
        (nil? lines)
        (when-let [[_ marker] (and lines (re-matches mode-header (first lines)))]
          (let [[entries tail] (split-with #(not (re-matches mode-header %)) (rest lines))]
            (when (and (or (not= "+" marker) (seq entries))
                       (every? #(re-matches denial %) entries))
              (recur (seq tail) (dec remaining)))))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check! [canonical-path]
  (when (macos?)
    (let [paths (mapv str (take-while some? (iterate fs/parent canonical-path)))
          output (probe/read-output (into listing-command paths) nil)]
      (when-not (non-granting-listing? output (count paths))
        (boundary/refuse-acl! canonical-path))))
  canonical-path)

(comment ::posix-plus-macos-acls)
