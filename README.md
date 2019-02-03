# Pharmacist

Pharmacist is an "inversion of control" _library_ for data access: given
multiple sources of data, it finds the fastest way to fetch them all, optionally
caches results, retries failing fetches, handles errors in a unified way, and
optionally validates, transforms, and coerces data on the way back. Pharmacist
helps you isolate the mechanics of fetching data, and allows your data
processing functions to reach their full potential as pure consumers of Clojure
data, no longer mired in imperative HTTP mechanics, global data structures,
tedious error handling and retries, or mapping.

Pharmacist helps you:

- Isolate side-effecting "data sources" like HTTP requests in single-purpose
  functions
- Declaratively describe required data sources, optionally with inter-source
  dependencies
- Control error handling and retries declaratively
- Map and coerce remote data declaratively
- Run development-time validations on external data to verify your expectations
- Optionally configure caching across data sources
- Recursively fetch nested data sources
- Generate Datascript schema for fetched data
- Consume data as it becomes available, or wait for the entire dataset

Pharmacist coordinates data fetches, it does not actually implement fetching. It
can fetch anything for you by coordinating your functions - it can work with
both synchronous and asynchronous sources. Some relevant examples include:

* Data from the network
* Data from disk
* Data from global vars (e.g. app-wide atoms)
* Data from the browser's localStorage

## Table of contents

