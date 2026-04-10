(ns ai.miniforge.data-foundry.connector-file.writer
  "File writing for JSON and EDN formats."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn write-json
  "Write records as JSON array to file."
  [path records mode]
  (let [existing (when (and (= mode :append) (.exists (io/file path)))
                   (with-open [rdr (io/reader path)]
                     (json/read rdr :key-fn keyword)))
        all-records (if existing
                      (into (vec existing) records)
                      (vec records))]
    (io/make-parents path)
    (with-open [wtr (io/writer path)]
      (json/write all-records wtr))
    (count records)))

(defn write-edn
  "Write records as EDN vector to file."
  [path records mode]
  (let [existing (when (and (= mode :append) (.exists (io/file path)))
                   (read-string (slurp path)))
        all-records (if existing
                      (into (vec existing) records)
                      (vec records))]
    (io/make-parents path)
    (spit path (pr-str all-records))
    (count records)))

(defn write-file
  "Write records to file in the given format. Returns count written."
  [path records format mode]
  (case format
    :json (write-json path records mode)
    :edn  (write-edn path records mode)))
