(ns ^{:doc "Utility functions for creating and managing task
            representations for task switching"}
  arcadia.utility.task-sets
  (:require [arcadia.utility [descriptors :as d]]
            [arcadia.utility.attention-strategy :as att]))

(defn sr-link
  "Creates a stimulus/response link for use in tasks.
   stimulus-descriptors: a conjunctive sequence of descriptors matching
                         to desired elements in accessible content
   response: an (action) element to produce upon stimulus presentation"
  [stimulus-descriptors response]
  {:stimulus stimulus-descriptors
   :response (if (fn? response) response (constantly response))
   :context-dependent? (fn? response)})

(defn initial-response
  "Creates a stimulus that should be performed on initiating a task.
   stimulus-descriptors: a conjunctive sequence of descriptors matching
                         to desired elements in accessible content
   response: an (action) element to produce upon stimulus presentation"
  [response]
  {:initial? true
   :response response})

(defn merge-sr-links
  "Combine the old sr-links with the new ones, preferring new links if they have
  matching stimulus descriptors as the old ones."
  [new old]
  (let [new-descriptors (set (map :descriptor new))]
    (concat new (filter #(not (new-descriptors (:descriptor %))) old))))

#_{:clj-kondo/ignore [:unused-private-var]}
(defn- combine-strategies
  "Currently does complete replacement of the old strategy with the new one."
  [old new] ;TODO update weights smartly, move to support or core file
  new)

(defn- fire-sr-link
  "A helper function to obtain a response from an sr-link given stimulus
   elements and accessible content. If check-matches is true, the elements
   will be matched against the stimulus constraint. Otherwise, this constraint
   is assumed to hold."
  [sr-link elements content check-matches?]
  (and (or (not check-matches?)
           (d/matches-constraint? (:stimulus sr-link) elements))
       (apply (:response sr-link)
              (concat elements (list content))))) ;; use concat to avoid ordering bugs

(defn get-response
  "Given an sr-link, elements, and accessible content, returns a response
   if elements match the stimulus constraint or nil otherwise."
  [sr-link elements content]
  (fire-sr-link sr-link elements content true))

(defn responses
  "Given an sr-link and accessible content, returns a sequence of all
   non-nil responses of the sr-link."
  [sr-link content]
  (filter some? (map #(fire-sr-link sr-link % content false)
                  (d/get-constraint-matches (:stimulus sr-link) content))))

(defn collect-responses
  "Returns a sequence of all non-nil responses of the sr-links
   to each stimulus matched from accessible content"
  [sr-links content]
  (mapcat #(responses % content) sr-links))

(defn task-set
  "Creates a task set schema.
   handle: string naming the schema
   parameters: seq of keywords that name parameters in the strategy and sr-links
   strategy: attentional strategy
   sr-links: seq of sr-links"
  [handle strategy sr-links]
  {:handle handle
  ;;  :parameters parameters
   :initial-responses (filter :initial? sr-links)
   :stimulus-responses (remove :initial? sr-links)
   :strategy (mapv #(apply att/partial-strategy %) strategy)})

;; {:stimulus [:p descZ] :response {…} :context-dependent? false} 
;; {:p descA}
;; {:stimulus [descA descZ] :response {…} :context-dependent? false}]
;; or 
;; {:p (descA descB)}
;; {:stimulus [descA descB descZ] :response {…} :context-dependent? false}]

;; (defn replace-sr-parameters [sr-links parameter-map]
;;   (mapv #(let [[args descs] ((juxt filter replace) keyword? (:stimulus %))]
;;            (if (empty? args)
;;              %
;;              (vec (concat (flatten (map (fn [x] (get parameter-map x)) args)) descs))))
;;         sr-links))

;; [{:function :p :priority 4 :step 0 :base 4}]
;; {:p descA}
;; [{:function (strategy-tier descA) :priority 4 :step 0 :base 4}]
;; or 
;; {:p (descA descB)}
;; [{:function (strategy-tier descA descB) :priority 4 :step 0 :base 4}]

;; (defn replace-strategy-parameters
;;   [strategy parameter-map]
;;   (mapv #(if (keyword? (:function %))
;;            (if (coll? ((:function %) parameter-map))
;;              (assoc % :function (apply att/strategy-tier ((:function %) parameter-map)))
;;              (assoc % :function (att/strategy-tier ((:function %) parameter-map))))
;;            %)
;;         strategy))

;; (defn instantiate
;;   ([schema]
;;    (instantiate schema {}))
;;   ([schema parameter-map]
;;    (if (= (set (keys parameter-map)) (set (:parameters schema)))
;;      {:handle (:handle schema)
;;       :initial-responses (:initial-responses schema)
;;       :stimulus-responses (replace-sr-parameters (:stimulus-responses schema) parameter-map)
;;       :strategy (replace-strategy-parameters (:strategy schema) parameter-map)}
;;      (throw (IllegalArgumentException. 
;;              (str "parameter map does not match schema requirements."
;;                   " expected: " (:parameters schema) 
;;                   " received: " (keys parameter-map) ))))))

(defn build-library
  "Given a sequence of task schemata, create a map from the task handle to the task."
  [& tasks]
  (zipmap (map :handle tasks) tasks))

(defn subvocalize
  "Creates a subvocalization request for the word as part of
   the task specified by task-kw"
  [word task-kw]
  {:name "subvocalize"
   :arguments {:lexeme word
               :task task-kw
               :effector :articulator}
   :world nil
   :type "action"})

(defn task-configuration
  "who knows"
  ([handle task-schema]
   (task-configuration handle task-schema nil nil nil nil))
  ([handle task-schema parameters activate]
   (task-configuration handle task-schema parameters activate nil nil))
  ([handle parameters task-schema activate terminate end]
   {:handle handle
    :parameters parameters
    :task-set task-schema
    :instance-activation activate
    :instance-termination terminate
    :termination end}))