(defproject lein-memcached "0.1.0-SNAPSHOT"
  :description "Creates an in-memory memcached server based on jmemcached"
  :url "https://github.com/mdaley/lein-memcached"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.thimbleware.jmemcached/jmemcached-core "1.0.0"
                  :exclusions [org.slf4j/slf4j-api]]
                 [ch.qos.logback/logback-classic "1.1.2"]]
  :scm {:name "git"
        :url "https://github.com/mdaley/lein-memcached"}
  :eval-in-leiningen true
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]]

  :memcached {:host "localhost"
              :port 11211
              :max-items 100
              :max-bytes 100000
              :verbose true
              :binary false})
