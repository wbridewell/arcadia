(ns arcadia.component.working-memory
  "Stores an element in working memory when there is a request to memorize it.
  Elements in working memory are reported in accessible content on every
  cycle.

  Focus Responsive
    * memorize
    * memory-update

  Remember a new element or update the contents of an element already in
  working memory.

  Default Behavior
  Keep working memory element representations up to date with their
  representations in visual short-term memory if there is a correspondence.
  Report elements in working memory to accessible content in a world called
  \"working-memory\".

  Produces
   * elements of various sorts
       each element is essentially identical to its original form with the
       exception that it is moved to the world \"working-memory\" to avoid
       confusion with non-memory elements.

  NOTE: In this implementation, working memory is unlimited in size and
  duration. These properties will change in the future. Specifically,
  the capacity will likely be reduced to four amodal elements. The durability
  will be influenced by rehearsal, but the exact direction is unclear.

  NOTE: There is evidence that working memory is organized hierarchically and
  we may pursue a similar organization. For further information, see
  D’Esposito, M., & Postle, B. R. (2015). The Cognitive Neuroscience of
  Working Memory. Annual Review of Psychology, 66(1), 115–142.
  http://doi.org/10.1146/annurev-psych-010814-015031"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [clojure.walk :as walk]
            [clojure.data.generators :as dgen]
            (arcadia.utility [general :as g]
                             [objects :as obj]
                             [possible-relations :as prel])))

;; NOTE: We will likely want to move toward a consolidation approach where
;; elements in working memory only approximate their original content. Most
;; likely this will involve converting non-conceptual content into some sort
;; of approach to reproducing that non-conceptual content. Basically, we need
;; to simulate the ability to generate lower-level, sensory-motor
;; representations from memory.

;; Remove these tags recursively for memory consolidation
(def ^:private dissoc-keys (list))

;; Default values for activation parameters
;; **Has no effect by default**
(def ^:parameter decay-rate 0.0)
(def ^:parameter refresh-rate 0.0)
(def ^:parameter max-activation 1.0)
(def ^:parameter min-activation 0.0)
(def ^:parameter randomness 0.0)
(def ^:parameter determiistic? true)

(defn- consolidate
  "Consolidate an element by recursively removing its :world key"
  [element]
  (apply g/recursive-dissoc (conj dissoc-keys element)))

(defn- decay-activation
  "Recalculate the activation of an element after exponential decay"
  [activation params]
  (max (- activation (* activation (g/perturb (:decay-rate params)
                                              (:randomness params))))
       (:min-activation params)))

(defn- decay
  "If an element in WM has an activation, exponentially decay its
   activation. If the new activation of the element is below the
   min-activation, return nil. Otherwise, return the unchanged element"
  [element params]
  (if-let [activation (-> element meta :activation)]
    (vary-meta element assoc :activation
               (decay-activation activation params))
    element))

(defn- refresh-activation
  "Recalculate the activation of an element after exponential refresh"
  [activation params]
  (min (+ activation (* (- (:max-activation params) activation)
                        (g/perturb (:refresh-rate params)
                                   (:randomness params))))
       (:max-activation params)))

(defn- refresh
  "If an element in WM has an activation, exponentially refresh its
   activation, upper bounding by max-activation."
  [element params]
  (if-let [activation (-> element meta :activation)]
    (vary-meta element assoc :activation
               (refresh-activation activation params))
    element))

(defn- retrieve
  "Retrieve element non-deterministically using activation
   as probability of retrieval. Elements with no activation
   attribute have a 100% probability of retrieval"
  [element params]
  (let [activation (if (-> element meta :activation)
                     (g/normalize (-> element meta :activation)
                                  (:min-activation params)
                                  (:max-activation params))
                     1.0)]
    (when (>= activation (dgen/double))
      element)))

;; HACK:
;; Used for a model in progress.
(defn- remember-possibility [possibility]
  {:name (:name possibility)
   :arguments (:arguments possibility)
   :world "working-memory" 
   :type "possibility"})

(defn- update-in-wm
  "Replace an old value in an element with a new value and change its ID."
  ([item old-val new-val] (update-in-wm item old-val new-val true))
  ([item old-val new-val consolidate?]
   (let [wm-old-val (if consolidate? (consolidate old-val) old-val)
         wm-new-val (if consolidate? (consolidate new-val) new-val)]
     (walk/postwalk-replace {wm-old-val wm-new-val} item))))

(defn- remember-generic
  "Create a working memory version of the element, removing the element's
   :world keys."
  [element source]
  (vary-meta
   (merge (consolidate element) {:world "working-memory"})
   assoc :activation (or (-> element meta :activation)
                         (:max-activation (:params source)))))

