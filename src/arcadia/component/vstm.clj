(ns arcadia.component.vstm
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            (arcadia.utility [descriptors :as d] [general :as g]
                             [objects :as obj])
            [arcadia.utility.geometry :as geo]
            [clojure.set :refer [difference]]))

;;; Visual short-term memory

;; Stores object files with associated visual property information.
;; Location information is handled separately to reflect the known
;; disassociativity between spatial and featural processing.
;; See work on apparent motion and motion correspondence, for
;; instance, and notice that there is evidence that people resolve
;; spatial information of proto-objects before they resolve objects.
;;
;; UPDATE 9/26
;; We're merging vstm (which stores in object files in vstm) with
;; novel-object-vision (which determines if a newly produced object
;; file matches on already in vstm). Note that this is a return to
;; the way the code used to work.

;; NOTE: Look at how size is used and think about what we can do instead.
;; Usually objects will be of roughly the same size, but in the change
;; detection task, size can be manipulated dramatically. Without collecting
;; scene statistics (e.g., various sizes of things and how those change), what
;; can be done? It's unclear what sort of general statements one might make
;; about a scene that would be helpful for identity judgments. What are the
;; expectations that develop? Is this too challenging to attempt beyond a task
;; specific model?

;; NOTE: Timestamps are generally a terrible idea, especially when age can be
;; represented by position in sequence. However, VSTM reports its contents as
;; separate elements, and age of encoding is used in various places. As a
;; result, VSTM elements currently have timestamps. This will almost certainly
;; change in the future.

;; NOTE: Track 4 objects, with a slot left over for processing new fixations.
(def ^:parameter vstm-size
  "The number of objects that can be stored simultaneously."
  4)

(def ^:parameter diff-size-comparator
  "function that determines whether two objects differ in size"
  nil)

(def ^:parameter diff-loc-comparator
  "function that determines whether two objects differ in location"
  nil)

