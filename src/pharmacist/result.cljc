(ns pharmacist.result
  "Data structures and functions to work with the result
  of [[pharmacist.data-source]] fetch functions. A result is a map with the key
  `:pharmacist.result/success?` (a boolean) and any number of the below keys.

| key                              | description |
| ---------------------------------|-------------|
| `:pharmacist.result/success?`    | Boolean. Indicates successful retrieval
| `:pharmacist.result/data`        | The resulting data from the fetch functions
| `:pharmacist.result/retryable?`  | A boolean indicating whether this failure is worth retrying
| `:pharmacist.result/retrying-in` | Number of milliseconds to wait before retrying
| `:pharmacist.result/refresh`     | Optional set of parameters that must be refreshed before retrying

  The following keys are set by [[pharmacist.prescription/fill]]:

| key                                | description |
| -----------------------------------|-------------|
| `:pharmacist.result/partial?`      | `true` when the selection returned collection items, but the collection items are not yet available
| `:pharmacist.result/attempts`      | The number of attempts made to fetch this source
| `:pharmacist.result/retrying?`     | `true` if the result was a failure and Pharmacist intends to try it again
| `:pharmacist.result/raw-data`      | When the source has a schema that transforms `::pharmacist.result/data`, this key has the unprocessed result
| `:pharmacist.result/timeout-after` | The number of milliseconds at which this source was considered timed out
| `:pharmacist.cache/cached-at`      | Timestamp indicating when this result was originally cached
"
  (:require #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::success? boolean?)
(s/def ::data any?)
(s/def ::raw-data any?)
(s/def ::attempts number?)
(s/def ::retryable? boolean?)
(s/def ::partial? boolean?)
(s/def ::refresh (s/coll-of keyword?))
(s/def ::timeout-after number?)
(s/def :pharmacist.cache/cached-at number?)

(s/def ::result (s/keys :req [::success?]
                        :opt [::raw-data ::data ::attempts ::retryable? ::refresh
                              ::partial? ::timeout-after :pharmacist.cache/cached-at]))

(s/def ::success?-args (s/cat :result ::result))

(defn success?
  "Returns true if this particular result was a success, as indicated by
  the `:pharmacist.result/success?` key"
  [result]
  (::success? result))

(s/fdef success?
  :args ::success?-args
  :ret boolean?)

(s/def ::success-args (s/or :unary (s/cat :data any?)
                            :binary (s/cat :data any?
                                           :config map?)))

(defn success
  "Create a successful result with data. Optionally provide further details about
  the result as `config`.

```clj
(require '[pharmacist.result :as result])

(result/success {:data \"Yes\"} {:my.app/custom-annotation 42})
;;=>
;; {::result/success? true
;;  ::result/data {:data \"Yes\"}
;;  :my.app/custom-annotation 42}
```"
  [data & [config]]
  (merge
   {::success? true ::data data}
   config))

(s/fdef success
  :args ::success-args
  :ret ::result)

(s/def ::failure-args (s/or :nullary (s/cat)
                            :unary (s/cat :data any?)
                            :binary (s/cat :data any?
                                           :config (s/keys :opt [::retryable?]))))

(defn failure
  "Create a failed result, optionally with data and additional keys for the result.

```clojure
(require '[pharmacist.result :as result])

(result/failure {:message \"Oops!\"} {::result/retryable? true})
```"
  [& [data config]]
  (merge {::success? false}
         (when data {::data data})
         config))

(s/fdef failure
  :args ::failure-args
  :ret ::result)

(defn error
  "Create a failed result that caused an unexpected error - e.g. throwing an
  exception, not fulfilling fetch's contract, etc. `result` is whatever the
  original result was - possibly even an exception, and `error` is a map of:

| key         | description |
| ------------|-------------|
| `:message`  | A helpful message trying to help the developer understand the problem |
| `:type`     | A keyword indicating the type of error |
| `:reason`   | An optional reason - one type of error may occur for different reasons |
| `:upstream` | The exception that caused this error, if any |"
  [result error]
  {::success? false
   ::original-result result
   ::error error})
