(ns gsheetplus.core
  "Low-level Google Sheets API operations: reading ranges, batch updates,
  sheet management, and URL utilities."
  (:require [clojure.tools.logging :as log]
            [gsheetplus.cell :as cell]
            [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import (clojure.lang ExceptionInfo)
           (com.google.api.client.googleapis.json GoogleJsonResponseException)
           (com.google.api.services.sheets.v4 Sheets)
           (com.google.api.services.sheets.v4.model
             AddSheetRequest AutoFillRequest BatchUpdateSpreadsheetRequest
             CopySheetToAnotherSpreadsheetRequest DeleteDimensionRequest DeleteSheetRequest
             DimensionRange GridRange InsertDimensionRequest CopyPasteRequest Request
             SheetProperties SourceAndDestination UpdateCellsRequest UpdateSheetPropertiesRequest)))

;;; ── Error helpers ─────────────────────────────────────────────────────────

(defn rate-limit-error?
  "True if `e` is a Google API 429 (rate limit) response."
  [e]
  (and (instance? GoogleJsonResponseException e)
       (= 429 (.getStatusCode e))))

(defn try-catch-gsr-exception
  "Call `f`. If a GoogleJsonResponseException matching `err-pattern` is thrown,
  return true. If f succeeds, return false. Other exceptions re-thrown."
  [f err-pattern]
  (try (f) false
       (catch GoogleJsonResponseException e
         (if (re-find err-pattern (.getMessage e))
           true
           (throw e)))))

;;; ── Retry stub ────────────────────────────────────────────────────────────

(defn- calc-delay
  "Returns a delay in ms based on the delay spec."
  [delay-spec attempt]
  (cond
    (nil? delay-spec) 0
    ;; [:random-range :min 200 :max 500]
    (= :random-range (first delay-spec))
    (let [{:keys [min max]} (apply hash-map (rest delay-spec))]
      (+ min (rand-int (- max min))))
    ;; [:random-exp-backoff :base 500 :+/- 0.25 :max 10000]
    (= :random-exp-backoff (first delay-spec))
    (let [{:keys [base max] jitter :+/-} (apply hash-map (rest delay-spec))
          base-delay (* base (Math/pow 2 attempt))
          jittered (* base-delay (+ 1 (* jitter (- (* 2 (rand)) 1))))]
      (long (min jittered (or max Long/MAX_VALUE))))))

(defn do-with-retries
  "Options:
    :max-retries      - number of retries (default 3)
    :retry-delay      - nil | [:random-range :min M :max N]
                        | [:random-exp-backoff :base B :+/- J :max M]
    :retryable-error? - (fn [ex]) → bool; default: any exception
    :log-level        - :info/:warn/:debug/:error (default: nil = silent)
    :message          - string prefix for retry log message
    :context          - map merged into ex-data on final ExceptionInfo failure,
                        or logged alongside plain exception message"
  [{:keys [max-retries retry-delay retryable-error? log-level message context]
    :or   {max-retries      3
           retryable-error? (constantly true)}}
   f]
  (loop [attempt 0]
    (let [result (try {:ok (f)} (catch Exception e {:err e}))]
      (if-let [e (:err result)]
        (if (and (retryable-error? e) (< attempt max-retries))
          (do
            (when log-level
              (log/logf log-level e "%s (attempt %d/%d)"
                        (or message "Retrying after error") (inc attempt) max-retries))
            (let [d (calc-delay retry-delay attempt)]
              (when (pos? d) (Thread/sleep ^Long d)))
            (recur (inc attempt)))
          ;; Final failure
          (if (instance? ExceptionInfo e)
            (throw (doto (ex-info (.getMessage e) (merge context (ex-data e)) (.getCause e))
                     (.setStackTrace (.getStackTrace e))))
            (do
              (when (and context log-level)
                (log/logf log-level e "%s %s" (or message "Failed") context))
              (throw e))))
        (:ok result)))))

(defn with-retries
  "TODO: Replace with a retry implementation that does not require `safely`.
  Currently, executes `f` exactly once with no retries."
  [f _err-message]
  (do-with-retries {:message _err-message} f))

;;; ── URL utilities ─────────────────────────────────────────────────────────

(defn spreadsheet-link
  "Format a Google Sheets URL for a specific sheet tab."
  [spreadsheet-id sheet-id]
  (format "https://docs.google.com/spreadsheets/d/%s/edit#gid=%s"
          spreadsheet-id sheet-id))

(defn parse-spreadsheet-link
  "Parse a Google Sheets URL into {:spreadsheet-id \"...\" :sheet-id 123}.
  Returns nil for nil input."
  [url]
  (when url
    (let [[_ spreadsheet-id] (re-find #"^https://docs.google.com/spreadsheets/d/([^/]+)" url)
          [_ sheet-id] (re-find #"(?:\?|#)gid=(\d+)" url)]
      {:spreadsheet-id spreadsheet-id
       :sheet-id       (when sheet-id (Long/parseLong sheet-id))})))

;;; ── Low-level reads ───────────────────────────────────────────────────────

(defn- get-cells*
  "Fetch `sheet-range` from `spreadsheet-id`. Returns a vector of rows (each
  row is a vector of raw cell maps). Works for A1-notation and Named Ranges."
  [^Sheets service spreadsheet-id sheet-range]
  (let [fields "sheets(properties(title),data(rowData(values(effectiveValue,userEnteredFormat))))"
        data (-> service
                 .spreadsheets
                 (.get spreadsheet-id)
                 (.setRanges [sheet-range])
                 (.setFields fields)
                 .execute)
        table (first (get data "sheets"))]
    (mapv #(get % "values")
          (-> (get table "data") first (get "rowData")))))

(defn get-cells
  "Fetch `sheet-range` from `spreadsheet-id`, returning rows of raw cell maps.
  Wrapped in an OTEL span."
  [^Sheets service spreadsheet-id sheet-range]
  (span/with-span! ["gsheetplus/get-cells" {:spreadsheet.id spreadsheet-id
                                            :sheet.range    sheet-range}]
    (with-retries
      #(get-cells* service spreadsheet-id sheet-range)
      "get-cells rate limit")))

(defn gsheet-get
  "Call spreadsheets.values.get with UNFORMATTED_VALUE render option."
  [^Sheets service spreadsheet-id range]
  (.execute (doto (-> service .spreadsheets .values (.get spreadsheet-id range))
              (.setValueRenderOption "UNFORMATTED_VALUE"))))

(defn gsheet-get-values
  "Return the \"values\" vec-of-vecs from a gsheet-get response."
  [^Sheets service spreadsheet-id range]
  (get (gsheet-get service spreadsheet-id range) "values"))

;;; ── Batch update (exec!) ──────────────────────────────────────────────────

(defn exec!
  "Execute `requests` (sequence of Request) as a single batchUpdate.
  Retries up to 2 times on 429. Wrapped in an OTEL span."
  ([service spreadsheet-id requests]
   (exec! service spreadsheet-id requests 0))
  ([^Sheets service spreadsheet-id requests retry]
   (span/with-span! ["gsheetplus/exec!" {:spreadsheet.id spreadsheet-id
                                         :request.count  (count requests)}]
     (try
       (-> service
           .spreadsheets
           (.batchUpdate spreadsheet-id
                         (doto (BatchUpdateSpreadsheetRequest.)
                           (.setRequests requests)))
           .execute)
       (catch GoogleJsonResponseException e
         (if (and (rate-limit-error? e) (<= retry 2))
           (do
             (log/warn e "Caught 429 (RATE LIMIT), retrying...")
             (Thread/sleep (* 1000 65))
             (exec! service spreadsheet-id requests (inc retry)))
           (throw e)))))))

(defn append!
  "Append `data-rows` to the sheet identified by `sheet-id`. No-op for empty rows.
  Wrapped in an OTEL span."
  [^Sheets service spreadsheet-id sheet-id data-rows]
  (when (seq data-rows)
    (span/with-span! ["gsheetplus/append!" {:spreadsheet.id spreadsheet-id
                                            :sheet.id       sheet-id}]
      (-> service
          .spreadsheets
          .values
          (.append spreadsheet-id (str sheet-id) nil)
          .execute))))

;;; ── Request builders ──────────────────────────────────────────────────────

(defn update-grid-request
  "UpdateCellsRequest for a 2D region starting at [row-idx col-idx].
  `data` is a vec of rows, each row a vec of CellDataValue-compatible values.
  Indexes are 0-based."
  [sheet-id row-idx col-idx data]
  (-> (Request.)
      (.setUpdateCells
        (-> (UpdateCellsRequest.)
            (.setRange (-> (GridRange.)
                           (.setSheetId (int sheet-id))
                           (.setStartRowIndex (int row-idx))
                           (.setStartColumnIndex (int col-idx))
                           (.setEndRowIndex (int (+ row-idx (count data))))
                           (.setEndColumnIndex (int (+ col-idx (count (first data)))))))
            (.setRows (mapv cell/row->row-data data))
            (.setFields "userEnteredValue")))))

(defn update-cells-request
  "UpdateCellsRequest for a single row of data at [row-idx col-idx]. 0-based."
  [sheet-id row-idx col-idx data]
  (update-grid-request sheet-id row-idx col-idx [data]))

(defn delete-rows-request
  "DeleteDimensionRequest for ROWS [start-row, end-row). 0-based."
  [sheet-id start-row end-row]
  (-> (Request.)
      (.setDeleteDimension
        (-> (DeleteDimensionRequest.)
            (.setRange (-> (DimensionRange.)
                           (.setSheetId sheet-id)
                           (.setDimension "ROWS")
                           (.setStartIndex (int start-row))
                           (.setEndIndex (int end-row))))))))

(defn delete-cols-request
  "DeleteDimensionRequest for COLUMNS [start-col, end-col). 0-based."
  [sheet-id start-col end-col]
  (-> (Request.)
      (.setDeleteDimension
        (-> (DeleteDimensionRequest.)
            (.setRange (-> (DimensionRange.)
                           (.setSheetId (int sheet-id))
                           (.setDimension "COLUMNS")
                           (.setStartIndex (int start-col))
                           (.setEndIndex (int end-col))))))))

(defn insert-dimension-request
  "InsertDimensionRequest for `dim` (\"ROWS\" or \"COLUMNS\") starting at `start`.
  `inherit-from-before` controls formatting inheritance. 0-based."
  [sheet-id dim inherit-from-before start num]
  (doto (Request.)
    (.setInsertDimension
      (doto (InsertDimensionRequest.)
        (.setInheritFromBefore (boolean inherit-from-before))
        (.setRange (doto (DimensionRange.)
                     (.setSheetId (int sheet-id))
                     (.setDimension dim)
                     (.setStartIndex (int start))
                     (.setEndIndex (int (+ start num)))))))))

(defn insert-rows-request
  "InsertDimensionRequest for ROWS before `start-row`. 0-based."
  [sheet-id inherit-from-before start-row num-rows]
  (insert-dimension-request sheet-id "ROWS" inherit-from-before start-row num-rows))

(defn copypaste-rows-request
  "CopyPasteRequest copying rows [src-start-row, src-end-row) to dst-start-row. 0-based."
  [sheet-id src-start-row src-end-row dst-start-row dst-end-row]
  (-> (Request.)
      (.setCopyPaste
        (-> (CopyPasteRequest.)
            (.setSource (-> (GridRange.)
                            (.setSheetId sheet-id)
                            (.setStartRowIndex (int src-start-row))
                            (.setEndRowIndex (int src-end-row))))
            (.setDestination (-> (GridRange.)
                                 (.setSheetId sheet-id)
                                 (.setStartRowIndex (int dst-start-row))
                                 (.setEndRowIndex (int dst-end-row))))))))

(defn copypaste-cols-request
  "CopyPasteRequest copying columns [src-start-col, src-end-col) to dst-start-col.
  `options` may include `:paste-type` string. 0-based."
  [sheet-id src-start-col src-end-col dst-start-col dst-end-col
   {:keys [paste-type]}]
  (-> (Request.)
      (.setCopyPaste
        (-> (CopyPasteRequest.)
            (cond-> paste-type (.setPasteType paste-type))
            (.setSource (-> (GridRange.)
                            (.setSheetId (int sheet-id))
                            (.setStartColumnIndex (int src-start-col))
                            (.setEndColumnIndex (int src-end-col))))
            (.setDestination (-> (GridRange.)
                                 (.setSheetId (int sheet-id))
                                 (.setStartColumnIndex (int dst-start-col))
                                 (.setEndColumnIndex (int dst-end-col))))))))

(defn auto-fill-request
  "AutoFillRequest filling from columns [src-start-col, src-end-col) for `fill-length` columns.
  0-based."
  [sheet-id src-start-col src-end-col fill-length]
  (-> (Request.)
      (.setAutoFill
        (doto (AutoFillRequest.)
          (.setSourceAndDestination
            (doto (SourceAndDestination.)
              (.setSource (-> (GridRange.)
                              (.setSheetId (int sheet-id))
                              (.setStartColumnIndex (int src-start-col))
                              (.setEndColumnIndex (int src-end-col))))
              (.setDimension "COLUMNS")
              (.setFillLength (int fill-length))))))))

;;; ── Convenience write fns ─────────────────────────────────────────────────

(defn update-grid!
  "Build and execute an update-grid-request. 0-based row/col."
  [service spreadsheet-id sheet-id row-idx col-idx data]
  (exec! service spreadsheet-id
         [(update-grid-request sheet-id row-idx col-idx data)]))

(defn insert-data!
  "Insert `vector-row-data` rows at `row`/`col`, sending both the insert-rows and
  update-grid requests in a single exec! call. `row` and `col` are 0-based."
  [service spreadsheet-id sheet-id row col vector-row-data & [inherit-from-before]]
  (log/debug (format "Inserting %d rows" (count vector-row-data)))
  (exec! service spreadsheet-id
         [(insert-rows-request sheet-id (or inherit-from-before false) row (count vector-row-data))
          (update-grid-request sheet-id row col vector-row-data)]))

;;; ── Sheet structure ───────────────────────────────────────────────────────

(defn info
  "Return the full spreadsheets.get response for `spreadsheet-id`."
  [^Sheets service spreadsheet-id]
  (-> service .spreadsheets (.get spreadsheet-id) .execute))

(defn find-sheet-id
  "Return the numeric sheet ID for `sheet-title` within `spreadsheet-id`, or nil."
  [^Sheets service spreadsheet-id sheet-title]
  (->> (get (info service spreadsheet-id) "sheets")
       (some (fn [{:strs [properties]}]
               (when (= sheet-title (get properties "title"))
                 (get properties "sheetId"))))))

(defn get-sheet-name
  "Return the title string for `sheet-id` in `spreadsheet-id`, or nil."
  [^Sheets service spreadsheet-id sheet-id]
  (->> (get (info service spreadsheet-id) "sheets")
       (some (fn [{:strs [properties]}]
               (when (= sheet-id (get properties "sheetId"))
                 (get properties "title"))))))

(defn add-sheet
  "Add a new tab with `title` to `spreadsheet-id`. Returns the API response."
  [^Sheets service spreadsheet-id title]
  (exec! service spreadsheet-id
         [(-> (Request.)
              (.setAddSheet
                (-> (AddSheetRequest.)
                    (.setProperties
                      (-> (SheetProperties.)
                          (.setTitle title))))))]))

;;; ── Sheet management ──────────────────────────────────────────────────────

(defn delete-sheet
  "Delete the sheet tab identified by `sheet-id` from `spreadsheet-id`."
  [^Sheets service spreadsheet-id sheet-id]
  (log/info "Deleting sheet" {:spreadsheet-id spreadsheet-id :sheet-id sheet-id})
  (exec! service spreadsheet-id
         [(-> (Request.)
              (.setDeleteSheet
                (doto (DeleteSheetRequest.)
                  (.setSheetId sheet-id))))]))

(defn- set-sheet-properties!
  [service spreadsheet-id request]
  (-> service
      .spreadsheets
      (.batchUpdate spreadsheet-id
                    (doto (BatchUpdateSpreadsheetRequest.)
                      (.setRequests [request])))
      .execute))

(defn update-title
  "Rename sheet `sheet-id` to `new-sheet-title`."
  [^Sheets service spreadsheet-id sheet-id new-sheet-title]
  (log/info "Updating sheet title" {:sheet-id sheet-id :new-title new-sheet-title})
  (set-sheet-properties!
    service spreadsheet-id
    (-> (Request.)
        (.setUpdateSheetProperties
          (doto (UpdateSheetPropertiesRequest.)
            (.setProperties (doto (SheetProperties.)
                              (.setTitle new-sheet-title)
                              (.setSheetId (Integer. sheet-id))))
            (.setFields "title"))))))

(defn update-title-with-incrementing-name
  "Rename sheet `sheet-id`. If name already exists, append `.01`, `.02`, etc."
  [service spreadsheet-id sheet-id sheet-name0]
  (loop [sheet-name sheet-name0 subver 1]
    (if (try-catch-gsr-exception
          #(update-title service spreadsheet-id sheet-id sheet-name)
          #"already exists")
      (recur (format "%s.%02d" sheet-name0 subver) (inc subver))
      sheet-name)))

(defn set-hidden
  "Set the hidden property of sheet `sheet-id` to `hidden?`."
  [^Sheets service spreadsheet-id sheet-id hidden?]
  (log/info "Setting sheet hidden" {:sheet-id sheet-id :hidden? hidden?})
  (set-sheet-properties!
    service spreadsheet-id
    (-> (Request.)
        (.setUpdateSheetProperties
          (doto (UpdateSheetPropertiesRequest.)
            (.setProperties (doto (SheetProperties.)
                              (.setHidden hidden?)
                              (.setSheetId (Integer. sheet-id))))
            (.setFields "hidden"))))))

(defn copy-to
  "Copy sheet `src-sheet-id` from `src-spreadsheet-id` to `dst-spreadsheet-id`.
  If `sheet-title` is provided, the new sheet is renamed after copying.
  Returns the properties of the newly created sheet."
  [^Sheets service src-spreadsheet-id src-sheet-id dst-spreadsheet-id sheet-title]
  (let [response (-> service
                     .spreadsheets
                     .sheets
                     (.copyTo src-spreadsheet-id src-sheet-id
                              (doto (CopySheetToAnotherSpreadsheetRequest.)
                                (.setDestinationSpreadsheetId dst-spreadsheet-id)))
                     .execute
                     bean)]
    (if sheet-title
      (do (update-title service dst-spreadsheet-id (get response :sheetId) sheet-title)
          (assoc response :title sheet-title))
      response)))