(def ^:parameter forget-untracked-first?
  "When we run out of space in VSTM, should we prioritize forgetting untracked
   objects to make room for new objects?" false)

;; Various values used to determine how much a segment from one frame
;; must overlap with the segment in another frame to be considered idential.
;;
;; Looking at different data sets for multiple object tracking suggests that
;; the amount of overlap can be fairly small since small, fast moving objects
;; are tracked reasonably well.
;(def ^:private intersection-similarity-threshold 0.5) ;; must intersect by at least half.
;(def ^:private intersection-similarity-threshold 0.25) ;; must intersect by at least one-quarter.
(def ^:private intersection-similarity-threshold 0.05) ;; very minimal intersection required (speed issues)

(defn- reasonable-intersection?
  "True if the area of the rectangle representing a region of overlap is larger
  than the needed area."
  [overlap overlap-needed]
  (and overlap
       (> (* (:width overlap) (:height overlap))
          overlap-needed)))

(defn- same-slot?
  "True if the two objects occupy the same VSTM slot."
  [element0 element1]
  (= (obj/get-slot element0) (obj/get-slot element1)))

(defn- same-object?
  "Compares the focus to the object based on size and location."
  [focus object locations
   {diff-size-comparator :diff-size-comparator diff-loc-comparator :diff-loc-comparator}]
  ;; NOTE: Object equality is based on a variety of features, some of which are
  ;; specific to particular videos or tasks.
  ;;
  ;; In many cases, reasonable-intersection? may be the only necessary
  ;; comparison function, but similar-size?, similar-colors? or any other task
  ;; specific form of comparison could be included here as well.
  (let [rect0 (obj/get-estimated-region focus locations)
        rect1 (obj/get-estimated-region object locations)
        ;;Check whether each object even has a location in space
        located?0 (geo/positioned? rect0)
        located?1 (geo/positioned? rect1)
        overlap (and located?0 located?1 (geo/intersection rect0 rect1))]
    (and rect0 rect1
         (if diff-size-comparator
           (diff-size-comparator focus object)
           (obj/similar-size-regions? rect0 rect1))
         (if (or (not located?0) (not located?1))
           (= located?0 located?1)
           (or (and (reasonable-intersection?
                     overlap (* (geo/area rect0) intersection-similarity-threshold))
                    (reasonable-intersection?
                     overlap (* (geo/area rect1) intersection-similarity-threshold)))
               (and diff-loc-comparator (diff-loc-comparator focus object))
               ;;Lastly, handle the special case where an object just disappeared
               ;;(there's no corresponding object location) but we don't kow it yet.
               (and (same-slot? focus object)
                    (-> focus :arguments :tracked?) (-> object :arguments :tracked?)))))))

(defn- get-new-object-slot-number
  "Returns the slot number that would be used if a new VSTM object were being
  allocated."
  [objects {size :vstm-size forget-untracked-first? :forget-untracked-first?}]
  (or (and (< (count objects) size)
           (apply min (difference (set (range 1 (inc size)))
                                  (set (map #(-> % :arguments :slot) objects)))))
      (when forget-untracked-first?
        (-> (sort-by (juxt #(-> % :arguments :tracked?) #(-> % :arguments :timestamp))
                     (fn [[t1? time1] [t2? time2]]
                       (or (and t2? (not t1?))
                           (and (= t1? t2?) (< time1 time2))))
                     objects)
            first :arguments :slot))
      (-> (sort-by #(-> % :arguments :timestamp) < objects) first :arguments :slot)))

(defn- remember-object
  [obj old-object new-slot timestamp]
  {:name "object"
   :arguments (assoc (:arguments obj)
                     :slot (or (-> old-object :arguments :slot) new-slot)
                     :timestamp timestamp)
   :world "vstm"
   :type "instance"})

(defn- update-object-status
  "Updates an object's :tracked? field."
  [obj tracked?]
  (assoc obj
         :arguments (assoc (:arguments obj)
                           :tracked? (and tracked? (-> obj :arguments :tracked?)))))

(defn- update-VSTM-status
  "For each VSTM object, updates the :tracked? field, which indicates whether
   object-locator is tracking this object. Also sets :new-in-vstm? to false, since
   all of these objects have been in VSTM for more than one cycle."
  [objs lost-objects locs]
  (map #(update-object-status
         %
         (and (or (empty? lost-objects) (not (lost-objects %)))
              (some? (obj/get-location % locs))))
       objs))

(defn- make-old-relation
  [current previous origin]
  {:name "visual-equality"
   :arguments {:new current :old previous :object current :origin origin}
   :world nil
   :type "relation"})

(defn- make-new-relation
  [current origin]
  {:name "visual-new-object"
   :arguments {:object current :origin origin}
   :world nil
   :type "event"})

;; Look for an object in the focus.  If there is one, compare it to existing
;; objects in vstm to see if it's a match. Then update vstm and generate
;; a visual-equality or visual-new-object report.
;;
;; There are six possible outcomes here, given a new object with a slot value:
;; Matched nothing/matched the object with our slot value/matched an object with a
;; different slot value.
;;                          AND
;; The vstm object with our slot value is active/it is inactive.
;;
;; (Also, if there's a fixation in focus and it isn't to an existing object,
;; update our list of objects in vstm to indiate we are beginning to track a
;; new object, which will be marked as :inactive until it is encoded as above.)
(defrecord VSTM [buffer counter parameters]
  Component
  (receive-focus
   [component focus content]
   (let [ ;; Is the focus on an object?  If so, retrieve any vstm object at the same
          ;; location (loc-object) and also look for a vstm object that matches the
          ;; focus (old-object).
          ;; NOTE: vstm isn't allowed to have two objects at the same location.
          ;; So if there's a loc-object and it isn't the same as old-object, loc-object
          ;; loses its location--it gets marked as not :tracked? until we find it again.
         focus-object (when (d/element-matches? focus :name "object"
                                                :world #(not (= % "vstm"))
                                                :origin #(not (= % "vstm")))
                        focus)

         ;;Put any object with the same slot as focus-object at the front
         objects (sort-by #(same-slot? % focus-object) #(false? %2)
                          (obj/get-vstm-objects content))

         loc-objects (when focus-object
                       (filter #(obj/same-region? % focus-object content) objects))

         old-objects (when focus-object
                       (filter #(same-object? focus-object % content parameters)
                                objects))

         old-object (or (g/find-first #((set old-objects) %) loc-objects)
                        (first old-objects))

         new-object (and focus-object
                         (remember-object focus-object old-object
                                          (get-new-object-slot-number
                                           objects parameters)
                                          (swap! counter inc)))

         updated-objs (update-VSTM-status
                       (remove #(same-slot? % new-object) objects)
                       ;; lost objects
                       ;; if there's a non-matching object at the same location,
                       ;; i.e., it's in loc-objects but NOT old-objects, then
                       ;; lose that one.
                       (clojure.set/difference (set loc-objects) (set old-objects))
                       content)]

     (reset! (:buffer component)
             (cond
               focus-object
               (conj
                updated-objs
                new-object
                (or (and old-object (make-old-relation new-object old-object
                                                       focus-object))
                    (and focus-object (make-new-relation new-object focus-object))))

               (and (= (:name focus) "memorize") (= (:name (:element (:arguments focus))) "number-report"))
               []

               :else
               updated-objs))))

  (deliver-result
   [component]
   @buffer))

(defmethod print-method VSTM [comp ^java.io.Writer w]
  (.write w (format "VSTM{}")))

;;The optional arguments are functions. When two objects are compared to
;;determine identity, if their sizes are different but diff-size-comparator
;;is true, they still pass as the same object.
(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (VSTM. (atom nil) (atom 0) p)))
