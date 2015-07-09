# lein-memcached

A Leiningen plugin to run an in-memory instance of memcached using Thimbleware's jmemcache-daemon. Useful for testing purposes.

Does not behave correctly for expiry, only to be used to test get, add, delete and set. For binary mode the client will need to be set to ClientMode/Static to work.

## Usage

Add `[lein-memcached "0.1.0"]` to the `:plugins` vector of your project.

Start the memcached daemon when running lein by specifying it before the other tasks that you are running, for example:

    $ lein memcached run

Once the task completes, the memcached daemon will also be terminated.

If for some reason you'd like to run the plugin by itself you can invoke it like this, with any further tasks:

    $ lein memcached
    
When you want to stop it just press <kbd>Ctrl</kbd>+<kbd>C</kbd>.
   
## Configuration

There are optional pieces of configuration that control how the memcached daemon operates:

```clojure
(defproject my-project "1.0.0-SNAPSHOT"
  ...
  :plugins [[lein-memcached "0.1.0"]]
  ...
  :memcached {:port 12345 ; optional - port on which the daemon listens, default value is 11211
              :max-items  10 ; optional - max items in the cache, default value is 100
              :max-bytes 1024 ; optional - max number of bytes in the cache, default value is 100000
              :verbose true ; optional - verbose logging, default is false
              :binary false ; optional - talk binarys instead of plain text, default is false
              }
  ...
)
```

## Simple Example

Git clone this project and go to the root directory. Note the settings in `project.clj`.

Run the command:

    $ lein memcached
    
Memcached will start and you will see some logging messages.

Open a telnet session to the daemon:

    $ telnet localhost 11211
    
and execute a few memcached commands, for example:

    > version
    VERSION 0.9
    > set xyzkey 0 0 6
    > sdfsdf
    STORED
    > get xyzkey
    VALUE xyzkey 0 6
    sdfsdf
    END

## License

Copyright © 2015 Matthew Daley

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
