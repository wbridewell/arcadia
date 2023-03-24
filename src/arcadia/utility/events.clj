(ns ^{:doc "Utility functions for event matching, streaming, and storage"}
 arcadia.utility.events
  (:require [arcadia.utility [general :as g] [descriptors :as d]
             [relations :refer (to-string)]]))

(defn event-stream-equals
  "Tests for equality between event-streams"
  [e1 e2 descriptors]
  (and (= (:name e1) (:name e2) "event-stream")
       (= (:world e1) (:world e2))
       (= (:event-name (:arguments e1)) (:event-name (:arguments e2)))
       ;; all of the objects are the same (use labels to avoid identity mistakes)
       (and (-> e1 :arguments :objects some?)
            (-> e2 :arguments :objects some?)
            (= (set (map #(d/get-label % descriptors) (:objects (:arguments e1))))
               (set (map #(d/get-label % descriptors) (:objects (:arguments e2))))))))

(defn event-str
  "Returns a string representing the name of the event predicated over its objects."
  [e object-descriptors]
  (if (-> e :arguments :event-name (= "relation-update"))
    (-> e :arguments :new first
        (to-string true true)
        (#(str "relation-update(" % ")")))
    (str (-> e :arguments :event-name)
         "("
         (apply str (interpose ", " (map #(d/get-label % object-descriptors) (-> e :arguments :objects))))
         ")")))

(defn moment-equals
  "Tests for quality between two moments"
  [m1 m2 descriptors]
  (and (every? (fn [e] (some #(event-stream-equals e % descriptors)
                             (-> m2 :arguments :context)))
               (-> m1 :arguments :context))
       (every? (fn [e] (some #(event-stream-equals e % descriptors)
                             (-> m1 :arguments :context)))
               (-> m2 :arguments :context))))


(defn event-stream
  "Creates an episodic memory version of the event, with an encoded end-age and start-age."
  [event component content]
  (let [e (merge event {:name "event-stream"
                        :type "instance"
                        :world "episodic-memory"})
        ;; find the start age of any equal event in episodic-memory,
        ;; using 0 if none exist
        starting-period (or (->> component :event-streams deref
                                 (g/find-first #(event-stream-equals e % (:descriptors component)))
                                 :arguments :start-age)
                            0)]
    (update-in e [:arguments] #(assoc % :end-age 0 :start-age starting-period))))

(defn older?
  "Returns true if event1 is older than event2."
  [event1 event2]
  (> (-> event1 :arguments :start-age)
     (-> event2 :arguments :start-age)))



(defn reduce-moment
  "Returns a moment containing only events which match the descriptor d."
  [d moment]
  (update-in moment [:arguments :context] (partial d/filter-matches d)))


(defn reduce-history
  "Returns a sequence of episodes only containing only events matching the descriptor d.
  object-descriptors is a collection of labeled object descriptors used to test event equality."
  [d episodes object-descriptors]
  (->> episodes
       (map (partial reduce-moment d))
       (g/dedupe-p #(moment-equals %1 %2 object-descriptors))))

(defn remove-duplicate-events
  "Returns m2, with all of the durational events from m1 removed."
  [m1 m2 object-descriptors]
  (update-in m2 [:arguments :context]
             (partial remove (fn [e] (some #(event-stream-equals e % object-descriptors)
                                           (-> m1 :arguments :context))))))

(defn remove-durational
  "Given a seq of episodes (history) and some object-descriptors, converts all
  durational events into punctative events, where the temporal location is defined
  by the event's starting time."
  [history object-descriptors]
  (reverse
   (cons (last history)
         (map #(remove-duplicate-events (first %) (second %) object-descriptors)
              (partition 2 1 (reverse history))))))
