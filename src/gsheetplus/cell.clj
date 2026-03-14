(ns gsheetplus.cell
  "Cell data protocol and conversions between Clojure values and Google Sheets API types.
  Extend CellDataValue for custom write-time type coercions."
  (:require [java-time.api :as jtime])
  (:import (com.google.api.services.sheets.v4.model
             CellData CellFormat ExtendedValue NumberFormat RowData)
           java.time.temporal.ChronoUnit))

;;; ── Protocol ──────────────────────────────────────────────────────────────

(defprotocol CellDataValue
  "Coerce a Clojure value to a CellData instance for writing to Google Sheets."
  (->cell-data [v]))

;;; ── Date helpers ──────────────────────────────────────────────────────────

(defn date-serial-number
  "Google Sheets date serial number for a java.time.LocalDate.
  Days since 1899-12-30, returned as Double."
  [local-date]
  (double (.between ChronoUnit/DAYS (jtime/local-date 1899 12 30) local-date)))

(defn date-cell
  "CellData containing `local-date` formatted with the given Excel date pattern,
  e.g. \"yyyy-mm-dd\" or \"M/d/yyyy\"."
  [pattern local-date]
  (doto (CellData.)
    (.setUserEnteredValue
      (-> (ExtendedValue.)
          (.setNumberValue (date-serial-number local-date))))
    (.setUserEnteredFormat
      (-> (CellFormat.)
          (.setNumberFormat
            (-> (NumberFormat.)
                (.setType "DATE")
                (.setPattern pattern)))))))

;;; ── CellDataValue implementations ─────────────────────────────────────────

(extend-protocol CellDataValue
  String
  (->cell-data [s]
    (doto (CellData.)
      (.setUserEnteredValue
        (.setStringValue (ExtendedValue.) s))))

  Number
  (->cell-data [n]
    (doto (CellData.)
      (.setUserEnteredValue
        (.setNumberValue (ExtendedValue.) (double n)))))

  Boolean
  (->cell-data [b]
    (doto (CellData.)
      (.setUserEnteredValue
        (.setBoolValue (ExtendedValue.) b))))

  nil
  (->cell-data [_]
    (CellData.))

  java.time.LocalDate
  (->cell-data [dt]
    (date-cell "yyyy-mm-dd" dt)))

;;; ── Row write helper ──────────────────────────────────────────────────────

(defn row->row-data
  "Convert a vector of values (anything satisfying CellDataValue) to a RowData
  instance for use in UpdateCellsRequest."
  [row]
  (->> row
       (mapv ->cell-data)
       (.setValues (RowData.))))

;;; ── Cell read / conversion ────────────────────────────────────────────────

(defn cell->clj
  "Convert a raw Google Sheets API cell map to a Clojure value.
  Handles effectiveValue / userEnteredValue; prefers effectiveValue.
  Types: boolValue → Boolean, stringValue → String, DATE number → LocalDate,
         CURRENCY number → BigDecimal, number → Double, empty → nil."
  [cell-data]
  (let [ev (get cell-data "effectiveValue")
        uev (get cell-data "userEnteredValue")
        v (or ev uev)
        bool-val (get v "boolValue")
        string-val (get v "stringValue")
        number-val (get v "numberValue")
        number-format (get-in cell-data ["userEnteredFormat" "numberFormat" "type"])
        date? (and (= "DATE" number-format) (some? number-val))
        currency? (and (= "CURRENCY" number-format) (some? number-val))
        empty-cell? (and (nil? ev) (nil? uev) (instance? CellData cell-data))]
    (when (and (some? ev) (some? uev))
      (throw (ex-info "Ambiguous cell: both effectiveValue and userEnteredValue present"
                      {:cell-data cell-data})))
    (when (and (some? string-val) (some? number-val))
      (throw (ex-info "Ambiguous cell value: both stringValue and numberValue present"
                      {:cell-data cell-data})))
    (cond
      (some? bool-val) bool-val
      string-val string-val
      date? (jtime/plus (jtime/local-date 1899 12 30) (jtime/days (long number-val)))
      currency? (bigdec number-val)
      number-val number-val
      empty-cell? nil
      :else cell-data)))
