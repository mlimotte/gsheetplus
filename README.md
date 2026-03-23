# gsheetplus

A clean Clojure library for reading and writing Google Sheets. Designed for eventual extraction as a standalone library.

## Namespaces

| Namespace | Purpose |
|---|---|
| `gsheetplus.auth` | Build authenticated Sheets service instances from a JSON credentials stream |
| `gsheetplus.cell` | Cell data protocol and type conversions (read and write) |
| `gsheetplus.core` | Low-level API: reading ranges, batch updates, sheet management |
| `gsheetplus.table` | High-level table reading, header parsing, data transformation |

## Authentication

Credential retrieval is the caller's responsibility. You supply an `InputStream` of a Google service account JSON key file — obtained however you like (file, AWS Secrets Manager, environment variable, etc.).

### Setting up for Google Sheets permissions

* Create a new Google API project if one does not already exist:
    https://console.developers.google.com/projectselector/apis/dashboard

* Enable APIs
** https://console.developers.google.com/apis/api/sheets.googleapis.com/overview

* Get the service account id (which looks like an email address)
** Create a service account if you don't already have one
** Find the id at `https://console.developers.google.com/iam-admin/serviceaccounts/project`

* For the sheet you want to access:
  * Get the sheet id from the Sheet URL; the string following the `d/`
  * Click the Share button on the Sheet and add the service account id w/ view or edit permissions as required.

* Create a Key for your service account, as a JSON file. Pass these creds to the gsheet api. 
  (Hint: click the three dots next to the service account)
  
### Installation 

