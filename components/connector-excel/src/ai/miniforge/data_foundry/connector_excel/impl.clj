(ns ai.miniforge.data-foundry.connector-excel.impl
  "Implementation functions for the Excel connector.
   Downloads a remote Excel file and extracts records using column mappings."
  (:require [ai.miniforge.data-foundry.connector.handles :as h]
            [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-excel.messages :as msg]
            [ai.miniforge.schema.interface :as schema]
            [hato.client :as hc])
  (:import [java.io File FileOutputStream]
           [java.util UUID]
           [org.apache.poi.ss.usermodel WorkbookFactory Cell CellType DateUtil]))

;; -- Handle state --

(def ^:private handles (h/create))

(defn get-handle [handle] (h/get-handle handles handle))
(defn store-handle! [handle state] (h/store-handle! handles handle state))
(defn remove-handle! [handle] (h/remove-handle! handles handle))

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
    (throw (ex-info (msg/t :excel/sheet-not-found {:sheet sheet-name})
                    {:sheet sheet-name}))))

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

(defn- download-to-temp
  "Download a URL to a temporary file. Returns the File."
  [url]
  (let [resp (hc/get url {:as               :byte-array
                          :headers           {"User-Agent" "Mozilla/5.0"}
                          :throw-exceptions  false})
        status (:status resp)]
    (when-not (<= 200 status 299)
      (throw (ex-info (msg/t :excel/download-failed {:error (str "HTTP " status)})
                      {:status status})))
    (let [tmp (File/createTempFile "connector-excel-" ".xls")]
      (.deleteOnExit tmp)
      (with-open [out (FileOutputStream. tmp)]
        (.write out ^bytes (:body resp)))
      tmp)))

;; -- Lifecycle --

(defn do-connect
  "Validate config, download file, register handle."
  [config _auth]
  (let [url        (:excel/url config)
        sheet-name (:excel/sheet-name config)
        columns    (:excel/columns config)]
    (cond
      (nil? url)        (throw (ex-info (msg/t :excel/url-required) {:config config}))
      (nil? sheet-name) (throw (ex-info (msg/t :excel/sheet-required) {:config config}))
      (nil? columns)    (throw (ex-info (msg/t :excel/columns-required) {:config config}))
      :else
      (let [handle (str (UUID/randomUUID))
            tmp-file (download-to-temp url)
            workbook (WorkbookFactory/create tmp-file)]
        (store-handle! handle {:config   config
                               :workbook workbook
                               :tmp-file tmp-file})
        (result/connect-result handle)))))

(defn do-close [handle]
  (when-let [{:keys [workbook tmp-file]} (get-handle handle)]
    (try (.close workbook) (catch Exception _))
    (try (.delete ^File tmp-file) (catch Exception _)))
  (remove-handle! handle)
  (result/close-result))

;; -- Source --

(defn do-discover [handle]
  (if-let [{:keys [config]} (get-handle handle)]
    (result/discover-result [{:schema/name    (:excel/sheet-name config)
                              :schema/url     (:excel/url config)}])
    (throw (ex-info (msg/t :excel/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-extract
  "Parse the Excel sheet and return records."
  [handle _opts]
  (if-let [{:keys [config workbook]} (get-handle handle)]
    (let [{:excel/keys [sheet-name columns data-start-row series-id]} config
          filter-fn (when-let [min-val (:excel/min-value config)]
                      (min-value-filter min-val))
          records   (parse-sheet workbook sheet-name columns
                                 (or data-start-row 0) filter-fn)
          date-fmt  (:excel/date-format config)
          enriched  (mapv (partial normalize-record date-fmt series-id) records)]
      (result/extract-result enriched nil false))
    (throw (ex-info (msg/t :excel/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-checkpoint [cursor-state]
  (result/checkpoint-result cursor-state))