(defn- get-updated-element
  "Given the current equality-relations, update the element if
   it was recently changed"
  [e equality-relations]
  (or (some->> equality-relations
              (g/find-first #(= (:old (:arguments %)) e))
              :arguments :new)
      e))

(defn- memory-equality
  "Creates a relationship between an old working-memory item and
   its new version with updated VSTM objects"
  [old new reason]
  {:name "memory-equality"
   :arguments {:old old :new new :reason reason}
   :type "relation"
   :world "working-memory"})

(defn- memorization-feedback
  "Creates an interlingua element showing the success of a memorization.
   Provides a copy of the :old element and the :new WM version for other
   components to use."
  [old new]
  {:name "new-memory"
   :arguments {:old old :new new}
   :type "relation"
   :world nil})

(defn- get-refreshed-element
  "Refresh a WM if it's the focus of attention, or if
   it's directly related to the focus of attention"
  [focus working-memory equality-relations]
  (if (some #{(get-updated-element focus equality-relations)} working-memory)
    (g/find-in focus working-memory)
    (g/find-first (->> focus :arguments vals
                       (map #(get-updated-element % equality-relations))
                       set)
                  working-memory)))

(defn- update-activations
  "If an element is being maintained (refreshed, vocalized, etc), refresh its
   activation. Decay the activation of all other elements in working memory"
  [focus wm equality-relations params]
  (if-let [refreshed-element (get-refreshed-element focus @wm equality-relations)]
    ;; if an element is being maintained/refreshed/focused on,
    ;; refresh if and decay all other elements
    (reset! wm (conj (map #(decay % params)
                          (remove #{refreshed-element} @wm))
                     (refresh refreshed-element params)))
    ;; otherwise decay all elements
    (reset! wm (map #(decay % params) @wm))))


(defrecord WorkingMemory [buffer new-memories equality-relations params]
  Component
  (receive-focus
   [component focus content]
   ;; clear all new-memories from last cycle
   (reset! (:new-memories component) nil)

   ;; Refresh an attended element in WM and decay activation
   ;; in all other elements still in working memory
   (when (and @(:buffer component)
              (:update-activations? (:params component)))
     (update-activations focus (:buffer component)
                         @(:equality-relations component) (:params component)))
   (cond
     ;; when storing an object (or objects), replace its
     ;; source with the working memory component.
     ;; Also, output an element matching the to-be-remembered
     ;; element with its new version in working-memory
     (and (= (:name focus) "memorize")
          (= (:type focus) "action")
          ;; ongoing memorization requests don't encode anything new,
          ;; they just increase the activation of the element
          (-> focus :arguments :ongoing? not)
          ;; there is a separate, capacity limited notion of working memory for tasks.
          (not= (-> focus :arguments :element :name) "task"))
     (let [old (or (:elements (:arguments focus))
                   (list (:element (:arguments focus))))
           new (map #(remember-generic % component) old)]
       (reset! (:new-memories component) (map #(memorization-feedback %1 %2) old new))
       (reset! (:equality-relations component)
               (list (memory-equality nil (list new) focus)))
       (swap! (:buffer component) concat new))


     (and (= (:name focus) "memory-update")
          (= (:type focus) "action"))
      ;; HACK:
      ;; Used for a model in progress.
      ;; Could be a list of possibilities with the same relation. This would be a list rather than a single
      ;; element because we can include both the possibility and the negated possibility if we're unsure.
      ;; Assume such a structure contains an :elements field and a :relation field at the top level.
     (if (= (:type (first (:elements (:arguments focus)))) "possibility")
       (reset! (:buffer component)
               (concat (remove #(prel/same-possible-relations? % (first (:elements (:arguments focus))))
                               @(:buffer component))
                       (map #(remember-possibility %) (:elements (:arguments focus)))))

       ;; Update the items in working memory, and create
       ;; a memory-equality to broadcast completion of the update
       (reset! (:buffer component)
               (let [old (map #(get-updated-element % @(:equality-relations component))
                              (g/as-seq (:old (:arguments focus))))
                     n (->> focus :arguments :new g/as-seq
                            (map #(remember-generic % component)))
                     ;; if we care about activations, make sure the new versions
                     ;; get the activations from the old elements
                     new (if (and (:update-activations? (:params component))
                                  (= (count old) (count n)))
                           (map #(vary-meta %2 assoc :activation (-> %1 (g/find-in @(:buffer component)) meta :activation))
                                old n)
                           n)]
                 (reset! (:equality-relations component)
                         (list (memory-equality old new focus)))
                 (reset! (:new-memories component) nil)
                 (concat (remove (set old) @(:buffer component))
                         new))))

     :else
     (do (reset! (:new-memories component) nil)
         (reset! (:equality-relations component) nil)))

   (comment
    ;; If a new object in vstm matches an old object, replace references to the old object
    ;; with references to the new throughout working memory and add a
    ;; "memory-equality" relation to broadcast for any updated elements
    (let [equality-relation (g/find-first #(= (:name %) "visual-equality") content)
          ;; pre-consolidate the old/new elements
          old-val (some-> equality-relation :arguments :old consolidate)
          new-val (some-> equality-relation :arguments :new consolidate)]
      (when equality-relation
        (reset! (:buffer component)
                (map #(let [new (update-in-wm % old-val new-val false)]
                        (when-not (= % new)
                          (swap! (:equality-relations component) conj
                                 (obj/memory-equality % new component)))
                        new)
                     @(:buffer component)))))))
  (deliver-result
   [component]
   (concat @(:equality-relations component)
           @(:new-memories component)
           (if (:deterministic? (:params component))
             @(:buffer component)
             (set (remove nil? (map #(retrieve % (:params component))
                                    @(:buffer component))))))))


(defmethod print-method WorkingMemory [comp ^java.io.Writer w]
  (.write w (format "WorkingMemory{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->WorkingMemory (atom nil) (atom nil) (atom nil)
                     (assoc p :update-activations?
                            (not (and (zero? (:decay-rate p))
                                      (zero? (:refresh-rate p))))))))
