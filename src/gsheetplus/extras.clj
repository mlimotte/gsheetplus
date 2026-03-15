(ns gsheetplus.extras
  (:require [cheshire.core :as json]
            [gsheetplus.auth :as g+auth])
  (:import (java.io ByteArrayInputStream)
           [com.google.api.client.http HttpRequestInitializer]))

(defn gcreds-stream-from-aws-secrets-manager
  "Get the Google api keys from the `secrets` Map, as a Stream. The Google
  API client library we're using expects the creds to be either a separate file
  or a Stream. Creating the stream this way, allows us to embed the API creds
  in the common `secrets` file."
  [aws secret-id]
  (let [get-secret-value (requiring-resolve 'skipp.util.aws.secretsmanager/get-secret-value)]
    (-> (get-secret-value aws secret-id)
        (or (throw (ex-info "`google` api keys are required." {:type :unauthorized})))
        json/generate-string
        (.getBytes "utf-8")
        ByteArrayInputStream.)))

(defn set-timeout
  ;; Based on https://developers.google.com/api-client-library/java/google-api-java-client/errors#timeouts
  "The credentials, e.g. (GoogleCredential/fromStream creds-stream transport factory)
  can be used for the request-initializer."
  [request-initializer connection-timeout-millis read-timeout-millis]
  (reify HttpRequestInitializer
    (initialize [this http-request]
      (.initialize request-initializer http-request)
      (.setConnectTimeout http-request connection-timeout-millis)
      (.setReadTimeout http-request read-timeout-millis))))

;; For google sheets:
(defn login-with-aws-secret
  [aws secret-id]
  (g+auth/build-service (gcreds-stream-from-aws-secrets-manager aws secret-id)))
