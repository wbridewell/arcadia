(ns ^{:doc "Utility functions for creating and managing task
            representations for task switching"}
  arcadia.utility.tasks
  (:require [arcadia.utility [descriptors :as d]]))

(defn sr-link
  "Creates a stimulus/response link for use in tasks.
   stimulus-descriptors: a conjunctive sequence of descriptors matching
                         to desired elements in accessible content
   response: an (action) element to produce upon stimulus presentation"
  [stimulus-descriptors response]
  {:stimulus stimulus-descriptors
   ;; TODO: force response to always be a function and remove context-dependent?
   ;; otherwise, elements with the same ID will be continually generated. Until
   ;; we decide to remove the ID field, we should ensure that each element has
   ;; a unique value.
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

(defn defsr [descriptor response]
  {:descriptor descriptor :link (sr-link descriptor response)})

(defn defas
  "Takes a function that selects an interlingua element from a set, a priority
  for that function within a strategy, and optionally a value to increment the
  function's priority on each cycle to avoid element starvation."
  ([focus-selector priority]
   (defas focus-selector priority 0))
  ([focus-selector priority step]
   {:function focus-selector :priority priority :step step :base priority}))

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

(defn task
  "Creates a task representation for task-switching.
   handle: a string naming the task for subvocalization
   strategy: the attentional strategy used when the task is active
   sr-links: a sequence of sr-links associated with the task
   complete: a descriptor that when matched indicates that the task is complete"
  ([handle strategy sr-links complete]
   {:handle handle
    :initial-responses (filter :initial? sr-links)
    :stimulus-responses (remove :initial? sr-links)
    :strategy strategy
    :completion-condition complete}))

(defn build-task-library
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
   :source nil
   :type "action"})
