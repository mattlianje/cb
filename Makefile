.PHONY: compile test publish-local bundle fmt fmt-check repl clean

VERSION := 0.1.1
BUNDLE_DIR := bundles
GPG_KEY := F36FE8EEBD829E6CF1A5ADB6246482D1268EDC6E

# Compile all cross versions
compile:
	./mill cb.__.compile

test:
	./mill cb.__.test

repl:
	./mill -i 'cb[2.13.10].console'

publish-local:
	./mill cb.__.publishLocal

bundle:
	@echo "Building publish artifacts..."
	@./mill show cb.__.publishArtifacts > /dev/null
	@rm -rf $(BUNDLE_DIR) && mkdir -p $(BUNDLE_DIR)/xyz/matthieucourt
	@for scalaVer in 2.12.17 2.13.10; do \
		artifactId=$$(./mill show cb[$$scalaVer].artifactId 2>/dev/null | tr -d '"'); \
		dir=$(BUNDLE_DIR)/xyz/matthieucourt/$$artifactId/$(VERSION); \
		mkdir -p $$dir; \
		cp out/cb/$$scalaVer/pom.dest/*.pom $$dir/$$artifactId-$(VERSION).pom; \
		cp out/cb/$$scalaVer/jar.dest/out.jar $$dir/$$artifactId-$(VERSION).jar; \
		cp out/cb/$$scalaVer/sourceJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-sources.jar; \
		cp out/cb/$$scalaVer/docJar.dest/out.jar $$dir/$$artifactId-$(VERSION)-javadoc.jar; \
		for f in $$dir/*; do \
			md5sum $$f | cut -d' ' -f1 > $$f.md5; \
			sha1sum $$f | cut -d' ' -f1 > $$f.sha1; \
			gpg --batch --yes -ab -u $(GPG_KEY) $$f; \
		done; \
		echo "Packaged $$artifactId"; \
	done
	@cd $(BUNDLE_DIR) && zip -r cb-$(VERSION)-bundle.zip xyz
	@rm -rf $(BUNDLE_DIR)/xyz
	@echo "\nBundle ready: $(BUNDLE_DIR)/cb-$(VERSION)-bundle.zip"

fmt:
	./mill mill.scalalib.scalafmt/

fmt-check:
	./mill mill.scalalib.scalafmt/ --check

clean:
	./mill clean
	rm -rf $(BUNDLE_DIR)
