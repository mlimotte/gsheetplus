(ns gsheetplus.alloy.gs-render
  (:require
   [gsheetplus.core :as g+core]
   [clojure.tools.logging :as log]
   [gsheetplus.alloy.render :as render])
  (:import (java.time LocalDate)))

(def row (comp first :path))
(def col (comp second :path))

(defn update-cell-request
  [sheet-id row-idx col-idx value]
  (let [v (if (and (string? value) (re-matches #"[-+]?\d+(\.\d+)?" value))
            (Double/parseDouble value)
            value)]
    (g+core/update-cells-request sheet-id row-idx col-idx [v])))

(defmulti instruc->gsreq
  (fn [sheet-id instruction] (:instruct instruction)))

(defn delete-rows-instruct
  [start-rowidx & [end-exclusive-rowidx]]
  (hash-map :instruct :delete-rows
            :start-rowidx start-rowidx
            :end-rowidx (or end-exclusive-rowidx (inc start-rowidx))))

(defmethod instruc->gsreq :delete-rows
  [sheet-id instruction]
  [(g+core/delete-rows-request sheet-id (:start-rowidx instruction) (:end-rowidx instruction))])

(defn error-message
  [src-rowidx rowidx colidx err-msg]
  (hash-map :instruct :error-message
            :src-rowidx src-rowidx :rowidx rowidx :colidx colidx :value err-msg))

(defmethod instruc->gsreq :error-message
  [sheet-id instruction]
  ; TODO find a way to pass error messages back to google sheet
  ;[(update-cell-request sheet-id (:rowidx instruction) (:colidx instruction) (:value instruction))]
  [])

(defn update-instruct
  [rowidx colidx value]
  (hash-map :instruct :update :rowidx rowidx :colidx colidx
            :value (if (or (number? value) (boolean? value) (instance? LocalDate value))
                     value
                     (str value))))

(defmethod instruc->gsreq :update
  [sheet-id instruction]
  [(update-cell-request sheet-id (:rowidx instruction) (:colidx instruction) (:value instruction))])

(defn dupe-rows-instruct
  [start-rowidx end-src-row-exclusive num-blocks]
  (hash-map :instruct :dupe-rows
            :rowidx start-rowidx
            :end-src-row-exclusive end-src-row-exclusive
            :num-blocks num-blocks))

(defmethod instruc->gsreq :dupe-rows
  [sheet-id instruction]
  (let [{:keys [end-src-row-exclusive num-blocks]} instruction
        start-src-row (:rowidx instruction)
        rows-per-block (- end-src-row-exclusive start-src-row)
        ; `dec num-blocks` because the source rows will be used for the first block
        rows-to-insert (* rows-per-block (dec num-blocks))]
    (if (pos? rows-to-insert)
      [(g+core/insert-rows-request sheet-id true end-src-row-exclusive rows-to-insert)
       ; Copy start-src-row to end-src-row (inclusive!) over the inserted rows in order to get
       ; literal values, formulas and formatting. src will repeat automatically to cover the
       ; dst range.
       (g+core/copypaste-rows-request sheet-id
                                      start-src-row end-src-row-exclusive
                                      end-src-row-exclusive (+ end-src-row-exclusive rows-to-insert))]
      [])))

(defn generate-instructions*
  "Generate instructions for interpretation by `instruc->gsreq`"
  [items loop-indices row-offset]
  (mapcat
   (fn [{:keys [op collector value] :as item}]
     (let [rowidx (+ (row item) row-offset)]
       (cond
         (#{::render/eval-error} value)
         [(error-message (row item) rowidx (col item) (:err-msg item))]

         (= op :FOR)
         (let [end-rowidx (+ (:end-token-row item) row-offset)
               num-rows (dec (- end-rowidx rowidx))
               lc (:loop-count item)
               num-blocks (if (empty? loop-indices)
                            (first lc)
                            (get-in lc loop-indices))
               children (:children item)]
           (-> []
               (conj (delete-rows-instruct rowidx))
               (concat
                (mapcat
                 (fn [idx]
                   (generate-instructions* children
                                           (conj loop-indices idx)
                                           (+ row-offset (* idx num-rows))))
                 (range num-blocks)))
               vec                                         ; so subsequent conj go at the end
               (cond->
                (zero? num-blocks)
                 (conj (delete-rows-instruct (inc rowidx) end-rowidx))
                 (pos? num-blocks)
                 (conj (dupe-rows-instruct (inc rowidx) end-rowidx num-blocks)))))

         (= value ::render/remove-row)
         [(delete-rows-instruct rowidx)]

         (#{::render/no-eval ::render/no-value} value)
         [(update-instruct rowidx (col item) "")]

         (and (= op :VALUE))                               ; no need to update a literal/static value
         []

         (not (nil? value))
         [(update-instruct rowidx (col item) value)]

         (not (empty? collector))
         (let [x (get-in collector loop-indices)]
           (if (#{::render/eval-error} x)
             [(error-message (row item) rowidx (col item) (:err-msg item))]
             [(update-instruct rowidx (col item) x)])))))

   items))

(defn generate-instructions
  [items]
  (log/info "Starting generate-instructions pass...")
  ; Note: `reverse` so that Instructions are processed from bottom up. This way, deleting a
  ;       row near the top of the file does not impact how we process rows below it
  ;       (since those rows have already been processed).
  (reverse (generate-instructions* items [] 0)))
