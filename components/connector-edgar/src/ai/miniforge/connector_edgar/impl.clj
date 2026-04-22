(ns ai.miniforge.connector-edgar.impl
  "Implementation functions for the EDGAR connector.
   Queries SEC EDGAR EFTS for filings, fetches filing documents,
   and extracts structured data from XML."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-edgar.messages :as msg]
            [ai.miniforge.connector-http.interface :as http]
            [ai.miniforge.schema.interface :as schema]
            [babashka.http-client :as bb-http]
            [cheshire.core :as cheshire]
            [clojure.data.xml :as xml])
  (:import [java.io ByteArrayInputStream]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.time.temporal TemporalAdjusters]
           [java.util UUID]))

;; -- Handle state --

(def ^:private handles (connector/create-handle-registry))

(defn get-handle [handle] (connector/get-handle handles handle))
(defn store-handle! [handle state] (connector/store-handle! handles handle state))
(defn remove-handle! [handle] (connector/remove-handle! handles handle))

;; -- Rate limiting --

(def ^:private last-request-at (atom 0))

(defn- rate-limit!
  "Sleep to enforce requests-per-second."
  [rps]
  (reset! last-request-at (http/time-based-acquire! rps @last-request-at)))

;; -- HTTP helpers --

(defn- fetch-url
  "Fetch a URL with the SEC-required User-Agent header."
  [url user-agent]
  (rate-limit! 8)
  (let [resp (bb-http/get url {:headers {"User-Agent" user-agent}
                               :as      :bytes
                               :throw   false
                               :timeout 30000})
        status (:status resp)]
    (when (<= 200 status 299)
      (:body resp))))

(defn- fetch-json
  "Fetch a URL and parse response as JSON."
  [url user-agent]
  (when-let [body (fetch-url url user-agent)]
    (cheshire/parse-string (String. ^bytes body "UTF-8") keyword)))

;; -- XML parsing --

(defn- parse-xml
  "Parse a byte-array as XML, return the root element. `clojure.data.xml`
   elements are records shaped `{:tag :attrs :content}` with content
   being a seq of child elements and text strings."
  [^bytes xml-bytes]
  (xml/parse (ByteArrayInputStream. xml-bytes)))

(defn- elements-by-tag
  "Return every descendant element whose `:tag` matches `tag-kw`.
   Depth-first, excluding `el` itself — matches the javax.xml
   `getElementsByTagName` it replaces. `(rest (tree-seq …))` drops the
   root; `tree-seq` is otherwise self-inclusive."
  [el tag-kw]
  (filter (fn [n] (and (map? n) (= tag-kw (:tag n))))
          (rest (tree-seq :content :content el))))

(defn- text-content
  "Concatenated text content of the first descendant element with
   `tag-kw`, or nil when empty. Matches the javax.xml
   `getTextContent` behaviour the caller expects."
  [el tag-kw]
  (when-let [match (first (elements-by-tag el tag-kw))]
    (let [text (->> (tree-seq :content :content match)
                    (filter string?)
                    (apply str))]
      (when-not (empty? text) text))))

;; -- EDGAR EFTS API --

(def ^:private efts-base "https://efts.sec.gov/LATEST/search-index")
(def ^:private archives-base "https://www.sec.gov/Archives/edgar/data")

(defn- search-filings
  "Search EDGAR EFTS for filings of a given form type in a date range."
  [form-type start-date end-date sample-size user-agent]
  (let [url (str efts-base
                 "?q=%22" form-type "%22"
                 "&forms=" form-type
                 "&dateRange=custom"
                 "&startdt=" start-date
                 "&enddt=" end-date
                 "&_source=adsh,ciks,file_date"
                 "&from=0&size=" sample-size)]
    (when-let [data (fetch-json url user-agent)]
      (get-in data [:hits :hits]))))

(defn- fetch-filing-xml
  "Fetch a filing's XML document. Tries common filenames."
  [adsh cik user-agent filenames]
  (let [adsh-path (clojure.string/replace adsh "-" "")
        cik-num   (str (parse-long cik))]
    (some (fn [fname]
            (fetch-url (str archives-base "/" cik-num "/" adsh-path "/" fname)
                       user-agent))
          filenames)))

;; -- Form 4 transaction extraction --

