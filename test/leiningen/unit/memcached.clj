(ns leiningen.unit.memcached
  (:require [leiningen.memcached :refer :all]
            [midje.sweet :refer :all])
  (:import [net.spy.memcached
            AddrUtil
            ClientMode
            MemcachedClient
            BinaryConnectionFactory]
           [net.spy.memcached.ops ConfigurationType]
           [net.spy.memcached.transcoders SerializingTranscoder Transcoder]
           [java.net InetSocketAddress]
           [java.util.concurrent TimeUnit]))

(def ^Transcoder default_transcoder (.getDefaultTranscoder (BinaryConnectionFactory.)))

(defn create-client
  []
  (MemcachedClient. (list (InetSocketAddress. "localhost" 11211))))

(defn create-binary-client
  []
  (MemcachedClient. (BinaryConnectionFactory. ClientMode/Static) (list (InetSocketAddress. "localhost" 11211))))

(defn store-value
  [client key value]
  (-> client
      (.set key 12000 value (SerializingTranscoder.))
      (.get 10000 TimeUnit/MILLISECONDS)))


(defn store-value-binary
  [client key value]
  (-> client
      (.set key 12000 value default_transcoder)
      (.get 10000 TimeUnit/MILLISECONDS)))

(defn get-value
  [client key]
  (-> client
      (.asyncGet key)
      (.get 10000 TimeUnit/MILLISECONDS)))

(defn get-value-binary
  [client key]
  (-> client
      (.asyncGet key default_transcoder)
      (.get 10000 TimeUnit/MILLISECONDS)))

(defn get-value-and-touch
  [client key]
  (-> client
      (.asyncGetAndTouch key 10000 default_transcoder)
      (.get 10000 TimeUnit/MILLISECONDS)
      .getValue))

(fact-group
 :unit
 :ascii

 (with-state-changes
   [(before :facts (do (start-memcached {})))
    (after :facts (do (stop-memcached)))]

  (fact "starts up cluster - with no error")

  (fact "Can store a value"
        (store-value (create-client) "startup" "value") => true)

  (fact "Can retreive a stored value"
        (let [client (create-client)]
          (store-value client "key123" "my value")
          (get-value client "key123")  => "my value"))))

(fact-group
 :unit
 :ascii

 (with-state-changes
   [(before :facts (do (start-memcached {:memcached {:binary true}})))
    (after :facts (do (stop-memcached)))]

  (fact "starts up cluster - with no error")

  (fact "Can store a value"
        (store-value-binary (create-binary-client) "startup" "value") => true)

  (fact "Can retreive a stored value"
        (let [client (create-binary-client)]
          (store-value-binary client "key123" "my value")
          (get-value-binary client "key123")  => "my value"))

  (fact "Can retreive and touch a stored value"
        (let [client (create-binary-client)]
          (store-value-binary client "key123" "my value")
          (get-value-and-touch client "key123")  => "my value"))))