- [Install](#install)
- [Data sources](#data-sources)
- [Prescriptions](#prescriptions)
- [Retries](#retry-on-failure)
- [Caching](#caching)
- [Mapping/coercion](#mapping-and-coercion)
- [Nested data sources](#data-begets-data)
- [Synchronous fetches](#synchronous-fetches)
- [HTTP convenience function](#http-convenience-function)
- [Reference](#reference)
- [Example: Single-page application](#spa)

## Install

**NB!** The goal is for Pharmacist to become a stable library worthy of your
trust, which never intentionally breaks backwards compatibility. Currently,
however, it is still under development, and slight changes may occur. This will
be the case for as long as the version is prefixed with a `0`.

With tools.deps:

```clj
cjohansen/pharmacist {:mvn/version "0.2019.02.03-1"}
```

With Leiningen:

```clj
[cjohansen/pharmacist "0.2019.02.03-1"]
```

## Data sources

To fetch data with Pharmacist you implement **data sources** - functions that
fetch data from somewhere, provide a **prescription** - a description of how
to invoke and coordinate data source functions, and finally select some or all
of the described data.

A data source is any function that accepts a data source description and returns
a Pharmacist result:

```clj
(require '[pharmacist.data-source :as data-source]
         '[pharmacist.result :as result]
         '[clj-http.client :as http])

(defn spotify-playlists [{::data-source/keys [params]}]
  (let [res (http/get "https://api.spotify.com/playlists"
                      {:oauth-token (:token params)
                       :throw-exceptions false})]
    (if (http/success? res)
      (result/success (:body res))
      (result/failure {:error "Failed to fetch playlists" :res res}))))
```

While you certainly could make good of use it with a single data source,
Pharmacist is designed to alleviate the pain of coordinating multiple sources,
so a meaningful hello world will need at least two sources. Let's define another
one, which fetches an API token:

```clj
(defn spotify-auth [{::data-source/keys [params]}]
  (let [res (http/post "https://accounts.spotify.com/api/token"
                       {:throw-exceptions false
                        :form-params {:grant_type "client_credentials"}
                        :basic-auth [(:spotify-api-user params)
                                     (:spotify-api-password params)]})]
    (if (http/success? res)
      (result/success (:body res))
      (result/failure {:error "Failed to acquire token" :res res}))))
```

## Prescriptions

To make things interesting, we will tell Pharmacist to use the token from the
auth data source as a parameter to the one fetching playlists. This is what the
**prescription** is for:

```clj
(def prescription
  {::auth {::data-source/fn #'spotify-auth
           ::data-source/params {:spotify-api-user "username"
                                 :spotify-api-password "password"}}

   ::playlists {::data-source/fn #'spotify-playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]}}})
```

Inlining the username and password is a terrible idea, we'll see [a better
solution](#initial-params) for this shortly.

Passing quoted vars (`#'spotify-auth`) instead of function values
(`spotify-auth`) makes your code reloadable, and enables Pharmacist to infer the
function name in a predictable manner.

Notice the value of the `:token` parameter in `::playlists`. The metadata
informs Pharmacist that this value will be provided by the `::auth` data source.
Pharmacist now knows to fetch `::auth` first, to extract `:access_token` from
its resulting data, and pass it as the `:token` parameter to `::playlists`. If
there had been no such dependency, Pharmacist would've retrieved both items in
parallel (or possibly only the one you selected).

To fetch data, pass the prescription to `pharmacist.prescription/fill`, and then
`pharmacist.prescription/select` the desired keys:

```clj
(require '[pharmacist.prescription :as p]
         '[pharmacist.result :as result]
         '[clojure.core.async :refer [go-loop <!]])

(let [ch (p/select (p/fill prescription) [::playlists])]
  (go-loop []
    (when-let [item (<! ch)]
      (prn (::result/path item) (::result/success? item) (::result/data item))
      (recur))))
```

`fill` returns a lazy collection of your data. You may `select` from it multiple
times, but it will always give you the same data back. `select` returns a
`core.async` channel that receives a message every time a single data source is
attempted fetched. If you'd rather get everything in one go, you can use
`collect`:

```clj
(require '[pharmacist.prescription :as p]
         '[clojure.core.async :refer [go <!]])

(go
  (let [res (p/collect (p/select (p/fill prescription) [::playlists]))]
    (<! res)))
```

`collect` returns a channel that emits a single message containing an overall
success indicator (`::result/succes?`), a combined map of `{path data}` for all
successfully fetched data sources (`::result/data`), and a list of all sources
with their individual results (including for sources that where never attempted
fetched due to failing dependencies). For the above example, `::result/data` in
the message from the `collect` channel would contain:

```clj
{::auth {:token "..."}
 ::playlists {...}}
```

You can also fetch the whole thing synchronously:

```clj
(require '[pharmacist.prescription :as p]
         '[clojure.core.async :refer [<!!]])

(println (<!! (p/collect (p/select (p/fill prescription) [::playlists]))))
```

See [the section on async vs sync fetch](#synchronous-fetches) for more
information on the return value from collect.

### Initial parameters

Pharmacist dependencies can be used to string data from one source to another.
But what about parameters for the initial fetches? Sometimes, you can get away
by providing them inline in the prescription, but this limits the reusability of
the prescription. Depending on where you are calling `fill` from, you might have
input parameters from a number of sources:

- In a web server endpoint: Path/query/body parameters from HTTP requests
- In a single page application: Path/query parameters from the current page URL
- Runtime configuration options
- Environment variables
- Any number of other sources

These can be provided to `fill` as initially resolved data sources. Here's the
updated prescription:

```clj
;; Now with externalized configuration
(def prescription
  {::auth {::data-source/id :spotify/auth
           ::data-source/params {:spotify-api-user ^::data-source/dep [:config :spotify-api-user]
                                 :spotify-api-password ^::data-source/dep [:config :spotify-api-user]}}

   ::playlists {::data-source/id :spotify/playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]}}})
```

And this is how you fill it with runtime configuration:

```clj
(require '[pharmacist.prescription :as p]
         '[pharmacist.result :as result]
         '[clojure.core.async :refer [<!!]])

(def env (System/getenv))

(println
 (-> prescription
     (p/fill {:params {:config {:spotify-api-user (get env "SPOTIFY_USER")
                                :spotify-api-password (get env "SPOTIFY_PASS")}}})
     (p/select [::playlists])
     p/collect
     <!!))
```

There are no magical or conventional names here. Any key inside the map passed
to `:params` will be available as a dependency in your prescription, and you
access information from in just like other data sources.

If all of a source's params are already neatly available as a map from another
resource, declare the full map as a dependency:

```clj
(def prescription
  {::auth {::data-source/id :spotify/auth
           ::data-source/params ^::data-source/dep [:config]}

   ::playlists {::data-source/id :spotify/playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]}}})
```

## Retry on failure

HTTP and other network protocols don't always work exactly as planned.
Sometimes, problems go away if you just try again. To instruct Pharmacist to
retry a source, specify the maximum number of retries on the prescription:

```clj
(def prescription
  {::auth {::data-source/id :spotify/auth
           ::data-source/params {:spotify-api-user "username"
                                 :spotify-api-password "password"}
           ::data-source/retries 3}})
```

With this addition, the token will be retried 3 times before causing an error -
_if the result can be retried_, a decision made by the fetch implementation. You
will still be notified via the channel returned from `select` of failed requests
that are being retried, the message will look like:

```clj
{::result/success? false
 ::result/data {:error "Network failure"}
 ::result/attempts 1
 ::result/retrying? true}
```

`::result/retrying? true` means the source is going to be retried.

Some errors won't go away no matter how many times you retry. The result
returned from the data source can inform Pharmacist on whether or not it is
worth trying again:

```clj
(defn spotify-auth [{::data-source/keys [params]}]
  (let [res (http/post "https://accounts.spotify.com/api/token"
                       {:throw-exceptions false
                        :form-params {:grant_type "client_credentials"}
                        :basic-auth [(:spotify-api-user params)
                                     (:spotify-api-password params)]})]
    (if (http/success? res)
      (result/success (:body res))
      (result/failure {:error "Failed to acquire token" :res res}
                      {::result/retryable? (not= 401 (:status %))}))))
```

When `::result/retryable?` is `false`, Pharmacist will not retry the fetch, even
if the maximum number of retries is not exhausted. If `::result/retryable?` is
not set, its default value is `true`.

### Retries with refreshed dependencies

If you are using [caching](#caching), it might not be worth retrying a fetch
with the same (possibly stale) set of dependencies - you might need to refresh
some or all of them. To continue the example of the authentication token, a 403
response from a service could be worth retrying, but only with a fresh token.

Given the following prescription:

```clj
(def prescription
  {::auth {::data-source/id :spotify/auth
           ::data-source/params ^::data-source/dep [:config]}

   ::playlists {::data-source/id :spotify/playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]}}})
```

The playlist resource can indicate that it might be worth a retry with a new
token the following way:

```clj
(defn spotify-playlists [{::data-source/keys [params]}]
  (let [res (http/get "https://api.spotify.com/playlists"
                      {:oauth-token (:token params)
                       :throw-exceptions false})]
    (if (http/success? res)
      (result/success (:body res))
      (result/failure {:error "Failed to fetch playlists" :res res}
                      (when (= 403 (:status res))
                        {::result/retryable? true
                         ::result/refresh [:token]})))))
```

Pharmacist will know that the `:token` parameter came from the `::auth` source,
fetch it again (bypassing the cache), and then retry the playlist with a fresh
token.

## Caching

Caching of data sources is primarily handled by two functions:

```clj
(defn get [path prescription])

(defn put [path prescription result])
```

`get` attempts to get a cached copy of the data source. It will be passed the
path to the source (e.g. `[::auth]` and `[::playlists]` in the above example),
and the individual data source with fully resolved `::data-source/params` (e.g.
values will be filled in for inter-source dependencies). If this function
returns a non-nil value, the value will be used in place of fetching the remote
source, and `put` will never be called.

If the value does not exist in the cache, the data will be fetched from the
source, and if successful, `put` will be called with the same arguments as
`get`, along with the fetched result.

You pass these functions to `fill` as the `:cache` parameter:

```clj
(require '[clojure.string :as str]
         '[pharmacist.data-source :as data-source]
         '[pharmacist.prescription :as prescription])

(defn cache-get [cache path prescription]
  (get @cache (data-source/cache-key prescription)))

(defn cache-put [cache path prescription value]
  (swap! cache assoc (data-source/cache-key prescription) value))

(def cache (atom {}))

(prescription/fill
 prescription
 {:cache {:get (partial cache-get cache)
          :put (partial cache-put cache)}})
```

### Cache keys

As demonstrated above, you can rely on Pharmacist to generate cache keys for
you - the default implementation will combine `::data-source/id` with all
`::data-source/params` (after resolving dependencies) in a vector.

Because the cache key requires the fully resolved parameters, all of a source's
parameters must be fully resolved before looking it up in the cache. This means
that if you have a playlist data source like above, that depends on a token
resource, then Pharmacist is unable to check the cache for a playlist until it's
fetched a token, which is a little backwards. If the playlist is already cached
and you don't need the token for anything else, there is no need to fetch the
token at all.

Pharmacist solves this problem with `:pharmacist.data-source/cache-params`, a
vector of parameter paths (as in vectors you could pass to `get-in` etc)
required to build the cache key:

```clj
(require '[pharmacist.data-source :as data-source]
         '[pharmacist.prescription :as p]
         '[myapp.spotify :as spotify])

(def prescription
  {::auth {::data-source/fn #'spotify/auth
           ::data-source/params ^::data-source/dep [:config]}

   ::playlists {::data-source/fn #'spotify/playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]
                                      :id ^::data-source/dep [::playlist-id]}
                ::data-source/cache-params [[:id]]}})

(-> prescription
    (p/fill {:params {::playlist-id 42}})
    (p/select [::playlists]))
```

Now Pharmacist will know that only the `:id` is necessary to look for playlists
in the cache, meaning that its cache key will be something like:

```clj
[:spotify/playlists {[:id] 42}]
```

If you now select for the `::playlists`, Pharmacist may skip the `::auth` data
source entirely when the playlist in question exists in the cache.

`:pharmacist.data-source/cache-params` can also be used to reduce the amount of
data that make up cache keys. Parameters can sometimes be huge maps, and
typically only a few keys, or even a single key, like `:id`, is enough to adress
the source. You can use paths to reduce the amount of data that goes into the
cache key:

```clj
{::data-source/fn #'spotify/playlists
 ::data-source/params {:token ^::data-source/dep [::auth :access_token]
                       :user ^::data-source/dep [::user]}
 ::data-source/cache-params [[:user :id]]}
```

### Atoms with map caches

Because maps in atoms is such a versatile caching strategy (it will work with
plain maps and atoms, as well as atoms containing any of Clojure's `core.cache`
types), Pharmacist provides a convenience function for them:

```clj
(require '[pharmacist.prescription :as prescription]
         '[pharmacist.cache :as cache])

(prescription/fill prescription {:cache (cache/atom-map (atom {}))})
```

Combine this with the `core.cache` TTL cache to cache all data for one hour:

```clj
(require '[pharmacist.prescription :as prescription]
         '[pharmacist.cache :as pc]
         '[clojure.core.cache :as cache])

(prescription/fill
 prescription
 {:cache (pc/atom-map (atom (cache/ttl-cache-factory {} :ttl (* 60 60 1000))))})
```

**NB!** This approach caches everything the same way. If you need more control,
provide functions that dispatch on `path`, `::data-source/id`, or even
individual results. The caching functions are passed the full source, meaning
that you could annotate it with information about how long different types of
data should be cached etc. You can also set `:ttl`, `:expires-at` or similar on
your sources and/or fetch results, and use these in your cache get/put
functions.

When using `atom-map` cache, you can control which params are used for cache
keys with `:pharmacist.data-source/cache-params` as described above.

## Mapping and coercion

When consuming external data, we might want to map the keys and coerce the types
of the result set to better fit our world-view. Mapping and type-coercion is
handled by Pharmacist schemas, which can also be used to generate specs,
validate payloads, and generate
[Datascript](https://github.com/tonsky/datascript) schemas.

A schema specifies what parts of a data source result to extract, what data
types to expect (enforcable through dev-time asserts), how to coerce data, and
how to map names. It also serves as documentation of the parts of an external
source of data your app is concerned with:

```clj
(def playlist
  {::data-source/fn #'spotify/playlists
   ::data-source/params {:token ^::data-source/dep [::auth :access_token]
                         :id ^::data-source/dep [::playlist-id]}
   ::data-source/schema
   {:image/url {::schema/spec string? ;; 1
                ::schema/source :url} ;; 2
    :image/width {::schema/spec int?
                  ::schema/source :width
                  ::schema/coerce parse-int} ;; 3
    :image/height {::schema/spec int?
                   ::schema/source :height}

    :playlist/id {::schema/unique ::schema/identity
                  ::schema/source schema/infer-ns} ;; 4
    :playlist/collaborative {::schema/spec boolean?
                             ::schema/source :collaborative}
    :playlist/title {::schema/spec string?
                     ::schema/source :name} ;; 5
    :playlist/image {::schema/spec (s/keys :req [:image/url :image/width :image/height])} ;; 6
    :playlist/images {::schema/spec (s/coll-of :playlist/image)
                      ::schema/source :images} ;; 7
    ::schema/entity {::schema/spec (s/keys :req [:playlist/id ;; 8
                                                 :playlist/collaborative
                                                 :playlist/title
                                                 :playlist/images])}}})
```

1. A `clojure.spec.alpha` spec for `:image/url`. Provides documentation, and can
   be used with asserts during development to verify assumptions about external
   data.
2. When consuming external data, it is often nice to be able to map keys to
   idiomatic Clojure names, or, as in this case, to provide a namespace for the
   key. This instructs Pharmacist to populate the `:image/url` key in the
   resulting data from the `:url` attribute of the source data.
3. `::schema/coerce` can be set to any function that takes a single argument -
   the value to coerce - and returns a coerced value. If you are enforcing specs
   during development, the coercer should produce a value that is compatible
   with the spec.
4. Some key mappings can be mechanically inferred. Pharmacist provides
   convenience functions for inferral, e.g. `schema/infer-ns` infers a
   namespaced key from its bare counterpart. In this case, `:playlist/id` will
   be populated from `:id`. The `::schema/source` function is called with a map
   and a key.
5. Remote keys can be completely renamed as well. This shows an example of
   mapping `:name` in the API payload to `:playlist/title` in our local data.
6. Key specs inform Pharmacist to look up and map nested data structures
7. Collection specs inform Pharmacist to loop through `:images` in the payload
   and map each one with the keys in the `:playlist/image` key spec.
8. By convention, the schema is expected to define `:pharmacist.schema/entity`
   as either a map or collection spec, and this key is used to map the root
   data. If this convention does not suit your needs, you can implement
   `pharmacist.schema/coerce-data` and provide another root spec, see the API
   docs.

With this schema in place, data from this source will be automatically mapped
when you call `prescription/fill`. You can further utilize this schema if you
want:

```clj
(require '[pharmacist.schema :as schema])

;; Define global specs
(schema/define-specs! playlist)

;; Assert that payloads match specs in schema
(schema/assert-payloads! playlist)

;; Datascript schema
(def ds-schema (schema/datascript-schema playlist))
```

The asserts generated by `assert-payloads!` are `clojure.spec.alpha/assert`, and
will typically be compiled out of production code. They can be useful to detect
incorrect mappings and/or inconsistent API responses during development.

# Data begets data

Sometimes, fetching a piece of data will make you aware of new data sources to
fetch. Linked resources from a REST API would be one such example. Because it
can be hard to predict upfront how many there will be, it's hard to formulate
this in a prescription. It is fully possible to perform multiple HTTP requests
within the same data source, but it is not recommended, because you then opt out
of the retry mechanism, caching, and error handling in Pharmacist for each
intermediary request.

To take advantage of Pharmacist's prescription filling abilities for an unknown
number of child resources, you can define a collection data source. The
collection data source includes a reference to another data source to apply to
its children, and then its result is either a vector of parameters to pass to
the data source, or a map of paths to parameters to the child resource.

To demonstrate this with an example, let's fetch some data from an HTTP
endpoint, and then recursively look up linked resources.
[ghibliapi.herokuapp.com](https://ghibliapi.herokuapp.com) provides metadata on
Studio Ghibli movies. The payload for a Ghibli movie includes hypermedia links
to the people in the movie:

```json
{
  "id": "58611129-2dbc-4a81-a72f-77ddfc1b1b49",
  "title": "My Neighbor Totoro",
  ...
  "people": [
    "https://ghibliapi.herokuapp.com/people/986faac6-67e3-4fb8-a9ee-bad077c2e7fe",
    "https://ghibliapi.herokuapp.com/people/d5df3c04-f355-4038-833c-83bd3502b6b9",
    "https://ghibliapi.herokuapp.com/people/3031caa8-eb1a-41c6-ab93-dd091b541e11",
    "https://ghibliapi.herokuapp.com/people/87b68b97-3774-495b-bf80-495a5f3e672d",
    "https://ghibliapi.herokuapp.com/people/d39deecb-2bd0-4770-8b45-485f26e1381f",
    "https://ghibliapi.herokuapp.com/people/f467e18e-3694-409f-bdb3-be891ade1106",
    "https://ghibliapi.herokuapp.com/people/08ffbce4-7f94-476a-95bc-76d3c3969c19",
    "https://ghibliapi.herokuapp.com/people/0f8ef701-b4c7-4f15-bd15-368c7fe38d0a"
  ],
  "species": [
    "https://ghibliapi.herokuapp.com/species/af3910a6-429f-4c74-9ad5-dfe1c4aa04f2",
    "https://ghibliapi.herokuapp.com/species/603428ba-8a86-4b0b-a9f1-65df6abef3d3",
    "https://ghibliapi.herokuapp.com/species/74b7f547-1577-4430-806c-c358c8b6bcf5"
  ],
  "locations": [
    "https://ghibliapi.herokuapp.com/locations/"
  ],
  "vehicles": [
    "https://ghibliapi.herokuapp.com/vehicles/"
  ],
  "url": "https://ghibliapi.herokuapp.com/films/58611129-2dbc-4a81-a72f-77ddfc1b1b49"
}
```

We can use Pharmacist to recursively pull down this structure. We'll start with
functions to fetch movies and people:

```clj
(require '[pharmacist.data-source :as data-source]
         '[clj-http.client :as http])

(defn ghibli-film [{::data-source/keys [params]}]
  (let [res (http/get (str "https://ghibliapi.herokuapp.com/films/" (:id params))
                      {:throw-exceptions false})]
    (if (http/success? res)
      (result/success (:body res))
      (result/failure {:error "Failed to fetch playlists"}))))

(defn ghibli-person [{::data-source/keys [params]}]
  (let [res (http/get (:url params)
                      {:throw-exceptions false})]
    (if (http/success? res)
      (response/success (:body %))
      (response/failure {:error "Failed to fetch person"}))))
```

In order to load all the people from a movie, we need a selection function that
can decide _which_ exact people to fetch. We'll make one that just pulls all the
ones from the movie:

```clj
(defn people-urls [{::data-source/keys [params]}]
  (result/success (map (fn [url] {:url url}) (:movie/people params))))
```

These three functions can be stitched together with a prescription. First off,
we'll define the movie and the person, with their schemas for coercion:

```clj
(def prescription
  {:movie
   {::data-source/fn #'ghibli-film
    ::data-source/params {:id ^::data-source/dep [:movie-id]}
    ::data-source/schema
    {:movie/id {::schema/unique ::schema/identity
                ::schema/spec uuid?
                ::schema/coerce uuid
                ::schema/source :id}
     :movie/title {::schema/spec string?
                   ::schema/source :title}
     :movie/description {::schema/spec string?
                         ::schema/source :description}
     :movie/release-date {::schema/spec string?
                          ::schema/source :release_date}
     :movie/people {::schema/spec (s/coll-of string?)}
     ::schema/entity {::schema/spec (s/keys :req [:movie/id
                                                  :movie/title
                                                  :movie/description
                                                  :movie/release-date
                                                  :movie/people])}}}

   :person
   {::data-source/fn #'ghibli-person
    ::data-source/params {:url ^::data-source/dep [:url]}
    ::data-source/schema
    {:person/id {::schema/unique ::schema/identity
                 ::schema/spec uuid?
                 ::schema/coerce uuid
                 ::schema/source :id}
     :person/name {::schema/spec string?
                   ::schema/source :name}
     :person/age {::schema/spec number?
                  ::schema/source :age}
     ::schema/entity {}}}})
```

Finally, the collection source:

```clj
(def prescription
  {;;...
   :people
   {::data-source/fn #'people-urls
    ::data-source/params ^::data-source/dep [:movie]
    ::data-source/coll-of :person}})
```

Finally, let's fetch My Neighbour Totoro with all of its people:

```clj
(require '[pharmacist.prescription :as prescription])

(-> prescription
    (prescription/fill
     {:params {:movie-id "58611129-2dbc-4a81-a72f-77ddfc1b1b49"}})
    (prescription/select [:people]))
```

Now, when a person is fetched, your channel will receive a message like:

```clj
{::result/path [:movie :movie/people 0]
 ::result/success? true
 ::result/data {:person/id "986faac6-67e3-4fb8-a9ee-bad077c2e7fe"
                :person/name "Satsuki Kusakabe"
                :person/age "11"}}
```

You can combine all of these events into a single map using
`pharmacist.prescription/merge-sources`:

```clj
(require '[pharmacist.prescription :as prescription]
         '[clojure.core.async :as a])

(let [ch (-> prescription
             (prescription/fill
              {:params {:movie-id "58611129-2dbc-4a81-a72f-77ddfc1b1b49"}})
             (prescription/select [:people]))
      messages (a/go-loop [messages []]
                 (when-let [message (a/<! ch)]
                   (recur (conj messages message))))]
  (prescription/merge-results messages)
  ;;=>
  {:movie {:movie/id "58611129-2dbc-4a81-a72f-77ddfc1b1b49"
           :movie/title "My Neighbour Totoro"
           ;;...
           :movie/people [{:person/id "986faac6-67e3-4fb8-a9ee-bad077c2e7fe"
                           :person/name "Satsuki Kusakabe"
                           :person/age "11"}
                          ;;...
                          ]}})
```

If you don't care about consuming the messages at your earliest convenience,
`pharmacist.prescription/collect` can collect all of them for you:

```clj
(-> prescription
     (prescription/fill
      {:params {:movie-id "58611129-2dbc-4a81-a72f-77ddfc1b1b49"}})
     (prescription/select [:people])
     (prescription/collect))
```

`collect` returns a channel that emits a single message, where `::result/data`
is the result of passing all the messages through `merge-results`.

# Synchronous fetches

Pharmacist uses [core.async](https://github.com/clojure/core.async) for flow
control, and as such you can get results synchronously using `<!!` - on the JVM.
ClojureScript consumption must always be asynchronous.

In either case, you can pass the channel returned from `select` through
`collect` to collect every message into a channel that emits them all in one go.
The map contained in this message contains:

#### `:pharmacist.result/success?`

A boolean indicating whether the entire data set was successfully loaded or not.

#### `:pharmacist.result/data`

A combined map of all successful results, like the one produced by
`merge-results`.

#### `:pharmacist.result/sources`

A list of all the sources in the prescription. Each entry in this list is a map
of `:path`, `:source` (the initial prescription), and `:result` (the
final result). In the event of an overall failure, the `:result` maps can tell
you where things failed.

In the following example, `:auth` failed, so `:playlist` (which requires the
`:auth` token) is not only considered a failure - it wasn't even attempted:

```clj
{::result/success? false
 ::result/data {:prefs {:dark-mode? true}}
 ::result/sources [{:path :prefs
                    :source {::data-source/id :user/prefs}
                    :result {::result/success? true
                             ::result/data {:dark-mode? true}
                             ::result/attempts 1}}

                   {:path :auth
                    :source {::data-source/id :spotify/auth
                             ::data-source/params {:spotify-api-user "user"
                                                   :spotify-api-password "pass"}}
                    :result {::result/success? false
                             ::result/data {:error "Failed to authenticate user"}
                             ::result/attempts 3}}

                   {:path :playlists
                    :source {::data-source/id :spotify/playlists
                             ::data-source/params {:token ^::data-source/dep [:auth :access_token]}}
                    :result {::result/success? false
                             ::result/attempts 0}}]}
```

# HTTP convenience function

Because HTTP data sources are very common, Pharmacist provides a convenience
function for them, based on [clj-http](https://github.com/dakrone/clj-http)
(Clojure) and [cljs-http](https://github.com/r0man/cljs-http) (ClojureScript)-
both expected to be provided by your application.

```clj
(require '[pharmacist.data-source :as data-source]
         '[pharmacist.http :as http])

(defn spotify-playlist [{::data-source/keys [params]}]
  (http/http-data-source {:method :get
                          :url "https://api.spotify.com/playlists"
                          :oauth-token (:token params)}))
```

## Reference

API docs will be available on cljdoc.org eventually.

### Prescriptions

#### `:pharmacist.data-source/fn`

The function that fetches data. Should return either a
`pharmacist.result/success` or a `pharmacist.result/failure`.

#### `:pharmacist.data-source/fn-async`

The function that fetches data, asynchronously. Only used if
`:pharmacist.data-source/fn` is not provided. Should return a core async channel
that emits a single message which is either a `pharmacist.result/success` or a
`pharmacist.result/failure`.

#### `:pharmacist.data-source/id`

ID that addresses a data source. If not explicitly provided, it is inferred from
`:pharmacist.data-source/fn` or `:pharmacist.data-source/async-fn`.

#### `:pharmacist.data-source/params`

Parameters passed from a prescription to a data source. Can be or include
dependencies on initial parameters and other data sources.

#### `:pharmacist.data-source/retries`

How many times a data source should be retried in case of failure. Defaults to
`0`. If `:pharmacist.result/retryable?` is set to `false`, this value is
ignored.

#### `:pharmacist.data-source/dep`

This keyword is set as meta data on parameters in
`:pharmacist.data-source/params` to mark them as dependencies on other data
sources.

### Results

#### `:pharmacist.result/success?`

Boolean, indicating whether or not the source was successfully loaded.

#### `:pharmacist.result/data`

The data loaded. If the result is not successful, this key might contain error
information, failed HTTP requests etc.

#### `:pharmacist.result/attempts`

The number of attempts made to load this specific piece of data.

#### `:pharmacist.result/retryable?`

A boolean indicating whether or not this failure result is retryable.

<a id="spa"></a>
# Example: Page data for a single-page application

So you're writing a single-page application. When the user visits a URL you want
to fetch some data and render it. To play on the earlier example, we want to
fetch a user's playlists from Spotify. To do so, we must first ensure that we
have a user (e.g. someone is logged in), and then fetch playlists from the
Spotify API.

We will use Pharmacist to trigger user logins in addition to fetch data. We will
define one data source that simply looks up the user's token from the global app
state, and another one which uses HTTP. If the global state doesn't have a
token, or the HTTP endpoint fails, we'll have the user log in using an OAuth 2.0
flow.

```clj
(require '[pharmacist.data-source :as data-source]
         '[pharmacist.result :as result]
         '[pharmacist.http :as http])

(def store (atom {}))

(defn spotify-auth [prescription]
  (if-let [token (:token @store)]
    (result/success {:token token})
    (result/failure)))

(defn spotify-playlist [prescription]
  (http/http-data-source
   {:method :get
    :url "https://api.spotify.com/playlists"
    :oauth-token (-> prescription ::data-source/params :token)}))
```

Here's the prescription:

```clj
(def prescription
  {::auth {::data-source/fn #'spotify-auth}
   ::playlists {::data-source/fn #'spotify-playlists
                ::data-source/params {:token ^::data-source/dep [::auth :token]}}})
```

We can now fetch data from this. If any of the sources fail, we'll assume there
was an authentication problem, and redirect the user to login. In reality you'd
probably want to check this more rigorously.

```clj
(require '[pharmacist.prescription :as prescription]
         '[pharmacist.result :as result]
         '[cljs.core.async :as a])

(def scopes
  ["playlist-read-collaborative"
   "playlist-modify-private"
   "playlist-modify-public"
   "playlist-read-private"])

(defn token-url [state]
  (str "https://accounts.spotify.com/en/authorize"
       "?scope=" (js/encodeURIComponent (str/join " " scopes))
       "&client_id=" (:spotify-client-id state)
       "&response_type=token"
       "&state=" state
       "&redirect_uri=" (js/encodeURIComponent (router/qualified-url :location/auth))))

(let [ch (-> prescription
             prescription/fill
             (prescription/select [::playlists]))]
  (a/go-loop [res {}]
    (when-let [msg (a/<! ch)]
      (if-not (result/success? msg)
        (let [nonce (rand-str 12)]
          (swap! store assoc :auth/state nonce)
          (set! js/window.location (token-url nonce)))
        (recur (assoc res (::result/path msg) (::result/data msg)))))))
```

The `let` block will return the channel from the `go-loop`, which you can read
the full result set from if all goes well.
