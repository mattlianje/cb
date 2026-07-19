.PHONY: compile test publish-local fmt fmt-check repl clean

# Compile all cross versions
compile:
	./mill cb.__.compile

test:
	./mill cb.__.test

repl:
	./mill -i 'cb[2.13.10].console'

publish-local:
	./mill cb.__.publishLocal

fmt:
	./mill mill.scalalib.scalafmt/

fmt-check:
	./mill mill.scalalib.scalafmt/ --check

clean:
	./mill clean
