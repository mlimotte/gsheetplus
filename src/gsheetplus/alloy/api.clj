(ns gsheetplus.alloy.api
  (:require [clojure.string :as string]
            [clojure.walk]
            [clojure.zip :as z]
            [clojure.tools.logging :as log]
            [gsheetplus.core :as g+core]
            [gsheetplus.table :as g+table]
            [gsheetplus.alloy.grammar :as grammar]
            [gsheetplus.alloy.render :as render]
            [gsheetplus.alloy.gs-render :as gs-render]))

(defn render
  "Render the nodes of the zipper using the standard parser.
  - zipper is a clojure.zip structure.
  - context-map is a data structure to use for REF lookup.
  - extensions (nilable) is a Map of filters and custom-tags.
    {:filters {:upper-case (fn [x] (.toUpperCase x))
               :embiginate clojure.string/upper-case
               ; With arguments:
               :plus2 (fn [node-val & args] (apply + node-val 2 args))}
     :inline-ctags {:key-count (fn [context-map] (count (keys context-map)))}}"
  [zipper context-map extensions]
  (->> (grammar/parse-struct grammar/the-parser zipper)
       (render/render-ast context-map (or extensions {}))
       (render/unroll)
       (gs-render/generate-instructions)))

(defn read-data
  "`range` can just be a Tab name, for all data."
  ; TODO * Instead of just reading values, i.e. "effectiveValue" and "userEnteredValue",
  ;      as per `cell->clj`, get userEnteredValue or formulas.
  ;      * Also update and formulas that had template syntax in them back to the sheet.
  [service spreadsheet-id range]
  (log/info (str "Read data from google sheet " {:spreadsheet-id spreadsheet-id :range range}))
  (g+table/read-as-vec-vec service spreadsheet-id range))

(defn render-google-sheet
  "Render the google sheet using the given context-map.
  Returns a Map of keys :errors, :spreadsheet-id and :sheet-id"
  [gsheet-service spreadsheet-id sheet-name context-map extensions]
  (log/info "Render google sheet with alloy")
  (let [sheet-id (g+core/find-sheet-id gsheet-service spreadsheet-id sheet-name)
        sheet-data (read-data gsheet-service spreadsheet-id sheet-name)
        zipper (z/vector-zip sheet-data)
        instructions (render zipper context-map extensions)
        gs-requests (doall (mapcat (partial gs-render/instruc->gsreq sheet-id) instructions))]
    (if (pos? (count gs-requests))
      (do (log/info (str "(alloy) Sending requests to google sheets API (batched). "
                         {:count (count gs-requests) :spreadsheet-id spreadsheet-id :sheet-id sheet-id}))
          (g+core/exec! gsheet-service spreadsheet-id gs-requests))
      (log/warn "(alloy) No google sheet requests, no updates will be made."))
    ;; Return any error messages
    {:errors         (some->> instructions
                              (filter #(= (:instruct %) :error-message))
                              (map (fn [{:keys [rowidx colidx err-msg]}]
                                     (format "Error at (%s, %s) %s" rowidx colidx err-msg)))
                              seq)
     :spreadsheet-id spreadsheet-id
     :sheet-id       sheet-id}))

(defn kw-or-str
  [x]
  (when x
    (if (string/starts-with? x ":") (keyword (subs x 1)) x)))
