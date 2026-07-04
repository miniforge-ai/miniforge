;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.connector-excel.impl
  "Implementation functions for the Excel connector.
   Downloads a remote Excel file and extracts records using column mappings."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-excel.messages :as msg]
            [ai.miniforge.response.interface :as response]
            [babashka.http-client :as http])
  (:import [java.io File FileOutputStream]
           [java.util UUID]
           [org.apache.poi.ss.usermodel WorkbookFactory Cell DateUtil]))

;; -- Handle state --

(def ^:private handles (connector/create-handle-registry))

(defn get-handle [handle] (connector/get-handle handles handle))
(defn store-handle! [handle state] (connector/store-handle! handles handle state))
(defn remove-handle! [handle] (connector/remove-handle! handles handle))

;; -- Excel parsing --

(defn- cell-value
  "Extract a typed value from a POI Cell."
  [^Cell cell]
  (when cell
    (case (.name (.getCellType cell))
      "NUMERIC" (if (DateUtil/isCellDateFormatted cell)
                  (.getDateCellValue cell)
                  (.getNumericCellValue cell))
      "STRING"  (.getStringCellValue cell)
      "BOOLEAN" (.getBooleanCellValue cell)
      "BLANK"   nil
      "FORMULA" (try (.getNumericCellValue cell)
                     (catch Exception _ (.getStringCellValue cell)))
      nil)))

(defn- row-to-record
  "Extract a record map from a POI Row using column index→keyword mapping."
  [row columns]
  (when row
    (reduce-kv
     (fn [m col-idx field-key]
       (let [cell (.getCell row col-idx)
             v    (cell-value cell)]
         (if (some? v)
           (assoc m field-key v)
           m)))
     {}
     columns)))

(defn parse-sheet
  "Parse an Excel sheet into records using column mapping.
   columns: map of column-index (int) to keyword field name.
   data-start-row: 0-indexed row to start reading data from.
   row-filter: optional predicate fn applied to raw row map."
  [workbook sheet-name columns data-start-row row-filter]
  (if-let [sheet (.getSheet workbook sheet-name)]
    (let [last-row (.getLastRowNum sheet)]
      (loop [row-idx data-start-row
             records (transient [])]
        (if (> row-idx last-row)
          (persistent! records)
          (let [record (row-to-record (.getRow sheet row-idx) columns)]
            (recur (inc row-idx)
                   (if (and record
                            (seq record)
                            (or (nil? row-filter) (row-filter record)))
                     (conj! records record)
                     records))))))
    (response/throw-anomaly! :anomalies/not-found
                             (msg/t :excel/sheet-not-found {:sheet sheet-name})
                             {:sheet sheet-name
                              :config/error :invalid-config
                              :config/invalid-config-reason :missing-excel-sheet})))

;; -- Filtering --

(defn- min-value-filter
  "Create a filter that keeps records where :date >= min-val."
  [min-val]
  (fn [record]
    (let [v (:date record)]
      (and (number? v) (>= v min-val)))))

;; -- Record normalization --

(defn- decimal-year-to-iso
  "Convert a decimal year (e.g. 2026.03) to ISO date string (2026-03-01)."
  [d]
  (let [year  (int d)
        month (Math/round (* (- d year) 100.0))]
    (format "%d-%02d-01" year (max 1 month))))

(defn- normalize-record
  "Normalize a record: convert decimal-year dates, enrich with series-id."
  [date-fmt series-id record]
  (cond-> record
    (and (= date-fmt :decimal-year) (number? (:date record)))
    (assoc :date (decimal-year-to-iso (:date record)))
    series-id
    (assoc :series_id series-id)))

;; -- Download --

(defn- download-to-temp-result
  "Download a URL to a temporary file. Returns the File or a legacy response
   anomaly map when the HTTP response is not successful."
  [url]
  (let [resp (http/get url {:as      :bytes
                            :headers {"User-Agent" "Mozilla/5.0"}
                            :throw   false})
        status (:status resp)]
    (if-not (<= 200 status 299)
      (response/make-anomaly :anomalies/unavailable
                             (msg/t :excel/download-failed {:error (str "HTTP " status)})
                             {:status status})
      (let [tmp (File/createTempFile "connector-excel-" ".xls")]
        (.deleteOnExit tmp)
        (with-open [out (FileOutputStream. tmp)]
          (.write out ^bytes (:body resp)))
        tmp))))