[![Clojars Project](https://img.shields.io/clojars/v/io.github.mlimotte/gsheetplus.svg)](https://clojars.org/io.github.mlimotte/gsheetplus)

### Build a service

```clojure
(require '[gsheetplus.auth :as auth])
(require '[clojure.java.io :as io])

;; From a file on disk (development)
(def service (auth/build-service (io/input-stream "path/to/service-account.json")))

;; From a string (e.g. pulled from secrets manager)
(def service (auth/build-service (io/input-stream (.getBytes json-string "UTF-8"))))

;; From a classpath resource
(def service (auth/build-service (io/resource "google-creds.json")))
```

`build-service` authenticates with Sheets + Drive scopes and returns a `com.google.api.services.sheets.v4.Sheets` instance. Caching the returned service is the caller's responsibility.

## Reading Sheets

### Simple table — headers in row 1, data below

```clojure
(require '[gsheetplus.table :as table])

(def records
  (table/read-single-table service spreadsheet-id "Sheet1" {}))

;; => ({:name "Alice" :age 30} {:name "Bob" :age 25} ...)
```

Headers are normalized to kebab-case keywords: `"First Name"` → `:first-name`.
Parenthetical annotations are stripped: `"Price (USD)"` → `:price`.

#### Options

```clojure
(table/read-single-table service spreadsheet-id "Sheet1"
  {:prune-start?      true   ; drop rows before a /START marker row
   :stop-on-blank-row? true  ; stop at the first all-blank record
   :row-idx?          true   ; add ::core/row-idx (1-based) to each record
   :filter-fn         some?  ; keep only records satisfying this predicate
   :column-name-fn    my-fn  ; custom header → keyword conversion
   :drop-rows         2})    ; skip the first N rows before treating row 1 as headers
```

> **Note:** A `/STOP` marker in column A always truncates the sheet at that row, regardless of options.

#### By URL

Paste the full sheet URL (including `#gid=...`) directly:

```clojure
(table/read-single-table service
  "https://docs.google.com/spreadsheets/d/SPREADSHEET_ID/edit#gid=SHEET_ID"
  {})
```

### Sheet with leading info params

Using `table/read-single-table-with-info`, a sheet can have key-value metadata rows before the table (before the first blank line):

```
Title      My Report
Version    3

Name       Score
Alice      95
Bob        87
```

```clojure
(let [[info records]
      (table/read-single-table-with-info service spreadsheet-id "Sheet1")]
  (:title info)   ; => "My Report"
  (:version info) ; => "3"
  records)        ; => ({:name "Alice" :score 95} ...)
```

### Raw vec-of-vecs

```clojure
;; Returns a vector of vectors of Clojure values (strings, numbers, booleans, dates)
(table/read-as-vec-vec service spreadsheet-id "Sheet1")
```

### Multi-section sheets

```clojure
;; Sheet where non-blank column A = section name, next row = headers
(table/split-tables (table/read-as-vec-vec service spreadsheet-id "Config"))
;; => {:section-one ({:col-a "x" ...} ...) :section-two (...)}
```

## Writing Sheets

### Batch update (the general mechanism)

```clojure
(require '[gsheetplus.core :as core])

;; Build requests, then execute together
(core/exec! service spreadsheet-id
  [(core/update-grid-request sheet-id 1 0 [["Alice" 30] ["Bob" 25]])])
```

### Update a grid of cells

```clojure
;; row-idx and col-idx are 0-based
(core/update-grid! service spreadsheet-id sheet-id
  1      ; start row (skip header at row 0)
  0      ; start col
  [["Alice" 30 true]
   ["Bob"   25 false]])
```

### Insert rows then write data

```clojure
(core/insert-data! service spreadsheet-id sheet-id
  1   ; row (0-based, rows inserted before this index)
  0   ; col
  [["Alice" 30] ["Bob" 25]])
```

### Append rows

```clojure
(core/append! service spreadsheet-id sheet-id
  [["Alice" 30] ["Bob" 25]])
```

## Cell Type Conversions

When **reading**, `cell->clj` converts API cell maps to Clojure types automatically:

| Sheet type | Clojure type |
|---|---|
| String | `String` |
| Number | `Double` |
| Number (DATE format) | `java.time.LocalDate` |
| Number (CURRENCY format) | `BigDecimal` |
| Boolean | `Boolean` |
| Empty | `nil` |

When **writing**, values are coerced via the `CellDataValue` protocol. Built-in support:

| Clojure type | Sheet cell |
|---|---|
| `String` | string |
| `Number` | number |
| `Boolean` | boolean |
| `java.time.LocalDate` | date (formatted `yyyy-mm-dd`) |
| `nil` | empty cell |

### Extend for custom types

```clojure
(require '[gsheetplus.cell :as cell])

(extend-protocol cell/CellDataValue
  java.time.LocalDateTime
  (->cell-data [dt]
    (cell/date-cell "yyyy-mm-dd hh:mm:ss" (.toLocalDate dt))))
```

## Sheet Management

```clojure
;; Find a sheet's numeric ID by title
(core/find-sheet-id service spreadsheet-id "Sheet1")

;; Get a sheet title by numeric ID
(core/get-sheet-name service spreadsheet-id 12345)

;; Spreadsheet metadata
(core/info service spreadsheet-id)

;; Add / delete / rename / copy sheets
(core/add-sheet service spreadsheet-id "New Tab")
(core/delete-sheet service spreadsheet-id sheet-id)
(core/update-title service spreadsheet-id sheet-id "Renamed Tab")
(core/copy-to service src-spreadsheet-id src-sheet-id dst-spreadsheet-id "New Name")

;; URL helpers
(core/spreadsheet-link spreadsheet-id sheet-id)
;; => "https://docs.google.com/spreadsheets/d/.../edit#gid=..."

(core/parse-spreadsheet-link "https://docs.google.com/spreadsheets/d/ABC/edit#gid=123")
;; => {:spreadsheet-id "ABC" :sheet-id 123}
```

## Observability

OTEL spans are emitted automatically for major I/O calls when the OpenTelemetry Java agent is present:

| Span name | When |
|---|---|
| `gsheetplus/get-cells` | Any range read |
| `gsheetplus/exec!` | Any batch update |
| `gsheetplus/append!` | Row append |
| `gsheetplus/read-single-table` | High-level table read |
| `gsheetplus/read-single-table-with-info` | High-level read with info params |

Spans are no-ops when the agent is absent — no configuration required.

## Run Tests

```bash
clj -M:test
```

## Building and Releasing

```bash

# Build and deploy to Clojars (requires CLOJARS_USERNAME and CLOJARS_PASSWORD env vars)

# First update the version number in build.clj. If you forget, you will be warned to do so.
clj -T:build deploy
```

The version and artifact coordinates are set in `build.clj`.

## Thanks

A big thank you to the team at SparkFund for [google-apps-clj](https://github.com/SparkFund/google-apps-clj). It was a wonderfully designed library that I relied on heavily for years. While it is no longer actively maintained, it laid an excellent foundation and directly inspired this project. `gsheetplus` extends that work with a richer set of higher-level functions for reading and writing sheets.
