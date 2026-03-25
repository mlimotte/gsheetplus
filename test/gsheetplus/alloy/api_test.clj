(ns gsheetplus.alloy.api-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer :all]
    [clojure.string :as string]
    [gsheetplus.auth :as auth]
    [gsheetplus.core :as g+core]
    [gsheetplus.alloy.api :as alloy]
    [gsheetplus.alloy.examples :refer [context-map1]])
  (:import (java.util Date)))

;; Instructions: You must manually delete the sheet indicated by new-sheet-name before running this.
(deftest ^:integration test-render-a-google-sheet

  ;; NOTE: If you want to run this test, you will need to adapt it for your credentials
  ;;   1. The tmp/*json file below for cred is not in git. You must supply
  ;;      your own file if you want to run this test.
  ;;   2. The spreadsheet file is one in my org. Create your own file and share it
  ;;      with your "service-account"

  (let [spreadsheet-id "1LFTwmxP39esqwplAzFcDnc6-crHwNs1Q9IOPf-JlRvc"
        sheet-name "skipp.alloy.api-test"
        gsheet-service (auth/build-service (io/input-stream "tmp/google-sheets-credentials-bridge.json"))
        new-sheet-name (str sheet-name 1)
        src-sheet-id (g+core/find-sheet-id gsheet-service spreadsheet-id sheet-name)
        existing-sheet-id (g+core/find-sheet-id gsheet-service spreadsheet-id new-sheet-name)]
    (when existing-sheet-id
      (g+core/delete-sheet gsheet-service spreadsheet-id existing-sheet-id))
    (g+core/copy-to gsheet-service spreadsheet-id src-sheet-id spreadsheet-id new-sheet-name)
    ;; The only test is that we don't throw an (uncaught) exception
    (alloy/render-google-sheet
      gsheet-service spreadsheet-id new-sheet-name context-map1
      {:filters      {:take    (fn [node-val n] (take n node-val))
                      :boolean boolean
                      :default (fn [v arg1] (if (string/blank? v) arg1 v))}
       :inline-ctags {:datetime (fn [context-map] (str (Date.)))}})))

(deftest kw-or-str-test
  (is (= (alloy/kw-or-str "foo") "foo"))
  (is (= (alloy/kw-or-str ":foo") :foo))
  (is (= (alloy/kw-or-str nil) nil)))
