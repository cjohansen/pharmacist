test:
	clojure -A:dev -A:test

autotest:
	clojure -A:dev -A:test --watch

.PHONY: test autotest
