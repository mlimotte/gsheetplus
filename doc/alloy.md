# Alloy — Google Sheets Template Engine

Alloy is a template engine for Google Sheets. It reads a sheet that contains template syntax, evaluates the templates against a Clojure data structure (a *context map*), and writes the results back to the sheet in place.

The mental model: the sheet is a template file, the context map is the data, and Alloy renders the template into a final document by modifying cell values and expanding/contracting rows as needed.

## Quick Start

```clojure
(require '[gsheetplus.alloy.api :as alloy]
         '[gsheetplus.auth :as auth]
         '[clojure.java.io :as io])

(def service (auth/build-service (io/input-stream "path/to/service-account.json")))

(alloy/render-google-sheet
  service
  "your-spreadsheet-id"
  "Sheet1"
  {:name "Alice" :items [{:sku "A1" :qty 3} {:sku "B2" :qty 7}]}
  nil)
;; => {:errors nil :spreadsheet-id "..." :sheet-id 12345}
```

The sheet is modified in place. Cells containing template syntax are replaced with evaluated values. For-loop rows are duplicated or deleted as needed.  This approach retains as much or your formatting (fonts, column sizes, cell formats), static text and formulas as possible.

## Template Syntax

Template expressions use Jinja-like delimiters:

| Syntax | Purpose |
|---|---|
| `{{ expr }}` | Evaluate expression and substitute value |
| `{% tag %}` | Control-flow tag (for, if, with, etc.) |

Whitespace inside delimiters is ignored: `{{x}}`, `{{ x }}`, and `{{  x  }}` are all equivalent.

A cell may contain a mix of literal text and template expressions:

```
Hello {{ name }}, you have {{ count }} items.
```

## Variable References

A variable reference (`REF`) looks up a value from the context map by navigating a path of keys.

```
{{ name }}           ; top-level key :name
{{ order.total }}    ; nested: (get-in ctx [:order :total])
{{ items.0 }}        ; index into a sequential: (get-in ctx [:items 0])
```

Key lookup is flexible:
- Clojure keywords are tried first, then string equivalents.
- Namespaced keys use a `/` separator: `{{ a..b..c/s }}` looks up `:a.b.c/s`.
  (The `..` notation avoids ambiguity with the `.` path separator.)

A reference that evaluates to `nil` renders as an empty string.

## Expressions

Expressions support infix arithmetic and comparison operators inside `{{ }}`:

```
{{ x + 1 }}
{{ price * qty }}
{{ score >= 90 }}
{{ name != "unknown" }}
```

Supported operators: `=`, `!=`, `<`, `>`, `<=`, `>=`, `+`, `-`, `*`, `/`

Parentheses are allowed: `{{ (a + b) * c }}`

## Filters

Filters transform a value using the pipe `|` operator:

```
{{ name | upper-case }}
{{ price | round:2 }}
{{ value | default:"-" }}
```

Multiple filters chain left-to-right:

```
{{ name | trim | upper-case }}
```

Filters with arguments pass them after `:`, separated by `:`:

```
{{ items | take:5 }}
```

Filters are provided via the `extensions` map (see [Extensions](#extensions)).

## Control Flow Tags

### `for` / `endfor`

Iterates over a sequential value. **The `{% for %}` and `{% endfor %}` tags must each occupy the first cell of their row.** Alloy will duplicate or delete rows as needed: the template rows are cloned once per iteration, then updated.

```
{% for item in items %}
{{ item.sku }}   {{ item.qty }}
{% endfor %}
```

If `items` is empty, all rows between `{% for %}` and `{% endfor %}` are deleted. If `items` has N entries, the template rows are duplicated N times.

Nested loops are supported:

```
{% for section in sections %}
{% for row in section.rows %}
{{ row.label }}   {{ row.value }}
{% endfor %}
{% endfor %}
```

### `if` / `elif` / `else` / `endif`

Conditionally includes or suppresses cell content. `{% if %}` can appear anywhere in a cell.

```
{% if score >= 90 %}Excellent{% elif score >= 70 %}Good{% else %}Needs work{% endif %}
```

When an `if`/`endif` pair spans multiple rows, the entire row is deleted for false branches (analogous to a for-loop with an empty list).

### `with` / `endwith`

Binds a name to a value in the local context:

```
{% with label = "Total" %}
{{ label }}: {{ total }}
{% endwith %}
```

The binding is available only within the `with`/`endwith` block.

## Extensions

Extensions provide custom filters and custom tags. Pass them as the last argument to `render-google-sheet` or `render`.

```clojure
(def extensions
  {:filters
   {:upper-case clojure.string/upper-case
    :lower-case clojure.string/lower-case
    :default    (fn [v fallback] (if (clojure.string/blank? (str v)) fallback v))
    :take       (fn [coll n] (take n coll))
    :add        (fn [x n] (+ x n))}

   :inline-ctags
   {:now (fn [ctx] (str (java.util.Date.)))}})
```

### Filters

A filter function receives the value being piped, followed by any arguments:

```clojure
; No arguments: (fn [value] ...)
:upper-case clojure.string/upper-case

; With arguments: (fn [value & args] ...)
:add (fn [x & args] (apply + x args))
```

### Inline Custom Tags

Inline custom tags (`inline-ctags`) are zero-argument tags that call a function and render its return value into the cell:

```
{% now %}
{% request-id %}
```

```clojure
:inline-ctags
{:now        (fn [ctx] (str (java.util.Date.)))
 :request-id (fn [ctx] (java.util.UUID/randomUUID))}
```

The function receives the current context map and returns any value, which is stringified into the cell.

## Error Handling

When a template error occurs (e.g. unknown filter, evaluation failure), Alloy does not throw. Instead:

- The cell is left empty (or replaced with an empty string).
- The error is collected and returned in the `:errors` key of the result map.

```clojure
(let [{:keys [errors spreadsheet-id sheet-id]}
      (alloy/render-google-sheet service sid sheet-name ctx extensions)]
  (when errors
    (doseq [e errors]
      (println "Template error:" e))))
```

Each entry in `:errors` is a string describing the location and message: `"Error at (row, col) message"`.

## API Reference

### `gsheetplus.alloy.api/render-google-sheet`

```clojure
(render-google-sheet gsheet-service spreadsheet-id sheet-name context-map extensions)
```

Reads `sheet-name` from the spreadsheet, renders all template expressions against `context-map`, and writes the results back. Returns `{:errors [...] :spreadsheet-id "..." :sheet-id N}`.

### `gsheetplus.alloy.api/render`

```clojure
(render zipper context-map extensions)
```

Lower-level: takes a `clojure.zip` zipper over a vector-of-vectors structure and returns a sequence of instructions (without executing them). Useful for testing or custom rendering pipelines.

### `gsheetplus.alloy.api/read-data`

```clojure
(read-data service spreadsheet-id range)
```

Reads the sheet as a vector of vectors of raw values. `range` can be a tab name or an A1-notation range.

## Namespace Layers

| Namespace | Purpose |
|---|---|
| `gsheetplus.alloy.api` | Public entry points: `render-google-sheet`, `render`, `read-data` |
| `gsheetplus.alloy.grammar` | Instaparse grammar; parses cell strings into an AST |
| `gsheetplus.alloy.render` | Walks the AST zipper; evaluates expressions and collects results |
| `gsheetplus.alloy.gs-render` | Translates rendered items into Google Sheets API requests |
