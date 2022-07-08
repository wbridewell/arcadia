(ns arcadia.component.episodic-ltm
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.display :as display]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;

(def ^:parameter ^:required episode-boundaries nil)

(defn- query-failure [query-string component]
  {:name "query-failure"
   :type "instance"
   :world "episodic-memory"
   :source component
   :arguments {:query query-string}})

(defn- recall 
  ([episode component]
   (when episode
     (assoc episode :world "episodic-memory" :source component)))
  ([episode query-string component]
   (if (and episode query-string)
     (assoc-in (recall episode component) [:arguments :query] query-string)
     (query-failure query-string component))))

;; we need to support partial matches
;;
;; we are only comparing the arguments of an episode, so we only compare 
;; the set of objects, the spatial locations, and the events. we want to 
;; support partial matches so that we can get episode completion. we want
;; to rank partial matches by amount of match. we want to return the best 
;; match along with its degree of match scaled between 0 and 1. if there 
;; are equivalently similar episodes, we want to return one sampled at random. 

;; XXX: 
;; for spatial, encode the vertical vs horizontal layout of the computer screen so that 
;; we can do a similarity match. this should probably be a set of regions. 
;; 
;; for temporal, it's hard to say what we should do. in some sense we ought to 
;; record the active event streams within an episode and then do a set-based comparison 
;; on those. minimally, we shouldn't penalize nil events so much because we want to 
;; recover expectations about future events.
;;
;; for conceptual, i think we are okay.
(defn- similarity [new-episode historical-episode]
  (/
   (+ (if (= (-> new-episode :arguments :spatial) (-> historical-episode :arguments :spatial)) 1 0)
      (if (= (-> new-episode :arguments :temporal) (-> historical-episode :arguments :temporal)) 1 0)
      (if (= (-> new-episode :arguments :conceptual) (-> historical-episode :arguments :conceptual)) 1 0))
   3))

(defn- rank-episodes [e h]
  (sort-by first > (map #(vector (similarity e %) %) h)))

(defn- matching-episode [e h]
  (let [best (rank-episodes e h)]
    ;; 0.5 is a similarity threshold for returning an episode.
    (when ((fnil > 0) (first (first best)) 0.9) 
      (display/elem (second (first best)))
      ;(display/break "Episodic LTM")
      (second (first best)))))

(defn- respond [e q h]
  ;; if we get here, we're dealing with object information
  (let [queries (-> e :arguments :conceptual)]
    ;; need to find an episode where all of these descriptors match at least one object in the episode
    (loop [c h]
      (cond (empty? c)
            nil
            (every? #(d/some-match % (-> (first c) :arguments :conceptual)) queries)
            (first c)
            :else
            (recur (rest c))))))

(defrecord EpisodicLTM [buffer history boundaries encode?]
  Component
  (receive-focus
   [component focus content]
   (display/env "Episodic LTM")
   ;; encode episodes when there is an attended event (assumed that attention maps to relevant/important)
   (when @encode?
     ;; record an episode whenever an event occurs
     (swap! (:history component) conj (d/first-element content :name "episode" :world nil :type "instance"))
     (swap! (:encode? component) not))
   (when (some #(d/descriptor-matches? % focus) (:boundaries component))
     ;; record the next episode, which will contain the event.
     (swap! (:encode? component) not))
   ;; if there is a focal query, address it, otherwise do spontaneous recall
   (if (d/element-matches? focus :name "episode" :type "instance" :world "query")
     ;; only handle queries based on objects for now. add events, timings, and locations later. 
     (reset! (:buffer component)
             (recall (respond focus (d/first-element content :name "query" :type "instance" :world "query" :key "what")
                              @(:history component))
                     "what"
                     component))
     (reset! (:buffer component)
             (recall (matching-episode (d/first-element content :name "episode" :type "instance" :world nil)
                                       @(:history component))
                     component))))
  
  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method EpisodicLTM [comp ^java.io.Writer w]
  (.write w (format "EpisodicLTM{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->EpisodicLTM (atom nil) (atom nil) (:episode-boundaries p) (atom false))))