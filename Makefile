test:
	clojure -A:dev -A:test

autotest:
	clojure -A:dev -A:test --watch

pharmacist.jar: src/**/*.*
	clojure -A:jar

deploy: pharmacist.jar
	mvn deploy:deploy-file -Dfile=pharmacist.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

.PHONY: test autotest deploy
