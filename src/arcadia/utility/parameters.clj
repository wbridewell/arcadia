(ns arcadia.utility.parameters
  ^{:doc "Supports establishing parameters with default values in a namespace."}
  (:require [arcadia.utility.general :as general]
            [arcadia.utility.model :as model]
            clojure.string))

(defn default-parameters
  "Returns a map of the form {:name value} that includes the parameters and
   default values from current-ns."
  [current-ns]
  (into {}
    (map #(vector (keyword (:name (meta %))) (var-get %))
      (filter #(:parameter (meta %))
              (concat (vals (ns-refers current-ns))
                      (vals (ns-interns current-ns)))))))

(defn required-parameter-keys
  "Returns a sequence of the keywords for the required parameters in current-ns."
  [current-ns]
  (doall
    (map #(keyword (:name (meta %)))
         (filter #(and (:required (meta %)) (:parameter (meta %)))
                 (concat (vals (ns-refers current-ns))
                         (vals (ns-interns current-ns)))))))

(defn merge-parameter-maps
  "Takes a map of base-parameters and merges them with those in new-parameters.
   Signals an error if the new parameter keys are not already in the base map."
  [new-parameters current-ns]
  (let [base-parameters (default-parameters current-ns)
        err (doall (remove nil?
                         (map #(if (contains? base-parameters %) nil %)
                              (keys new-parameters))))]
    ;; enforce the use of only those parameters supported by the current
    ;; namespace. this enables us to catch some spelling errors that could
    ;; lead to difficult to trace bugs.
    (if (seq err)
      (throw (IllegalArgumentException.
              (model/error-string
               (str (last (clojure.string/split (name (ns-name current-ns)) #"\."))
                    " has unexpected parameters: " (pr-str err)
                    "\nThis might be caused by a parameter name with a missing "
                    "parameter value in your model file."))))
      (general/nested-merge base-parameters new-parameters))))

(defn missing-required-parameters
  "Returns required parameters in current-ns that are not present in the
   parameter-map or have nil values."
  [parameter-map current-ns]
  (doall
    (remove nil?
          (map #(if (and (contains? parameter-map %)
                         (get parameter-map %))
                 nil
                 %)
               (required-parameter-keys current-ns)))))

(defn merge-parameters-in-ns
  "Takes a map of parameters and merges them with the default parameters
   of the component specified by the supplied namespace."
  [parameter-map current-ns]
  (let [err (missing-required-parameters parameter-map current-ns)]
    ;; enforce the presence of non-nil values for required parameters.
    (if (seq err)
      (throw (IllegalArgumentException.
              (str (last (clojure.string/split (name (ns-name current-ns)) #"\."))
                   " is missing required parameters: " (pr-str err))))
      (merge-parameter-maps parameter-map current-ns))))

(defmacro merge-parameters
  "Takes a map of parameters and merges them with the default parameters
   of the current namespace."
  [parameter-map]
  `(merge-parameters-in-ns ~parameter-map ~*ns*))
