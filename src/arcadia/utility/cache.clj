(ns
  ^{:doc "Supports caching information between model runs."}
  arcadia.utility.cache)

(def ^:private cache (atom {}))

(defn get-cache
  "Retrieves an item from the cache."
  [item-key]
  (get @cache item-key))

(defn reset-cache!
  "Sets an item in the cache to the specified value."
  [item-key value]
  (swap! cache assoc item-key value))

(defn swap-cache!
  "Sets an item in the cache to the result of calling a function on it with the specified
  arguments (similar to swap!)."
  [item-key fname & args]
  (swap! cache assoc item-key
         (apply fname (cons (get-cache item-key) args))))