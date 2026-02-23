SUBDIR ?= ddd-lab

.PHONY: help

help:
	$(MAKE) -C $(SUBDIR) help

%:
	$(MAKE) -C $(SUBDIR) $@