(defn- extract-form4-transactions
  "Extract open-market P/S transactions from Form 4 XML bytes."
  [^bytes xml-bytes transaction-codes]
  (try
    (let [root (parse-xml xml-bytes)]
      (for [txn (elements-by-tag root :nonDerivativeTransaction)
            :let [code   (text-content txn :transactionCode)
                  shares (text-content txn :transactionShares)
                  price  (text-content txn :transactionPricePerShare)]
            :when (and code (contains? transaction-codes code))]
        {:code   code
         :shares (when shares (parse-double shares))
         :price  (when price (parse-double price))}))
    (catch Exception _ [])))

;; -- Aggregation --

(defn- monthly-windows
  "Generate [start end] date string pairs for each month from start to today."
  [^String start-date-str]
  (let [start  (LocalDate/parse start-date-str)
        today  (LocalDate/now)
        fmt    DateTimeFormatter/ISO_LOCAL_DATE]
    (loop [d (.withDayOfMonth start 1)
           windows []]
      (if (.isAfter d (.withDayOfMonth today 1))
        windows
        (let [month-end (.with d (TemporalAdjusters/lastDayOfMonth))
              end       (if (.isAfter month-end today) today month-end)]
          (recur (.plusMonths d 1)
                 (conj windows [(.format d fmt) (.format end fmt)])))))))

(defn- fetch-filing-transactions
  "Fetch and extract transactions from a single EFTS hit."
  [hit user-agent filenames transaction-codes]
  (let [src  (:_source hit)
        adsh (:adsh src)
        cik  (first (:ciks src))]
    (when (and adsh cik)
      (when-let [xml (fetch-filing-xml adsh cik user-agent filenames)]
        (extract-form4-transactions xml transaction-codes)))))

(defn- count-by-code
  "Count transactions matching a given code."
  [txns code]
  (count (filter #(= code (:code %)) txns)))

(defn- aggregate-buy-sell-ratio
  "Aggregate Form 4 transactions into monthly buy:sell ratio records."
  [config user-agent]
  (let [{:edgar/keys [start-date sample-size transaction-codes
                      series-id filing-filenames]} config
        codes   (or transaction-codes #{"P" "S"})
        fnames  (or filing-filenames ["form4.xml" "ownership.xml" "primary_doc.xml"])
        windows (monthly-windows (or start-date "2025-01-01"))]
    (vec
     (for [[start end] windows
           :let [hits    (or (search-filings "4" start end
                                             (or sample-size 200)
                                             user-agent)
                             [])
                 sample  (take (or sample-size 200) hits)
                 txns    (mapcat #(fetch-filing-transactions % user-agent fnames codes) sample)
                 buys    (count-by-code txns "P")
                 sells   (count-by-code txns "S")
                 ratio   (if (pos? sells)
                           (double (/ buys sells))
                           0.0)]]
       {:date      start
        :series_id (or series-id "INSIDER_BUY_SELL_RATIO")
        :value     (format "%.4f" ratio)}))))

;; -- Lifecycle --

(defn do-connect
  "Validate config, register handle."
  [config _auth]
  (let [form-type  (:edgar/form-type config)
        user-agent (:edgar/user-agent config)
        aggregation (:edgar/aggregation config)]
    (cond
      (nil? form-type)   (throw (ex-info (msg/t :edgar/form-type-required) {:config config}))
      (nil? user-agent)  (throw (ex-info (msg/t :edgar/user-agent-required) {:config config}))
      (nil? aggregation) (throw (ex-info (msg/t :edgar/aggregation-required) {:config config}))
      :else
      (let [handle (str (UUID/randomUUID))]
        (store-handle! handle {:config config})
        (connector/connect-result handle)))))

(defn do-close [handle]
  (remove-handle! handle)
  (connector/close-result))

;; -- Source --

(defn do-discover [handle]
  (if-let [{:keys [config]} (get-handle handle)]
    (connector/discover-result [{:schema/name      (:edgar/form-type config)
                              :schema/aggregation (:edgar/aggregation config)}])
    (throw (ex-info (msg/t :edgar/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-extract
  "Extract aggregated records from EDGAR filings."
  [handle _opts]
  (if-let [{:keys [config]} (get-handle handle)]
    (let [user-agent (:edgar/user-agent config)
          records    (case (:edgar/aggregation config)
                       :monthly-buy-sell-ratio
                       (aggregate-buy-sell-ratio config user-agent)

                       (throw (ex-info (msg/t :edgar/aggregation-unknown {:agg (:edgar/aggregation config)})
                                       {:aggregation (:edgar/aggregation config)})))]
      (connector/extract-result records nil false))
    (throw (ex-info (msg/t :edgar/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-checkpoint [cursor-state]
  (connector/checkpoint-result cursor-state))