(defn- download-to-temp
  "Boundary wrapper around the anomaly-returning `download-to-temp-result`.
   Preserves the connector's historical throwing download contract."
  [url]
  (let [result (download-to-temp-result url)]
    (if (response/anomaly-map? result)
      (response/throw-anomaly! (:anomaly/category result)
                               (:anomaly/message result)
                               (dissoc result
                                       :anomaly/category
                                       :anomaly/message
                                       :anomaly/id
                                       :anomaly/timestamp))
      result)))

;; -- Lifecycle --

(defn do-connect
  "Validate config, download file, register handle."
  [config _auth]
  (let [url        (:excel/url config)
        sheet-name (:excel/sheet-name config)
        columns    (:excel/columns config)]
    (cond
      (nil? url)        (response/throw-anomaly! :anomalies/incorrect
                                                 (msg/t :excel/url-required)
                                                 {:config config
                                                  :config/error :invalid-config
                                                  :config/invalid-config-reason :missing-excel-url})
      (nil? sheet-name) (response/throw-anomaly! :anomalies/incorrect
                                                 (msg/t :excel/sheet-required)
                                                 {:config config
                                                  :config/error :invalid-config
                                                  :config/invalid-config-reason :missing-excel-sheet-name})
      (nil? columns)    (response/throw-anomaly! :anomalies/incorrect
                                                 (msg/t :excel/columns-required)
                                                 {:config config
                                                  :config/error :invalid-config
                                                  :config/invalid-config-reason :missing-excel-columns})
      :else
      (let [handle (str (UUID/randomUUID))
            tmp-file (download-to-temp url)
            workbook (WorkbookFactory/create tmp-file)]
        (store-handle! handle {:config   config
                               :workbook workbook
                               :tmp-file tmp-file})
        (connector/connect-result handle)))))

(defn do-close [handle]
  (when-let [{:keys [workbook tmp-file]} (get-handle handle)]
    (try (.close workbook) (catch Exception _))
    (try (.delete ^File tmp-file) (catch Exception _)))
  (remove-handle! handle)
  (connector/close-result))

;; -- Source --

(defn- require-handle
  "Retrieve handle state or return an anomaly."
  [handle]
  (connector/require-handle handles handle
                            {:message (msg/t :excel/handle-not-found {:handle handle})}))

(defn- handle-anomaly->response [handle-anomaly]
  (response/make-anomaly :anomalies/not-found
                         (:anomaly/message handle-anomaly)
                         (:anomaly/data handle-anomaly)))

(defn do-discover [handle]
  (let [handle-state (require-handle handle)]
    (if (anomaly/anomaly? handle-state)
      (handle-anomaly->response handle-state)
      (let [{:keys [config]} handle-state]
        (connector/discover-result [{:schema/name (:excel/sheet-name config)
                                     :schema/url  (:excel/url config)}])))))

(defn do-extract
  "Parse the Excel sheet and return records."
  [handle _opts]
  (let [handle-state (require-handle handle)]
    (if (anomaly/anomaly? handle-state)
      (handle-anomaly->response handle-state)
      (let [{:keys [config workbook]} handle-state
            {:excel/keys [sheet-name columns data-start-row series-id]} config
            filter-fn (when-let [min-val (:excel/min-value config)]
                        (min-value-filter min-val))
            records   (parse-sheet workbook sheet-name columns
                                   (or data-start-row 0) filter-fn)
            date-fmt  (:excel/date-format config)
            enriched  (mapv (partial normalize-record date-fmt series-id) records)]
        (connector/extract-result enriched nil false)))))

(defn do-checkpoint [cursor-state]
  (connector/checkpoint-result cursor-state))
