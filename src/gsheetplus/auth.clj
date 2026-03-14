(ns gsheetplus.auth
  "Build authenticated Google Sheets service instances from JSON credentials.
  Credential retrieval is the caller's responsibility."
  (:require [clojure.tools.logging :as log])
  (:import (com.google.api.client.googleapis.auth.oauth2 GoogleCredential)
           (com.google.api.client.googleapis.javanet GoogleNetHttpTransport)
           (com.google.api.client.json.jackson2 JacksonFactory)
           (com.google.api.services.sheets.v4 Sheets Sheets$Builder SheetsScopes)
           (com.google.api.services.drive DriveScopes)
           (java.io InputStream)))

(def ^:private scopes
  "OAuth scopes required for Sheets read/write and Drive access."
  [SheetsScopes/SPREADSHEETS
   DriveScopes/DRIVE])

(defn build-service
  "Build an authenticated Sheets service from a JSON service-account credentials InputStream.
  `creds-stream` can be anything accepted by GoogleCredential/fromStream."
  [^InputStream creds-stream]
  (log/info "Building Google Sheets service")
  (let [transport (GoogleNetHttpTransport/newTrustedTransport)
        factory (JacksonFactory/getDefaultInstance)
        creds (-> (GoogleCredential/fromStream creds-stream transport factory)
                  (.createScoped scopes))]
    (-> (Sheets$Builder. transport factory creds)
        (.setApplicationName "gsheetplus")
        .build)))

