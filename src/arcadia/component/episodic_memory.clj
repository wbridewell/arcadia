(ns arcadia.component.episodic-memory
  "Episodic Memory takes events from accessible content and stores them
  in an episodic buffer based on value equality, so that events over time
  are merged into the same event stream.

  Event-streams have :start-age and :end-age arguments. :start refers to
  the number of cycles since the event stream began (the age of the first event).
  :end-age refers to the number of cycles since the event sream has been active
  (the age of the most recent event of the stream). This way, streams can be
  ordered based on their starting ages, or their \"ending\" ages.

  Finally, event streams end after a fixed number of cycles of non-activity,
  determined by the parameter event-lifespan. This allows for event-streams
  to continue over cycles which don't have the event actually firing.

  Focus Responsive
    No.

  Default Behavior
    -Encodes events into episodic-memory stream representations
    -Broadcasts current episode to accessible content
    -Broadcasts episode history into accessible content
    -Ages event-streams, and drops streams older than event-lifespan
     (streams can also specify the lifespan of their own events using
     the keyword :event-lifespan)

  Produces
    event-stream- in the \"episodic-memory\" world, an event-stream
                  element is put out for each event-stream that is
                  active within the current episode
    history- this is an element storing a stack of all previous episodes
             (excluding the current episode) in its :episodes argument."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [events :as events] [general :as g] [descriptors :as d]]))

;; TODO: need to create utility functions for retrieving episodes
;;       from the history element in order to encapsulate log compression.
;;

;; HACK: this is an arbitrary value, based on a complete guess.
;;       Ideally, we need a value which is based on psychological
;;       data, if that's a thing that exists.
(def ^:parameter event-lifespan 10)
(def ^:parameter ^:required descriptors "a sequence of descriptors (required)" nil)

(defn- episode
  "Create an episode for the current context of event-streams"
  [component]
  {:name "episode"
   :arguments {:context @(:event-streams component)}
   :type "instance"
   :world "episodic-memory"
   :source component})

(defn- age
  "Increase the age of the event-stream by one."
  [event-stream]
  (update-in event-stream [:arguments]
             #(update (update % :end-age inc) :start-age inc)))

(defn- ended?
  "Return true if the event-stream has aged past its lifespan,
  using the component :event-lifespan as a default. If the event's
  :event-lifespan is set to nil, then the event is treated as ongoing."
  [component event-stream]
  ;; NOTE: we need to add code to clip fluents. If event-lifespan is
  ;;       set to nil (but not just missing), the event should last until
  ;;       its fluent is clipped. If we need this functionality, we need
  ;;       a way to specify which events mark the beginning and end of fluents.
  (when-not (and (-> event-stream :arguments (contains? :event-lifespan))
                 (-> event-stream :arguments :event-lifespan nil?))
    (> (-> event-stream :arguments :end-age)
       (or (-> event-stream :arguments :event-lifespan)
           (:event-lifespan component)))))

(defn- new-episode?
  "Return true if some event-stream has changed from the previous episode to the present."
  [component]
  (or (empty? @(:episodes component))
      (let [prev (-> @(:episodes component) first :arguments :context)]
        ;;; XXX: seems like a bug.
        (not (every? (fn [e] (some #(events/event-stream-equals % e (:descriptors component))
                                   prev))
                     @(:event-streams component)))
        (not (every? (fn [e] (some #(events/event-stream-equals % e (:descriptors component))
                                   @(:event-streams component)))
                     prev)))))

(defn- history [component]
  {:name "history"
   :arguments {:episodes @(:episodes component)}
   :type "instance"
   :world "episodic-memory"
   :source component})

(defrecord EpisodicMemory [event-streams episodes descriptors event-lifespan] Component
  (receive-focus
   [component focus content]
   ;; increase the age of all event-streams, removing any which
   ;; have aged beyond event-lifespan
   (reset! (:event-streams component)
           (remove (partial ended? component)
                  (map age @(:event-streams component))))
   (reset! (:episodes component)
           (map (fn [e] (update-in e [:arguments :context] (partial map age))) @(:episodes component)))

   ;; if there are new events, encode them as event-streams,
   ;; and merge them into existing event-streams
   (when-let [new-events (seq (map #(events/event-stream % component content)
                                   (d/filter-elements content :name "event" :world nil)))]
     ;; update the event-streams
     (reset! (:event-streams component)
             ;; get rid of old versions of the new events
             (g/distinctp #(events/event-stream-equals %1 %2 (:descriptors component))
                          (concat new-events @(:event-streams component))))

     ;; if the state of event-streams has changed at all, create a new episode
     (when (new-episode? component)
       (swap! (:episodes component) conj (episode component)))))

  (deliver-result
   [component]
   (into #{(history component)} @(:event-streams component))))

(defmethod print-method EpisodicMemory [comp ^java.io.Writer w]
  (.write w (format "EpisodicMemory{}")))

(defn start
 "Start the EpisodicMemory component. By default, retain events from
  the past [default-event-lifespan] cycles. Optionally, models can choose
  an arbitrary lifespan. "
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->EpisodicMemory (atom nil) (atom nil) (:descriptors p) (:event-lifespan p))))
