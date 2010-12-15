;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns lamina.core.seq
  (:use
    [lamina.core.channel]
    [clojure.contrib.generic.functor])
  (:require
    [lamina.core.observable :as o]
    [lamina.core.queue :as q])
  (:import
    [java.util.concurrent TimeoutException]
    [lamina.core.queue EventQueue]
    [lamina.core.channel Channel]))

;;;

(defn lazy-channel-seq
  "Creates a lazy-seq which consumes messages from the channel.  Only elements
   which are realized will be consumes.

   (take 1 (lazy-channel-seq ch)) will only take a single message from the channel,
   and no more.  If there are no messages in the channel, execution will halt until
   a message is enqueued.

   'timeout' controls how long (in ms) the sequence will wait for each element.  If
   the timeout is exceeded or the channel is closed, the sequence will end.  By default,
   the sequence will never time out."
  ([ch]
     (lazy-channel-seq ch -1))
  ([ch timeout]
     (let [timeout-fn (if (fn? timeout)
			timeout
			(constantly timeout))]
       (lazy-seq
	 (when-not (closed? ch)
	   (let [value (promise)]
	     (receive (poll {:ch ch} (timeout-fn))
	       #(deliver value
		  (when (first %)
		    [(second %)])))
	     (let [val @value]
	       (when (and val
		       (or
			 (not (closed? ch))
			 (not (nil? (first val)))))
		 (concat val (lazy-channel-seq ch timeout-fn))))))))))

(defn channel-seq
  "Creates a non-lazy sequence which consumes all messages from the channel within the next
   'timeout' milliseconds.  A timeout of 0, which is the default, will only consume messages
   currently within the channel.

   This call is synchronous, and will hang the thread until the timeout is reached or the channel
   is closed."
  ([ch]
     (channel-seq ch 0))
  ([ch timeout]
     (doall
       (lazy-channel-seq ch
	 (if (neg? timeout)
	   (constantly timeout)
	   (let [t0 (System/currentTimeMillis)]
	     #(max 0 (- timeout (- (System/currentTimeMillis) t0)))))))))

(defn wait-for-message
  "Synchronously consumes a single message from a channel.  If no message is received within the
   timeout, a java.util.concurrent.TimeoutException is thrown.  By default, this function will
   not time out."
  ([ch]
     (wait-for-message ch -1))
  ([ch timeout]
     (let [val (-> ch queue (q/dequeue ::none))]
       (if-not (= ::none val)
	 val
	 (let [result (promise)]
	  (receive (poll {:ch ch} timeout) #(deliver result %))
	  (if-let [result @result]
	    (second result)
	    (throw (TimeoutException. "Timed out waiting for message from channel."))))))))
;;;

(defn- create-temp-subscription [source key true-handler false-handler]
  (let [latch (ref false)
	monitor (Object.)]
    (o/subscribe source
      {key
       (o/observer
	 (fn [msgs]
	   (if @latch
	     (locking monitor
	       (true-handler msgs))
	     (when false-handler
	       (false-handler msgs)))))})
    [latch monitor]))

(defn- sample-queue [ch latch finalizer]
  (let [q (-> ch ^EventQueue queue .q)]
    (let [msgs (dosync
		 (ensure q)
		 (let [msgs @q]
		   (finalizer q)
		   (ref-set latch true)
		   msgs))]
      msgs)))

;;TODO: these two functions are very similar, but subtly different - factor out the commonalities
(defn receive-all
  "Adds one or more callbacks which will receive all messages from the channel."
  [ch & callbacks]
  (cond
    (closed? ch)
    false

    (constant-channel? ch)
    (apply receive ch callbacks)

    :else
    (let [distributor (-> ch queue q/distributor)
	  observers (zipmap
		      callbacks
		      (map
			(fn [f] (o/observer #(doseq [m %] (f m))))
			callbacks))
	  send-to-callbacks (fn [msgs]
			      (doseq [msg msgs]
				(doseq [c callbacks]
				  (c msg))))]
      (let [[latch monitor] (create-temp-subscription
			      distributor
			      (first callbacks)
			      send-to-callbacks
			      #(-> ch queue (q/enqueue %)))]
	(locking monitor
	  (send-to-callbacks
	    (sample-queue ch latch
	      #(ref-set % clojure.lang.PersistentQueue/EMPTY)))
	  (o/subscribe distributor observers))
	true))))

(defn siphon
  [source destination-function-map]
  (cond
    (closed? source)
    false

    (constant-channel? source)
    (do
      (receive source
	#(doseq [[dst f] destination-function-map]
	   (let [msg (first (f %))]
	     (enqueue dst msg))))
      true)

    :else
    (let [distributor (-> source queue q/distributor)
	  send-to-destinations (fn [msgs]
				 (doseq [[dst f] destination-function-map]
				   (apply enqueue dst (f msgs))))]
      (let [[latch monitor] (create-temp-subscription
			      distributor
			      (-> destination-function-map ffirst queue q/source)
			      send-to-destinations
			      #(-> source queue (q/enqueue %)))]
	(locking monitor
	  (send-to-destinations
	    (sample-queue source latch
	      #(ref-set % clojure.lang.PersistentQueue/EMPTY)))
	  (o/siphon
	    distributor
	    (into {} (map (fn [[ch f]] [(-> ch queue q/source) f]) destination-function-map))))
	true))))

;;;

(defn fork
  ([ch]
     (first (fork 1 ch)))
  ([n ch]
     (cond
       (closed? ch)
       (repeat n ch)

       (constant-channel? ch)
       (repeat n ch)

       :else
       (let [qs (q/copy-queue (queue ch) (repeat n identity))]
	 (map #(Channel. nil-channel %) qs)))))

(defn map* [f ch]
  (let [ch* (channel)]
    (siphon ch {ch* #(map f %)})
    ch*))

(defn filter* [f ch]
  (let [ch* (channel)]
    (siphon ch {ch* #(filter f %)})
    ch*))
