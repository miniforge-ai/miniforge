(ns ai.miniforge.workflow.publish
  "Generic publication helpers for workflow outputs."
  (:require
   [ai.miniforge.logging.interface :as log]
   [babashka.fs :as fs]
   [cheshire.core :as json]))

(defn create-directory-publisher
  ([output-dir]
   (create-directory-publisher output-dir {}))
  ([output-dir opts]
   (merge {:publisher/type :directory
           :publisher/output-dir output-dir
           :publisher/default-format :edn}
          opts)))

(defn serialize-content
  [format content]
  (case format
    :json (json/generate-string content {:pretty true})
    :text (str content)
    (pr-str content)))

(defn extension-for
  [format]
  (case format
    :json "json"
    :text "txt"
    "edn"))

(defn normalize-publication
  [publisher publication]
  (let [format (or (:publication/format publication)
                   (:publisher/default-format publisher)
                   :edn)
        path (:publication/path publication)
        normalized-path (if path
                          path
                          (str "publication-" (random-uuid) "." (extension-for format)))]
    (assoc publication
           :publication/format format
           :publication/path normalized-path)))

(defn publish-to-directory!
  [publisher publication logger]
  (let [output-dir (:publisher/output-dir publisher)
        publication (normalize-publication publisher publication)
        path (:publication/path publication)
        content (:publication/content publication)
        format (:publication/format publication)
        full-path (fs/path output-dir path)]
    (fs/create-dirs (fs/parent full-path))
    (spit (str full-path) (serialize-content format content))
    (when logger
      (log/info logger :workflow :publication/written
                {:data {:path (str full-path)
                        :format format}}))
    {:success? true
     :publication/type :directory
     :publication/path (str full-path)
     :publication/format format
     :publication/output-dir (str output-dir)}))

(defn publish!
  "Publish a workflow output using the configured publisher."
  ([publisher publication]
   (publish! publisher publication nil))
  ([publisher publication logger]
   (case (:publisher/type publisher)
     :directory (publish-to-directory! publisher publication logger)
     {:success? false
      :error (str "Unknown publisher type: " (:publisher/type publisher))})))
