(ns overseer.errors
  "Functions for catching/handling various types of exceptions
   that arise during handler execution"
  (:require [clj-json.core :as json]
            (raven-clj
               [core :as raven]
               [interfaces :as raven.interface])
            [taoensso.timbre :as timbre]))

(defn try-thunk
  "Returns the value of calling f, or of calling exception-handler
   with any exception thrown"
  [exception-handler f]
  (try (f)
    (catch Throwable ex
      (exception-handler ex))))

(defn filter-serializable
  "Used for stripping out non-serializable fields
   before sending JSON over the wire to Sentry"
  [data]
  (let [safe?
        (fn [x]
          (try (json/generate-string x)
            (catch Exception ex false)))
        sanitize
        (fn [[k v]]
          (when (and (safe? k) (safe? v))
            [k v]))]
    (->> (map sanitize data)
         (filter identity)
         (into {}))))

(defn sanitized-ex-data [ex]
  (when-let [data (ex-data ex)]
    (filter-serializable data)))

(defn sentry-capture
  "Send an exception and an optional map of additional context
   info to Sentry"
  [dsn ex extra]
  (let [event-map {:message (.getMessage ex) :extra (or extra {})}]
    (try
      (->> ex
           (raven.interface/stacktrace event-map)
           (raven/capture dsn))
      (catch Exception ex'
        (timbre/error "Sentry exception handler failed")
        (timbre/error ex')))))

(defn ->default-exception-handler
  "Construct an handler function that by default logs exceptions
   and optionally sends to Sentry if configured"
  [config job]
  (if-let [dsn (get-in config [:sentry :dsn])]
    (fn [ex]
      (when-not (= :aborted (:overseer/status (ex-data ex)))
        (let [extra (merge (select-keys job [:job/type :job/id])
                           (or (sanitized-ex-data ex) {}))]
          (sentry-capture dsn ex extra))))
    (fn [ex]
      (timbre/error ex))))

(defn failure-info
  "Construct a map of information about an exception, including
   user-supplied ex-data if present"
  [ex]
  (let [exc-data (ex-data ex)
        m {:overseer/status (or (:overseer/status exc-data) :failed)
           :overseer/failure {:reason :system/exception
                              :exception (class ex)
                              :message (.getMessage ex)}}]
    (if exc-data
      (assoc-in m [:overseer/failure :data] exc-data)
      m)))

(defn ->job-exception-handler
  "Exception handler for job thunks; invokes the default handler,
   then returns a map of failure info, using user-provided ex-data if present.
   Attempts to parse special signal status out of ex, else defaults to :failed"
  [config job]
  (fn [ex]
    (let [default-handler (->default-exception-handler config job)]
      (default-handler ex))
      (failure-info ex)))
