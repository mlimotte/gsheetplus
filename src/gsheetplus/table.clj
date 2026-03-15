(ns gsheetplus.table
  "High-level table reading and data parsing for Google Sheets.
  Builds on gsheetplus.core (raw cell reads) and gsheetplus.cell (type conversion)."
  (:require
    [camel-snake-kebab.core :as csk]
    [clojure.string :as string]
    [gsheetplus.cell :as cell]
    [gsheetplus.core :as core]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;;; ── Stop/start row markers ────────────────────────────────────────────────

(defn prune-start-rows
  "Drop rows up to and including the first row where column A = \"/START\"."
  [raw]
  (rest (drop-while #(not= (first %) "/START") raw)))

(defn drop-after-stop-row
  "Truncate rows at the first row where column A = \"/STOP\"."
  [raw]
  (take-while #(not= (first %) "/STOP") raw))

;;; ── Raw data read ─────────────────────────────────────────────────────────

(defn read-as-vec-vec
  "Read `sheet-title-or-range` from `spreadsheet-id` as a vector of vectors
  of Clojure values (cell->clj applied to each cell)."
  [service spreadsheet-id sheet-title-or-range]
  (let [table (core/get-cells service spreadsheet-id sheet-title-or-range)]
    (mapv (partial mapv cell/cell->clj) table)))

(defn raw-data
  "Read the sheet as vec-of-vecs, truncating at /STOP if present."
  [service spreadsheet-id sheet-title-or-range]
  (->> (read-as-vec-vec service spreadsheet-id sheet-title-or-range)
       drop-after-stop-row))

;;; ── Info params ───────────────────────────────────────────────────────────

(defn- nil-or-blank?
  [v]
  (or (nil? v) (and (string? v) (string/blank? v))))

(defn info-params
  "Extract leading Name-Value pairs from a vec-vec sheet (as from raw-data).
  Reads rows until the first blank in column A.
  Returns [info-map remaining-rows start-row-idx]."
  [raw-data]
  (let [[part1 part2] (split-with #(not (nil-or-blank? (first %))) raw-data)
        blank-count   (count (take-while #(nil-or-blank? (first %)) part2))
        start-row-idx (+ (count part1) blank-count)]
    [(->> part1
          (remove (fn [[_ v & _]] (nil-or-blank? v)))
          (map (fn [[k v & _]] [(csk/->kebab-case-keyword (str k)) v]))
          (into {}))
     (drop-while #(nil-or-blank? (first %)) part2)
     start-row-idx]))

;;; ── Header parsing ────────────────────────────────────────────────────────

(defn headers-from-row
  "Parse a raw header row (vector of string-or-nil) into a vector of keywords.
  Options:
    :column-name-fn   - fn to convert header strings (default csk/->kebab-case-keyword)
    :keep-leading-blanks - when true, preserve leading nil headers for alignment"
  [headers-raw & [{:keys [column-name-fn keep-leading-blanks]
                   :or   {column-name-fn csk/->kebab-case-keyword}}]]
  (cond->>
   (->> (if keep-leading-blanks
          (drop-while nil-or-blank? headers-raw)
          headers-raw)
        (take-while (complement nil?))
        (map str)
        (take-while (complement nil-or-blank?))
        (map #(string/replace % #"\s*\([^\)]+\)\s*" " "))
        (map string/trim)
        (map column-name-fn))
    keep-leading-blanks
    (concat (repeat (count (take-while nil-or-blank? headers-raw)) nil))))

#_(defn headers-from-row
  [headers-raw & [{:keys [keep-leading-blanks] :as options}]]
  (cond->>
    (->> (if keep-leading-blanks
           (drop-while lang/nil-or-blank? headers-raw)
           headers-raw)
         (take-while (complement nil?))
         (map str)
         ; string/blank? would handle nils, but we must make sure everything is a string first
         (take-while (complement lang/nil-or-blank?))
         (map #(string/replace % #"\s*\([^\)]+\)\s*" " "))
         (map string/trim)
         (map csk/->kebab-case-keyword))
    keep-leading-blanks
    (concat
      (repeat (count (take-while lang/nil-or-blank? headers-raw)) nil))))

;;; ── Row → record ──────────────────────────────────────────────────────────

(defn- zipmap-all-keys
  "zipmap that preserves all keys, including those without corresponding values."
  [ks vs]
  (loop [m  (transient {})
         ks (seq ks)
         vs (seq vs)]
    (if ks
      (recur (assoc! m (first ks) (first vs)) (next ks) (next vs))
      (persistent! m))))

(defn row->record
  "Map a header vector to a row vector. By default removes nil-key and blank-value entries.
  Options:
    :remove-blank-keys  - when false, keeps all entries including blanks (default true)"
  [headers row & [{:keys [remove-blank-keys]
                   :or   {remove-blank-keys true}}]]
  (let [row (map #(if (string? %) (string/trim %) %) row)]
    (if remove-blank-keys
      (->> (zipmap headers row)
           (remove (fn [[k v]] (or (nil? k) (nil-or-blank? v))))
           (into {}))
      (zipmap-all-keys headers row))))

;;; ── Block / section splitters ─────────────────────────────────────────────

(defn blocks
  "Split `raw-data` into contiguous blocks identified by a marker string in
  column A. `block-titles` is a seq of expected marker strings in order.
  Rows before the first block are discarded. The title row is the first row of
  each returned block."
  [raw-data block-titles]
  (let [[blocks last-block _]
        (reduce
         (fn [[blocks current-block [t1 :as titles]] row]
           (let [match? (and t1 (= (first row) t1))]
             (cond
               (and match? current-block)     [(conj blocks current-block) [row] (rest titles)]
               (and match? (not current-block)) [blocks [row] (rest titles)]
               (and (not match?) current-block) [blocks (conj current-block row) titles]
               :else                            [blocks current-block titles])))
         [[] nil block-titles]
         raw-data)]
    (if last-block (conj blocks last-block) blocks)))

(defn split-tables
  "Parse a vec-vec where non-blank column-A values are section names.
  The row immediately after each section name is the header row.
  Returns a map of kebab-keyword section names → sequences of records."
  [raw]
  (->> raw
       (map-indexed vector)
       (reduce
        (fn [[section-name headers acc] [idx [a :as row]]]
          (if-let [new-section (when-not (nil-or-blank? a)
                                 (-> a
                                     (string/replace #"\s*\([^\)]+\)\s*" " ")
                                     string/trim
                                     csk/->kebab-case-keyword))]
            [new-section nil (assoc acc new-section [])]
            (if headers
              [section-name
               headers
               (update acc section-name conj
                       (let [record (row->record headers row)]
                         (when (seq record)
                           (assoc record ::core/row-idx (inc idx)))))]
              [section-name
               (headers-from-row row {:keep-leading-blanks true})
               acc])))
        [nil nil {}])
       last
       (#(update-vals % (partial filter seq)))))

(defn split-sections
  "Group a vec-vec into sections (and optionally subsections).
  Options:
    :section-fn       - (record, raw-row) → section name string or nil
    :subsection-fn    - (record, raw-row) → subsection name string or nil (optional)
    :record-filter-fn - (record) → truthy to keep record (optional)"
  [raw headers {:keys [section-fn subsection-fn record-filter-fn]}]
  (->> (reduce
        (fn [[section-name subsection-name acc] row]
          (let [record          (row->record headers row)
                new-section     (section-fn record row)
                section-name    (or new-section section-name)
                new-subsection  (when subsection-fn (subsection-fn record row))
                subsection-name (if new-section
                                  new-subsection
                                  (or new-subsection subsection-name))
                keep?           (or new-section new-subsection
                                    (not record-filter-fn)
                                    (record-filter-fn record))]
            [section-name subsection-name
             (cond-> acc
               keep?
               (conj (cond-> record
                       section-name    (assoc :section-name section-name)
                       subsection-name (assoc :subsection-name subsection-name))))]))
        [nil nil []]
        raw)
       last
       (partition-by :section-name)
       (map (fn [[section-record & other-records]]
              (assoc section-record
                     :records (if subsection-fn
                                (->> other-records
                                     (partition-by :subsection-name)
                                     (map (fn [[sub-record & sub-records]]
                                            (assoc sub-record :records sub-records))))
                                other-records))))))

;;; ── Fill-down ─────────────────────────────────────────────────────────────

(defn fill-down
  "For each key in `keys-to-fill`, propagate non-blank values from the previous
  record to any record with a blank value for that key.
  `required?` — when true, throws if a fill key has no value to propagate from."
  [keys-to-fill required? records]
  (first
   (reduce
    (fn [[acc carried] record]
      (let [merged
            (into {}
                  (map (fn [k]
                         (let [v (get record k)
                               v (if (nil-or-blank? v) (get carried k) v)]
                           (when (and required? (nil-or-blank? v))
                             (throw (ex-info (format "Missing required value for %s" k)
                                             {:record record :carried carried})))
                           [k v]))
                       keys-to-fill))
            new-record (merge record merged)]
        [(conj acc new-record) new-record]))
    [[] {}]
    records)))

;;; ── High-level table reads ────────────────────────────────────────────────

(defn- resolve-sheet-title
  "If `sheet-title-or-id` is a number, resolve it to a title string."
  [service spreadsheet-id sheet-title-or-id]
  (if (number? sheet-title-or-id)
    (core/get-sheet-name service spreadsheet-id sheet-title-or-id)
    sheet-title-or-id))

(defn- parse-gsheet-url
  "Parse a full Google Sheets URL into [spreadsheet-id sheet-id] or throw."
  [url]
  (let [[_ spreadsheet-id sheet-id] (re-matches #"https://.*/d/([^/]+)/.*[?#]gid=(\d+)" url)]
    (when-not sheet-id
      (throw (ex-info "Could not parse URL. Must end with \"#gid=...\" or \"?gid=...\""
                      {:url url})))
    [spreadsheet-id (Long/parseLong sheet-id)]))

(defn read-single-table
  "Read a Google sheet as a sequence of records (maps keyed by header keywords).
  `sheet` can be a sheet title string, a numeric sheet-id, or a full gsheet URL.

  Options:
    :column-name-fn    - header string → keyword (default csk/->kebab-case-keyword)
    :prune-start?      - drop rows before /START marker
    :drop-rows         - drop the first N rows
    :filter-fn         - keep only records matching this predicate
    :row-idx?          - add ::core/row-idx (1-based) to each record
    :stop-on-blank-row? - stop at the first all-blank record

  Returns sequence with :headers metadata. Throws on duplicate headers."
  ([service gsheet-url options]
   (let [[spreadsheet-id sheet-id] (parse-gsheet-url gsheet-url)]
     (read-single-table service spreadsheet-id sheet-id options)))
  ([service spreadsheet-id sheet options]
   (let [{:keys [prune-start? drop-rows filter-fn row-idx?
                 stop-on-blank-row? column-name-fn]
          :or   {column-name-fn csk/->kebab-case-keyword}} options]
     (span/with-span! ["gsheetplus/read-single-table"
                       {:spreadsheet.id spreadsheet-id :sheet (str sheet)}]
       (let [sheet-title (resolve-sheet-title service spreadsheet-id sheet)
             [headers-raw & data-raw]
             (cond-> (read-as-vec-vec service spreadsheet-id sheet-title)
               drop-rows    (->> (drop drop-rows))
               prune-start? prune-start-rows
               true         drop-after-stop-row)
             headers (headers-from-row headers-raw {:column-name-fn column-name-fn})]
         (when (not= (count headers) (count (distinct headers)))
           (throw (ex-info "Duplicate headers" {:headers headers})))
         (with-meta
           (cond->> (map #(row->record headers % options) data-raw)
             stop-on-blank-row? (take-while #(some some? (vals %)))
             row-idx?           (map-indexed (fn [idx r] (assoc r ::core/row-idx (inc idx))))
             filter-fn          (filter filter-fn))
           {:headers headers}))))))

(defn read-single-table-with-info
  "Read a sheet that has leading Name-Value info params followed by a table.
  Returns [info-map records].

  Options: :prune-start?, :filter-fn, :row-idx?, :column-name-fn"
  [service spreadsheet-id sheet-name & [{:keys [prune-start? filter-fn row-idx?
                                                column-name-fn]
                                         :or   {column-name-fn csk/->kebab-case-keyword}
                                         :as   options}]]
  (span/with-span! ["gsheetplus/read-single-table-with-info"
                    {:spreadsheet.id spreadsheet-id :sheet.name sheet-name}]
    (let [rows                           (read-as-vec-vec service spreadsheet-id sheet-name)
          [info remaining start-row-idx] (info-params rows)
          [headers-raw & data-raw]       (cond-> remaining
                                           prune-start? prune-start-rows
                                           true         drop-after-stop-row)
          headers                        (headers-from-row headers-raw {:column-name-fn column-name-fn})]
      [info
       (cond->> (map (partial row->record headers) data-raw)
         row-idx?  (map-indexed (fn [idx r]
                                  (assoc r ::core/row-idx (+ (or start-row-idx 0) (inc idx)))))
         filter-fn (filter filter-fn))])))
